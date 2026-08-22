package com.tajuli.digitorandroid.editor.processing

import com.tajuli.digitorandroid.editor.model.AdvancedColorMath
import com.tajuli.digitorandroid.editor.model.ColorGrade
import com.tajuli.digitorandroid.editor.model.TimelineClip
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/** CPU per-pixel reference path. Work is split by scanline. */
class CpuColorProcessor(
    workerCount: Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 8),
) : AutoCloseable {
    private val workerCount = workerCount.coerceIn(1, 16)
    private val workers = Executors.newFixedThreadPool(this.workerCount)

    fun processClipArgb8888(pixels: IntArray, width: Int, height: Int, clip: TimelineClip) {
        if (width <= 0 || height <= 0) return
        parallelRows(width, height) { index ->
            val argb = pixels[index]
            val a = (argb ushr 24) and 0xFF
            val rgb = AdvancedColorMath.applyClip(
                clip,
                ((argb ushr 16) and 0xFF) / 255f,
                ((argb ushr 8) and 0xFF) / 255f,
                (argb and 0xFF) / 255f,
            )
            val r = (rgb[0] * 255f + .5f).toInt().coerceIn(0, 255)
            val g = (rgb[1] * 255f + .5f).toInt().coerceIn(0, 255)
            val b = (rgb[2] * 255f + .5f).toInt().coerceIn(0, 255)
            pixels[index] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /** Kept for existing tests and legacy callers. */
    fun processArgb8888(pixels: IntArray, width: Int, height: Int, grade: ColorGrade) {
        if (grade.isIdentity || width <= 0 || height <= 0) return
        parallelRows(width, height) { index -> pixels[index] = applyGrade(pixels[index], grade) }
    }

    internal fun applyGrade(argb: Int, grade: ColorGrade): Int {
        val a = (argb ushr 24) and 0xFF
        var r = ((argb ushr 16) and 0xFF) / 255f
        var g = ((argb ushr 8) and 0xFF) / 255f
        var b = (argb and 0xFF) / 255f
        r = (r * grade.redScale).coerceIn(0f, 1f)
        g = (g * grade.greenScale).coerceIn(0f, 1f)
        b = (b * grade.blueScale).coerceIn(0f, 1f)
        val hsl = rgbToHsl(r, g, b)
        hsl[0] = ((hsl[0] + grade.hueDegrees / 360f) % 1f + 1f) % 1f
        hsl[1] = (hsl[1] + grade.saturationDelta / 100f).coerceIn(0f, 1f)
        hsl[2] = (hsl[2] + grade.lightnessDelta / 100f).coerceIn(0f, 1f)
        val rgb = hslToRgb(hsl[0], hsl[1], hsl[2])
        val rr = (rgb[0] * 255f + .5f).toInt().coerceIn(0, 255)
        val gg = (rgb[1] * 255f + .5f).toInt().coerceIn(0, 255)
        val bb = (rgb[2] * 255f + .5f).toInt().coerceIn(0, 255)
        return (a shl 24) or (rr shl 16) or (gg shl 8) or bb
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

    private fun rgbToHsl(r: Float, g: Float, b: Float): FloatArray {
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val l = (mx + mn) * .5f
        if (mx == mn) return floatArrayOf(0f, 0f, l)
        val d = mx - mn
        val s = if (l > .5f) d / (2f - mx - mn) else d / (mx + mn)
        val h = when (mx) {
            r -> ((g - b) / d + if (g < b) 6f else 0f) / 6f
            g -> ((b - r) / d + 2f) / 6f
            else -> ((r - g) / d + 4f) / 6f
        }
        return floatArrayOf(h, s, l)
    }

    private fun hslToRgb(h: Float, s: Float, l: Float): FloatArray {
        if (s == 0f) return floatArrayOf(l, l, l)
        val q = if (l < .5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        fun hue(t0: Float): Float {
            var t = t0
            if (t < 0f) t += 1f
            if (t > 1f) t -= 1f
            return when {
                t < 1f / 6f -> p + (q - p) * 6f * t
                t < .5f -> q
                t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
                else -> p
            }
        }
        return floatArrayOf(hue(h + 1f / 3f), hue(h), hue(h - 1f / 3f))
    }

    override fun close() = workers.shutdown()
}
