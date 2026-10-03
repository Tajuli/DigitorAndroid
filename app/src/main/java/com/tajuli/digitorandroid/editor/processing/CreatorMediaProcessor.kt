package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.tajuli.digitorandroid.editor.model.SpeedCurveSchedule
import com.tajuli.digitorandroid.editor.model.SpeedCurveSpec
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.preview.PreviewExportCoordinator
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Derived-media operations. Results are ordinary MP4 files, so the existing preview/export paths
 * do not need a second timestamp model for speed/reverse/freeze clips. */
@UnstableApi
class CreatorMediaProcessor(context: Context) {
    data class DerivedMedia(
        val uri: String,
        val durationUs: Long,
        val hasAudio: Boolean,
        val smoothInterpolated: Boolean = false,
    )

    private val appContext = context.applicationContext
    private val outputDir = File(appContext.filesDir, "derived_media").apply { mkdirs() }

    suspend fun bakeSpeed(clip: TimelineClip, speed: Float, frameRate: Int): DerivedMedia =
        bakeSpeedCurve(
            clip = clip,
            curve = SpeedCurveSpec.constant(speed.coerceIn(.25f, 4f), smoothSlowMotion = false),
            frameRate = frameRate,
        )

    /**
     * Bakes a creator velocity curve into an ordinary MP4.
     *
     * Media3 owns the variable-speed audio clock so pitch/time handling stays synchronized with the
     * video timing. When Smooth Slow Motion is enabled and the curve enters a sub-1x section, video
     * is rendered directly from the original decoded frames at the project cadence. The source
     * timestamps are mapped through the exact sampled schedule used by Media3; missing output
     * instants are filled with intermediate frames rather than simple frame repeats.
     */
    suspend fun bakeSpeedCurve(
        clip: TimelineClip,
        curve: SpeedCurveSpec,
        frameRate: Int,
    ): DerivedMedia {
        val normalized = curve.normalized()
        val schedule = normalized.sampledSchedule(clip.durationUs)
        val sourceHasAudio = hasAudio(clip.uri)
        val smooth = normalized.smoothSlowMotion && normalized.hasSlowMotion

        if (!smooth) {
            val output = nextFile("speed_curve")
            runCurveTransformer(
                clip = clip,
                schedule = schedule,
                frameRate = frameRate,
                output = output,
                sourceHasAudio = sourceHasAudio,
                audioOnly = false,
            )
            return DerivedMedia(
                uri = output.toUriString(),
                durationUs = schedule.outputDurationUs,
                hasAudio = sourceHasAudio,
            )
        }

        val smoothVideo = nextFile("smooth_slowmo_video")
        var retimedAudio: File? = null
        var finalOutput: File? = null
        try {
            if (sourceHasAudio) {
                retimedAudio = nextFile("speed_curve_audio")
                runCurveTransformer(
                    clip = clip,
                    schedule = schedule,
                    frameRate = frameRate,
                    output = retimedAudio,
                    sourceHasAudio = true,
                    audioOnly = true,
                )
            }

            renderSmoothCurveVideo(
                clip = clip,
                schedule = schedule,
                frameRate = frameRate,
                output = smoothVideo,
            )

            if (!sourceHasAudio) {
                return DerivedMedia(
                    uri = smoothVideo.toUriString(),
                    durationUs = schedule.outputDurationUs,
                    hasAudio = false,
                    smoothInterpolated = true,
                )
            }

            finalOutput = nextFile("speed_curve_smooth")
            mergeVideoWithAudio(
                videoFile = smoothVideo,
                audioFile = checkNotNull(retimedAudio),
                output = finalOutput,
            )
            smoothVideo.delete()
            retimedAudio.delete()
            return DerivedMedia(
                uri = finalOutput.toUriString(),
                durationUs = schedule.outputDurationUs,
                hasAudio = true,
                smoothInterpolated = true,
            )
        } catch (error: Throwable) {
            smoothVideo.delete()
            retimedAudio?.delete()
            finalOutput?.delete()
            throw error
        }
    }

