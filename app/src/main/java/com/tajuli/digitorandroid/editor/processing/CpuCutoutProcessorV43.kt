package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.tajuli.digitorandroid.editor.model.CutoutModeV43
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.resolvedCutoutV43
import java.io.File
import java.util.LinkedHashMap
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** CPU fallback parity for V44 portrait mattes and the V43 chroma keyer. */
class CpuCutoutProcessorV43(
    private val context: Context,
    workerCount: Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 8),
) : AutoCloseable {
    private val workerCount = workerCount.coerceIn(1, 16)
    private val workers = Executors.newFixedThreadPool(this.workerCount)
    private val maskCache = object : LinkedHashMap<String, MaskPixels>(4, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MaskPixels>?): Boolean = size > 4
    }

    /**
     * V44 mattes are applied in source coordinates before CpuTransformProcessor. Soft MODNet alpha,
     * edge shift/clean and dehalo are kept close to the GPU shader contract so CPU-only devices do
     * not fall back to a visibly different binary silhouette.
     */
    fun applyPersonToSource(source: Bitmap, clip: TimelineClip, sourceTimeUs: Long): Bitmap {
        val settings = clip.resolvedCutoutV43()
        if (settings.mode != CutoutModeV43.PERSON) return source
        val bracket = personBracket(clip, sourceTimeUs) ?: return source
        val a = loadMask(bracket.a.file) ?: return source
        val b = loadMask(bracket.b.file) ?: a
        val width = source.width.coerceAtLeast(1)
        val height = source.height.coerceAtLeast(1)
        val sourcePixels = IntArray(width * height)
        source.getPixels(sourcePixels, 0, width, 0, 0, width, height)
        val pixels = sourcePixels.copyOf()

        fun rawMask(u: Float, v: Float): Float =
            lerp(a.sample(u, v), b.sample(u, v), bracket.mix)

        val du = if (width <= 1) 0f else 1f / (width - 1).toFloat()
        val dv = if (height <= 1) 0f else 1f / (height - 1).toFloat()

        parallelRows(width, height) { index ->
            val x = index % width
            val y = index / width
            val u = if (width <= 1) 0f else x.toFloat() / (width - 1).toFloat()
            val v = if (height <= 1) 0f else y.toFloat() / (height - 1).toFloat()

            val raw = rawMask(u, v)
            val thresholdBias = (.5f - settings.personThreshold) * .22f
            val shifted = (raw + settings.edgeShiftV44 + thresholdBias).coerceIn(0f, 1f)
            val contrast = 1f + settings.edgeCleanV44 * 1.65f
            val cleaned = ((shifted - .5f) * contrast + .5f).coerceIn(0f, 1f)
            val neighbour = (
                rawMask(u + du, v) + rawMask(u - du, v) +
                    rawMask(u, v + dv) + rawMask(u, v - dv)
                ) * .25f
            val uncertain = (1f - abs(cleaned * 2f - 1f)).coerceIn(0f, 1f)
            val featherMix = (settings.personFeather * 2.2f).coerceIn(0f, .42f) * uncertain
            val matte = lerp(
                cleaned,
                (neighbour + settings.edgeShiftV44).coerceIn(0f, 1f),
                featherMix,
            ).coerceIn(0f, 1f)

            val argb = sourcePixels[index]
            val sourceAlpha = (argb ushr 24) and 0xFF
            var r = ((argb ushr 16) and 0xFF) / 255f
            var g = ((argb ushr 8) and 0xFF) / 255f
            var blue = (argb and 0xFF) / 255f

            val edgeBand = (1f - abs(matte * 2f - 1f)) *
                smoothstep(.015f, .12f, matte) * (1f - smoothstep(.88f, .995f, matte))
            if (edgeBand > .0001f && settings.dehaloV44 > .0001f) {
                val gx = rawMask(u + du * 2f, v) - rawMask(u - du * 2f, v)
                val gy = rawMask(u, v + dv * 2f) - rawMask(u, v - dv * 2f)
                val length = sqrt(gx * gx + gy * gy)
                if (length > .0002f) {
                    val reach = 1.5f + 3f * settings.dehaloV44
                    val interiorX = (x + (gx / length) * reach).roundToInt().coerceIn(0, width - 1)
                    val interiorY = (y + (gy / length) * reach).roundToInt().coerceIn(0, height - 1)
                    val interior = sourcePixels[interiorY * width + interiorX]
                    val ir = ((interior ushr 16) and 0xFF) / 255f
                    val ig = ((interior ushr 8) and 0xFF) / 255f
                    val ib = (interior and 0xFF) / 255f
                    val weight = (settings.dehaloV44 * edgeBand * .82f).coerceIn(0f, 1f)
                    r = lerp(r, ir, weight)
                    g = lerp(g, ig, weight)
                    blue = lerp(blue, ib, weight)
                }
            }

            val outAlpha = (sourceAlpha * matte + .5f).toInt().coerceIn(0, 255)
            val rr = (r * 255f + .5f).toInt().coerceIn(0, 255)
            val gg = (g * 255f + .5f).toInt().coerceIn(0, 255)
            val bb = (blue * 255f + .5f).toInt().coerceIn(0, 255)
            pixels[index] = (outAlpha shl 24) or (rr shl 16) or (gg shl 8) or bb
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /** Chroma runs after CPU color/effects, mirroring the shared GPU effect ordering. */
    fun processChromaArgb8888(
        pixels: IntArray,
        width: Int,
        height: Int,
        clip: TimelineClip,
    ) {
        val settings = clip.resolvedCutoutV43()
        if (settings.mode != CutoutModeV43.CHROMA_KEY || width <= 0 || height <= 0) return
        val source = pixels.copyOf()
        val keyChroma = rgbToChroma(settings.keyRed, settings.keyGreen, settings.keyBlue)
        val similarity = settings.chromaSimilarity
        val softness = max(.0005f, settings.chromaSoftness)
        val spillRange = max(.005f, settings.chromaSoftness * 2.5f)
        val spill = settings.spillSuppression.coerceIn(0f, 1f)

        fun distanceAt(x0: Int, y0: Int): Float {
            val x = x0.coerceIn(0, width - 1)
            val y = y0.coerceIn(0, height - 1)
            val argb = source[y * width + x]
            val r = ((argb ushr 16) and 0xFF) / 255f
            val g = ((argb ushr 8) and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f
            val c = rgbToChroma(r, g, b)
            val dc0 = c.first - keyChroma.first
            val dc1 = c.second - keyChroma.second
            return sqrt(dc0 * dc0 + dc1 * dc1)
        }

        parallelRows(width, height) { index ->
            val x = index % width
            val y = index / width
            var distance = distanceAt(x - 1, y)
            distance += distanceAt(x + 1, y)
            distance += distanceAt(x, y - 1)
            distance += distanceAt(x, y + 1)
            distance *= 2f
            distance += distanceAt(x, y)
            distance /= 9f

            val baseMask = distance - similarity
            val matte = (baseMask / softness).coerceIn(0f, 1f).pow(1.5f)
            val cleanColor = (baseMask / spillRange).coerceIn(0f, 1f).pow(1.5f)
            val spillWeight = (1f - cleanColor) * spill * .78f

            val argb = source[index]
            val sourceAlpha = (argb ushr 24) and 0xFF
            var r = ((argb ushr 16) and 0xFF) / 255f
            var g = ((argb ushr 8) and 0xFF) / 255f
            var b = (argb and 0xFF) / 255f
            val luma = .2126f * r + .7152f * g + .0722f * b
            r = lerp(r, luma, spillWeight)
            g = lerp(g, luma, spillWeight)
            b = lerp(b, luma, spillWeight)
            val outAlpha = (sourceAlpha * matte + .5f).toInt().coerceIn(0, 255)
            val rr = (r * 255f + .5f).toInt().coerceIn(0, 255)
            val gg = (g * 255f + .5f).toInt().coerceIn(0, 255)
            val bb = (b * 255f + .5f).toInt().coerceIn(0, 255)
            pixels[index] = (outAlpha shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
    }

    private fun personBracket(clip: TimelineClip, sourceTimeUs: Long): MaskBracket? {
        val frames = PersonCutoutMaskStoreV43.index(context, clip).frames
        if (frames.isEmpty()) return null
        if (frames.size == 1) return MaskBracket(frames[0], frames[0], 0f)
        var rightIndex = frames.binarySearchBy(sourceTimeUs) { it.sourceTimeUs }
        if (rightIndex >= 0) return MaskBracket(frames[rightIndex], frames[rightIndex], 0f)
        rightIndex = -rightIndex - 1
        val right = frames.getOrNull(rightIndex)
        val left = frames.getOrNull(rightIndex - 1)
        if (left == null && right != null) return MaskBracket(right, right, 0f)
        if (right == null && left != null) return MaskBracket(left, left, 0f)
        if (left == null || right == null) return null
        val span = (right.sourceTimeUs - left.sourceTimeUs).coerceAtLeast(1L)
        val mix = ((sourceTimeUs - left.sourceTimeUs).toDouble() / span.toDouble()).toFloat().coerceIn(0f, 1f)
        return MaskBracket(left, right, mix)
    }

    @Synchronized
    private fun loadMask(file: File): MaskPixels? {
        maskCache[file.absolutePath]?.let { return it }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        return try {
            val width = bitmap.width.coerceAtLeast(1)
            val height = bitmap.height.coerceAtLeast(1)
            val argb = IntArray(width * height)
            bitmap.getPixels(argb, 0, width, 0, 0, width, height)
            MaskPixels(
                width,
                height,
                FloatArray(argb.size) { index -> ((argb[index] ushr 16) and 0xFF) / 255f },
            ).also { maskCache[file.absolutePath] = it }
        } finally {
            bitmap.recycle()
        }
    }

    private fun parallelRows(width: Int, height: Int, operation: (Int) -> Unit) {
        val stripe = max(1, height / workerCount)
        val jobs = mutableListOf<Callable<Unit>>()
        var y = 0
        while (y < height) {
            val startY = y
            val endY = min(height, y + stripe)
            jobs += Callable {
                for (row in startY until endY) {
                    var index = row * width
                    val end = index + width
                    while (index < end) {
                        operation(index)
                        index++
                    }
                }
            }
            y = endY
        }
        workers.invokeAll(jobs).forEach { it.get() }
    }

    override fun close() {
        synchronized(this) { maskCache.clear() }
        workers.shutdown()
    }

    private data class MaskBracket(
        val a: PersonCutoutMaskFrameV43,
        val b: PersonCutoutMaskFrameV43,
        val mix: Float,
    )

    private data class MaskPixels(
        val width: Int,
        val height: Int,
        val confidence: FloatArray,
    ) {
        fun sample(u: Float, v: Float): Float {
            val fx = u.coerceIn(0f, 1f) * (width - 1).coerceAtLeast(0)
            val fy = v.coerceIn(0f, 1f) * (height - 1).coerceAtLeast(0)
            val x0 = floor(fx).toInt().coerceIn(0, width - 1)
            val y0 = floor(fy).toInt().coerceIn(0, height - 1)
            val x1 = min(width - 1, x0 + 1)
            val y1 = min(height - 1, y0 + 1)
            val tx = fx - x0
            val ty = fy - y0
            val top = lerp(confidence[y0 * width + x0], confidence[y0 * width + x1], tx)
            val bottom = lerp(confidence[y1 * width + x0], confidence[y1 * width + x1], tx)
            return lerp(top, bottom, ty)
        }
    }

    companion object {
        private fun rgbToChroma(r: Float, g: Float, b: Float): Pair<Float, Float> =
            Pair(
                -.168736f * r - .331264f * g + .5f * b,
                .5f * r - .418688f * g - .081312f * b,
            )

        private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
            val t = ((x - edge0) / (edge1 - edge0).coerceAtLeast(.0001f)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)
    }
}
