package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.os.Handler
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult as Media3ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.preview.PreviewExportCoordinator
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

@UnstableApi
class GpuExportBackend(
    private val context: Context,
    private val compositionBuilder: com.tajuli.digitorandroid.editor.render.Media3CompositionBuilder = com.tajuli.digitorandroid.editor.render.Media3CompositionBuilder(),
) : ExportBackend {
    override suspend fun export(
        project: TimelineProject,
        output: File,
        onProgress: (ExportProgress) -> Unit,
    ): ExportResult = suspendCancellableCoroutine { continuation ->
        // Realtime preview and export both use full-resolution MediaCodec + OpenGL resources. Many
        // Android devices cannot keep both graphs alive simultaneously; native codec/driver failure
        // can kill the process before Media3 can deliver an ExportException. Acquire an exclusive
        // export lease first, which synchronously releases preview decoders/GL and restores them when
        // this export finishes, fails, or is cancelled.
        onProgress(ExportProgress.Stage("GPU: releasing preview resources", 0.01f))
        val previewLease = PreviewExportCoordinator.acquireExportLease()

        onProgress(ExportProgress.Stage("GPU: building multitrack composition", 0.02f))
        val composition = runCatching { compositionBuilder.build(project) }
            .getOrElse { error ->
                previewLease.close()
                if (continuation.isActive) continuation.resumeWithException(error)
                return@suspendCancellableCoroutine
            }
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()

        var progressHandler: Handler? = null
        var progressRunnable: Runnable? = null

        fun stopProgressPolling() {
            val handler = progressHandler
            val runnable = progressRunnable
            if (handler != null && runnable != null) {
                handler.removeCallbacks(runnable)
            }
        }

        fun restorePreview() {
            previewLease.close()
        }

        val transformer = runCatching {
            Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: Media3ExportResult) {
                        stopProgressPolling()
                        restorePreview()
                        if (continuation.isActive) {
                            onProgress(ExportProgress.Stage("GPU: complete", 1f))
                            continuation.resume(
                                ExportResult(
                                    output = output,
                                    backend = Backend.GPU,
                                    note = "GPU export complete",
                                ),
                            )
                        }
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: Media3ExportResult,
                        exportException: ExportException,
                    ) {
                        stopProgressPolling()
                        restorePreview()
                        if (continuation.isActive) continuation.resumeWithException(exportException)
                    }
                })
                .build()
        }.getOrElse { error ->
            restorePreview()
            if (continuation.isActive) continuation.resumeWithException(error)
            return@suspendCancellableCoroutine
        }

        val handler = Handler(transformer.applicationLooper)
        progressHandler = handler
        val progressHolder = ProgressHolder()
        val runnable = object : Runnable {
            override fun run() {
                if (!continuation.isActive) return
                val state = runCatching { transformer.getProgress(progressHolder) }
                    .getOrElse { Transformer.PROGRESS_STATE_UNAVAILABLE }
                when (state) {
                    Transformer.PROGRESS_STATE_AVAILABLE -> {
                        val fraction = (progressHolder.progress / 100f).coerceIn(0f, 0.99f)
                        onProgress(
                            ExportProgress.Stage(
                                "GPU: rendering ${progressHolder.progress}%",
                                fraction,
                            ),
                        )
                    }
                    Transformer.PROGRESS_STATE_WAITING_FOR_AVAILABILITY -> {
                        onProgress(ExportProgress.Stage("GPU: preparing export", 0.04f))
                    }
                }
                if (state != Transformer.PROGRESS_STATE_NOT_STARTED && continuation.isActive) {
                    handler.postDelayed(this, 250L)
                }
            }
        }
        progressRunnable = runnable

        continuation.invokeOnCancellation {
            stopProgressPolling()
            restorePreview()
            handler.post { runCatching { transformer.cancel() } }
        }

        onProgress(ExportProgress.Stage("GPU: MediaCodec + OpenGL export", 0.05f))
        runCatching {
            transformer.start(composition, output.absolutePath)
            handler.post(runnable)
        }.onFailure { error ->
            stopProgressPolling()
            restorePreview()
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }
}
