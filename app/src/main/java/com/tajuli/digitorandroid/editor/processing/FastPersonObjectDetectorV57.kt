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
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

private const val PERSON_DETECTOR_MODEL_ASSET_V57 = "efficientdet_lite0_int8.tflite"
private const val PERSON_SEMANTIC_MODEL_ASSET_V58 = "selfie_multiclass_256x256.tflite"
private const val PERSON_DETECTOR_SCORE_THRESHOLD_V58 = .15f
private const val PERSON_DETECTOR_MAX_RESULTS_V58 = 20
private const val PERSON_SEMANTIC_CONFIDENCE_V61 = .18f

internal data class PersonDetectionV57(
    val bounds: RectF,
    val score: Float,
    val source: String = "unknown",
)

/**
 * Person ROI localizer used before PP-MattingV2.
 *
 * SelfieMulticlass confidence masks are evaluated on every analyzed frame and are preferred for
 * close-up portrait ROI geometry because they localize actual human pixels rather than a generic
 * object rectangle. EfficientDet-Lite0 remains an independent detector/fallback. Neither output is
 * used as the final cutout alpha; PP-MattingV2 remains responsible for the stored soft matte.
 *
 * We intentionally do not depend on the Android category mask for SelfieMulticlass. Confidence
 * masks expose float probabilities per class and avoid category-index issues seen on some Android
 * MediaPipe/model combinations.
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
            .setOutputConfidenceMasks(true)
            .build(),
    )

    val backendLabel: String =
        "SelfieMulticlass confidence ROI + EfficientDet-Lite0 int8 CPU fallback"

    fun detectPeople(bitmap: Bitmap): List<PersonDetectionV57> {
        check(!bitmap.isRecycled) { "Cannot detect a person on a recycled bitmap" }

        // Always evaluate the semantic portrait locator. In the previous implementation it only ran
        // after EfficientDet returned no result, which made a broad object box win even when a much
        // tighter human-pixel bbox was available.
        val semanticBounds = runCatching { detectWithSelfieMulticlassConfidence(bitmap) }.getOrNull()
        val efficientDetPeople = runCatching { detectWithEfficientDet(bitmap) }
            .getOrElse { emptyList() }

        return buildList {
            semanticBounds?.let { bounds ->
                add(
                    PersonDetectionV57(
                        bounds = bounds,
                        // Ranking weight rather than a calibrated detector probability. Prefer the
                        // semantic human bbox when it is plausible; PP-Matting remains final alpha.
                        score = .98f,
                        source = "SelfieMulticlass confidence",
                    ),
                )
            }
            addAll(efficientDetPeople)
        }
    }

    private fun detectWithEfficientDet(bitmap: Bitmap): List<PersonDetectionV57> {
        val result = detector.detect(BitmapImageBuilder(bitmap).build())
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
            val left = box.left.coerceIn(0f, bitmap.width.toFloat())
            val top = box.top.coerceIn(0f, bitmap.height.toFloat())
            val right = box.right.coerceIn(0f, bitmap.width.toFloat())
            val bottom = box.bottom.coerceIn(0f, bitmap.height.toFloat())
            if (right - left < 2f || bottom - top < 2f) return@mapNotNull null

            PersonDetectionV57(
                bounds = RectF(left, top, right, bottom),
                score = personCategory.score(),
                source = "EfficientDet",
            )
        }
    }

    /**
     * Union SelfieMulticlass confidence masks for classes 1..N (all non-background person parts),
     * then keep the largest plausible connected human component and return only its source bbox.
     * Category 0 is background. This mask is a locator only and is never stored as cutout alpha.
     */
    private fun detectWithSelfieMulticlassConfidence(bitmap: Bitmap): RectF? {
        val result = semanticFallback.segment(BitmapImageBuilder(bitmap).build())
        val masks = result.confidenceMasks().orElse(null)
        if (masks == null || masks.size < 2) {
            return detectWithSelfieMulticlassCategoryFallback(result, bitmap)
        }

        val width = masks.first().width.coerceAtLeast(1)
        val height = masks.first().height.coerceAtLeast(1)
        val count = width * height
        val personConfidence = FloatArray(count)
        var usableMasks = 0

        // Index 0 is background. Union all person-part classes by max confidence.
        for (maskIndex in 1 until masks.size) {
            val mask = masks[maskIndex]
            if (mask.width != width || mask.height != height) continue
            val bytes = ByteBufferExtractor.extract(mask).order(ByteOrder.nativeOrder())
            bytes.rewind()
            val floats = bytes.asFloatBuffer()
            if (floats.remaining() < count) continue
            usableMasks++
            for (i in 0 until count) {
                val confidence = floats.get(i)
                if (confidence > personConfidence[i]) personConfidence[i] = confidence
            }
        }
        if (usableMasks == 0) return detectWithSelfieMulticlassCategoryFallback(result, bitmap)

        val foreground = BooleanArray(count)
        for (i in 0 until count) {
            foreground[i] = personConfidence[i] >= PERSON_SEMANTIC_CONFIDENCE_V61
        }
        return connectedPersonBounds(foreground, width, height, bitmap)
            ?: detectWithSelfieMulticlassCategoryFallback(result, bitmap)
    }

    /** Category-mask fallback retained only as a secondary compatibility path. */
    private fun detectWithSelfieMulticlassCategoryFallback(
        result: com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenterResult,
        bitmap: Bitmap,
    ): RectF? {
        val mpMask = result.categoryMask().orElse(null) ?: return null
        val width = mpMask.width.coerceAtLeast(1)
        val height = mpMask.height.coerceAtLeast(1)
        val count = width * height
        val buffer = ByteBufferExtractor.extract(mpMask)
        buffer.rewind()
        if (buffer.remaining() < count) return null
        val labels = ByteArray(count)
        buffer.get(labels)
        val foreground = BooleanArray(count)
        for (i in 0 until count) {
            foreground[i] = (labels[i].toInt() and 0xFF) != 0
        }
        return connectedPersonBounds(foreground, width, height, bitmap)
    }

    private fun connectedPersonBounds(
        foreground: BooleanArray,
        width: Int,
        height: Int,
        bitmap: Bitmap,
    ): RectF? {
        val count = width * height
        val visited = BooleanArray(count)
        val queue = IntArray(count)
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
            val centerDistance = sqrt(
                (cx - frameCx) * (cx - frameCx) + (cy - frameCy) * (cy - frameCy),
            )
            val centerBonus = 1f + .30f *
                (1f - (centerDistance / frameDiag).coerceIn(0f, 1f))
            val verticalBonus = 1f + .12f *
                (boxH.toFloat() / height.toFloat()).coerceIn(0f, 1f)
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
        return RectF(
            bestLeft * scaleX,
            bestTop * scaleY,
            bestRight * scaleX,
            bestBottom * scaleY,
        )
    }

    override fun close() {
        runCatching { detector.close() }
        runCatching { semanticFallback.close() }
    }
}
