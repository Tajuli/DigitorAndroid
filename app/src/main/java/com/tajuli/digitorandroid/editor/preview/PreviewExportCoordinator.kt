package com.tajuli.digitorandroid.editor.preview

import java.io.Closeable
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hands heavy video resources from realtime preview to export and back.
 *
 * Low/mid-range Android devices often expose only a small number of simultaneous MediaCodec
 * instances and a limited GPU texture budget. Keeping either the GPU preview decoder or the
 * software fallback MediaMetadataRetriever alive while Transformer opens export decode/encode can
 * terminate the process in native codec/driver code instead of returning a Kotlin exception.
 */
internal object PreviewExportCoordinator {
    private val engines = CopyOnWriteArraySet<DavinciFramePreviewEngine>()

    // A Semaphore is deliberately used instead of a ReentrantLock/ReadWriteLock: Transformer may
    // finish on a different application-looper thread than the coroutine that starts export, and a
    // semaphore can safely be released cross-thread. Software fallback frames take this same gate,
    // so export waits for any in-flight retriever decode and blocks new fallback decodes until done.
    private val previewDecodeGate = Semaphore(1, true)
    private val mutableExportActive = MutableStateFlow(false)
    val exportActive: StateFlow<Boolean> = mutableExportActive.asStateFlow()

    fun register(engine: DavinciFramePreviewEngine) {
        engines += engine
    }

    fun unregister(engine: DavinciFramePreviewEngine) {
        engines -= engine
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
