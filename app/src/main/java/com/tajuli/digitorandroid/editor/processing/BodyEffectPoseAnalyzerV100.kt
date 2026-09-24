package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.google.gson.Gson
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.tajuli.digitorandroid.editor.model.BodyLandmarkV100
import com.tajuli.digitorandroid.editor.model.BodyPoseSampleV100
import com.tajuli.digitorandroid.editor.model.BodyPoseTrackV100
import com.tajuli.digitorandroid.editor.model.TimelineClip
import java.io.File
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Durable MediaPipe PoseLandmarker track used only by the V100 body-effect engine.
 *
 * The old architecture guessed a body rectangle from the face. V100 instead stores a real 33-point
 * skeleton at the same 12 fps cadence used by Medium PP-MattingV2, then interpolates landmarks at
 * render time. This keeps electricity/energy attached to limbs while preview and export share the
 * exact same offline analysis.
 */
object BodyPoseTrackStoreV100 {
    private val gson = Gson()

    fun load(context: Context, clip: TimelineClip): BodyPoseTrackV100? {
        val file = fileFor(context, clip.uri)
        if (!file.isFile) return null
        return runCatching { gson.fromJson(file.readText(), BodyPoseTrackV100::class.java) }
            .getOrNull()
            ?.takeIf { it.sourceUri == clip.uri && it.version == 1 }
    }

    fun save(context: Context, track: BodyPoseTrackV100) {
        val file = fileFor(context, track.sourceUri)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(gson.toJson(track))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    fun hasCoverage(context: Context, clip: TimelineClip): Boolean {
        val track = load(context, clip) ?: return false
        val expected = BodyEffectPoseAnalyzerV100.expectedSampleCount(clip)
        return track.covers(clip.sourceInUs, clip.sourceOutUs) &&
            track.samples.size >= (expected * .82f).roundToInt().coerceAtLeast(1) &&
            track.detectedRatio() >= MIN_DETECTED_RATIO
    }

    private fun fileFor(context: Context, uri: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(uri.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(32)
        return File(File(context.filesDir, "body_pose_tracks_v100_full_12fps"), "$digest.json")
    }

    private const val MIN_DETECTED_RATIO = .58f
}

class BodyEffectPoseAnalyzerV100(private val context: Context) {
    fun analyzeAndStore(clip: TimelineClip): BodyPoseTrackV100 {
        BodyPoseTrackStoreV100.load(context, clip)?.let { existing ->
            if (BodyPoseTrackStoreV100.hasCoverage(context, clip)) return existing
        }

        val fresh = if (clip.isImageV21) analyzeImage(clip) else analyzeVideo(clip)
        BodyPoseTrackStoreV100.save(context, fresh)
        return fresh
    }

    private fun analyzeImage(clip: TimelineClip): BodyPoseTrackV100 {
        val bitmap = decodeImage(Uri.parse(clip.uri))
        val sample = bitmap?.let { source ->
            try {
                createLandmarker(RunningMode.IMAGE).use { landmarker ->
                    val mpImage = BitmapImageBuilder(source).build()
                    BodyPoseSampleV100(
                        sourceTimeUs = clip.sourceInUs,
                        landmarks = landmarksFrom(landmarker.detect(mpImage)),
                    )
                }
            } finally {
                source.recycle()
            }
        } ?: BodyPoseSampleV100(clip.sourceInUs, null)

        val end = clip.sourceOutUs.coerceAtLeast(clip.sourceInUs + 1L)
        return BodyPoseTrackV100(
            sourceUri = clip.uri,
            analyzedStartUs = clip.sourceInUs,
            analyzedEndUs = end,
            samples = listOf(sample, sample.copy(sourceTimeUs = end)),
        )
    }

    private fun analyzeVideo(clip: TimelineClip): BodyPoseTrackV100 {
        val retriever = MediaMetadataRetriever()
        val start = clip.sourceInUs.coerceAtLeast(0L)
        val end = clip.sourceOutUs.coerceAtLeast(start + 1L)
        val samples = ArrayList<BodyPoseSampleV100>()
        try {
            retriever.setDataSource(context, Uri.parse(clip.uri))
            createLandmarker(RunningMode.VIDEO).use { landmarker ->
                targetTimes(start, end).forEach { sourceUs ->
                    val frame = scaledFrameAtTime(retriever, sourceUs)
                    if (frame == null) {
                        samples += BodyPoseSampleV100(sourceUs, null)
                        return@forEach
                    }
                    try {
                        val mpImage = BitmapImageBuilder(frame).build()
                        val timestampMs = sourceUs / 1_000L
                        val result = landmarker.detectForVideo(mpImage, timestampMs)
                        samples += BodyPoseSampleV100(sourceUs, landmarksFrom(result))
                    } finally {
                        frame.recycle()
                    }
                }
            }
        } finally {
            runCatching { retriever.release() }
        }
        return BodyPoseTrackV100(
            sourceUri = clip.uri,
            analyzedStartUs = start,
            analyzedEndUs = end,
            samples = samples,
        )
    }

    private fun createLandmarker(mode: RunningMode): PoseLandmarker {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .setDelegate(Delegate.CPU)
            .build()
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(mode)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(.55f)
            .setMinPosePresenceConfidence(.55f)
            .setMinTrackingConfidence(.62f)
            .build()
        return PoseLandmarker.createFromOptions(context, options)
    }

    private fun landmarksFrom(result: com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult): List<BodyLandmarkV100>? {
        val pose = result.landmarks().firstOrNull() ?: return null
        if (pose.size < EXPECTED_LANDMARKS) return null
        return pose.take(EXPECTED_LANDMARKS).map { point ->
            BodyLandmarkV100(
                x = point.x().coerceIn(0f, 1f),
                y = point.y().coerceIn(0f, 1f),
                z = point.z(),
            )
        }
    }

    private fun scaledFrameAtTime(retriever: MediaMetadataRetriever, sourceUs: Long): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            if (width > 0 && height > 0) {
                val longEdge = max(width, height)
                val scale = if (longEdge <= ANALYSIS_LONG_EDGE) 1f
                else ANALYSIS_LONG_EDGE / longEdge.toFloat()
                val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
                val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
                retriever.getScaledFrameAtTime(
                    sourceUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    targetWidth,
                    targetHeight,
                )?.let(::ensureArgb)?.let { return it }
            }
        }

        val raw = retriever.getFrameAtTime(sourceUs, MediaMetadataRetriever.OPTION_CLOSEST)
            ?: return null
        val normalized = ensureArgb(raw)
        if (normalized !== raw && !raw.isRecycled) raw.recycle()
        val longEdge = max(normalized.width, normalized.height)
        if (longEdge <= ANALYSIS_LONG_EDGE) return normalized
        val scale = ANALYSIS_LONG_EDGE / longEdge.toFloat()
        return Bitmap.createScaledBitmap(
            normalized,
            (normalized.width * scale).roundToInt().coerceAtLeast(1),
            (normalized.height * scale).roundToInt().coerceAtLeast(1),
            true,
        ).also { if (it !== normalized) normalized.recycle() }
    }

