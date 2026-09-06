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
 * PP-MattingV2 runs on the same mobile GPU that the MediaCodec/OES analysis decoder and temporal
 * GL stage use. On some low/mid-range vendor drivers, letting OES readback race several frames
 * ahead while Paddle Lite OpenCL is executing can return visually corrupted analysis frames even
 * though no GL/OpenCL API call reports an error. Those bad source frames then produce corrupted
 * mattes that leak large strips of the original background into preview/export.
 *
 * V58 therefore keeps direct Bitmap ownership transfer, but makes the inference hand-off a strict
 * GPU barrier: decode/readback of the next selected frame cannot start until PP-MattingV2 + hair +
 * temporal refinement for the current frame has finished. Neural inference is still OpenCL GPU;
 * this only removes unsafe cross-context overlap on the device GPU.
 */
internal class AsyncCutoutInferenceWorkerV48(
    private val process: (sourceTimeUs: Long, bitmap: Bitmap) -> Boolean,
    private val onCompleted: ((completedFrames: Int) -> Unit)? = null,
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DigitorCutoutGpuInferV49").apply { priority = Thread.NORM_PRIORITY + 1 }
    }
    private val slots = Semaphore(1)
    private val failure = AtomicReference<Throwable?>(null)
    private val completed = AtomicInteger(0)
    @Volatile private var closed = false

    /**
     * Takes ownership of [owned]. The bitmap is always recycled after GPU inference finishes.
     *
     * This call intentionally does not return until the inference task is complete. The decoder's
     * onFrame callback therefore becomes a synchronization point between OES/GL readback and
     * Paddle Lite OpenCL instead of allowing both GPU contexts to overlap and corrupt later mattes.
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
            executor.submit {
                try {
                    if (failure.get() == null && process(sourceTimeUs, owned)) {
                        val done = completed.incrementAndGet()
                        onCompleted?.invoke(done)
                    }
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                } finally {
                    if (!owned.isRecycled) owned.recycle()
                }
            }.get()
            failure.get()?.let { throw it }
        } finally {
            slots.release()
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
        CutoutAnalysisQualityV47.MEDIUM -> 250_000L   // 4 fps hair over 12 fps PP-MattingV2
        CutoutAnalysisQualityV47.HIGH -> 125_000L     // 8 fps hair over every-frame PP-MattingV2
    }
