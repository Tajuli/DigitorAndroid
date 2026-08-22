package com.tajuli.digitorandroid.editor.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Applies a Resolve-style HSL qualifier as a mask for the whole node.
 *
 * When the qualifier is enabled, the H/S/L key is calculated from the RGB entering that node.
 * All node grading is then computed normally, but the graded result is blended back over the
 * original node input using the qualifier mask. Pixels outside the key remain untouched.
 */
object QualifiedColorMath {
    fun applyClip(clip: TimelineClip, r: Float, g: Float, b: Float): FloatArray {
        var rgb = floatArrayOf(r, g, b)
        val editableNodes = clip.nodeGraph.nodes
            .filter { it.kind == NodeKind.SERIAL || it.kind == NodeKind.PARALLEL }
            .sortedWith(compareBy<ColorNode> { it.position.x }.thenBy { it.position.y })
        for (node in editableNodes) {
            rgb = applyNode(node, rgb[0], rgb[1], rgb[2])
        }
        return rgb
    }

    fun applyNode(node: ColorNode, r0: Float, g0: Float, b0: Float): FloatArray {
        val sourceR = r0.coerceIn(0f, 1f)
        val sourceG = g0.coerceIn(0f, 1f)
        val sourceB = b0.coerceIn(0f, 1f)
        val q = node.advancedColor.qualifier

        if (!q.enabled) {
            return AdvancedColorMath.applyNode(node, sourceR, sourceG, sourceB)
        }

        // Calculate the whole node grade without the legacy qualifier-only masking. The qualifier
        // below will gate the entire node result instead.
        val unmaskedNode = node.copy(
            advancedColor = node.advancedColor.copy(
                qualifier = q.copy(enabled = false),
            ),
        )
        var processed = AdvancedColorMath.applyNode(unmaskedNode, sourceR, sourceG, sourceB)

        // Preserve the existing qualified H/S/L shift controls as part of this node's processed
        // result. They are also limited by the same final node mask.
        if (q.hueShiftDegrees != 0f || q.saturationShift != 0f || q.luminanceShift != 0f) {
            val hsl = rgbToHsl(processed[0], processed[1], processed[2])
            hsl[0] = wrap01(hsl[0] + q.hueShiftDegrees / 360f)
            hsl[1] = (hsl[1] + q.saturationShift).coerceIn(0f, 1f)
            hsl[2] = (hsl[2] + q.luminanceShift).coerceIn(0f, 1f)
            processed = hslToRgb(hsl[0], hsl[1], hsl[2])
        }

        val mask = qualifierMask(q, sourceR, sourceG, sourceB)
        return floatArrayOf(
            lerp(sourceR, processed[0].coerceIn(0f, 1f), mask),
            lerp(sourceG, processed[1].coerceIn(0f, 1f), mask),
            lerp(sourceB, processed[2].coerceIn(0f, 1f), mask),
        )
    }

    internal fun qualifierMask(q: HslQualifier, r: Float, g: Float, b: Float): Float {
        if (!q.enabled) return 1f
        val hsl = rgbToHsl(r, g, b)
        val hueDeg = hsl[0] * 360f
        val hueWidth = q.hueWidthDegrees.coerceIn(1f, 360f)
        val hueMask = if (hueWidth >= 359.999f) {
            1f
        } else {
            val hueDistance = min(abs(hueDeg - q.hueCenterDegrees), 360f - abs(hueDeg - q.hueCenterDegrees))
            val hueHalf = (hueWidth * .5f).coerceIn(.5f, 180f)
            val hueSoft = max(.5f, hueHalf * q.softness.coerceIn(0f, 1f))
            1f - smoothstep((hueHalf - hueSoft).coerceAtLeast(0f), hueHalf, hueDistance)
        }
        val satMask = rangeMask(hsl[1], q.saturationMin, q.saturationMax, q.softness)
        val lumMask = rangeMask(hsl[2], q.luminanceMin, q.luminanceMax, q.softness)
        return (hueMask * satMask * lumMask).coerceIn(0f, 1f)
    }

    private fun rangeMask(value: Float, minValue: Float, maxValue: Float, softness: Float): Float {
        val lo = min(minValue, maxValue).coerceIn(0f, 1f)
        val hi = max(minValue, maxValue).coerceIn(0f, 1f)
        if (lo <= .0001f && hi >= .9999f) return 1f
        val edge = (softness.coerceIn(0f, 1f) * .25f).coerceAtLeast(.0001f)
        val rise = smoothstep(lo - edge, lo + edge, value)
        val fall = 1f - smoothstep(hi - edge, hi + edge, value)
        return (rise * fall).coerceIn(0f, 1f)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

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
