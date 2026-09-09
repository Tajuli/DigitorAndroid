package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val PERSON_DETECTOR_MODEL_ASSET_V57 = "efficientdet_lite0_int8.tflite"
private const val PERSON_SEMANTIC_MODEL_ASSET_V58 = "selfie_multiclass_256x256.tflite"
private const val PERSON_DETECTOR_SCORE_THRESHOLD_V58 = .15f
private const val PERSON_DETECTOR_MAX_RESULTS_V58 = 20
private const val PERSON_SEMANTIC_INPUT_LONG_EDGE_V69 = 384
private const val PERSON_DETECTOR_INPUT_LONG_EDGE_V69 = 640
private const val PERSON_GUARD_SIDE_PAD_V65 = .07f
private const val PERSON_GUARD_TOP_PAD_V65 = .12f
private const val PERSON_GUARD_BOTTOM_PAD_V65 = .07f
private const val PERSON_GUARD_INWARD_SHRINK_X_FRAME_V65 = .012f
private const val PERSON_GUARD_INWARD_SHRINK_Y_FRAME_V65 = .010f
private const val PERSON_GUARD_RESET_IOU_V65 = .03f

internal data class PersonDetectionV57(
    val bounds: RectF,
    val score: Float,
    val source: String = "unknown",
)

/**
 * Person ROI localizer used before PP-MattingV2.
 *
 * SelfieMulticlass semantic output is evaluated on every analyzed frame and is preferred for
 * close-up portrait ROI geometry. EfficientDet-Lite0 remains an independent fallback. Neither is
 * used as final alpha.
 *
 * V65 adds motion-safe bbox hysteresis: outward edges follow immediately while inward contraction
 * is deliberately slow. Extra top/side/bottom guard protects hijab/hair/head/arms/hands from the
 * hard outside-ROI clamp during fast movement or one weak segmentation frame.
 *
 * V68 switches the SelfieMulticlass locator from six deep-copied float confidence masks to one
 * category mask. MediaPipe's synchronous confidence path allocates width*height*4 bytes for every
 * class on every frame; the category path needs only one byte per pixel and still preserves the
 * union of hair/skin/clothes/accessories as the person ROI. Scratch arrays are reused and
 * EfficientDet is invoked only when the semantic localizer misses.
 *
 * V69 fixes the caller-Bitmap ownership trap without leaving MediaPipe input MPImages unclosed.
 * Each MediaPipe call now gets a small private Bitmap. The MPImage is explicitly closed after the
 * synchronous task returns, so BitmapImageContainer may recycle only our private copy and every
 * native image wrapper has a deterministic lifetime during long High/every-frame runs.
 */
