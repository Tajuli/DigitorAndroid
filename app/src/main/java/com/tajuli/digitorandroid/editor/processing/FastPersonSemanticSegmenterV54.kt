package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import kotlin.math.max
import kotlin.math.roundToInt

private const val PERSON_SEMANTIC_MODEL_ASSET_V54 = "selfie_multiclass_256x256.tflite"
private const val PERSON_SEMANTIC_LONG_EDGE_V54 = 384

/**
 * Lightweight per-frame neural person guide used between expensive PP-MattingV2 detail refreshes.
 *
 * The already-packaged MediaPipe SelfieMulticlass model runs on every analyzed frame and treats
 * categories 1..5 (hair/body/face/clothes/accessories) as person. A tiny separable-like blur turns
 * the category mask into a soft guide so the temporal matte stage can preserve PP-MattingV2 hair
 * and edge detail instead of replacing it with a hard segmentation contour.
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

    fun segmentSoftPersonMask(bitmap: Bitmap): Bitmap? {
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
        val categories = ByteBufferExtractor.extract(mpMask)
        categories.rewind()
        if (categories.remaining() < width * height) return null

        val alpha = IntArray(width * height)
        for (i in alpha.indices) {
            // Official SelfieMulticlass labels: 0 background; 1..5 are person-related classes.
            alpha[i] = if ((categories.get().toInt() and 0xFF) == 0) 0 else 255
        }

        val soft1 = blur(alpha, width, height)
        val soft2 = blur(soft1, width, height)
        val pixels = IntArray(alpha.size) { index ->
            val value = soft2[index].coerceIn(0, 255)
            Color.argb(255, value, value, value)
        }
        val full = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        full.setPixels(pixels, 0, width, 0, 0, width, height)

        val longEdge = max(width, height)
        if (longEdge <= PERSON_SEMANTIC_LONG_EDGE_V54) return full
        val scale = PERSON_SEMANTIC_LONG_EDGE_V54.toFloat() / longEdge.toFloat()
        return Bitmap.createScaledBitmap(
            full,
            (width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1),
            true,
        ).also { full.recycle() }
    }

    private fun blur(source: IntArray, width: Int, height: Int): IntArray {
        val out = IntArray(source.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = source[y * width + x] * 4
                var weight = 4
                if (x > 0) { sum += source[y * width + x - 1]; weight++ }
                if (x + 1 < width) { sum += source[y * width + x + 1]; weight++ }
                if (y > 0) { sum += source[(y - 1) * width + x]; weight++ }
                if (y + 1 < height) { sum += source[(y + 1) * width + x]; weight++ }
                out[y * width + x] = sum / weight
            }
        }
        return out
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
