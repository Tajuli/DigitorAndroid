package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult as Media3ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.preview.PreviewExportCoordinator
import com.tajuli.digitorandroid.editor.render.Media3CompositionBuilder
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

        // Gallery/Photos providers normally hand the editor content:// URIs. Some vendor DataSource
        // implementations can preview those URIs but fail when Transformer re-opens a still image
        // through ImageAssetLoader during export. Materialize only still-image sources into a short-
        // lived app-private file so the real export path always sees a stable seekable source.
        // Moving-video/audio URIs are untouched.
        val preparedImages = runCatching { materializeStillImageSources(project) }
            .getOrElse { error ->
                previewLease.close()
                if (continuation.isActive) continuation.resumeWithException(error)
                return@suspendCancellableCoroutine
            }
        val exportProject = preparedImages.project

        val videoTrackCount = exportProject.tracks.count { track ->
            track.kind == TrackKind.VIDEO && !track.muted && track.clips.isNotEmpty()
        }
        val compositionStage = if (videoTrackCount == 1) {
            "GPU: building stable single-layer export"
        } else {
            "GPU: building multitrack composition"
        }
        onProgress(ExportProgress.Stage("$compositionStage · ${quality.label}", 0.02f))

        // Text-only timeline regions need an actual video frame stream. Use a real cache PNG rather
        // than a data: URI: vendor Media3/DataSource stacks are not equally reliable with data-image
        // URIs. The file is tiny, app-private, and can be reused across exports.
        val blankFrameUri = runCatching { ensureBlankFramePngUri() }
            .getOrElse { error ->
                preparedImages.close()
                previewLease.close()
                if (continuation.isActive) continuation.resumeWithException(error)
                return@suspendCancellableCoroutine
            }
        val compositionBuilder = StableGpuExportCompositionBuilder(
            Media3CompositionBuilder(blankFrameUri = blankFrameUri),
        )
        val composition = runCatching { compositionBuilder.build(exportProject) }
            .getOrElse { error ->
                preparedImages.close()
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
            preparedImages.close()
            previewLease.close()
        }

        fun cleanupFailedOutput() {
            runCatching {
                if (output.exists()) output.delete()
            }
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
            // repeatedly return invalid-data errors while draining the stream. Prefer the software
            // AVC decoder there, but preserve Media3's DefaultAssetLoaderFactory. A bare
            // ExoPlayerAssetLoader.Factory cannot load still-image MediaItems, while the default
            // factory dispatches images to ImageAssetLoader and normal A/V to ExoPlayerAssetLoader.
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
                    DefaultAssetLoaderFactory(
                        context,
                        decoderFactory,
                        Clock.DEFAULT,
                        null,
                    ),
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
                        cleanupFailedOutput()
                        if (continuation.isActive) continuation.resumeWithException(exportException)
                    }
                })
                .build()
        }.getOrElse { error ->
            restorePreview()
            cleanupFailedOutput()
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
                cleanupFailedOutput()
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
                cleanupFailedOutput()
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
        startRunnable = starter

        continuation.invokeOnCancellation {
            stopCallbacks()
            if (transformerStarted.get()) {
                handler.post {
                    runCatching { transformer.cancel() }
                    restorePreview()
                    cleanupFailedOutput()
                }
            } else {
                restorePreview()
                cleanupFailedOutput()
            }
        }

        // Some Android codec stacks release native decoder/GL resources asynchronously even after
        // MediaCodec.stop/release returns. Give Codec2/SurfaceFlinger a short quiescent window before
        // opening the export decoder + encoder pair.
        onProgress(ExportProgress.Stage("GPU: waiting for codec release · ${quality.label}", 0.03f))
        handler.postDelayed(starter, EXPORT_START_GRACE_MS)
    }

    private fun materializeStillImageSources(project: TimelineProject): PreparedImageProject {
        val tempFiles = mutableListOf<File>()
        val supportDir = File(context.cacheDir, IMAGE_SOURCE_DIR).apply { mkdirs() }
        return try {
            val tracks = project.tracks.map { track ->
                track.copy(
                    clips = track.clips.map { clip ->
                        if (!clip.isImageV21) return@map clip
                        val source = Uri.parse(clip.uri)
                        if (source.scheme.equals("file", ignoreCase = true)) return@map clip

                        val mimeType = clip.sourceMimeTypeV21
                            ?.takeIf { it.startsWith("image/") }
                            ?: context.contentResolver.getType(source)?.takeIf { it.startsWith("image/") }
                            ?: "image/png"
                        val suffix = when (mimeType.lowercase()) {
                            "image/jpeg", "image/jpg" -> ".jpg"
                            "image/webp" -> ".webp"
                            "image/heic" -> ".heic"
                            "image/heif" -> ".heif"
                            else -> ".png"
                        }
                        val stableFile = File.createTempFile("still_${clip.id.take(8)}_", suffix, supportDir)
                        val input = context.contentResolver.openInputStream(source)
                            ?: error("Could not open still image for export: ${clip.label}")
                        input.use { sourceStream ->
                            stableFile.outputStream().buffered().use { target ->
                                sourceStream.copyTo(target, 1024 * 1024)
                            }
                        }
                        require(stableFile.length() > 0L) { "Still image source was empty: ${clip.label}" }
                        tempFiles += stableFile
                        clip.copy(
                            uri = Uri.fromFile(stableFile).toString(),
                            sourceMimeTypeV21 = mimeType,
                        )
                    },
                )
            }
            PreparedImageProject(project.copy(tracks = tracks), tempFiles)
        } catch (error: Throwable) {
            tempFiles.forEach { file -> runCatching { file.delete() } }
            throw error
        }
    }

    private fun ensureBlankFramePngUri(): String {
        val supportDir = File(context.cacheDir, "digitor_export_support").apply { mkdirs() }
        val blankFile = File(supportDir, BLANK_FRAME_FILE_NAME)
        if (!blankFile.exists() || blankFile.length() < 16L) {
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.BLACK)
                blankFile.outputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        "Could not create export blank frame"
                    }
                }
            } finally {
                bitmap.recycle()
            }
        }
        return Uri.fromFile(blankFile).toString()
    }

    private fun preferredAvcDecoderIsUnisoc(): Boolean = runCatching {
        MediaCodecSelector.DEFAULT
            .getDecoderInfos(MimeTypes.VIDEO_H264, false, false)
            .firstOrNull()
            ?.name
            ?.contains("unisoc", ignoreCase = true) == true
    }.getOrDefault(false)

    private class PreparedImageProject(
        val project: TimelineProject,
        private val tempFiles: List<File>,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            tempFiles.forEach { file -> runCatching { file.delete() } }
        }
    }

    private companion object {
        const val EXPORT_START_GRACE_MS = 350L
        const val BLANK_FRAME_FILE_NAME = "blank_frame_v15.png"
        const val IMAGE_SOURCE_DIR = "digitor_export_images_v42"
    }
}
