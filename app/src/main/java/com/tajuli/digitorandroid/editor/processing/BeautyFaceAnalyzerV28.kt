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
import com.tajuli.digitorandroid.editor.model.BeautyFaceGeometryV28
import com.tajuli.digitorandroid.editor.model.BeautyFaceSampleV28
import com.tajuli.digitorandroid.editor.model.BeautyFaceTrackV28
import com.tajuli.digitorandroid.editor.model.BeautyRectV28
import com.tajuli.digitorandroid.editor.model.TimelineClip
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlinx.coroutines.suspendCancellableCoroutine

/** Persisted local face tracks keep beauty masks available after save/load without bloating project JSON. */
object BeautyFaceTrackStoreV28 {
    private val gson = Gson()

    fun load(context: Context, clip: TimelineClip): BeautyFaceTrackV28? {
        val file = fileFor(context, clip.uri)
        if (!file.isFile) return null
        return runCatching { gson.fromJson(file.readText(), BeautyFaceTrackV28::class.java) }
            .getOrNull()
            ?.takeIf { it.sourceUri == clip.uri && it.version == 1 }
    }

    fun hasCoverage(context: Context, clip: TimelineClip): Boolean =
        load(context, clip)?.covers(clip.sourceInUs, clip.sourceOutUs) == true

    fun save(context: Context, track: BeautyFaceTrackV28) {
        val file = fileFor(context, track.sourceUri)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(gson.toJson(track))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    private fun fileFor(context: Context, uri: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(32)
        return File(File(context.filesDir, "beauty_face_tracks_v32_fast"), "$digest.json")
    }
}

/**
 * Low-latency beauty analysis.
 *
 * The editor never needs to decode hundreds of random frames before showing or exporting a filter.
 * A five-anchor face seed upgrades the instant GPU fallback quickly; a bounded background refinement
 * then uses <=48 low-resolution sync frames and <=12 semantic mask frames for the whole clip.
 */
class BeautyFaceAnalyzerV28(private val context: Context) {
    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .setMinFaceSize(.12f)
        .enableTracking()
        .build()

    /** Very small seed used by preview. No semantic model is run here. */
    suspend fun primeAndStore(clip: TimelineClip): BeautyFaceTrackV28 {
        val existing = BeautyFaceTrackStoreV28.load(context, clip)
        if (existing != null && existing.samples.count { it.geometry != null } >= PRIME_FACE_ANCHORS) return existing

        val fresh = if (clip.isImageV21) {
            analyzeImage(clip, requireHairMask = false, requireSkinMask = false)
        } else {
            analyzeVideoAnchors(
                clip = clip,
                faceAnchorCount = PRIME_FACE_ANCHORS,
                requireHairMask = false,
                requireSkinMask = false,
                semanticAnchorLimit = 0,
                frameOption = MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            )
        }
        val merged = existing?.mergedWith(fresh) ?: fresh
        BeautyFaceTrackStoreV28.save(context, merged)
        return merged
    }

    /** Bounded background quality refinement. Never performs the old hundreds-of-seeks scan. */
    suspend fun refineFastAndStore(
        clip: TimelineClip,
        requireHairMask: Boolean = true,
        requireSkinMask: Boolean = true,
    ): BeautyFaceTrackV28 {
        val existing = BeautyFaceTrackStoreV28.load(context, clip)
        val targetFaceAnchors = targetFaceAnchorCount(clip)
        val faceReady = existing?.covers(clip.sourceInUs, clip.sourceOutUs) == true &&
            existing.samples.count { it.geometry != null } >= minOf(targetFaceAnchors, 12)
        val hairReady = !requireHairMask || BeautyHairMaskStoreV29.hasCoverage(context, clip)
        val skinReady = !requireSkinMask || BeautyFaceSkinMaskStoreV31.hasCoverage(context, clip)
        if (faceReady && hairReady && skinReady) return existing!!

        val fresh = if (clip.isImageV21) {
            analyzeImage(clip, requireHairMask, requireSkinMask)
        } else {
            analyzeVideoAnchors(
                clip = clip,
                faceAnchorCount = targetFaceAnchors,
                requireHairMask = requireHairMask,
                requireSkinMask = requireSkinMask,
                semanticAnchorLimit = SEMANTIC_ANCHOR_LIMIT,
                frameOption = MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            )
        }
        val merged = existing?.mergedWith(fresh) ?: fresh
        BeautyFaceTrackStoreV28.save(context, merged)
        return merged
    }