    private fun ensureArgb(bitmap: Bitmap): Bitmap =
        if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
        else bitmap.copy(Bitmap.Config.ARGB_8888, false)
            ?: error("Could not convert pose-analysis frame to ARGB_8888")

    private fun decodeImage(uri: Uri): Bitmap? = runCatching {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longEdge = max(info.size.width, info.size.height)
                if (longEdge > ANALYSIS_LONG_EDGE) {
                    val scale = ANALYSIS_LONG_EDGE / longEdge.toFloat()
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
            }
        } else {
            context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
        } ?: return@runCatching null
        ensureArgb(raw).also { normalized ->
            if (normalized !== raw && !raw.isRecycled) raw.recycle()
        }
    }.getOrNull()

    companion object {
        private const val MODEL_ASSET = "pose_landmarker_full.task"
        private const val ANALYSIS_FPS = 12
        private const val ANALYSIS_LONG_EDGE = 720
        private const val EXPECTED_LANDMARKS = 33

        internal fun expectedSampleCount(clip: TimelineClip): Int {
            if (clip.isImageV21) return 2
            val durationUs = (clip.sourceOutUs - clip.sourceInUs).coerceAtLeast(1L)
            return ceil(durationUs / 1_000_000.0 * ANALYSIS_FPS.toDouble())
                .toInt()
                .coerceAtLeast(1)
        }

        private fun targetTimes(startUs: Long, endUs: Long): List<Long> {
            val start = startUs.coerceAtLeast(0L)
            val end = endUs.coerceAtLeast(start + 1L)
            val intervalUs = 1_000_000L / ANALYSIS_FPS
            val out = ArrayList<Long>()
            var t = start
            while (t < end) {
                out += t
                t += intervalUs
            }
            val last = (end - 1L).coerceAtLeast(start)
            if (out.isEmpty() || out.last() != last) out += last
            return out.distinct()
        }
    }
}
