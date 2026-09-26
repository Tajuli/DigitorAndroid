package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.tajuli.digitorandroid.editor.model.BeautyRectV28
import com.tajuli.digitorandroid.editor.model.EyePose
import com.tajuli.digitorandroid.editor.model.TrackedEye
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/**
 * Shared face/eye landmark detector.
 *
 * GPU is preferred and retried once after preview resources have been handed off. MediaPipe GPU
 * objects are created, invoked and closed on the same dedicated analysis thread. CPU remains a
 * compatibility fallback only after both GPU initialization attempts fail.
 */
internal class EyeLandmarkDetector(
    context: Context,
    private val runningMode: RunningMode = RunningMode.IMAGE,
    preferGpu: Boolean = true,
) : AutoCloseable {
    private val landmarker: FaceLandmarker
    val gpuAccelerated: Boolean
    val gpuFailureReason: String?

    private var previous: EyePose? = null

    init {
        var created: FaceLandmarker? = null
        var gpuFailure: Throwable? = null

        if (preferGpu) {
            repeat(2) { attempt ->
                if (created != null) return@repeat
                try {
                    created = create(context, Delegate.GPU)
                } catch (error: Throwable) {
                    gpuFailure = error
                    Log.w(
                        "DigitorFaceTracking",
                        "MediaPipe GPU face landmarker init attempt ${attempt + 1} failed",
                        error,
                    )
                    if (attempt == 0) SystemClock.sleep(60L)
                }
            }
        }

        gpuAccelerated = created != null
        gpuFailureReason = if (gpuAccelerated) {
            null
        } else {
            gpuFailure?.let { error ->
                buildString {
                    append(error.javaClass.simpleName)
                    error.message?.takeIf { it.isNotBlank() }?.let {
                        append(": ")
                        append(it)
                    }
                    error.cause?.takeIf { it !== error }?.message
                        ?.takeIf { it.isNotBlank() && it != error.message }
                        ?.let {
                            append(" · cause: ")
                            append(it)
                        }
                }.take(220)
            } ?: "Unknown GPU delegate initialization failure"
        }
        landmarker = created ?: create(context, Delegate.CPU)
    }

    private fun create(context: Context, delegate: Delegate): FaceLandmarker {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("face_landmarker.task")
            .setDelegate(delegate)
            .build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(runningMode)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(.42f)
            .setMinFacePresenceConfidence(.42f)
            .setMinTrackingConfidence(.42f)
            .setOutputFaceBlendshapes(false)
            .setOutputFacialTransformationMatrixes(false)
            .build()
        return FaceLandmarker.createFromOptions(context, options)
    }

    fun detect(
        bitmap: Bitmap,
        timeUs: Long = 0L,
        stabilizeInInputSpace: Boolean = true,
    ): EyePose? {
        val image = BitmapImageBuilder(bitmap).build()
        val result = try {
            when (runningMode) {
                RunningMode.VIDEO ->
                    landmarker.detectForVideo(image, timeUs.coerceAtLeast(0L) / 1_000L)
                RunningMode.IMAGE -> landmarker.detect(image)
                RunningMode.LIVE_STREAM ->
                    error("LIVE_STREAM requires asynchronous result handling")
            }
        } finally {
            image.close()
        }
        return poseFrom(
            result = result,
            width = bitmap.width,
            height = bitmap.height,
            stabilizeInInputSpace = stabilizeInInputSpace,
        )
    }

    private fun poseFrom(
        result: FaceLandmarkerResult,
        width: Int,
        height: Int,
        stabilizeInInputSpace: Boolean,
    ): EyePose? {
        val landmarks = result.faceLandmarks().firstOrNull()
        if (landmarks == null || landmarks.size < 468) {
            previous = null
            return null
        }

        fun eye(
            outerIndex: Int,
            innerIndex: Int,
            topIndex: Int,
            bottomIndex: Int,
        ): TrackedEye? {
            val outer = landmarks.getOrNull(outerIndex) ?: return null
            val inner = landmarks.getOrNull(innerIndex) ?: return null
            val top = landmarks.getOrNull(topIndex) ?: return null
            val bottom = landmarks.getOrNull(bottomIndex) ?: return null

            val ax = outer.x() * width
            val ay = outer.y() * height
            val bx = inner.x() * width
            val by = inner.y() * height
            val length = hypot(bx - ax, by - ay)
            if (length < 3f) return null

            var angle = atan2(by - ay, bx - ax)
            if (cos(angle) < 0f) angle += PI.toFloat()

            val cx = (outer.x() + inner.x()) * .5f
            val cy = (outer.y() + inner.y()) * .5f
            val verticalPx = hypot(
                (top.x() - bottom.x()) * width,
                (top.y() - bottom.y()) * height,
            )
            val openness = ((verticalPx / length - .035f) / .18f).coerceIn(0f, 1f)
            return TrackedEye(
                x = cx,
                y = cy,
                radius = length * .5f / width.coerceAtLeast(1),
                roll = angle,
                open = openness,
            )
        }

        val left = eye(33, 133, 159, 145) ?: return null
        val right = eye(362, 263, 386, 374) ?: return null

        val minX = landmarks.minOf { it.x() }.coerceIn(0f, 1f)
        val minY = landmarks.minOf { it.y() }.coerceIn(0f, 1f)
        val maxX = landmarks.maxOf { it.x() }.coerceIn(0f, 1f)
        val maxY = landmarks.maxOf { it.y() }.coerceIn(0f, 1f)
        val faceRect = BeautyRectV28(minX, minY, maxX, maxY).normalized()

        val mouthPoints = listOfNotNull(
            landmarks.getOrNull(61),
            landmarks.getOrNull(291),
            landmarks.getOrNull(13),
            landmarks.getOrNull(14),
        )
        val mouth = if (mouthPoints.size < 4) {
            null
        } else {
            BeautyRectV28(
                mouthPoints.minOf { it.x() },
                mouthPoints.minOf { it.y() },
                mouthPoints.maxOf { it.x() },
                mouthPoints.maxOf { it.y() },
            ).normalized()
        }

        val fresh = EyePose(left, right, 1, faceRect, mouth)
        if (!stabilizeInInputSpace) {
            previous = null
            return fresh
        }

        val old = previous
        val stable = if (
            old != null &&
            hypot(left.x - old.left.x, left.y - old.left.y) <
                max(left.radius, .01f) * .20f
        ) {
            fresh.copy(
                left = old.left.interpolate(left, .8f).copy(open = left.open),
                right = old.right.interpolate(right, .8f).copy(open = right.open),
            )
        } else {
            fresh
        }
        previous = stable
        return stable
    }

    override fun close() {
        landmarker.close()
    }
}