    private suspend fun runCurveTransformer(
        clip: TimelineClip,
        schedule: SpeedCurveSchedule,
        frameRate: Int,
        output: File,
        sourceHasAudio: Boolean,
        audioOnly: Boolean,
    ) {
        val provider = CurveSpeedProvider(schedule, clip.sourceInUs)
        val mediaItem = MediaItem.Builder()
            .setUri(clip.uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionUs(clip.sourceInUs)
                    .setEndPositionUs(clip.sourceOutUs)
                    .build(),
            )
            .build()
        val builder = EditedMediaItem.Builder(mediaItem)
            .setSpeed(provider)
        if (audioOnly) {
            builder.setRemoveVideo(true)
        } else {
            builder.setFrameRate(frameRate.coerceAtLeast(1))
        }
        val edited = builder.build()
        val trackTypes = when {
            audioOnly -> setOf(C.TRACK_TYPE_AUDIO)
            sourceHasAudio -> setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)
            else -> setOf(C.TRACK_TYPE_VIDEO)
        }
        val sequence = EditedMediaItemSequence.Builder(trackTypes).addItem(edited).build()
        runTransformer(Composition.Builder(listOf(sequence)).build(), output)
    }

    private suspend fun renderSmoothCurveVideo(
        clip: TimelineClip,
        schedule: SpeedCurveSchedule,
        frameRate: Int,
        output: File,
    ) = withContext(Dispatchers.Default) {
        val fps = frameRate.coerceIn(1, 60)
        val frameStepUs = (1_000_000L / fps).coerceAtLeast(1L)
        val coroutineContext = currentCoroutineContext()
        val lease = PreviewExportCoordinator.acquireAnalysisLease("Smooth Slow Motion")
        var encoder: CpuAvcEncoder? = null
        var previousPixels: IntArray? = null
        var previousOutputUs = 0L
        var nextOutputUs = 0L
        var targetWidth = 0
        var targetHeight = 0
        var blendScratch: IntArray? = null
        try {
            val decoder = GpuSequentialCutoutDecoderV47(
                context = appContext,
                analysisLongEdge = SMOOTH_MAX_LONG_EDGE,
            )
            decoder.decodeTargets(
                uri = Uri.parse(clip.uri),
                startUs = clip.sourceInUs,
                endUs = clip.sourceOutUs,
                targetTimesUs = emptyList(),
                emitEveryFrame = true,
            ) { sourceTimeUs, bitmap ->
                coroutineContext.ensureActive()
                try {
                    if (encoder == null) {
                        targetWidth = even(bitmap.width)
                        targetHeight = even(bitmap.height)
                        encoder = CpuAvcEncoder(targetWidth, targetHeight, fps, output)
                        blendScratch = IntArray(targetWidth * targetHeight)
                    }
                    val currentPixels = framePixels(bitmap, targetWidth, targetHeight)
                    val mappedOutputUs = schedule.outputTimeForSourceTime(
                        (sourceTimeUs - clip.sourceInUs).coerceIn(0L, schedule.durationUs),
                    )
                    val previous = previousPixels
                    if (previous == null) {
                        while (nextOutputUs <= mappedOutputUs && nextOutputUs < schedule.outputDurationUs) {
                            encoder?.encodeFrame(currentPixels, nextOutputUs)
                            nextOutputUs += frameStepUs
                        }
                    } else if (mappedOutputUs > previousOutputUs) {
                        val span = (mappedOutputUs - previousOutputUs).coerceAtLeast(1L)
                        while (nextOutputUs <= mappedOutputUs && nextOutputUs < schedule.outputDurationUs) {
                            val amount = ((nextOutputUs - previousOutputUs).toDouble() / span.toDouble())
                                .toFloat()
                                .coerceIn(0f, 1f)
                            val pixels = when {
                                amount <= .0001f -> previous
                                amount >= .9999f -> currentPixels
                                else -> SmoothFrameInterpolator.blendArgb(
                                    previous,
                                    currentPixels,
                                    amount,
                                    checkNotNull(blendScratch),
                                )
                            }
                            encoder?.encodeFrame(pixels, nextOutputUs)
                            nextOutputUs += frameStepUs
                        }
                    }
                    previousPixels = currentPixels
                    previousOutputUs = mappedOutputUs
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }

            val last = previousPixels ?: error("No video frames decoded for Smooth Slow Motion")
            val liveEncoder = encoder ?: error("Smooth Slow Motion encoder was not created")
            if (nextOutputUs == 0L) {
                liveEncoder.encodeFrame(last, 0L)
                nextOutputUs = frameStepUs
            }
            while (nextOutputUs < schedule.outputDurationUs) {
                coroutineContext.ensureActive()
                liveEncoder.encodeFrame(last, nextOutputUs)
                nextOutputUs += frameStepUs
            }
            liveEncoder.finish()
        } catch (error: Throwable) {
            output.delete()
            throw error
        } finally {
            runCatching { encoder?.close() }
            lease.close()
        }
    }

    private suspend fun mergeVideoWithAudio(
        videoFile: File,
        audioFile: File,
        output: File,
    ) {
        val videoItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(videoFile)))
            .setRemoveAudio(true)
            .build()
        val audioItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(audioFile)))
            .setRemoveVideo(true)
            .build()
        val videoSequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
            .addItem(videoItem)
            .build()
        val audioSequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
            .addItem(audioItem)
            .build()
        runTransformer(Composition.Builder(listOf(videoSequence, audioSequence)).build(), output)
    }

    suspend fun reverseVideo(clip: TimelineClip, frameRate: Int): DerivedMedia = withContext(Dispatchers.Default) {
        val fps = frameRate.coerceIn(1, 60)
        val durationUs = clip.durationUs.coerceAtLeast(1L)
        val frameStepUs = (1_000_000L / fps).coerceAtLeast(1L)
        val frameCount = ceil(durationUs.toDouble() / frameStepUs.toDouble()).toInt().coerceAtLeast(1)
        val retriever = MediaMetadataRetriever()
        val output = nextFile("reverse")
        try {
            retriever.setDataSource(appContext, Uri.parse(clip.uri))
            val firstSourceUs = (clip.sourceOutUs - 1L).coerceAtLeast(clip.sourceInUs)
            val first = retriever.getFrameAtTime(firstSourceUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: error("Could not decode selected video")
            val targetWidth = even(first.width)
            val targetHeight = even(first.height)
            first.recycle()
            CpuAvcEncoder(targetWidth, targetHeight, fps, output).use { encoder ->
                for (index in 0 until frameCount) {
                    val sourceUs = (clip.sourceOutUs - 1L - index * frameStepUs)
                        .coerceIn(clip.sourceInUs, (clip.sourceOutUs - 1L).coerceAtLeast(clip.sourceInUs))
                    val frame = retriever.getFrameAtTime(sourceUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        ?: continue
                    try {
                        encoder.encodeFrame(framePixels(frame, targetWidth, targetHeight), index * frameStepUs)
                    } finally {
                        frame.recycle()
                    }
                }
                encoder.finish()
            }
            DerivedMedia(output.toUriString(), frameCount * frameStepUs, hasAudio = false)
        } catch (error: Throwable) {
            output.delete()
            throw error
        } finally {
            retriever.release()
        }
    }

    suspend fun freezeFrame(
        clip: TimelineClip,
        timelineUs: Long,
        freezeDurationUs: Long,
        frameRate: Int,
    ): DerivedMedia = withContext(Dispatchers.Default) {
        val fps = frameRate.coerceIn(1, 60)
        val durationUs = freezeDurationUs.coerceIn(100_000L, 30_000_000L)
        val frameStepUs = (1_000_000L / fps).coerceAtLeast(1L)
        val frameCount = ceil(durationUs.toDouble() / frameStepUs.toDouble()).toInt().coerceAtLeast(1)
        val localUs = (timelineUs - clip.timelineStartUs).coerceIn(0L, (clip.durationUs - 1L).coerceAtLeast(0L))
        val sourceUs = (clip.sourceInUs + localUs)
            .coerceIn(clip.sourceInUs, (clip.sourceOutUs - 1L).coerceAtLeast(clip.sourceInUs))
        val retriever = MediaMetadataRetriever()
        val output = nextFile("freeze")
        try {
            retriever.setDataSource(appContext, Uri.parse(clip.uri))
            val frame = retriever.getFrameAtTime(sourceUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: error("Could not decode freeze frame")
            try {
                val targetWidth = even(frame.width)
                val targetHeight = even(frame.height)
                val pixels = framePixels(frame, targetWidth, targetHeight)
                CpuAvcEncoder(targetWidth, targetHeight, fps, output).use { encoder ->
                    for (index in 0 until frameCount) {
                        encoder.encodeFrame(pixels, index * frameStepUs)
                    }
                    encoder.finish()
                }
            } finally {
                frame.recycle()
            }
            DerivedMedia(output.toUriString(), frameCount * frameStepUs, hasAudio = false)
        } catch (error: Throwable) {
            output.delete()
            throw error
        } finally {
            retriever.release()
        }
    }

    private suspend fun runTransformer(composition: Composition, output: File) = suspendCancellableCoroutine<Unit> { continuation ->
        if (output.exists()) output.delete()
        lateinit var transformer: Transformer
        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                if (continuation.isActive) continuation.resume(Unit)
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException,
            ) {
                output.delete()
                if (continuation.isActive) continuation.resumeWithException(exportException)
            }
        }
        transformer = Transformer.Builder(appContext).addListener(listener).build()
        continuation.invokeOnCancellation { runCatching { transformer.cancel() } }
        runCatching { transformer.start(composition, output.absolutePath) }
            .onFailure { error ->
                output.delete()
                if (continuation.isActive) continuation.resumeWithException(error)
            }
    }

    private fun framePixels(frame: Bitmap, width: Int, height: Int): IntArray {
        val scaled = if (frame.width == width && frame.height == height) frame
        else Bitmap.createScaledBitmap(frame, width, height, true)
        return try {
            IntArray(width * height).also { pixels ->
                scaled.getPixels(pixels, 0, width, 0, 0, width, height)
            }
        } finally {
            if (scaled !== frame) scaled.recycle()
        }
    }

    private fun hasAudio(uri: String): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, Uri.parse(uri))
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                ?.equals("yes", ignoreCase = true) == true
        } catch (_: Throwable) {
            false
        } finally {
            retriever.release()
        }
    }

    private fun nextFile(prefix: String): File =
        File(outputDir, "${prefix}_${System.currentTimeMillis()}_${System.nanoTime()}.mp4")

    private fun File.toUriString(): String = Uri.fromFile(this).toString()

    private fun even(value: Int): Int {
        val safe = value.coerceAtLeast(2)
        return if (safe % 2 == 0) safe else safe - 1
    }

    private class CurveSpeedProvider(
        private val schedule: SpeedCurveSchedule,
        private val sourceOffsetUs: Long,
    ) : SpeedProvider {
        private var absoluteClock: Boolean? = null

        override fun getSpeed(timeUs: Long): Float =
            schedule.speedAtSourceTime(relativeTime(timeUs))

        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long {
            val relative = relativeTime(timeUs)
            val next = schedule.nextChangeAfter(relative) ?: return C.TIME_UNSET
            return if (absoluteClock == true) sourceOffsetUs + next else next
        }

        private fun relativeTime(timeUs: Long): Long {
            if (absoluteClock == null) {
                absoluteClock = sourceOffsetUs > 0L && timeUs >= sourceOffsetUs
            }
            return if (absoluteClock == true) {
                (timeUs - sourceOffsetUs).coerceIn(0L, schedule.durationUs)
            } else {
                timeUs.coerceIn(0L, schedule.durationUs)
            }
        }
    }

    private companion object {
        const val SMOOTH_MAX_LONG_EDGE = 4_096
    }
}
