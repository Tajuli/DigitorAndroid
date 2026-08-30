package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.os.Handler
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExoPlayerAssetLoader
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult as Media3ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
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
    ): ExportResult = export(project, output, ExportQuality.HIGH, onProgress)

    suspend fun export(
        project: TimelineProject,
        output: File,
        quality: ExportQuality,
        onProgress: (ExportProgress) -> Unit,
    ): ExportResult = suspendCancellableCoroutine { continuation ->
        val requestedBitrate = quality.videoBitrate(project.width, project.height, project.frameRate)
        onProgress(ExportProgress.Stage("GPU: releasing preview resources · ${quality.label}", 0.01f))
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
        onProgress(ExportProgress.Stage("$compositionStage · ${quality.label}", 0.02f))

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
            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(
                    VideoEncoderSettings.Builder()
                        .setBitrate(requestedBitrate)
                        .build(),
                )
                .build()

            val transformerBuilder = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoderFactory)

            // Some Unisoc AVC hardware decoders accept camera H.264 during configuration but then
            // repeatedly return invalid-data errors while draining the stream. In that situation
            // Transformer can terminate in vendor Codec2/VSP code before it can report a normal
            // ExportException. Keep hardware decoding everywhere else, but on devices whose
            // preferred AVC decoder is a Unisoc implementation, prefer Android's software AVC
            // decoder for export. Other MIME types keep the platform's normal decoder priority.
            if (preferredAvcDecoderIsUnisoc()) {
                val selector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                    val delegate = if (mimeType == MimeTypes.VIDEO_H264) {
                        MediaCodecSelector.PREFER_SOFTWARE
                    } else {
                        MediaCodecSelector.DEFAULT
                    }
                    delegate.getDecoderInfos(
                        mimeType,
                        requiresSecureDecoder,
                        requiresTunnelingDecoder,
                    )
                }
                val decoderFactory = DefaultDecoderFactory.Builder(context)
                    .setMediaCodecSelector(selector)
                    .setEnableDecoderFallback(true)
                    .build()
                transformerBuilder.setAssetLoaderFactory(
                    ExoPlayerAssetLoader.Factory(context, decoderFactory, Clock.DEFAULT),
                )
            }

            transformerBuilder
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: Media3ExportResult) {
                        stopCallbacks()
                        restorePreview()
                        if (continuation.isActive) {
                            onProgress(ExportProgress.Stage("GPU: complete · ${quality.label}", 1f))
                            continuation.resume(
                                ExportResult(
                                    output = output,
                                    backend = Backend.GPU,
                                    note = "GPU export complete · ${quality.label} · ${requestedBitrate / 1_000_000f} Mbps target",
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
                                "GPU: rendering ${progressHolder.progress}% · ${quality.label}",
                                fraction,
                            ),
                        )
                    }
                    Transformer.PROGRESS_STATE_WAITING_FOR_AVAILABILITY -> {
                        onProgress(ExportProgress.Stage("GPU: preparing export · ${quality.label}", 0.04f))
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
            onProgress(ExportProgress.Stage("GPU: MediaCodec + OpenGL export · ${quality.label}", 0.05f))
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
        onProgress(ExportProgress.Stage("GPU: waiting for codec release · ${quality.label}", 0.03f))
        handler.postDelayed(starter, EXPORT_START_GRACE_MS)
    }

    private fun preferredAvcDecoderIsUnisoc(): Boolean = runCatching {
        MediaCodecSelector.DEFAULT
            .getDecoderInfos(MimeTypes.VIDEO_H264, false, false)
            .firstOrNull()
            ?.name
            ?.contains("unisoc", ignoreCase = true) == true
    }.getOrDefault(false)

    private companion object {
        const val EXPORT_START_GRACE_MS = 350L
    }
}
