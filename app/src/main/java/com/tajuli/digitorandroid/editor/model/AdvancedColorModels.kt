package com.tajuli.digitorandroid.editor.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Signed chromatic deltas plus a Y/master value. puckX/puckY persist the actual wheel puck so the
 * UI can round-trip exactly when switching clips/nodes.
 */
data class ColorWheelValue(
    val red: Float = 0f,
    val green: Float = 0f,
    val blue: Float = 0f,
    val luma: Float = 0f,
    val puckX: Float = 0f,
    val puckY: Float = 0f,
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
    val global: ColorWheelValue = ColorWheelValue(),
    val shadowRange: Float = 0.30f,
    val highlightRange: Float = 0.70f,
)

data class CurvePoint(
    val x: Float,
    val y: Float,
)

/**
 * Dynamic custom curve. It starts with only the start/end points, just like Resolve custom curves.
 * The historic Curve5 name is kept so older call sites/source compatibility continue to compile.
 */
data class Curve5(
    val points: List<CurvePoint> = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f)),
) {
    private fun ordered(): List<CurvePoint> = points
        .map { CurvePoint(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) }
        .sortedBy { it.x }
        .let { if (it.size >= 2) it else listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f)) }

    fun valueAt(x: Float): Float {
        val p = ordered()
        val input = x.coerceIn(0f, 1f)
        if (input <= p.first().x) return p.first().y
        if (input >= p.last().x) return p.last().y

        // With only the default start/end handles, interpolation must be exactly linear. The old
        // Catmull-Rom endpoint duplication bent this nominally neutral curve, so every fresh node
        // changed midtones/highlights before the user touched any control.
        if (p.size == 2) {
            val a = p[0]
            val b = p[1]
            val span = (b.x - a.x).coerceAtLeast(0.000001f)
            val t = ((input - a.x) / span).coerceIn(0f, 1f)
            return (a.y + (b.y - a.y) * t).coerceIn(0f, 1f)
        }

        val right = p.indexOfFirst { it.x >= input }.coerceAtLeast(1)
        val left = right - 1
        val a = p[left]
        val b = p[right]
        val span = (b.x - a.x).coerceAtLeast(0.000001f)
        val t = ((input - a.x) / span).coerceIn(0f, 1f)

        // Catmull-Rom-style smooth interpolation. Clamp keeps legal video-range normalized output.
        val y0 = p.getOrElse(left - 1) { a }.y
        val y1 = a.y
        val y2 = b.y
        val y3 = p.getOrElse(right + 1) { b }.y
        val t2 = t * t
        val t3 = t2 * t
        val y = 0.5f * (
            2f * y1 +
                (-y0 + y2) * t +
                (2f * y0 - 5f * y1 + 4f * y2 - y3) * t2 +
                (-y0 + 3f * y1 - 3f * y2 + y3) * t3
            )
        return y.coerceIn(0f, 1f)
    }

    /** Compatibility helper used by older UI: updates only a point's output/Y. */
    fun withPoint(index: Int, value: Float): Curve5 {
        val p = ordered().toMutableList()
        if (index !in p.indices) return this
        p[index] = p[index].copy(y = value.coerceIn(0f, 1f))
        return copy(points = p)
    }

    fun withPoint(index: Int, x: Float, y: Float): Curve5 {
        val p = ordered().toMutableList()
        if (index !in p.indices) return this
        val gap = 0.002f
        val minX = if (index == 0) 0f else p[index - 1].x + gap
        val maxX = if (index == p.lastIndex) 1f else p[index + 1].x - gap
        p[index] = CurvePoint(x.coerceIn(minX.coerceAtMost(maxX), maxX.coerceAtLeast(minX)), y.coerceIn(0f, 1f))
        return copy(points = p.sortedBy { it.x })
    }

    fun insertPoint(x: Float, y: Float): Curve5 {
        val p = ordered().toMutableList()
        val nx = x.coerceIn(0f, 1f)
        if (p.any { abs(it.x - nx) < 0.002f }) return this
        p += CurvePoint(nx, y.coerceIn(0f, 1f))
        return copy(points = p.sortedBy { it.x })
    }

    fun deletePoint(index: Int): Curve5 {
        val p = ordered().toMutableList()
        if (index <= 0 || index >= p.lastIndex) return this
        p.removeAt(index)
        return copy(points = p)
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
    val pickedRed: Float? = null,
    val pickedGreen: Float? = null,
    val pickedBlue: Float? = null,
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
        hsl[1] = applyColorBoost(hsl[1], c.colorBoost)
        val highlightWeight = smoothstep(.55f, 1f, hsl[2])
        val shadowWeight = 1f - smoothstep(0f, .45f, hsl[2])
        hsl[2] = (hsl[2] + c.highlights / 100f * .20f * highlightWeight + c.shadows / 100f * .20f * shadowWeight)
            .coerceIn(0f, 1f)
        val corrected = hslToRgb(hsl[0], hsl[1], hsl[2])
        r = corrected[0]
        g = corrected[1]
        b = corrected[2]

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
        r += .22f * (
            shadowW * (log.shadows.red + log.shadows.luma) +
                midW * (log.midtones.red + log.midtones.luma) +
                highW * (log.highlights.red + log.highlights.luma) +
                log.global.red + log.global.luma
            )
        g += .22f * (
            shadowW * (log.shadows.green + log.shadows.luma) +
                midW * (log.midtones.green + log.midtones.luma) +
                highW * (log.highlights.green + log.highlights.luma) +
                log.global.green + log.global.luma
            )
        b += .22f * (
            shadowW * (log.shadows.blue + log.shadows.luma) +
                midW * (log.midtones.blue + log.midtones.luma) +
                highW * (log.highlights.blue + log.highlights.luma) +
                log.global.blue + log.global.luma
            )

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
            r = qualified[0]
            g = qualified[1]
            b = qualified[2]
        }

        return floatArrayOf(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
    }

    /**
     * Resolve-style Color Boost behavior: low-saturation colors receive proportionally more chroma
     * gain, while already-saturated colors are protected. Neutral pixels stay neutral, so the
     * control cannot invent a hue in grayscale areas. This is Digitor's deterministic implementation
     * rather than an attempt to reproduce Resolve's proprietary internal formula.
     */
    private fun applyColorBoost(saturation: Float, amount: Float): Float {
        val s = saturation.coerceIn(0f, 1f)
        val strength = (amount / 100f).coerceIn(-1f, 1f)
        if (abs(strength) <= .000001f || s <= .000001f) return s
        val lowSaturationWeight = (1f - s) * (1f - s)
        return (s * (1f + strength * 1.5f * lowSaturationWeight)).coerceIn(0f, 1f)
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
        val r = r0.coerceIn(0f, 1f)
        val g = g0.coerceIn(0f, 1f)
        val b = b0.coerceIn(0f, 1f)
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) * .5f
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