internal class FastPersonObjectDetectorV57(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext

    private val detector = ObjectDetector.createFromOptions(
        appContext,
        ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(PERSON_DETECTOR_MODEL_ASSET_V57)
                    .setDelegate(Delegate.CPU)
                    .build(),
            )
            .setRunningMode(RunningMode.IMAGE)
            .setMaxResults(PERSON_DETECTOR_MAX_RESULTS_V58)
            .setScoreThreshold(PERSON_DETECTOR_SCORE_THRESHOLD_V58)
            .build(),
    )

    private val semanticFallback = ImageSegmenter.createFromOptions(
        appContext,
        ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(PERSON_SEMANTIC_MODEL_ASSET_V58)
                    .setDelegate(Delegate.CPU)
                    .build(),
            )
            .setRunningMode(RunningMode.IMAGE)
            .setOutputCategoryMask(true)
            .setOutputConfidenceMasks(false)
            .build(),
    )

    private var guardedPersonBounds: RectF? = null
    private var guardedFrameWidth: Int = 0
    private var guardedFrameHeight: Int = 0

    private var semanticForeground = BooleanArray(0)
    private var semanticVisited = BooleanArray(0)
    private var semanticQueue = IntArray(0)

    val backendLabel: String =
        "SelfieMulticlass category ROI + bounded/closed MediaPipe input + EfficientDet-Lite0 CPU fallback + motion-safe bbox guard"

    fun detectPeople(bitmap: Bitmap): List<PersonDetectionV57> {
        check(!bitmap.isRecycled) { "Cannot detect a person on a recycled bitmap" }
        resetGuardIfDimensionsChanged(bitmap.width, bitmap.height)

        val semanticBounds = runCatching { detectWithSelfieMulticlassCategory(bitmap) }.getOrNull()
        val rawCandidate = if (semanticBounds != null) {
            PersonDetectionV57(semanticBounds, .98f, "SelfieMulticlass category")
        } else {
            val efficientDetPeople = runCatching { detectWithEfficientDet(bitmap) }.getOrElse { emptyList() }
            chooseEfficientDetCandidate(efficientDetPeople)
        }

        if (rawCandidate == null) return emptyList()
        val guarded = motionSafeBounds(rawCandidate.bounds, bitmap.width, bitmap.height)
        return listOf(
            rawCandidate.copy(
                bounds = guarded,
                source = rawCandidate.source + " motion-safe",
            ),
        )
    }

    private fun chooseEfficientDetCandidate(detections: List<PersonDetectionV57>): PersonDetectionV57? {
        if (detections.isEmpty()) return null
        val previous = guardedPersonBounds
        return detections.maxByOrNull { detection ->
            val continuity = previous?.let { intersectionOverUnion(it, detection.bounds) } ?: 0f
            continuity * 2.5f + detection.score * 1.5f
        }
    }

    private fun resetGuardIfDimensionsChanged(width: Int, height: Int) {
        if (width != guardedFrameWidth || height != guardedFrameHeight) {
            guardedPersonBounds = null
            guardedFrameWidth = width
            guardedFrameHeight = height
        }
    }

    private fun motionSafeBounds(raw: RectF, frameWidth: Int, frameHeight: Int): RectF {
        val clipped = clipBounds(raw, frameWidth, frameHeight)
        if (clipped.width() < 2f || clipped.height() < 2f) return clipped

        val padded = clipBounds(
            RectF(
                clipped.left - clipped.width() * PERSON_GUARD_SIDE_PAD_V65,
                clipped.top - clipped.height() * PERSON_GUARD_TOP_PAD_V65,
                clipped.right + clipped.width() * PERSON_GUARD_SIDE_PAD_V65,
                clipped.bottom + clipped.height() * PERSON_GUARD_BOTTOM_PAD_V65,
            ),
            frameWidth,
            frameHeight,
        )

        val previous = guardedPersonBounds
        val next = if (previous == null || intersectionOverUnion(previous, padded) < PERSON_GUARD_RESET_IOU_V65) {
            padded
        } else {
            val maxShrinkX = frameWidth * PERSON_GUARD_INWARD_SHRINK_X_FRAME_V65
            val maxShrinkY = frameHeight * PERSON_GUARD_INWARD_SHRINK_Y_FRAME_V65
            RectF(
                if (padded.left <= previous.left) padded.left else minOf(padded.left, previous.left + maxShrinkX),
                if (padded.top <= previous.top) padded.top else minOf(padded.top, previous.top + maxShrinkY),
                if (padded.right >= previous.right) padded.right else maxOf(padded.right, previous.right - maxShrinkX),
                if (padded.bottom >= previous.bottom) padded.bottom else maxOf(padded.bottom, previous.bottom - maxShrinkY),
            )
        }

        val safe = clipBounds(next, frameWidth, frameHeight)
        guardedPersonBounds = RectF(safe)
        return safe
    }

    private fun clipBounds(bounds: RectF, frameWidth: Int, frameHeight: Int): RectF = RectF(
        bounds.left.coerceIn(0f, frameWidth.toFloat()),
        bounds.top.coerceIn(0f, frameHeight.toFloat()),
        bounds.right.coerceIn(0f, frameWidth.toFloat()),
        bounds.bottom.coerceIn(0f, frameHeight.toFloat()),
    )

    private fun intersectionOverUnion(a: RectF, b: RectF): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val intersection = maxOf(0f, right - left) * maxOf(0f, bottom - top)
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun detectWithEfficientDet(bitmap: Bitmap): List<PersonDetectionV57> {
        check(!bitmap.isRecycled) { "Cannot run EfficientDet on a recycled bitmap" }
        val owned = privateMediaPipeBitmap(bitmap, PERSON_DETECTOR_INPUT_LONG_EDGE_V69)
        val inputImage = BitmapImageBuilder(owned).build()
        try {
            val scaleX = bitmap.width.toFloat() / owned.width.toFloat()
            val scaleY = bitmap.height.toFloat() / owned.height.toFloat()
            val result = detector.detect(inputImage)
            return result.detections().mapNotNull { detection ->
                val personCategory = detection.categories()
                    .filter { category ->
                        category.categoryName().trim().equals("person", ignoreCase = true) ||
                            category.displayName().trim().equals("person", ignoreCase = true) ||
                            category.index() == 0
                    }
                    .maxByOrNull { it.score() }
                    ?: return@mapNotNull null

                val box = detection.boundingBox()
                val left = (box.left * scaleX).coerceIn(0f, bitmap.width.toFloat())
                val top = (box.top * scaleY).coerceIn(0f, bitmap.height.toFloat())
                val right = (box.right * scaleX).coerceIn(0f, bitmap.width.toFloat())
                val bottom = (box.bottom * scaleY).coerceIn(0f, bitmap.height.toFloat())
                if (right - left < 2f || bottom - top < 2f) return@mapNotNull null

                PersonDetectionV57(RectF(left, top, right, bottom), personCategory.score(), "EfficientDet")
            }
        } finally {
            runCatching { inputImage.close() }
            if (!owned.isRecycled) owned.recycle()
        }
    }

    private fun detectWithSelfieMulticlassCategory(bitmap: Bitmap): RectF? {
        check(!bitmap.isRecycled) { "Cannot run SelfieMulticlass on a recycled bitmap" }
        val owned = privateMediaPipeBitmap(bitmap, PERSON_SEMANTIC_INPUT_LONG_EDGE_V69)
        val inputImage = BitmapImageBuilder(owned).build()
        try {
            val result = semanticFallback.segment(inputImage)
            val mpMask = result.categoryMask().orElse(null) ?: return null
            try {
                val width = mpMask.width.coerceAtLeast(1)
                val height = mpMask.height.coerceAtLeast(1)
                val count = width * height
                ensureSemanticCapacity(count)

                val buffer = ByteBufferExtractor.extract(mpMask)
                buffer.rewind()
                if (buffer.remaining() < count) return null
                for (i in 0 until count) {
                    semanticForeground[i] = (buffer.get().toInt() and 0xFF) != 0
                }
                return connectedPersonBounds(semanticForeground, width, height, bitmap)
            } finally {
                runCatching { mpMask.close() }
            }
        } finally {
            runCatching { inputImage.close() }
            if (!owned.isRecycled) owned.recycle()
        }
    }

    private fun privateMediaPipeBitmap(source: Bitmap, maxLongEdge: Int): Bitmap {
        val longEdge = max(source.width, source.height).coerceAtLeast(1)
        if (longEdge <= maxLongEdge) {
            return source.copy(Bitmap.Config.ARGB_8888, false)
                ?: error("Could not create owned MediaPipe input bitmap")
        }
        val scale = maxLongEdge.toFloat() / longEdge.toFloat()
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        if (scaled !== source) return scaled
        return source.copy(Bitmap.Config.ARGB_8888, false)
            ?: error("Could not create owned MediaPipe input bitmap")
    }

    private fun ensureSemanticCapacity(count: Int) {
        if (semanticForeground.size >= count) return
        semanticForeground = BooleanArray(count)
        semanticVisited = BooleanArray(count)
        semanticQueue = IntArray(count)
    }

    private fun connectedPersonBounds(
        foreground: BooleanArray,
        width: Int,
        height: Int,
        bitmap: Bitmap,
    ): RectF? {
        val count = width * height
        ensureSemanticCapacity(count)
        val visited = semanticVisited
        val queue = semanticQueue
        visited.fill(false, 0, count)
        val minArea = max(32, (count * .004f).toInt())
        val frameCx = (width - 1) * .5f
        val frameCy = (height - 1) * .5f
        val frameDiag = sqrt((width * width + height * height).toFloat()).coerceAtLeast(1f)

        var bestScore = -1f
        var bestLeft = 0
        var bestTop = 0
        var bestRight = 0
        var bestBottom = 0

        for (start in 0 until count) {
            if (visited[start]) continue
            visited[start] = true
            if (!foreground[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            var area = 0
            var minX = width
            var minY = height
            var maxX = -1
            var maxY = -1
            var sumX = 0L
            var sumY = 0L

            while (head < tail) {
                val index = queue[head++]
                val y = index / width
                val x = index - y * width
                area++
                sumX += x
                sumY += y
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y

                fun offer(next: Int) {
                    if (visited[next]) return
                    visited[next] = true
                    if (!foreground[next]) return
                    queue[tail++] = next
                }
                if (x > 0) offer(index - 1)
                if (x + 1 < width) offer(index + 1)
                if (y > 0) offer(index - width)
                if (y + 1 < height) offer(index + width)
            }

            if (area < minArea || maxX < minX || maxY < minY) continue
            val boxW = maxX - minX + 1
            val boxH = maxY - minY + 1
            if (boxW < width * .05f || boxH < height * .10f) continue

            val cx = sumX.toFloat() / area.toFloat()
            val cy = sumY.toFloat() / area.toFloat()
            val centerDistance = sqrt((cx - frameCx) * (cx - frameCx) + (cy - frameCy) * (cy - frameCy))
            val centerBonus = 1f + .30f * (1f - (centerDistance / frameDiag).coerceIn(0f, 1f))
            val verticalBonus = 1f + .12f * (boxH.toFloat() / height.toFloat()).coerceIn(0f, 1f)
            val score = area.toFloat() * centerBonus * verticalBonus

            if (score > bestScore) {
                bestScore = score
                bestLeft = minX
                bestTop = minY
                bestRight = maxX + 1
                bestBottom = maxY + 1
            }
        }

        if (bestScore <= 0f) return null
        val scaleX = bitmap.width.toFloat() / width.toFloat()
        val scaleY = bitmap.height.toFloat() / height.toFloat()
        return RectF(bestLeft * scaleX, bestTop * scaleY, bestRight * scaleX, bestBottom * scaleY)
    }

    override fun close() {
        runCatching { detector.close() }
        runCatching { semanticFallback.close() }
    }
}
