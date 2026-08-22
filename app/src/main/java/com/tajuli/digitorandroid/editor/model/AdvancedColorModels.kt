package com.tajuli.digitorandroid.editor.model

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Channel values are signed control deltas. luma moves all RGB channels together. */
data class ColorWheelValue(
    val red: Float = 0f,
    val green: Float = 0f,
    val blue: Float = 0f,
    val luma: Float = 0f,
)

data class PrimaryWheels(
    val lift: ColorWheelValue = ColorWheelValue(),
    val gamma: ColorWheelValue = ColorWheelValue(),
    val gain: ColorWheelValue = ColorWheelValue(),
    val offset: ColorWheelValue = ColorWheelValue(),
)

data class LogWheels(
    val shadows: ColorWheelValue = ColorWheelValue(),
    val midtones: ColorWheelValue = ColorWheelValue(),
    val highlights: ColorWheelValue = ColorWheelValue(),
    val shadowRange: Float = 0.30f,
    val highlightRange: Float = 0.70f,
)

/** Five fixed-x points at 0, .25, .5, .75 and 1.0. */
data class Curve5(
    val p0: Float = 0f,
    val p1: Float = 0.25f,
    val p2: Float = 0.50f,
    val p3: Float = 0.75f,
    val p4: Float = 1f,
) {
    fun valueAt(x: Float): Float {
        val clamped = x.coerceIn(0f, 1f)
        val scaled = clamped * 4f
        val i = floor(scaled.toDouble()).toInt().coerceIn(0, 3)
        val t = scaled - i
        val a = point(i)
        val b = point(i + 1)
        return (a + (b - a) * t).coerceIn(0f, 1f)
    }

    fun withPoint(index: Int, value: Float): Curve5 = when (index) {
        0 -> copy(p0 = value.coerceIn(0f, 1f))
        1 -> copy(p1 = value.coerceIn(0f, 1f))
        2 -> copy(p2 = value.coerceIn(0f, 1f))
        3 -> copy(p3 = value.coerceIn(0f, 1f))
        4 -> copy(p4 = value.coerceIn(0f, 1f))
        else -> this
    }

    private fun point(index: Int): Float = when (index) {
        0 -> p0
        1 -> p1
        2 -> p2
        3 -> p3
        else -> p4
    }
}

data class RgbCurves(
    val master: Curve5 = Curve5(),
    val red: Curve5 = Curve5(),
    val green: Curve5 = Curve5(),
    val blue: Curve5 = Curve5(),
)

data class HslQualifier(
    val enabled: Boolean = false,
    val hueCenterDegrees: Float = 0f,
    val hueWidthDegrees: Float = 360f,
    val saturationMin: Float = 0f,
    val saturationMax: Float = 1f,
    val luminanceMin: Float = 0f,
    val luminanceMax: Float = 1f,
    val softness: Float = 0.08f,
    val hueShiftDegrees: Float = 0f,
    val saturationShift: Float = 0f,
    val luminanceShift: Float = 0f,
)

data class AdvancedColorGrade(
    val primary: PrimaryWheels = PrimaryWheels(),
    val log: LogWheels = LogWheels(),
    val curves: RgbCurves = RgbCurves(),
    val qualifier: HslQualifier = HslQualifier(),
)

/**
 * One deterministic per-pixel reference transform. GPU LUT generation and CPU fallback both call
 * this code so preview/export do not have separate color math.
 */
object AdvancedColorMath {
    fun applyClip(clip: TimelineClip, r: Float, g: Float, b: Float): FloatArray {
        var rgb = floatArrayOf(r, g, b)
        val editableNodes = clip.nodeGraph.nodes
            .filter { it.kind == NodeKind.SERIAL || it.kind == NodeKind.PARALLEL }
            .sortedWith(compareBy<ColorNode> { it.position.x }.thenBy { it.position.y })
        for (node in editableNodes) rgb = applyNode(node, rgb[0], rgb[1], rgb[2])
        return rgb
    }

