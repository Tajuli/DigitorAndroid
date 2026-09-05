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

/**
 * V53 GPU portrait matte that protects clothing/head-cover edges using SelfieMulticlass semantics.
 *
 * SelfieMulticlass classes are: 0 background, 1 hair, 2 body-skin, 3 face-skin, 4 clothes,
 * 5 others/accessories. A hijab/scarf often has useful class-4/5 confidence even where the generic
 * 1-background matte is uncertain. We use that confidence only as a bounded edge lift; it cannot
 * create foreground by itself. The V53 topology stage that follows removes disconnected background
 * islands such as chair/headrests.
 *
 * On phones where MediaPipe Tasks GPU cannot initialize, this class delegates to the existing
 * adaptive backend, preserving the Direct LiteRT GPU -> CPU fallback chain without a second neural
 * inference on the healthy MediaPipe-GPU path.
 */
internal class SemanticClothPersonMatteV53(context: Context) : PortraitMatteBackendV50 {
    private companion object {
        const val MODEL_ASSET = "selfie_multiclass_256x256.tflite"
        const val BACKGROUND_CLASS = 0
        const val CLOTHES_CLASS = 4
        const val ACCESSORIES_CLASS = 5

        fun createGpuSegmenter(context: Context): ImageSegmenter {
            val base = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .setDelegate(Delegate.GPU)
                .build()
            val options = ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.IMAGE)
                .setOutputCategoryMask(false)
                .setOutputConfidenceMasks(true)
                .build()
            return ImageSegmenter.createFromOptions(context, options)
        }

        fun smooth01(value: Float): Float {
            val x = value.coerceIn(0f, 1f)
            return x * x * (3f - 2f * x)
        }
    }

    private val gpuSegmenter: ImageSegmenter?
    private val fallback: PortraitMatteBackendV50?
    override val backendLabel: String

    init {
        val app = context.applicationContext
        val gpu = runCatching { createGpuSegmenter(app) }
        if (gpu.isSuccess) {
            gpuSegmenter = gpu.getOrThrow()
            fallback = null
            backendLabel = "SelfieMulticlass · MediaPipe GPU · 256 · Cloth protect V53"
        } else {
            gpuSegmenter = null
            fallback = MediaPipePersonMatteV51(app, requireGpu = false)
            backendLabel = fallback.backendLabel + " · Topology V53"
        }
    }

    override fun infer(source: Bitmap): Bitmap {
        val segmenter = gpuSegmenter ?: return fallback?.infer(source)
            ?: error("V53 portrait matte backend is unavailable")
        check(!source.isRecycled) { "Cannot run V53 person matte on a recycled bitmap" }

        val result = segmenter.segment(BitmapImageBuilder(source).build())
        val masks = result.confidenceMasks().orElse(emptyList())
        val backgroundImage = masks.getOrNull(BACKGROUND_CLASS)
            ?: error("SelfieMulticlass did not return background confidence")
        val clothesImage = masks.getOrNull(CLOTHES_CLASS)
        val accessoriesImage = masks.getOrNull(ACCESSORIES_CLASS)

        val width = backgroundImage.width.coerceAtLeast(1)
        val height = backgroundImage.height.coerceAtLeast(1)
        val count = width * height
        val background = ByteBufferExtractor.extract(backgroundImage).asFloatBuffer().apply { rewind() }
        val clothes = clothesImage?.let { ByteBufferExtractor.extract(it).asFloatBuffer().apply { rewind() } }
        val accessories = accessoriesImage?.let { ByteBufferExtractor.extract(it).asFloatBuffer().apply { rewind() } }

        check(background.remaining() >= count) { "SelfieMulticlass background mask is incomplete" }
        check(clothes == null || clothes.remaining() >= count) { "SelfieMulticlass clothes mask is incomplete" }
        check(accessories == null || accessories.remaining() >= count) { "SelfieMulticlass accessories mask is incomplete" }

        val pixels = IntArray(count)
        for (i in 0 until count) {
            val bg = background.get().coerceIn(0f, 1f)
            val basePerson = (1f - bg).coerceIn(0f, 1f)
            val cloth = clothes?.get()?.coerceIn(0f, 1f) ?: 0f
            val accessory = accessories?.get()?.coerceIn(0f, 1f) ?: 0f

            // Do not turn semantic confidence into an independent foreground mask. It only lifts an
            // already-present person edge, so a random chair/furniture pixel cannot be invented from
            // class noise. Clothes are weighted more than accessories because scarves/head-coverings
            // generally land in class 4, while microphones/glasses may land in class 5.
            val semantic = max(cloth, accessory * .72f)
            val edgeSupport = smooth01((semantic - .08f) / .44f)
            val protectedPerson = (
                basePerson + (1f - basePerson) * edgeSupport * .24f * smooth01(basePerson / .22f)
            ).coerceIn(0f, 1f)

            val x = ((protectedPerson - .025f) / .95f).coerceIn(0f, 1f)
            val smooth = smooth01(x)
            val value = (smooth * 255f).roundToInt().coerceIn(0, 255)
            pixels[i] = Color.argb(255, value, value, value)
        }

        val modelMask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        modelMask.setPixels(pixels, 0, width, 0, 0, width, height)
        if (width == source.width && height == source.height) return modelMask
        return Bitmap.createScaledBitmap(modelMask, source.width, source.height, true).also {
            modelMask.recycle()
        }
    }

    override fun close() {
        runCatching { gpuSegmenter?.close() }
        runCatching { fallback?.close() }
    }
}
