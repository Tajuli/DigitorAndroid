package com.tajuli.digitorandroid.editor.preview

import java.io.Closeable
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hands heavy video resources from realtime preview to export and back.
 *
 * Low/mid-range Android devices often expose only a small number of simultaneous MediaCodec
 * instances and a limited GPU texture budget. Keeping the full-resolution preview decoder/GL graph
 * alive while Transformer starts another decoder + encoder + GL graph can terminate the process at
 * the native codec/driver layer instead of producing a normal Kotlin exception.
 */
internal object PreviewExportCoordinator {
    private val engines = CopyOnWriteArraySet<DavinciFramePreviewEngine>()

    fun register(engine: DavinciFramePreviewEngine) {
        engines += engine
    }

    fun unregister(engine: DavinciFramePreviewEngine) {
        engines -= engine
    }

    /**
     * Releases all active preview decode/render sessions before export starts. Export is allowed to
     * continue only after every registered preview engine confirms that its MediaCodec/GL session
     * has actually been released. This is a barrier, not a best-effort hint.
     */
    fun acquireExportLease(): ExportLease {
        val attempted = engines.toList()
        val suspended = mutableListOf<DavinciFramePreviewEngine>()
        attempted.forEach { engine ->
            if (!engine.suspendForExternalGpuWork()) {
                suspended.forEach { it.resumeAfterExternalGpuWork() }
                throw IllegalStateException(
                    "Preview GPU resources did not release in time; export was not started",
                )
            }
            suspended += engine
        }
        return ExportLease(suspended)
    }

    class ExportLease internal constructor(
        private val engines: List<DavinciFramePreviewEngine>,
    ) : Closeable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            engines.forEach { engine -> engine.resumeAfterExternalGpuWork() }
        }
    }
}