    fun applyNode(node: ColorNode, r0: Float, g0: Float, b0: Float): FloatArray {
        var r = r0.coerceIn(0f, 1f)
        var g = g0.coerceIn(0f, 1f)
        var b = b0.coerceIn(0f, 1f)

        val c = node.corrections
        val exposure = 2.0.pow(c.exposure.toDouble()).toFloat()
        r *= exposure
        g *= exposure
        b *= exposure
        val contrast = (1f + c.contrast / 100f).coerceIn(0f, 3f)
        r = (r - .5f) * contrast + .5f
        g = (g - .5f) * contrast + .5f
        b = (b - .5f) * contrast + .5f
        val warm = (c.temperature / 100f).coerceIn(-1f, 1f)
        val tint = (c.tint / 100f).coerceIn(-1f, 1f)
        r *= 1f + warm * .12f + tint * .04f
        g *= 1f - tint * .05f
        b *= 1f - warm * .12f + tint * .04f
        var hsl = rgbToHsl(r, g, b)
        hsl[0] = wrap01(hsl[0] + c.hue / 360f)
        hsl[1] = (hsl[1] * (1f + c.saturation / 100f)).coerceIn(0f, 1f)
        val highlightWeight = smoothstep(.55f, 1f, hsl[2])
        val shadowWeight = 1f - smoothstep(0f, .45f, hsl[2])
        hsl[2] = (hsl[2] + c.highlights / 100f * .20f * highlightWeight + c.shadows / 100f * .20f * shadowWeight)
            .coerceIn(0f, 1f)
        val corrected = hslToRgb(hsl[0], hsl[1], hsl[2])
        r = corrected[0]; g = corrected[1]; b = corrected[2]

        val p = node.advancedColor.primary
        val lum0 = luma(r, g, b)
        r = primaryChannel(r, lum0, p.lift.red + p.lift.luma, p.gamma.red + p.gamma.luma, p.gain.red + p.gain.luma, p.offset.red + p.offset.luma)
        g = primaryChannel(g, lum0, p.lift.green + p.lift.luma, p.gamma.green + p.gamma.luma, p.gain.green + p.gain.luma, p.offset.green + p.offset.luma)
        b = primaryChannel(b, lum0, p.lift.blue + p.lift.luma, p.gamma.blue + p.gamma.luma, p.gain.blue + p.gain.luma, p.offset.blue + p.offset.luma)

        val log = node.advancedColor.log
        val lum = luma(r, g, b)
        val shadowW = 1f - smoothstep((log.shadowRange - .12f).coerceAtLeast(0f), (log.shadowRange + .12f).coerceAtMost(1f), lum)
        val highW = smoothstep((log.highlightRange - .12f).coerceAtLeast(0f), (log.highlightRange + .12f).coerceAtMost(1f), lum)
        val midW = (1f - shadowW - highW).coerceIn(0f, 1f)
        r += .22f * (shadowW * (log.shadows.red + log.shadows.luma) + midW * (log.midtones.red + log.midtones.luma) + highW * (log.highlights.red + log.highlights.luma))
        g += .22f * (shadowW * (log.shadows.green + log.shadows.luma) + midW * (log.midtones.green + log.midtones.luma) + highW * (log.highlights.green + log.highlights.luma))
        b += .22f * (shadowW * (log.shadows.blue + log.shadows.luma) + midW * (log.midtones.blue + log.midtones.luma) + highW * (log.highlights.blue + log.highlights.luma))

        val curves = node.advancedColor.curves
        val masterR = curves.master.valueAt(r.coerceIn(0f, 1f))
        val masterG = curves.master.valueAt(g.coerceIn(0f, 1f))
        val masterB = curves.master.valueAt(b.coerceIn(0f, 1f))
        r = curves.red.valueAt(masterR)
        g = curves.green.valueAt(masterG)
        b = curves.blue.valueAt(masterB)

        val q = node.advancedColor.qualifier
        if (q.enabled) {
            hsl = rgbToHsl(r, g, b)
            val hueDeg = hsl[0] * 360f
            val hueDistance = min(abs(hueDeg - q.hueCenterDegrees), 360f - abs(hueDeg - q.hueCenterDegrees))
            val hueHalf = (q.hueWidthDegrees * .5f).coerceIn(0.5f, 180f)
            val hueSoft = max(1f, hueHalf * q.softness.coerceIn(0f, 1f))
            val hueMask = 1f - smoothstep((hueHalf - hueSoft).coerceAtLeast(0f), hueHalf, hueDistance)
            val satMask = rangeMask(hsl[1], q.saturationMin, q.saturationMax, q.softness)
            val lumMask = rangeMask(hsl[2], q.luminanceMin, q.luminanceMax, q.softness)
            val mask = (hueMask * satMask * lumMask).coerceIn(0f, 1f)
            hsl[0] = wrap01(hsl[0] + q.hueShiftDegrees / 360f * mask)
            hsl[1] = (hsl[1] + q.saturationShift * mask).coerceIn(0f, 1f)
            hsl[2] = (hsl[2] + q.luminanceShift * mask).coerceIn(0f, 1f)
            val qualified = hslToRgb(hsl[0], hsl[1], hsl[2])
            r = qualified[0]; g = qualified[1]; b = qualified[2]
        }

        return floatArrayOf(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
    }

    private fun primaryChannel(value: Float, lum: Float, lift: Float, gamma: Float, gain: Float, offset: Float): Float {
        var v = value + lift * .25f * (1f - lum)
        val exponent = 2.0.pow((-gamma * .75f).toDouble()).toFloat().coerceIn(.15f, 6f)
        v = v.coerceAtLeast(0f).pow(exponent)
        v *= 2.0.pow((gain * .75f).toDouble()).toFloat()
        v += offset * .25f
        return v
    }

    private fun rangeMask(value: Float, minValue: Float, maxValue: Float, softness: Float): Float {
        val lo = min(minValue, maxValue).coerceIn(0f, 1f)
        val hi = max(minValue, maxValue).coerceIn(0f, 1f)
        val edge = (softness.coerceIn(0f, 1f) * .25f).coerceAtLeast(.0001f)
        val rise = smoothstep(lo - edge, lo + edge, value)
        val fall = 1f - smoothstep(hi - edge, hi + edge, value)
        return (rise * fall).coerceIn(0f, 1f)
    }

    private fun luma(r: Float, g: Float, b: Float): Float = (r * .2126f + g * .7152f + b * .0722f).coerceIn(0f, 1f)
    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge0 == edge1) return if (x < edge0) 0f else 1f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
    private fun wrap01(v: Float): Float = ((v % 1f) + 1f) % 1f

    private fun rgbToHsl(r0: Float, g0: Float, b0: Float): FloatArray {
        val r = r0.coerceIn(0f, 1f); val g = g0.coerceIn(0f, 1f); val b = b0.coerceIn(0f, 1f)
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
        if (s <= 0f) return floatArrayOf(l, l, l)
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
}
