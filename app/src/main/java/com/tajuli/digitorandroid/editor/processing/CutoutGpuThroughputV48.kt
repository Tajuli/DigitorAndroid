package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.graphics.Bitmap
import com.tajuli.digitorandroid.editor.model.CutoutAnalysisQualityV47
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Bounded producer/consumer helpers used by V49 Cutout.
 *
 * The GPU fast path transfers decoder Bitmap ownership directly into the inference queue. V48 made
 * a second full ARGB copy for every decoded frame before inference; at 720p High mode that extra
 * memory bandwidth was large enough to starve the GPU on mid-range phones. Decode can now stay up
 * to three frames ahead while the model is busy, with no duplicate frame copy in the hot path.
 */
internal class AsyncCutoutInferenceWorkerV48(
    private val process: (sourceTimeUs: Long, bitmap: Bitmap) -> Boolean,
    private val onCompleted: ((completedFrames: Int) -> Unit)? = null,
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DigitorCutoutGpuInferV49").apply { priority = Thread.NORM_PRIORITY + 1 }
    }
    private val slots = Semaphore(3)
    private val failure = AtomicReference<Throwable?>(null)
    private val completed = AtomicInteger(0)
    @Volatile private var closed = false

    /**
     * Takes ownership of [owned]. The bitmap is always recycled after GPU inference finishes.
     * Callers must not touch or recycle it after this method returns successfully.
     */
    fun enqueueOwned(sourceTimeUs: Long, owned: Bitmap) {
        failure.get()?.let {
            if (!owned.isRecycled) owned.recycle()
            throw it
        }
        check(!closed) {
            if (!owned.isRecycled) owned.recycle()
            "V49 Cutout inference worker is closed"
        }
        slots.acquire()
        try {
            executor.execute {
                try {
                    if (failure.get() == null && process(sourceTimeUs, owned)) {
                        val done = completed.incrementAndGet()
                        onCompleted?.invoke(done)
                    }
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                } finally {
                    if (!owned.isRecycled) owned.recycle()
                    slots.release()
                }
            }
        } catch (error: Throwable) {
            slots.release()
            if (!owned.isRecycled) owned.recycle()
            throw error
        }
    }

    /** Compatibility helper for call sites that cannot transfer ownership. */
    fun enqueueCopy(sourceTimeUs: Long, source: Bitmap) {
        val owned = source.copy(Bitmap.Config.ARGB_8888, false)
            ?: error("Could not retain decoded frame for V49 GPU inference")
        enqueueOwned(sourceTimeUs, owned)
    }

    fun awaitIdle(): Int {
        executor.submit {}.get()
        failure.get()?.let { throw it }
        return completed.get()
    }

    /** Runs after all queued frames, on the exact same thread as GPU model/GL inference. */
    fun runAfterPending(action: () -> Unit) {
        executor.submit {
            try {
                action()
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            }
        }.get()
        failure.get()?.let { throw it }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { executor.submit {}.get() }
        executor.shutdown()
        if (!executor.awaitTermination(30, TimeUnit.SECONDS)) executor.shutdownNow()
        failure.get()?.let { throw it }
    }
}

/**
 * Matte persistence is the intentional CPU/file-I/O boundary. Two low-priority encoders keep PNG
 * compression behind GPU inference instead of letting one encoder back-pressure High mode. Each
 * frame has a unique timestamp/file, so the writes are independent; the ready marker is still
 * published only after awaitIdle() drains both workers.
 */
internal class AsyncPersonCutoutMaskWriterV48(
    private val context: Context,
) : AutoCloseable {
    private val executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "DigitorCutoutMaskIoV49").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val slots = Semaphore(8)
    private val failure = AtomicReference<Throwable?>(null)
    @Volatile private var closed = false

    /** Takes ownership of [mask] and always recycles it after persistence. */
    fun enqueue(sourceUri: String, sourceTimeUs: Long, mask: Bitmap) {
        failure.get()?.let {
            if (!mask.isRecycled) mask.recycle()
            throw it
        }
        check(!closed) { "V49 matte writer is closed" }
        slots.acquire()
        try {
            executor.execute {
                try {
                    if (failure.get() == null) {
                        PersonCutoutMaskStoreV43.save(context, sourceUri, sourceTimeUs, mask)
                    }
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                } finally {
                    if (!mask.isRecycled) mask.recycle()
                    slots.release()
                }
            }
        } catch (error: Throwable) {
            slots.release()
            if (!mask.isRecycled) mask.recycle()
            throw error
        }
    }

    /** Compatibility overload for the analyzer's explicit application-context call site. */
    fun enqueue(appContext: Context, sourceUri: String, sourceTimeUs: Long, mask: Bitmap) {
        check(appContext.applicationContext.packageName == context.applicationContext.packageName)
        enqueue(sourceUri, sourceTimeUs, mask)
    }

    fun awaitIdle() {
        // Every queued writer owns exactly one permit until its file is durable. Acquiring the whole
        // pool is therefore a true drain barrier even with two workers; two no-op Futures are not,
        // because one fast worker could execute both while the other still compresses a large PNG.
        slots.acquire(8)
        slots.release(8)
        failure.get()?.let { throw it }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { awaitIdle() }
        executor.shutdown()
        if (!executor.awaitTermination(30, TimeUnit.SECONDS)) executor.shutdownNow()
        failure.get()?.let { throw it }
    }
}

/** Hair semantics do not need to run at 12/30/60 fps; GPU flow carries the last soft mask between refreshes. */
internal fun hairSemanticRefreshIntervalUsV48(quality: CutoutAnalysisQualityV47): Long =
    when (quality) {
        CutoutAnalysisQualityV47.LOW -> 250_000L      // 4 fps
        CutoutAnalysisQualityV47.MEDIUM -> 250_000L   // 4 fps hair over 12 fps MODNet
        CutoutAnalysisQualityV47.HIGH -> 125_000L     // 8 fps hair over every-frame MODNet
    }
