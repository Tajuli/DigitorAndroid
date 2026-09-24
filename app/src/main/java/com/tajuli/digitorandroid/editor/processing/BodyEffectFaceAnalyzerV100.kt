package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.google.gson.Gson
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.tajuli.digitorandroid.editor.model.BeautyRectV28
import com.tajuli.digitorandroid.editor.model.BodyFaceSampleV100
import com.tajuli.digitorandroid.editor.model.BodyFaceTrackV100
import com.tajuli.digitorandroid.editor.model.TimelineClip
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Body-effect-specific face tracker.
 *
 * Beauty tracking is intentionally not reused: eye-attached VFX need a dense, accuracy-first,
 * independently versioned track. This prevents beauty-cache density or detector settings from
 * changing Fire/Electric/Laser Eyes placement.
 */
object BodyFaceTrackStoreV100 {
    private val gson = Gson()

    fun load(context: Context, clip: TimelineClip): BodyFaceTrackV100? {
        val file = fileFor(context, clip.uri)
        if (!file.isFile) return null
        return runCatching { gson.fromJson(file.readText(), BodyFaceTrackV100::class.java) }
            .getOrNull()
            ?.takeIf { it.sourceUri == clip.uri && it.version == 1 }
    }

    fun save(context: Context, track: BodyFaceTrackV100) {
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
        val expected = BodyEffectFaceAnalyzerV100.expectedSampleCount(clip)
        return track.covers(clip.sourceInUs, clip.sourceOutUs) &&
            track.samples.size >= (expected * .82f).roundToInt().coerceAtLeast(1) &&
            track.detectedRatio() >= MIN_DETECTED_RATIO
    }

    private fun fileFor(context: Context, uri: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(uri.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(32)
        return File(File(context.filesDir, "body_face_tracks_v100_accurate_12fps"), "$digest.json")
    }

    private const val MIN_DETECTED_RATIO = .60f
}

class BodyEffectFaceAnalyzerV100(private val context: Context) {
    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .setMinFaceSize(.08f)
        .build()

    suspend fun analyzeAndStore(clip: TimelineClip): BodyFaceTrackV100 {
        BodyFaceTrackStoreV100.load(context, clip)?.let { existing ->
            if (BodyFaceTrackStoreV100.hasCoverage(context, clip)) return existing
        }

        val raw = if (clip.isImageV21) analyzeImage(clip) else analyzeVideo(clip)
        val stabilized = raw.copy(samples = stabilize(raw.samples))
        BodyFaceTrackStoreV100.save(context, stabilized)
        return stabilized
    }

    private suspend fun analyzeImage(clip: TimelineClip): BodyFaceTrackV100 {
        val bitmap = decodeImage(Uri.parse(clip.uri))
        val detector = FaceDetection.getClient(options)
        try {
            val eyes = bitmap?.let { detectEyes(detector, it) }
            val start = clip.sourceInUs
            val end = clip.sourceOutUs.coerceAtLeast(start + 1L)
            val sample = BodyFaceSampleV100(start, eyes?.first, eyes?.second)
            return BodyFaceTrackV100(
                sourceUri = clip.uri,
                analyzedStartUs = start,
                analyzedEndUs = end,
                samples = listOf(sample, sample.copy(sourceTimeUs = end)),
            )
        } finally {
            bitmap?.recycle()
            detector.close()
        }
    }

    private suspend fun analyzeVideo(clip: TimelineClip): BodyFaceTrackV100 {
        val detector = FaceDetection.getClient(options)
        val retriever = MediaMetadataRetriever()
        val start = clip.sourceInUs.coerceAtLeast(0L)
        val end = clip.sourceOutUs.coerceAtLeast(start + 1L)
        val samples = ArrayList<BodyFaceSampleV100>()
        try {
            retriever.setDataSource(context, Uri.parse(clip.uri))
            for (sourceUs in targetTimes(start, end)) {
                val frame = scaledFrameAtTime(retriever, sourceUs)
                if (frame == null) {
                    samples += BodyFaceSampleV100(sourceUs, null, null)
                    continue
                }
                try {
                    val eyes = detectEyes(detector, frame)
                    samples += BodyFaceSampleV100(sourceUs, eyes?.first, eyes?.second)
                } finally {
                    frame.recycle()
                }
            }
        } finally {
            runCatching { retriever.release() }
            detector.close()
        }
        return BodyFaceTrackV100(
            sourceUri = clip.uri,
            analyzedStartUs = start,
            analyzedEndUs = end,
            samples = samples,
        )
    }

    private suspend fun detectEyes(
        detector: FaceDetector,
        bitmap: Bitmap,
    ): Pair<BeautyRectV28, BeautyRectV28>? {
        val faces = detector.detect(InputImage.fromBitmap(bitmap, 0))
        val face = faces.maxByOrNull {
            it.boundingBox.width().toLong() * it.boundingBox.height().toLong()
        } ?: return null

        val faceRect = BeautyRectV28(
            face.boundingBox.left / bitmap.width.toFloat(),
            face.boundingBox.top / bitmap.height.toFloat(),
            face.boundingBox.right / bitmap.width.toFloat(),
            face.boundingBox.bottom / bitmap.height.toFloat(),
        ).normalized()

        val leftFallback = faceFraction(faceRect, .12f, .29f, .47f, .52f)
        val rightFallback = faceFraction(faceRect, .53f, .29f, .88f, .52f)
        val left = contourBounds(face, bitmap.width, bitmap.height, FaceContour.LEFT_EYE, leftFallback)
        val right = contourBounds(face, bitmap.width, bitmap.height, FaceContour.RIGHT_EYE, rightFallback)
        return left to right
    }

