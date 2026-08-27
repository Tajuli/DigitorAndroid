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
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.preview.PreviewExportCoordinator
import com.tajuli.digitorandroid.editor.render.StableGpuExportCompositionBuilder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

@UnstableApi
class GpuExportBackend(
    private val context: Context,
) : ExportBackend {
    private val compositionBuilder = StableGpuExportCompositionBuilder()

    override suspend fun export(
        project: TimelineProject,
        output: File,
        onProgress: (ExportProgress) -> Unit,
    ): ExportResult = suspendCancellableCoroutine { continuation ->
        onProgress(ExportProgress.Stage("GPU: releasing preview resources", 0.01f))
        val previewLease = runCatching { PreviewExportCoordinator.acquireExportLease() }
            .getOrElse { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
                return@suspendCancellableCoroutine
            }

        val videoTrackCount = project.tracks.count { track ->
            track.kind == TrackKind.VIDEO && !track.muted && track.clips.isNotEmpty()
        }
        val compositionStage = if (videoTrackCount == 1) {
            "GPU: building stable single-layer export"
        } else {
            "GPU: building multitrack composition"
        }
        onProgress(ExportProgress.Stage(compositionStage, 0.02f))

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
        var startRunnable: Runnable? = null
        val transformerStarted = AtomicBoolean(false)

        fun stopCallbacks() {
            val handler = progressHandler ?: return
            progressRunnable?.let(handler::removeCallbacks)
            startRunnable?.let(handler::removeCallbacks)
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
                        stopCallbacks()
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
                        stopCallbacks()
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
                if (!continuation.isActive || !transformerStarted.get()) return
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

        val starter = Runnable {
            if (!continuation.isActive) {
                restorePreview()
                return@Runnable
            }
            onProgress(ExportProgress.Stage("GPU: MediaCodec + OpenGL export", 0.05f))
            runCatching {
                transformer.start(composition, output.absolutePath)
                transformerStarted.set(true)
                handler.post(runnable)
            }.onFailure { error ->
                stopCallbacks()
                restorePreview()
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
        startRunnable = starter

        continuation.invokeOnCancellation {
            stopCallbacks()
            restorePreview()
            if (transformerStarted.get()) handler.post { runCatching { transformer.cancel() } }
        }

        // Some Android codec stacks release native decoder/GL resources asynchronously even after
        // MediaCodec.stop/release returns. Give Codec2/SurfaceFlinger a short quiescent window before
        // opening the export decoder + encoder pair.
        onProgress(ExportProgress.Stage("GPU: waiting for codec release", 0.03f))
        handler.postDelayed(starter, EXPORT_START_GRACE_MS)
    }

    private companion object {
        const val EXPORT_START_GRACE_MS = 350L
    }
}
