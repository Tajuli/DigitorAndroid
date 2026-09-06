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
import kotlin.math.max
import kotlin.math.sqrt

private const val PERSON_SEMANTIC_MODEL_ASSET_V54 = "selfie_multiclass_256x256.tflite"

/**
 * Lightweight person *localizer* for PP-MattingV2 ROI selection.
 *
 * Important: its segmentation result is never used as the final cutout matte. We only find the
 * largest plausible person component and return one bounding box. PP-MattingV2 remains solely
 * responsible for alpha/matting quality inside that ROI.
 */
internal class FastPersonSemanticSegmenterV54(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private var segmenter: ImageSegmenter
    var usingGpuDelegate: Boolean
        private set

    init {
        val gpu = runCatching { createSegmenter(appContext, Delegate.GPU) }
        if (gpu.isSuccess) {
            segmenter = gpu.getOrThrow()
            usingGpuDelegate = true
        } else {
            segmenter = createSegmenter(appContext, Delegate.CPU)
            usingGpuDelegate = false
        }
    }

    /** Returns one person bounding box in source-bitmap coordinates, or null when not confident. */
    fun detectPersonBounds(bitmap: Bitmap): RectF? {
        val result = runCatching { segmenter.segment(BitmapImageBuilder(bitmap).build()) }
            .getOrElse { error ->
                if (!usingGpuDelegate) throw error
                runCatching { segmenter.close() }
                segmenter = createSegmenter(appContext, Delegate.CPU)
                usingGpuDelegate = false
                segmenter.segment(BitmapImageBuilder(bitmap).build())
            }

        val mpMask = result.categoryMask().orElse(null) ?: return null
        val width = mpMask.width.coerceAtLeast(1)
        val height = mpMask.height.coerceAtLeast(1)
        val count = width * height
        val categories = ByteBufferExtractor.extract(mpMask)
        categories.rewind()
        if (categories.remaining() < count) return null

        val labels = ByteArray(count)
        categories.get(labels)
        val visited = BooleanArray(count)
        val queue = IntArray(count)

        val frameCx = (width - 1) * .5f
        val frameCy = (height - 1) * .5f
        val frameDiag = sqrt((width * width + height * height).toFloat()).coerceAtLeast(1f)
        val minComponentArea = max(32, (count * .004f).toInt())

        var bestScore = -1f
        var bestArea = 0
        var bestLeft = 0
        var bestTop = 0
        var bestRight = 0
        var bestBottom = 0

        for (start in 0 until count) {
            if (visited[start]) continue
            visited[start] = true
            if ((labels[start].toInt() and 0xFF) == 0) continue

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

                if (x > 0) enqueuePerson(index - 1, labels, visited, queue, tail).also { tail = it }
                if (x + 1 < width) enqueuePerson(index + 1, labels, visited, queue, tail).also { tail = it }
                if (y > 0) enqueuePerson(index - width, labels, visited, queue, tail).also { tail = it }
                if (y + 1 < height) enqueuePerson(index + width, labels, visited, queue, tail).also { tail = it }
            }

            if (area < minComponentArea || maxX < minX || maxY < minY) continue
            val boxW = maxX - minX + 1
            val boxH = maxY - minY + 1
            // Reject tiny furniture/flower false positives even if they happen to be isolated.
            if (boxW < width * .05f || boxH < height * .10f) continue

            val cx = sumX.toFloat() / area.toFloat()
            val cy = sumY.toFloat() / area.toFloat()
            val centerDistance = sqrt((cx - frameCx) * (cx - frameCx) + (cy - frameCy) * (cy - frameCy))
            val centerBonus = 1f + .30f * (1f - (centerDistance / frameDiag).coerceIn(0f, 1f))
            val verticalBonus = 1f + .12f * (boxH.toFloat() / height.toFloat()).coerceIn(0f, 1f)
            val score = area.toFloat() * centerBonus * verticalBonus

            if (score > bestScore) {
                bestScore = score
                bestArea = area
                bestLeft = minX
                bestTop = minY
                bestRight = maxX + 1
                bestBottom = maxY + 1
            }
        }

        if (bestScore <= 0f || bestArea < minComponentArea) return null
        val scaleX = bitmap.width.toFloat() / width.toFloat()
        val scaleY = bitmap.height.toFloat() / height.toFloat()
        return RectF(
            bestLeft * scaleX,
            bestTop * scaleY,
            bestRight * scaleX,
            bestBottom * scaleY,
        )
    }

    private fun enqueuePerson(
        index: Int,
        labels: ByteArray,
        visited: BooleanArray,
        queue: IntArray,
        tail: Int,
    ): Int {
        if (visited[index]) return tail
        visited[index] = true
        if ((labels[index].toInt() and 0xFF) == 0) return tail
        queue[tail] = index
        return tail + 1
    }

    override fun close() {
        segmenter.close()
    }

    private companion object {
        fun createSegmenter(context: Context, delegate: Delegate): ImageSegmenter {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(PERSON_SEMANTIC_MODEL_ASSET_V54)
                .setDelegate(delegate)
                .build()
            val options = ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setOutputCategoryMask(true)
                .setOutputConfidenceMasks(false)
                .build()
            return ImageSegmenter.createFromOptions(context, options)
        }
    }
}
