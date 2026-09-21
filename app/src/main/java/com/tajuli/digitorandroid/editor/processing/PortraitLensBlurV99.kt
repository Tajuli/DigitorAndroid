package com.tajuli.digitorandroid.editor.processing

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** CPU fallback for the normalized, foreground-rejecting disk kernel in CutoutEffectV43. */
object PortraitLensBlurV99 {
    private const val SAMPLES = 96
    private val offsets = FloatArray(SAMPLES * 2).also { result ->
        for (i in 0 until SAMPLES) {
            val radius = sqrt((i + .5f) / SAMPLES)
            val angle = i * 2.39996323f
            result[i * 2] = cos(angle) * radius
            result[i * 2 + 1] = sin(angle) * radius
        }
    }

    fun apply(
        source: IntArray,
        width: Int,
        height: Int,
        amount: Float,
        matteAt: (Float, Float) -> Float,
    ): IntArray {
        require(width > 0 && height > 0 && source.size == width * height)
        val strength = if (amount.isFinite()) amount.coerceIn(0f, 1f) else 0f
        if (strength == 0f) return source.copyOf()
        val radius = strength * 24f * minOf(width, height) / 1080f
        val result = source.copyOf()
        fun channel(pixel: Int, shift: Int) = ((pixel ushr shift) and 255).toFloat()
        fun sample(x: Float, y: Float, shift: Int): Float {
            val px = x.coerceIn(0f, (width - 1).toFloat())
            val py = y.coerceIn(0f, (height - 1).toFloat())
            val x0 = floor(px).toInt(); val y0 = floor(py).toInt()
            val x1 = minOf(x0 + 1, width - 1); val y1 = minOf(y0 + 1, height - 1)
            val tx = px - x0; val ty = py - y0
            val top = channel(source[y0 * width + x0], shift) * (1f - tx) + channel(source[y0 * width + x1], shift) * tx
            val bottom = channel(source[y1 * width + x0], shift) * (1f - tx) + channel(source[y1 * width + x1], shift) * tx
            return top * (1f - ty) + bottom * ty
        }
        for (y in 0 until height) for (x in 0 until width) {
            val index = y * width + x
            val u = (x + .5f) / width; val v = (y + .5f) / height
            val matte = matteAt(u, v).coerceIn(0f, 1f)
            if (matte >= .999f) continue
            var red = 0f; var green = 0f; var blue = 0f; var total = 0f
            for (i in 0 until SAMPLES) {
                val sx = (x + offsets[i * 2] * radius).coerceIn(-.5f, width - .5f)
                val sy = (y + offsets[i * 2 + 1] * radius).coerceIn(-.5f, height - .5f)
                fun mask(dx: Float, dy: Float) = matteAt(
                    ((sx + .5f + dx) / width).coerceIn(0f, 1f),
                    ((sy + .5f + dy) / height).coerceIn(0f, 1f),
                )
                val alpha = maxOf(mask(-.5f, -.5f), mask(.5f, .5f), mask(.5f, -.5f), mask(-.5f, .5f)).coerceIn(0f, 1f)
                val t = ((alpha - .02f) / .18f).coerceIn(0f, 1f)
                val weight = (1f - t * t * (3f - 2f * t)) * sample(sx, sy, 24) / 255f
                red += sample(sx, sy, 16) * weight
                green += sample(sx, sy, 8) * weight
                blue += sample(sx, sy, 0) * weight
                total += weight
            }
            if (total < .0001f) continue
            val original = source[index]
            fun blend(sum: Float, shift: Int) =
                (sum / total * (1f - matte) + channel(original, shift) * matte).roundToInt().coerceIn(0, 255)
            result[index] = (original and -0x1000000) or (blend(red, 16) shl 16) or
                (blend(green, 8) shl 8) or blend(blue, 0)
        }
        return result
    }
}
