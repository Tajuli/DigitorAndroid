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
        return File(File(context.filesDir, "beauty_face_tracks_v28"), "$digest.json")
    }
}

/**
 * On-device face/contour analysis used by stackable beauty filters.
 *
 * ML Kit provides the face box and feature contours. Hair is not a semantic ML Kit contour, so its
 * mask is a conservative face-aware upper-head region that the GPU further gates by dark hair-like
 * pixels. This avoids recoloring the background while keeping the feature fully offline.
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

    suspend fun analyzeAndStore(clip: TimelineClip): BeautyFaceTrackV28 {
        val existing = BeautyFaceTrackStoreV28.load(context, clip)
        if (existing?.covers(clip.sourceInUs, clip.sourceOutUs) == true) return existing

        val fresh = if (clip.isImageV21) analyzeImage(clip) else analyzeVideo(clip)
        val merged = existing?.mergedWith(fresh) ?: fresh
        BeautyFaceTrackStoreV28.save(context, merged)
        return merged
    }

    private suspend fun analyzeImage(clip: TimelineClip): BeautyFaceTrackV28 {
        val detector = FaceDetection.getClient(options)
        val bitmap = decodeImage(Uri.parse(clip.uri))
        try {
            val geometry = bitmap?.let { detectPrimaryGeometry(detector, it) }
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
            detector.close()
        }
    }

    private suspend fun analyzeVideo(clip: TimelineClip): BeautyFaceTrackV28 {
        val detector = FaceDetection.getClient(options)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(clip.uri))
            val start = clip.sourceInUs.coerceAtLeast(0L)
            val end = clip.sourceOutUs.coerceAtLeast(start + 1L)
            val duration = (end - start).coerceAtLeast(1L)
            // Roughly 4 fps on short clips, tapering to <=80 detector calls on long clips.
            val intervalUs = max(250_000L, duration / 80L).coerceAtMost(1_000_000L)
            val times = mutableListOf<Long>()
            var t = start
            while (t < end) {
                times += t
                t += intervalUs
            }
            if (times.isEmpty() || times.last() != end - 1L) times += (end - 1L).coerceAtLeast(start)

            val samples = ArrayList<BeautyFaceSampleV28>(times.size)
            for (sourceUs in times) {
                val raw = retriever.getFrameAtTime(sourceUs, MediaMetadataRetriever.OPTION_CLOSEST)
                if (raw == null) {
                    samples += BeautyFaceSampleV28(sourceUs, null)
                    continue
                }
                val prepared = downscaleForDetector(raw)
                try {
                    samples += BeautyFaceSampleV28(sourceUs, detectPrimaryGeometry(detector, prepared))
                } finally {
                    if (prepared !== raw) prepared.recycle()
                    raw.recycle()
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
            detector.close()
        }
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
        if (longEdge <= 720) return bitmap
        val scale = 720f / longEdge.toFloat()
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
}
