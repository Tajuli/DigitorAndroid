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
     * Releases all active preview decode/render sessions before export starts. The returned lease
     * must be closed on completion, failure, or cancellation to restore paused preview frames.
     */
    fun acquireExportLease(): ExportLease {
        val suspended = engines.filter { engine -> engine.suspendForExternalGpuWork() }
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