    /** Kept as the public compatibility entry point; now delegates to the bounded fast path. */
    suspend fun analyzeAndStore(
        clip: TimelineClip,
        requireHairMask: Boolean = true,
        requireSkinMask: Boolean = true,
    ): BeautyFaceTrackV28 = refineFastAndStore(clip, requireHairMask, requireSkinMask)

    private suspend fun analyzeImage(
        clip: TimelineClip,
        requireHairMask: Boolean,
        requireSkinMask: Boolean,
    ): BeautyFaceTrackV28 {
        val detector = FaceDetection.getClient(options)
        val hairSegmenter = if (requireHairMask) BeautyHairSegmenterV29(context) else null
        val skinSegmenter = if (requireSkinMask) BeautyFaceSkinSegmenterV31(context) else null
        val bitmap = decodeImage(Uri.parse(clip.uri))
        try {
            val geometry = bitmap?.let { image ->
                hairSegmenter?.segmentAndStore(context, clip, image, clip.sourceInUs)
                skinSegmenter?.segmentAndStore(context, clip, image, clip.sourceInUs)
                detectPrimaryGeometry(detector, image)
            }
            val end = clip.sourceOutUs.coerceAtLeast(clip.sourceInUs + 1L)
            return BeautyFaceTrackV28(
                sourceUri = clip.uri,
                analyzedStartUs = clip.sourceInUs,
                analyzedEndUs = end,
                samples = listOf(
                    BeautyFaceSampleV28(clip.sourceInUs, geometry),
                    BeautyFaceSampleV28(end, geometry),
                ),
            )
        } finally {
            bitmap?.recycle()
            skinSegmenter?.close()
            hairSegmenter?.close()
            detector.close()
        }
    }

    private suspend fun analyzeVideoAnchors(
        clip: TimelineClip,
        faceAnchorCount: Int,
        requireHairMask: Boolean,
        requireSkinMask: Boolean,
        semanticAnchorLimit: Int,
        frameOption: Int,
    ): BeautyFaceTrackV28 {
        val detector = FaceDetection.getClient(options)
        val hairSegmenter = if (requireHairMask) BeautyHairSegmenterV29(context) else null
        val skinSegmenter = if (requireSkinMask) BeautyFaceSkinSegmenterV31(context) else null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(clip.uri))
            val start = clip.sourceInUs.coerceAtLeast(0L)
            val end = clip.sourceOutUs.coerceAtLeast(start + 1L)
            val times = evenlySpacedTimes(start, end, faceAnchorCount)
            val semanticStride = if (semanticAnchorLimit <= 0) Int.MAX_VALUE
            else max(1, (times.size - 1) / max(1, semanticAnchorLimit - 1))
            val samples = ArrayList<BeautyFaceSampleV28>(times.size)

            for ((index, sourceUs) in times.withIndex()) {
                val frame = scaledFrameAtTime(retriever, sourceUs, frameOption)
                if (frame == null) {
                    samples += BeautyFaceSampleV28(sourceUs, null)
                    continue
                }
                try {
                    val semanticAnchor = semanticAnchorLimit > 0 &&
                        (index == 0 || index == times.lastIndex || index % semanticStride == 0)
                    if (semanticAnchor) {
                        hairSegmenter?.segmentAndStore(context, clip, frame, sourceUs)
                        skinSegmenter?.segmentAndStore(context, clip, frame, sourceUs)
                    }
                    samples += BeautyFaceSampleV28(sourceUs, detectPrimaryGeometry(detector, frame))
                } finally {
                    frame.recycle()
                }
            }

