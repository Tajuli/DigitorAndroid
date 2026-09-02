package com.tajuli.digitorandroid.editor.model

/**
 * V37 look-selection contract.
 *
 * A creator LOOK is deliberately different from BEAUTY. LOOKS are full-frame color transforms and
 * never depend on face boxes, skin masks, ML segmentation, or pixel location. Beauty may remain
 * semantic/spatial, but it is a separate stage and a separate group.
 *
 * Single-look semantics are intentional: when legacy projects contain several LOOK markers, the
 * last enabled marker wins. This gives old V36 projects deterministic behaviour without summing
 * unrelated looks into an unpredictable grade.
 */
enum class CreatorLookKernelV37 {
    /** Existing Digitor-owned analytic full-frame grade for generic looks. */
    GENERIC_GLOBAL,

    /**
     * Full-frame 3D color mapping calibrated from the user-supplied Normal/Cinematic-Dark pair.
     * The coefficients generate a tiny 17^3 LUT at shader creation time; no third-party LUT, code,
     * model or asset is bundled.
     */
    CINEMATIC_DARK_REFERENCE,
}

/**
 * Compact cubic RGB mapping used only to generate the V37 reference LUT.
 *
 * Terms, in order:
 *  1, r, g, b, r2, g2, b2, rg, rb, gb,
 *  r3, g3, b3, r2g, r2b, g2r, g2b, b2r, b2g, rgb.
 *
 * Each row stores the R/G/B output coefficient for one term. Keeping this mapping data-only makes
 * calibration JVM-testable while realtime rendering remains a cheap two-texture-sample 3D LUT.
 */
data class CreatorReferencePolynomialV37(
    val coefficients: Array<FloatArray>,
) {
    init {
        require(coefficients.size == TERM_COUNT)
        require(coefficients.all { it.size == 3 })
    }

    fun mapRgb(r: Float, g: Float, b: Float): FloatArray {
        val rc = r.coerceIn(0f, 1f)
        val gc = g.coerceIn(0f, 1f)
        val bc = b.coerceIn(0f, 1f)
        val r2 = rc * rc
        val g2 = gc * gc
        val b2 = bc * bc
        val terms = floatArrayOf(
            1f,
            rc, gc, bc,
            r2, g2, b2,
            rc * gc, rc * bc, gc * bc,
            r2 * rc, g2 * gc, b2 * bc,
            r2 * gc, r2 * bc,
            g2 * rc, g2 * bc,
            b2 * rc, b2 * gc,
            rc * gc * bc,
        )
        val out = FloatArray(3)
        for (termIndex in 0 until TERM_COUNT) {
            val term = terms[termIndex]
            val c = coefficients[termIndex]
            out[0] += term * c[0]
            out[1] += term * c[1]
            out[2] += term * c[2]
        }
        out[0] = out[0].coerceIn(0f, 1f)
        out[1] = out[1].coerceIn(0f, 1f)
        out[2] = out[2].coerceIn(0f, 1f)
        return out
    }

    companion object {
        const val TERM_COUNT = 20
    }
}

/**
 * Ridge-regularised cubic RGB fit from seven aligned frames of the user-supplied Normal and CapCut
 * Cinematic Dark videos (5/10/20/30/40/50/60 s). It models the measured full-frame mapping instead
 * of any face-specific response. On the supplied aligned frames the fitted mapping reduces mean
 * absolute RGB error from roughly 10 levels (old Digitor) to about 1.8-1.9 levels on an 8-bit scale.
 */
val CINEMATIC_DARK_REFERENCE_V37 = CreatorReferencePolynomialV37(
    coefficients = arrayOf(
        floatArrayOf(-.032808089f, -.023659867f, -.045973529f),
        floatArrayOf(1.392485632f, .036718310f, .096231001f),
        floatArrayOf(-.423305937f, 1.294635899f, -.109660031f),
        floatArrayOf(.286929774f, -.054181904f, 1.202121354f),
        floatArrayOf(-.007358719f, -.450435353f, .058045929f),
        floatArrayOf(.964123241f, -.410293296f, .637483465f),
        floatArrayOf(-.650989599f, -.261136532f, .358796729f),
        floatArrayOf(.178711882f, .610569929f, -.328779146f),
        floatArrayOf(-.187391116f, .473449398f, .119390100f),
        floatArrayOf(-.131480092f, .038396709f, -.406707054f),
        floatArrayOf(-.241478142f, .390071123f, .450826370f),
        floatArrayOf(-.714816788f, -1.023716170f, -1.390813007f),
        floatArrayOf(.944869582f, .634146720f, -.263326578f),
        floatArrayOf(-1.484565485f, -1.002966224f, -1.388962471f),
        floatArrayOf(.950280479f, .099043453f, -.636232787f),
        floatArrayOf(.678657641f, 1.267123759f, 2.662622104f),
        floatArrayOf(-.235223372f, .639946068f, .093215077f),
        floatArrayOf(-1.190089200f, -.905051132f, -.043291270f),
        floatArrayOf(-.357678231f, -.426518197f, -.219947584f),
        floatArrayOf(1.223119473f, .048972001f, .129604155f),
    ),
)

data class ActiveCreatorLookV37(
    val preset: CreatorFilterPresetV36,
    val intensity: Float,
    val kernel: CreatorLookKernelV37,
)

fun creatorLookKernelV37(presetId: String): CreatorLookKernelV37 = when (presetId) {
    "moody_cinema" -> CreatorLookKernelV37.CINEMATIC_DARK_REFERENCE
    else -> CreatorLookKernelV37.GENERIC_GLOBAL
}

fun TimelineClip.activeCreatorLookV37(): ActiveCreatorLookV37? {
    val applied = appliedCreatorFiltersV36()
    val selected = applied.entries
        .mapNotNull { (id, amount) ->
            val preset = creatorFilterPresetV36(id) ?: return@mapNotNull null
            if (preset.group != CreatorFilterGroupV36.LOOKS || amount <= .001f) return@mapNotNull null
            preset to amount.coerceIn(0f, 1f)
        }
        .lastOrNull()
        ?: return null

    return ActiveCreatorLookV37(
        preset = selected.first,
        intensity = selected.second,
        kernel = creatorLookKernelV37(selected.first.id),
    )
}
