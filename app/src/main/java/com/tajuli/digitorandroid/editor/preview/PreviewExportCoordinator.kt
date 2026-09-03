package com.tajuli.digitorandroid.editor.preview

import java.io.Closeable
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hands heavy video resources from realtime preview to export/analysis and back.
 *
 * Low/mid-range Android devices often expose only a small number of simultaneous MediaCodec
 * instances and a limited GPU texture budget. Keeping either the GPU preview decoder or the
 * software fallback MediaMetadataRetriever alive while Transformer or semantic analysis opens a
 * second decoder can terminate the process in native codec/driver code or make retriever frames
 * silently fail. External decode work therefore takes this shared lease first.
 */
internal object PreviewExportCoordinator {
    private val engines = CopyOnWriteArraySet<DavinciFramePreviewEngine>()

    // A Semaphore is deliberately used instead of a ReentrantLock/ReadWriteLock: Transformer may
    // finish on a different application-looper thread than the coroutine that starts export, and a
    // semaphore can safely be released cross-thread. Software fallback and semantic analysis take
    // this same gate, so only one heavy decoder owner exists at a time.
    private val previewDecodeGate = Semaphore(1, true)
    private val mutableExportActive = MutableStateFlow(false)
    val exportActive: StateFlow<Boolean> = mutableExportActive.asStateFlow()

    fun register(engine: DavinciFramePreviewEngine) {
        engines += engine
    }

    fun unregister(engine: DavinciFramePreviewEngine) {
        engines -= engine
    }

    /**
     * Forces the currently visible paused frame through the GPU graph again.
     *
     * Cached semantic assets such as V43 Auto Cutout mattes live outside the immutable project
     * snapshot, so writing a new matte does not by itself change the project StateFlow. Re-submit
     * the held playhead frame after an anchor is written so the new alpha mask is visible without
     * requiring the user to scrub, toggle a slider, or reopen the project.
     */
    fun refreshActivePreviews(delayMs: Long = 0L) {
        engines.forEach { engine -> engine.scheduleCurrentFrameRefresh(delayMs) }
    }

    /** Software fallback frame decode participates in the same resource barrier as GPU preview. */
    fun <T> withSoftwarePreviewDecode(block: () -> T): T {
        previewDecodeGate.acquireUninterruptibly()
        return try {
            block()
        } finally {
            previewDecodeGate.release()
        }
    }

    /**
     * Auto Cutout uses MediaMetadataRetriever plus MediaPipe. Some Android codec stacks cannot keep
     * that retriever alive beside Digitor's realtime MediaCodec session. Temporarily release preview
     * decode/GL resources, let semantic analysis own the decoder budget, then restore the exact
     * paused playhead when the lease closes.
     */
    fun acquireAnalysisLease(): AnalysisLease {
        previewDecodeGate.acquireUninterruptibly()
        val suspended = mutableListOf<DavinciFramePreviewEngine>()
        try {
            SoftwarePreviewRenderer.releaseCachedDecoderForExport()
            engines.toList().forEach { engine ->
                if (!engine.suspendForExternalGpuWork()) {
                    throw IllegalStateException("Preview resources did not release for Auto Cutout analysis")
                }
                suspended += engine
            }
            return AnalysisLease(suspended)
        } catch (error: Throwable) {
            suspended.forEach { engine ->
                engine.resumeAfterExternalGpuWork()
                engine.scheduleCurrentFrameRefresh(180L)
            }
            previewDecodeGate.release()
            throw error
        }
    }

    class AnalysisLease internal constructor(
        private val engines: List<DavinciFramePreviewEngine>,
    ) : Closeable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                engines.forEach { engine ->
                    engine.resumeAfterExternalGpuWork()
                    engine.scheduleCurrentFrameRefresh(180L)
                }
            } finally {
                previewDecodeGate.release()
            }
        }
    }

    /**
     * Releases all active preview decode/render sessions before export starts and takes exclusive
     * ownership of software-preview decoding. Export is allowed to continue only after every
     * preview resource is quiescent.
     */
    fun acquireExportLease(): ExportLease {
        previewDecodeGate.acquireUninterruptibly()
        mutableExportActive.value = true
        val attempted = engines.toList()
        val suspended = mutableListOf<DavinciFramePreviewEngine>()
        try {
            // SoftwarePreviewRenderer now keeps one retriever warm between adjacent fallback frames
            // for smooth Log playback. We own previewDecodeGate here, so it is safe and necessary to
            // tear that cached decoder down before Transformer opens the export decoder/encoder.
            SoftwarePreviewRenderer.releaseCachedDecoderForExport()

            attempted.forEach { engine ->
                if (!engine.suspendForExternalGpuWork()) {
                    throw IllegalStateException(
                        "Preview GPU resources did not release in time; export was not started",
                    )
                }
                suspended += engine
            }
            return ExportLease(suspended)
        } catch (error: Throwable) {
            suspended.forEach {
                it.resumeAfterExternalGpuWork()
                it.scheduleCurrentFrameRefresh(180L)
            }
            mutableExportActive.value = false
            previewDecodeGate.release()
            throw error
        }
    }

    class ExportLease internal constructor(
        private val engines: List<DavinciFramePreviewEngine>,
    ) : Closeable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                engines.forEach { engine ->
                    engine.resumeAfterExternalGpuWork()
                    // resumeAfterExternalGpuWork() rebuilds the decoder/GL session asynchronously.
                    engine.scheduleCurrentFrameRefresh(180L)
                }
            } finally {
                mutableExportActive.value = false
                previewDecodeGate.release()
            }
        }
    }
}
