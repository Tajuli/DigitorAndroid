package com.tajuli.digitorandroid.editor.processing

import com.tajuli.digitorandroid.editor.model.ColorGrade
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * CPU reference color path. Every pixel is transformed independently and work is split by scanline.
 * It intentionally uses Float math to stay close to the GPU shader path.
 */
class CpuColorProcessor(
    workerCount: Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 8),
) : AutoCloseable {
    private val workerCount = workerCount.coerceIn(1, 16)
    private val workers = Executors.newFixedThreadPool(this.workerCount)

    fun processArgb8888(pixels: IntArray, width: Int, height: Int, grade: ColorGrade) {
        if (grade.isIdentity || width <= 0 || height <= 0) return
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
                        pixels[index] = applyGrade(pixels[index], grade)
                        index++
                    }
                }
            }
            y = endY
        }
        workers.invokeAll(jobs).forEach { it.get() }
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

        val rr = (rgb[0] * 255f + 0.5f).toInt().coerceIn(0, 255)
        val gg = (rgb[1] * 255f + 0.5f).toInt().coerceIn(0, 255)
        val bb = (rgb[2] * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (a shl 24) or (rr shl 16) or (gg shl 8) or bb
    }

    private fun rgbToHsl(r: Float, g: Float, b: Float): FloatArray {
        val max = max(r, max(g, b))
        val min = min(r, min(g, b))
        val l = (max + min) * 0.5f
        if (max == min) return floatArrayOf(0f, 0f, l)
        val d = max - min
        val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        val h = when (max) {
            r -> ((g - b) / d + if (g < b) 6f else 0f) / 6f
            g -> ((b - r) / d + 2f) / 6f
            else -> ((r - g) / d + 4f) / 6f
        }
        return floatArrayOf(h, s, l)
    }

    private fun hslToRgb(h: Float, s: Float, l: Float): FloatArray {
        if (s == 0f) return floatArrayOf(l, l, l)
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        fun hue(p0: Float, q0: Float, t0: Float): Float {
            var t = t0
            if (t < 0f) t += 1f
            if (t > 1f) t -= 1f
            return when {
                t < 1f / 6f -> p0 + (q0 - p0) * 6f * t
                t < 1f / 2f -> q0
                t < 2f / 3f -> p0 + (q0 - p0) * (2f / 3f - t) * 6f
                else -> p0
            }
        }
        return floatArrayOf(hue(p, q, h + 1f / 3f), hue(p, q, h), hue(p, q, h - 1f / 3f))
    }

    override fun close() = workers.shutdown()
}