            return BeautyFaceTrackV28(
                sourceUri = clip.uri,
                analyzedStartUs = start,
                analyzedEndUs = end,
                samples = samples,
            )
        } finally {
            runCatching { retriever.release() }
            skinSegmenter?.close()
            hairSegmenter?.close()
            detector.close()
        }
    }

    private fun targetFaceAnchorCount(clip: TimelineClip): Int {
        val durationUs = (clip.sourceOutUs - clip.sourceInUs).coerceAtLeast(1L)
        val roughlyOnePerSecond = (durationUs / 1_000_000L).toInt() + 2
        return roughlyOnePerSecond.coerceIn(MIN_FAST_FACE_ANCHORS, MAX_FAST_FACE_ANCHORS)
    }

    private fun evenlySpacedTimes(start: Long, end: Long, count: Int): List<Long> {
        val last = (end - 1L).coerceAtLeast(start)
        val safeCount = count.coerceAtLeast(2)
        if (last <= start) return listOf(start, end)
        return (0 until safeCount).map { index ->
            if (index == safeCount - 1) last
            else start + ((last - start) * index.toLong()) / (safeCount - 1).toLong()
        }.distinct()
    }

    private fun scaledFrameAtTime(retriever: MediaMetadataRetriever, sourceUs: Long, option: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (width > 0 && height > 0) {
                val longEdge = max(width, height)
                val scale = if (longEdge <= ANALYSIS_LONG_EDGE) 1f else ANALYSIS_LONG_EDGE / longEdge.toFloat()
                val targetWidth = (width * scale).toInt().coerceAtLeast(1)
                val targetHeight = (height * scale).toInt().coerceAtLeast(1)
                retriever.getScaledFrameAtTime(sourceUs, option, targetWidth, targetHeight)?.let { return it }
            }
        }
        val raw = retriever.getFrameAtTime(sourceUs, option) ?: return null
        val scaled = downscaleForDetector(raw)
        if (scaled === raw) return raw
        raw.recycle()
        return scaled
    }

    private suspend fun detectPrimaryGeometry(detector: FaceDetector, bitmap: Bitmap): BeautyFaceGeometryV28? {
        val faces = detector.detect(InputImage.fromBitmap(bitmap, 0))
        val face = faces.maxByOrNull { it.boundingBox.width().toLong() * it.boundingBox.height().toLong() }
            ?: return null
        return geometryFromFace(face, bitmap.width.coerceAtLeast(1), bitmap.height.coerceAtLeast(1))
    }

    private suspend fun FaceDetector.detect(image: InputImage): List<Face> = suspendCancellableCoroutine { continuation ->
        process(image)
            .addOnSuccessListener { faces -> if (continuation.isActive) continuation.resume(faces) }
            .addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
            .addOnCanceledListener { if (continuation.isActive) continuation.cancel() }
    }

    private fun geometryFromFace(face: Face, width: Int, height: Int): BeautyFaceGeometryV28 {
        val box = face.boundingBox
        val faceRect = BeautyRectV28(
            box.left / width.toFloat(),
            box.top / height.toFloat(),
            box.right / width.toFloat(),
            box.bottom / height.toFloat(),
        ).normalized()

        val lipsFallback = faceFraction(faceRect, .27f, .61f, .73f, .83f)
        val leftEyeFallback = faceFraction(faceRect, .12f, .30f, .47f, .52f)
        val rightEyeFallback = faceFraction(faceRect, .53f, .30f, .88f, .52f)
        val leftBrowFallback = faceFraction(faceRect, .10f, .20f, .48f, .38f)
        val rightBrowFallback = faceFraction(faceRect, .52f, .20f, .90f, .38f)

        val lips = contourBounds(
            face,
            width,
            height,
            intArrayOf(
                FaceContour.UPPER_LIP_TOP,
                FaceContour.UPPER_LIP_BOTTOM,
                FaceContour.LOWER_LIP_TOP,
                FaceContour.LOWER_LIP_BOTTOM,
            ),
            lipsFallback,
            .10f,
        )
        val leftEye = contourBounds(face, width, height, intArrayOf(FaceContour.LEFT_EYE), leftEyeFallback, .18f)
        val rightEye = contourBounds(face, width, height, intArrayOf(FaceContour.RIGHT_EYE), rightEyeFallback, .18f)
        val leftBrow = contourBounds(
            face,
            width,
            height,
            intArrayOf(FaceContour.LEFT_EYEBROW_TOP, FaceContour.LEFT_EYEBROW_BOTTOM),
            leftBrowFallback,
            .18f,
        )
        val rightBrow = contourBounds(
            face,
            width,
            height,
            intArrayOf(FaceContour.RIGHT_EYEBROW_TOP, FaceContour.RIGHT_EYEBROW_BOTTOM),
            rightBrowFallback,
            .18f,
        )

        val faceWidth = (faceRect.right - faceRect.left).coerceAtLeast(.01f)
        val faceHeight = (faceRect.bottom - faceRect.top).coerceAtLeast(.01f)
        val hair = BeautyRectV28(
            left = faceRect.left - faceWidth * .10f,
            top = faceRect.top - faceHeight * .42f,
            right = faceRect.right + faceWidth * .10f,
            bottom = faceRect.top + faceHeight * .27f,
        ).normalized()

        return BeautyFaceGeometryV28(
            face = faceRect,
            lips = lips,
            leftEye = leftEye,
            rightEye = rightEye,
            leftBrow = leftBrow,
            rightBrow = rightBrow,
            hair = hair,
        )
    }

    private fun contourBounds(
        face: Face,
        width: Int,
        height: Int,
        contourTypes: IntArray,
        fallback: BeautyRectV28,
        expandFraction: Float,
    ): BeautyRectV28 {
        val points = contourTypes.flatMap { type -> face.getContour(type)?.points.orEmpty() }
        if (points.isEmpty()) return fallback
        val left = points.minOf { it.x } / width.toFloat()
        val right = points.maxOf { it.x } / width.toFloat()
        val top = points.minOf { it.y } / height.toFloat()
        val bottom = points.maxOf { it.y } / height.toFloat()
        val w = (right - left).coerceAtLeast(.005f)
        val h = (bottom - top).coerceAtLeast(.005f)
        return BeautyRectV28(
            left - w * expandFraction,
            top - h * expandFraction,
            right + w * expandFraction,
            bottom + h * expandFraction,
        ).normalized()
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

    private fun downscaleForDetector(bitmap: Bitmap): Bitmap {
        val longEdge = max(bitmap.width, bitmap.height)
        if (longEdge <= ANALYSIS_LONG_EDGE) return bitmap
        val scale = ANALYSIS_LONG_EDGE / longEdge.toFloat()
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun decodeImage(uri: Uri): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longEdge = max(info.size.width, info.size.height)
                if (longEdge > 1080) {
                    val scale = 1080f / longEdge.toFloat()
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1),
                    )
                }
            }
        } else {
            context.contentResolver.openInputStream(uri)?.use { stream -> BitmapFactory.decodeStream(stream) }
        }
    }.getOrNull()

    companion object {
        private const val PRIME_FACE_ANCHORS = 5
        private const val MIN_FAST_FACE_ANCHORS = 10
        private const val MAX_FAST_FACE_ANCHORS = 48
        private const val SEMANTIC_ANCHOR_LIMIT = 12
        private const val ANALYSIS_LONG_EDGE = 480
    }
}
