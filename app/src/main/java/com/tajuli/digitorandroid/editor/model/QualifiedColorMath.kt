package com.tajuli.digitorandroid.editor.model

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Applies a Resolve-style HSL qualifier as a mask for the whole node.
 *
 * The key is calculated from RGB entering the node. All node grading is computed normally, then
 * blended over the node input by the refined H/S/L matte. Pixels outside the matte pass through.
 *
 * Softness is an OUTER feather: the hard H/S/L range stays at 100% matte, then the matte falls
 * smoothly from 1 -> 0 outside that range. A hard boundary is therefore never reduced to 50%.
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

        // Compute the node grade without the legacy qualifier-only shift masking. The refined matte
        // below gates the entire node, matching Resolve's qualifier-on-node behavior.
        val unmaskedNode = node.copy(
            advancedColor = node.advancedColor.copy(
                qualifier = q.copy(enabled = false),
            ),
        )
        var processed = AdvancedColorMath.applyNode(unmaskedNode, sourceR, sourceG, sourceB)

        if (q.hueShiftDegrees != 0f || q.saturationShift != 0f || q.luminanceShift != 0f) {
            val hsl = rgbToHsl(processed[0], processed[1], processed[2])
            hsl[0] = wrap01(hsl[0] + q.hueShiftDegrees / 360f)
            hsl[1] = (hsl[1] + q.saturationShift).coerceIn(0f, 1f)
            hsl[2] = (hsl[2] + q.luminanceShift).coerceIn(0f, 1f)
            processed = hslToRgb(hsl[0], hsl[1], hsl[2])
        }

        val mask = qualifierMask(node, sourceR, sourceG, sourceB)
        return floatArrayOf(
            lerp(sourceR, processed[0].coerceIn(0f, 1f), mask),
            lerp(sourceG, processed[1].coerceIn(0f, 1f), mask),
            lerp(sourceB, processed[2].coerceIn(0f, 1f), mask),
        )
    }

    /** Compatibility overload retained for existing tests/callers. */
    internal fun qualifierMask(q: HslQualifier, r: Float, g: Float, b: Float): Float {
        val node = ColorNode(
            kind = NodeKind.SERIAL,
            label = "qualifier-mask",
            position = NodePosition(0f, 0f),
            advancedColor = AdvancedColorGrade(qualifier = q),
        )
        return qualifierMask(node, r, g, b)
    }

    internal fun qualifierMask(node: ColorNode, r: Float, g: Float, b: Float): Float {
        val q = node.advancedColor.qualifier
        if (!q.enabled) return 1f

        val hsl = rgbToHsl(r, g, b)

        // Pre-filter/blur are only a LUT-space approximation until a neighborhood shader exists.
        // Keep their contribution small and, importantly, do not move the hard 100% H/S/L edges.
        val preFilter = node.qualifierFinesse(QualifierFinesseKeys.PRE_FILTER, 0f).coerceIn(0f, 1f)
        val blurRadius = node.qualifierFinesse(QualifierFinesseKeys.BLUR_RADIUS, 0f).coerceIn(0f, 10f)
        val extraSoft = (preFilter * .03f + blurRadius / 10f * .05f).coerceIn(0f, .08f)

        val hueMask = hueMask(
            hueDegrees = hsl[0] * 360f,
            centerDegrees = q.hueCenterDegrees,
            widthDegrees = q.hueWidthDegrees,
            softness = (q.softness + extraSoft).coerceIn(0f, 1f),
            symmetry = node.qualifierFinesse(QualifierFinesseKeys.HUE_SYMMETRY, .5f).coerceIn(0f, 1f),
        )
        val satMask = rangeMask(
            value = hsl[1],
            minValue = q.saturationMin,
            maxValue = q.saturationMax,
            lowSoftness = (node.qualifierFinesse(QualifierFinesseKeys.SAT_LOW_SOFT, .08f) + extraSoft).coerceIn(0f, 1f),
            highSoftness = (node.qualifierFinesse(QualifierFinesseKeys.SAT_HIGH_SOFT, .08f) + extraSoft).coerceIn(0f, 1f),
        )
        val lumMask = rangeMask(
            value = hsl[2],
            minValue = q.luminanceMin,
            maxValue = q.luminanceMax,
            lowSoftness = (node.qualifierFinesse(QualifierFinesseKeys.LUM_LOW_SOFT, .08f) + extraSoft).coerceIn(0f, 1f),
            highSoftness = (node.qualifierFinesse(QualifierFinesseKeys.LUM_HIGH_SOFT, .08f) + extraSoft).coerceIn(0f, 1f),
        )

        var matte = (hueMask * satMask * lumMask).coerceIn(0f, 1f)
        matte = applyMatteFinesse(node, matte)
        return matte.coerceIn(0f, 1f)
    }

    /**
     * Hue width defines the full-strength core. Softness extends OUTSIDE that core.
     * q.softness is a 0..1 fraction of the hue circle; because there are two edges, each edge gets
     * half of that total circle fraction (softness * 180 degrees).
     */
    private fun hueMask(
        hueDegrees: Float,
        centerDegrees: Float,
        widthDegrees: Float,
        softness: Float,
        symmetry: Float,
    ): Float {
        val width = widthDegrees.coerceIn(1f, 360f)
        if (width >= 359.999f) return 1f

        val delta = signedHueDelta(hueDegrees, centerDegrees)
        val leftSpan = (width * symmetry).coerceIn(.5f, 180f)
        val rightSpan = (width * (1f - symmetry)).coerceIn(.5f, 180f)
        val hardSpan = if (delta < 0f) leftSpan else rightSpan
        val distance = kotlin.math.abs(delta)

        // Everything inside the selected hard hue range gets the complete node effect.
        if (distance <= hardSpan) return 1f

        val feather = (softness.coerceIn(0f, 1f) * 180f)
            .coerceAtMost((180f - hardSpan).coerceAtLeast(0f))
        if (feather <= .0001f) return 0f
        if (distance >= hardSpan + feather) return 0f

        return 1f - smoothstep(hardSpan, hardSpan + feather, distance)
    }

    /**
     * Low/High are the 100% matte boundaries. Low Soft feathers only BELOW Low and High Soft
     * feathers only ABOVE High. This gives a predictable 1 -> 0 falloff instead of centering the
     * transition on Low/High (which incorrectly made the boundary itself 50%).
     *
     * Soft values are literal 0..1 fractions of the full S/L axis, so e.g. 0.10 means a 10-point
     * feather on the 0..100 UI scale.
     */
    private fun rangeMask(
        value: Float,
        minValue: Float,
        maxValue: Float,
        lowSoftness: Float,
        highSoftness: Float,
    ): Float {
        val lo = min(minValue, maxValue).coerceIn(0f, 1f)
        val hi = max(minValue, maxValue).coerceIn(0f, 1f)
        if (lo <= .0001f && hi >= .9999f) return 1f

        val lowMask = when {
            lo <= .0001f -> 1f
            value >= lo -> 1f
            lowSoftness <= .0001f -> 0f
            else -> {
                val feather = lowSoftness.coerceIn(0f, 1f).coerceAtMost(lo)
                val outer = lo - feather
                if (value <= outer) 0f else smoothstep(outer, lo, value)
            }
        }

        val highMask = when {
            hi >= .9999f -> 1f
            value <= hi -> 1f
            highSoftness <= .0001f -> 0f
            else -> {
                val feather = highSoftness.coerceIn(0f, 1f).coerceAtMost(1f - hi)
                val outer = hi + feather
                if (value >= outer) 0f else 1f - smoothstep(hi, outer, value)
            }
        }

        return (lowMask * highMask).coerceIn(0f, 1f)
    }

    private fun applyMatteFinesse(node: ColorNode, input: Float): Float {
        var matte = input.coerceIn(0f, 1f)
        val blackClip = node.qualifierFinesse(QualifierFinesseKeys.BLACK_CLIP, 0f).coerceIn(0f, 1f)
        val whiteClip = node.qualifierFinesse(QualifierFinesseKeys.WHITE_CLIP, 1f).coerceIn(0f, 1f)
        val lo = min(blackClip, whiteClip - .001f).coerceIn(0f, .999f)
        val hi = max(whiteClip, lo + .001f).coerceIn(.001f, 1f)
        matte = smoothstep(lo, hi, matte)

        val cleanBlack = node.qualifierFinesse(QualifierFinesseKeys.CLEAN_BLACK, 0f).coerceIn(0f, 1f)
        if (cleanBlack > 0f) {
            matte = matte.pow(1f + cleanBlack * 5f)
        }

        val cleanWhite = node.qualifierFinesse(QualifierFinesseKeys.CLEAN_WHITE, 0f).coerceIn(0f, 1f)
        if (cleanWhite > 0f) {
            matte = 1f - (1f - matte).coerceIn(0f, 1f).pow(1f + cleanWhite * 5f)
        }

        val ratio = node.qualifierFinesse(QualifierFinesseKeys.IN_OUT_RATIO, 0f).coerceIn(-1f, 1f)
        matte = if (ratio >= 0f) {
            matte + (1f - matte) * ratio * .35f
        } else {
            matte * (1f + ratio * .35f)
        }
        return matte.coerceIn(0f, 1f)
    }

    private fun signedHueDelta(hue: Float, center: Float): Float =
        ((hue - center + 540f) % 360f) - 180f

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
