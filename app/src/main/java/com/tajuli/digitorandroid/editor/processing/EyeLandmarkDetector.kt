package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.os.Build
import com.tajuli.digitorandroid.editor.preview.PreviewExportCoordinator
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.gson.Gson
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.tajuli.digitorandroid.editor.model.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared frame detector for live preview and full export analysis. */
internal class EyeLandmarkDetector : AutoCloseable {
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(.08f).enableTracking().build()
            val detector = FaceDetection.getClient(options)
            var selectedIdentity: Int? = null
            var previous: EyePose? = null
            suspend fun detect(bitmap: Bitmap): EyePose? {
                // Await the native task to completion before releasing detector/bitmap on cancel.
                val faces = withContext(NonCancellable) {
                    kotlin.coroutines.suspendCoroutine<List<Face>> { continuation ->
                        detector.process(InputImage.fromBitmap(bitmap, 0))
                            .addOnSuccessListener { continuation.resume(it) }
                            .addOnFailureListener { continuation.resumeWithException(it) }
                            .addOnCanceledListener { continuation.resumeWithException(CancellationException("Face detection cancelled")) }
                    }
                }
                currentCoroutineContext().ensureActive()
                val face = if (selectedIdentity == null) faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                    else faces.firstOrNull { it.trackingId == selectedIdentity }
                if (face == null) { selectedIdentity = null; previous = null; return null }
                selectedIdentity = face.trackingId
                fun eye(type: Int, probability: Float?): TrackedEye? {
                    val points = face.getContour(type)?.points ?: return null
                    if (points.size < 8) return null
                    val a = points[0]; val b = points[points.size / 2]
                    val length = hypot(b.x-a.x, b.y-a.y)
                    if (length < 3f) return null
                    var angle = atan2(b.y-a.y, b.x-a.x)
                    if (cos(angle) < 0) angle += PI.toFloat()
                    val cx = (a.x+b.x)*.5f; val cy = (a.y+b.y)*.5f
                    val aperture = points.maxOf { abs(-(it.x-cx)*sin(angle)+(it.y-cy)*cos(angle)) } / length
                    val openness = min(((aperture-.035f)/.10f).coerceIn(0f, 1f), probability ?: 1f)
                    return TrackedEye(cx/bitmap.width, cy/bitmap.height, length*.5f/bitmap.width, angle, openness)
                }
                val left = eye(FaceContour.LEFT_EYE, face.leftEyeOpenProbability) ?: return null
                val right = eye(FaceContour.RIGHT_EYE, face.rightEyeOpenProbability) ?: return null
                val box = face.boundingBox
                val faceRect = BeautyRectV28(box.left.toFloat()/bitmap.width,box.top.toFloat()/bitmap.height,
                    box.right.toFloat()/bitmap.width,box.bottom.toFloat()/bitmap.height).normalized()
                val lip = (face.getContour(FaceContour.UPPER_LIP_TOP)?.points.orEmpty() +
                    face.getContour(FaceContour.LOWER_LIP_BOTTOM)?.points.orEmpty())
                val mouth = if(lip.isEmpty()) null else BeautyRectV28(lip.minOf{it.x}/bitmap.width,
                    lip.minOf{it.y}/bitmap.height,lip.maxOf{it.x}/bitmap.width,lip.maxOf{it.y}/bitmap.height)
                val fresh = EyePose(left, right, selectedIdentity, faceRect, mouth)
                // Mild stabilization only for sub-eye-width jitter; preserve fast motion and blinks.
                val old = previous
                val stable = if (old != null && old.identity == fresh.identity &&
                    hypot(left.x-old.left.x, left.y-old.left.y) < left.radius*.20f) {
                    fresh.copy(left = old.left.interpolate(left, .8f).copy(open = left.open),
                        right = old.right.interpolate(right, .8f).copy(open = right.open))
                } else fresh
                previous = stable
                return stable
            }

    override fun close() { detector.close() }
}
