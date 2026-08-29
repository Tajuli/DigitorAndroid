package com.tajuli.digitorandroid.editor.preview

import java.io.Closeable
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantReadWriteLock
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
    private val previewDecodeLock = ReentrantReadWriteLock(true)
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
        val lock = previewDecodeLock.readLock()
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }

    /**
     * Releases all active preview decode/render sessions before export starts and takes an exclusive
     * lock against software fallback decoding. Export is allowed to continue only after every
     * preview resource is quiescent.
     */
    fun acquireExportLease(): ExportLease {
        val exportLock = previewDecodeLock.writeLock()
        exportLock.lock()
        mutableExportActive.value = true
        val attempted = engines.toList()
        val suspended = mutableListOf<DavinciFramePreviewEngine>()
        try {
            attempted.forEach { engine ->
                if (!engine.suspendForExternalGpuWork()) {
                    throw IllegalStateException(
                        "Preview GPU resources did not release in time; export was not started",
                    )
                }
                suspended += engine
            }
            return ExportLease(suspended, exportLock)
        } catch (error: Throwable) {
            suspended.forEach {
                it.resumeAfterExternalGpuWork()
                it.scheduleCurrentFrameRefresh(180L)
            }
            mutableExportActive.value = false
            exportLock.unlock()
            throw error
        }
    }

    class ExportLease internal constructor(
        private val engines: List<DavinciFramePreviewEngine>,
        private val exportLock: Lock,
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
                exportLock.unlock()
            }
        }
    }
}
