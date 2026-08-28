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
import com.tajuli.digitorandroid.editor.model.TimelineClip
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
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
    )

    private val appContext = context.applicationContext
    private val outputDir = File(appContext.filesDir, "derived_media").apply { mkdirs() }

    suspend fun bakeSpeed(clip: TimelineClip, speed: Float, frameRate: Int): DerivedMedia {
        val safeSpeed = speed.coerceIn(.25f, 4f)
        val output = nextFile("speed")
        val sourceHasAudio = hasAudio(clip.uri)
        val provider = object : SpeedProvider {
            override fun getSpeed(timeUs: Long): Float = safeSpeed
            override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
        }
        val mediaItem = MediaItem.Builder()
            .setUri(clip.uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionUs(clip.sourceInUs)
                    .setEndPositionUs(clip.sourceOutUs)
                    .build(),
            )
            .build()
        val edited = EditedMediaItem.Builder(mediaItem)
            .setSpeed(provider)
            .setFrameRate(frameRate.coerceAtLeast(1))
            .build()
        val trackTypes = if (sourceHasAudio) {
            setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)
        } else {
            setOf(C.TRACK_TYPE_VIDEO)
        }
        val sequence = EditedMediaItemSequence.Builder(trackTypes).addItem(edited).build()
        val composition = Composition.Builder(listOf(sequence)).build()

        runTransformer(composition, output)
        val expectedDurationUs = (clip.durationUs.toDouble() / safeSpeed.toDouble()).toLong().coerceAtLeast(1L)
        return DerivedMedia(output.toUriString(), expectedDurationUs, sourceHasAudio)
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
}
