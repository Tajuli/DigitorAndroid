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
     * Global tone/chroma response calibrated from the user-supplied Normal/Cinematic-Dark pair.
     * This is an independently fitted approximation; no third-party LUT/code/assets are stored.
     */
    CINEMATIC_DARK_REFERENCE,
}

/**
 * Data-only reference kernel so calibration is testable on the JVM and the GPU receives parameters
 * instead of hiding them in shader source.
 *
 * Tone polynomial coefficients are ordered c5..c0 and evaluated with Horner's rule. The matrix is
 * row-major RGB-output order: R row, G row, B row.
 */
data class CreatorReferenceKernelV37(
    val toneC5: Float,
    val toneC4: Float,
    val toneC3: Float,
    val toneC2: Float,
    val toneC1: Float,
    val toneC0: Float,
    val chromaFloor: Float,
    val chromaLow: Float,
    val chromaHigh: Float,
    val chromaMaster: Float,
    val matrixR: FloatArray,
    val matrixG: FloatArray,
    val matrixB: FloatArray,
    val offset: FloatArray,
) {
    init {
        require(matrixR.size == 3 && matrixG.size == 3 && matrixB.size == 3)
        require(offset.size == 3)
    }

    fun toneLuma(input: Float): Float {
        val y = input.coerceIn(0f, 1f)
        return (((((toneC5 * y + toneC4) * y + toneC3) * y + toneC2) * y + toneC1) * y + toneC0)
            .coerceIn(0f, 1f)
    }
}

/**
 * Fitted from seven aligned frames of the user-supplied Normal and CapCut Cinematic Dark videos.
 * Median full-frame tone response was used, followed by a chroma-magnitude fit and small residual
 * matrix. The model is intentionally spatially invariant.
 */
val CINEMATIC_DARK_REFERENCE_V37 = CreatorReferenceKernelV37(
    toneC5 = .11038443f,
    toneC4 = .20514610f,
    toneC3 = -1.25236079f,
    toneC2 = .97381303f,
    toneC1 = .92797531f,
    toneC0 = .00854670f,
    chromaFloor = .50f,
    chromaLow = .04f,
    chromaHigh = .18f,
    chromaMaster = .95f,
    matrixR = floatArrayOf(1.00241445f, .14546172f, -.15868165f),
    matrixG = floatArrayOf(-.01362015f, 1.09564502f, -.08796748f),
    matrixB = floatArrayOf(-.03250342f, .01014680f, .99816702f),
    offset = floatArrayOf(.01373053f, .00844339f, .02524541f),
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
