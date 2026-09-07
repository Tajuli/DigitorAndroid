package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import com.tajuli.digitorandroid.editor.model.CutoutAnalysisQualityV47
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * UNISOC/T606 firmware on the target Z60 has already shown process-fatal vendor accelerator paths.
 * During Cutout it also has to share Mali-G57 between MediaCodec/OES OpenGL, ncnn Vulkan and Android
 * system composition (notification heads-up, charging UI, etc.). Serializing our GL->Vulkan stages
 * on this family deliberately gives up some pipeline overlap in exchange for driver stability.
 */
internal fun useInterruptionSafeSerialCutoutV66(): Boolean {
    val identity = buildList {
        add(Build.MANUFACTURER)
        add(Build.BRAND)
        add(Build.MODEL)
        add(Build.DEVICE)
        add(Build.PRODUCT)
        add(Build.BOARD)
        add(Build.HARDWARE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Build.SOC_MANUFACTURER)
            add(Build.SOC_MODEL)
        }
    }.joinToString("|") { it.orEmpty() }.lowercase()
    return ("symphony" in identity && "z60" in identity) ||
        "t606" in identity ||
        "unisoc" in identity ||
        "spreadtrum" in identity ||
        "sprd" in identity ||
        "ums9230" in identity ||
        "ums512" in identity
}

/**
 * Bounded producer/consumer helpers used by V49/V66 Cutout.
 *
 * Normal devices keep up to three frames of decode/inference overlap. Interruption-sensitive UNISOC
 * devices use a one-frame queue plus an executor barrier after each enqueue, so the OES decoder does
 * not render the next frame while ncnn Vulkan is still executing the previous PP-Matting frame.
 */
internal class AsyncCutoutInferenceWorkerV48(
    private val process: (sourceTimeUs: Long, bitmap: Bitmap) -> Boolean,
    private val onCompleted: ((completedFrames: Int) -> Unit)? = null,
) : AutoCloseable {
    private val serialGpuStages = useInterruptionSafeSerialCutoutV66()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DigitorCutoutGpuInferV49").apply { priority = Thread.NORM_PRIORITY + 1 }
    }
    private val slots = Semaphore(if (serialGpuStages) 1 else 3)
    private val failure = AtomicReference<Throwable?>(null)
    private val completed = AtomicInteger(0)
    @Volatile private var closed = false

    /**
     * Takes ownership of [owned]. The bitmap is always recycled after GPU inference finishes.
     * Callers must not touch or recycle it after this method returns successfully.
     */
    fun enqueueOwned(sourceTimeUs: Long, owned: Bitmap) {
        if (CutoutAnalysisRuntimeV66.isPauseRequested()) {
            if (!owned.isRecycled) owned.recycle()
            throw CutoutAnalysisPausedV66()
        }
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
            if (serialGpuStages) {
                // Same single-thread executor = hard ordering barrier: inference must finish before
                // MediaCodec/OES is allowed to produce the next selected frame on this device.
                executor.submit {}.get()
                failure.get()?.let { throw it }
                CutoutAnalysisRuntimeV66.throwIfPauseRequested()
            }
        } catch (error: Throwable) {
            // If executor.execute succeeded it owns/recycles the bitmap and releases the permit.
            // Only recycle here when the task could not have been submitted.
            if (error is java.util.concurrent.RejectedExecutionException) {
                slots.release()
                if (!owned.isRecycled) owned.recycle()
            }
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
 * Matte persistence is the intentional CPU/file-I/O boundary. Completed PNGs are atomic durable
 * checkpoints. Normal devices use two encoders/eight slots; interruption-sensitive UNISOC devices
 * use one encoder/three slots to reduce bitmap memory pressure while Vulkan is active.
 */
internal class AsyncPersonCutoutMaskWriterV48(
    private val context: Context,
) : AutoCloseable {
    private val conservative = useInterruptionSafeSerialCutoutV66()
    private val capacity = if (conservative) 3 else 8
    private val executor = Executors.newFixedThreadPool(if (conservative) 1 else 2) { runnable ->
        Thread(runnable, "DigitorCutoutMaskIoV49").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val slots = Semaphore(capacity)
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
        // pool is therefore a true drain barrier even with multiple workers.
        slots.acquire(capacity)
        slots.release(capacity)
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
        CutoutAnalysisQualityV47.MEDIUM -> 250_000L   // 4 fps hair over 12 fps PP-Matting
        CutoutAnalysisQualityV47.HIGH -> 125_000L     // 8 fps hair over every-frame PP-Matting
    }
