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
 * Small bounded producer/consumer helpers used by V48 Cutout.
 *
 * Decode, GPU inference and PNG persistence used to be serialized in one callback. These helpers
 * overlap hardware decode with inference and move PNG compression off the inference critical path
 * without allowing an unbounded number of full-resolution Bitmaps to accumulate.
 */
internal class AsyncCutoutInferenceWorkerV48(
    private val process: (sourceTimeUs: Long, bitmap: Bitmap) -> Boolean,
    private val onCompleted: ((completedFrames: Int) -> Unit)? = null,
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DigitorCutoutGpuInferV48").apply { priority = Thread.NORM_PRIORITY + 1 }
    }
    private val slots = Semaphore(2)
    private val failure = AtomicReference<Throwable?>(null)
    private val completed = AtomicInteger(0)
    @Volatile private var closed = false

    /**
     * Retains one bounded ARGB copy because the V47 decoder owns/recycles its callback Bitmap.
     * The copy lets hardware decode continue while the previous frame is inside GPU inference.
     */
    fun enqueueCopy(sourceTimeUs: Long, source: Bitmap) {
        failure.get()?.let { throw it }
        check(!closed) { "V48 Cutout inference worker is closed" }
        slots.acquire()
        val owned = try {
            source.copy(Bitmap.Config.ARGB_8888, false)
                ?: error("Could not retain decoded frame for V48 GPU inference")
        } catch (error: Throwable) {
            slots.release()
            throw error
        }
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
    }

    fun awaitIdle(): Int {
        failure.get()?.let { throw it }
        executor.submit {}.get()
        failure.get()?.let { throw it }
        return completed.get()
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

/** PNG compression is CPU work, but it no longer blocks the GPU inference loop frame-by-frame. */
internal class AsyncPersonCutoutMaskWriterV48(
    private val context: Context,
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DigitorCutoutMaskIoV48").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val slots = Semaphore(4)
    private val failure = AtomicReference<Throwable?>(null)
    @Volatile private var closed = false

    /** Takes ownership of [mask] and always recycles it after persistence. */
    fun enqueue(sourceUri: String, sourceTimeUs: Long, mask: Bitmap) {
        failure.get()?.let {
            if (!mask.isRecycled) mask.recycle()
            throw it
        }
        check(!closed) { "V48 matte writer is closed" }
        slots.acquire()
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
    }

    fun awaitIdle() {
        failure.get()?.let { throw it }
        executor.submit {}.get()
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

/** Hair semantics do not need to run at 12/30/60 fps; GPU flow carries the last soft mask between refreshes. */
internal fun hairSemanticRefreshIntervalUsV48(quality: CutoutAnalysisQualityV47): Long =
    when (quality) {
        CutoutAnalysisQualityV47.LOW -> 250_000L      // 4 fps
        CutoutAnalysisQualityV47.MEDIUM -> 250_000L   // 4 fps hair over 12 fps MODNet
        CutoutAnalysisQualityV47.HIGH -> 125_000L     // 8 fps hair over every-frame MODNet
    }