    private fun contourBounds(
        face: Face,
        width: Int,
        height: Int,
        contourType: Int,
        fallback: BeautyRectV28,
    ): BeautyRectV28 {
        val points = face.getContour(contourType)?.points.orEmpty()
        if (points.isEmpty()) return fallback
        val left = points.minOf { it.x } / width.toFloat()
        val right = points.maxOf { it.x } / width.toFloat()
        val top = points.minOf { it.y } / height.toFloat()
        val bottom = points.maxOf { it.y } / height.toFloat()
        val w = (right - left).coerceAtLeast(.005f)
        val h = (bottom - top).coerceAtLeast(.005f)
        return BeautyRectV28(
            left - w * EYE_EXPAND,
            top - h * EYE_EXPAND,
            right + w * EYE_EXPAND,
            bottom + h * EYE_EXPAND,
        ).normalized()
    }

    /**
     * Fills only very short detector dropouts, then applies motion-adaptive smoothing. Fast motion
     * gets a high alpha (low lag); small frame-to-frame jitter gets stronger filtering.
     */
    private fun stabilize(samples: List<BodyFaceSampleV100>): List<BodyFaceSampleV100> {
        if (samples.size < 2) return samples
        val filled = samples.toMutableList()

        for (i in 1 until filled.lastIndex) {
            if (filled[i].detected) continue
            val before = filled[i - 1]
            val after = filled[i + 1]
            if (!before.detected || !after.detected) continue
            val span = (after.sourceTimeUs - before.sourceTimeUs).coerceAtLeast(1L)
            if (span > SHORT_GAP_MAX_US) continue
            val t = ((filled[i].sourceTimeUs - before.sourceTimeUs).toDouble() / span.toDouble())
                .toFloat()
                .coerceIn(0f, 1f)
            filled[i] = filled[i].copy(
                leftEye = before.leftEye!!.lerp(after.leftEye!!, t),
                rightEye = before.rightEye!!.lerp(after.rightEye!!, t),
            )
        }

        var previous: BodyFaceSampleV100? = null
        return filled.map { current ->
            val prev = previous
            if (!current.detected || prev == null || !prev.detected) {
                previous = current
                current
            } else {
                fun smooth(a: BeautyRectV28, b: BeautyRectV28): BeautyRectV28 {
                    val acx = (a.left + a.right) * .5f
                    val acy = (a.top + a.bottom) * .5f
                    val bcx = (b.left + b.right) * .5f
                    val bcy = (b.top + b.bottom) * .5f
                    val motion = kotlin.math.sqrt(
                        (bcx - acx) * (bcx - acx) + (bcy - acy) * (bcy - acy),
                    )
                    val alpha = (.38f + motion * 9f).coerceIn(.38f, .92f)
                    return a.lerp(b, alpha)
                }
                val output = current.copy(
                    leftEye = smooth(prev.leftEye!!, current.leftEye!!),
                    rightEye = smooth(prev.rightEye!!, current.rightEye!!),
                )
                previous = output
                output
            }
        }
    }

    private suspend fun FaceDetector.detect(image: InputImage): List<Face> =
        suspendCancellableCoroutine { continuation ->
            process(image)
                .addOnSuccessListener { faces ->
                    if (continuation.isActive) continuation.resume(faces)
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
                .addOnCanceledListener {
                    if (continuation.isActive) continuation.cancel()
                }
        }

    private fun faceFraction(
        face: BeautyRectV28,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): BeautyRectV28 {
        val w = face.right - face.left
        val h = face.bottom - face.top
        return BeautyRectV28(
            face.left + w * left,
            face.top + h * top,
            face.left + w * right,
            face.top + h * bottom,
        ).normalized()
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
                retriever.getScaledFrameAtTime(
                    sourceUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    (width * scale).roundToInt().coerceAtLeast(1),
                    (height * scale).roundToInt().coerceAtLeast(1),
                )?.let(::ensureArgb)?.let { return it }
            }
        }
        val raw = retriever.getFrameAtTime(sourceUs, MediaMetadataRetriever.OPTION_CLOSEST)
            ?: return null
        val normalized = ensureArgb(raw)
        if (normalized !== raw && !raw.isRecycled) raw.recycle()
        return normalized
    }

    private fun ensureArgb(bitmap: Bitmap): Bitmap =
        if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
        else bitmap.copy(Bitmap.Config.ARGB_8888, false)
            ?: error("Could not convert body-face frame to ARGB_8888")

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
        private const val ANALYSIS_FPS = 12
        private const val ANALYSIS_LONG_EDGE = 720
        private const val EYE_EXPAND = .10f
        private const val SHORT_GAP_MAX_US = 190_000L

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
