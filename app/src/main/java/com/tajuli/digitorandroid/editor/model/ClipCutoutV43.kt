package com.tajuli.digitorandroid.editor.model

/** Clip-level cutout mode. Nullable TimelineClip persistence keeps legacy projects readable. */
enum class CutoutModeV43 { NONE, PERSON, CHROMA_KEY }

/**
 * V46 Pro Cutout settings. The historical V43 type name is intentionally retained so projects
 * created while the experimental cutout branches were being tested remain readable.
 *
 * PERSON means portrait alpha matting, not binary person segmentation. MODNet supplies a soft alpha
 * matte; hair fusion and V45 local spatial-flow temporal stabilization happen during analysis.
 * V46 adds a realtime source-RGB-guided fabric/cloth refinement pass shared by preview/export.
 */
data class ClipCutoutV43(
    val mode: CutoutModeV43 = CutoutModeV43.NONE,
    // Legacy controls retained for project compatibility. V46 maps them to matte alpha shaping.
    val personThreshold: Float = .50f,
    val personFeather: Float = .075f,
    val keyRed: Float = 0f,
    val keyGreen: Float = 1f,
    val keyBlue: Float = 0f,
    val chromaSimilarity: Float = .10f,
    val chromaSoftness: Float = .08f,
    val spillSuppression: Float = .55f,
    /** Negative shrinks the matte; positive grows it. */
    val edgeShiftV44: Float = -.004f,
    /** Contrast applied only to the uncertain alpha band. */
    val edgeCleanV44: Float = .24f,
    /** Texture-preserving edge colour decontamination strength. */
    val dehaloV44: Float = .30f,
    /** Strength used when MediaPipe HairSegmenter is fused into the MODNet alpha. */
    val hairDetailV44: Float = .62f,
    /** Local-flow previous-matte stabilization; 0 = none, 1 = strongest. */
    val temporalStabilityV44: Float = .54f,
) {
    fun normalized(): ClipCutoutV43 {
        val legacyPersonDefaults = personThreshold == .42f && personFeather == .12f
        val tunedThreshold = if (legacyPersonDefaults) .50f else personThreshold

        val untouchedV44QualityDefaults =
            edgeShiftV44 == -.015f &&
            edgeCleanV44 == .34f &&
            dehaloV44 == .62f &&
            hairDetailV44 == .72f &&
            temporalStabilityV44 == .68f
        val untouchedV45QualityDefaults =
            edgeShiftV44 == -.008f &&
            edgeCleanV44 == .30f &&
            dehaloV44 == .46f &&
            hairDetailV44 == .84f &&
            temporalStabilityV44 == .62f
        val untouchedQualityDefaults = untouchedV44QualityDefaults || untouchedV45QualityDefaults

        // V46 deliberately backs away from aggressive hair/dehalo/temporal processing because cloth
        // edges (hijab, scarves, sleeves) look more realistic when source texture and motion blur are
        // allowed to remain visible. Exact-tuple migration means creator-tuned projects are preserved.
        val tunedFeather = when {
            legacyPersonDefaults -> .075f
            untouchedQualityDefaults && personFeather == .06f -> .075f
            else -> personFeather
        }
        val tunedEdgeShift = if (untouchedQualityDefaults) -.004f else edgeShiftV44
        val tunedEdgeClean = if (untouchedQualityDefaults) .24f else edgeCleanV44
        val tunedDehalo = if (untouchedQualityDefaults) .30f else dehaloV44
        val tunedHairDetail = if (untouchedQualityDefaults) .62f else hairDetailV44
        val tunedTemporal = if (untouchedQualityDefaults) .54f else temporalStabilityV44

        return copy(
            personThreshold = tunedThreshold.coerceIn(.05f, .95f),
            personFeather = tunedFeather.coerceIn(.005f, .45f),
            keyRed = keyRed.coerceIn(0f, 1f),
            keyGreen = keyGreen.coerceIn(0f, 1f),
            keyBlue = keyBlue.coerceIn(0f, 1f),
            chromaSimilarity = chromaSimilarity.coerceIn(.01f, .40f),
            chromaSoftness = chromaSoftness.coerceIn(.005f, .30f),
            spillSuppression = spillSuppression.coerceIn(0f, 1f),
            edgeShiftV44 = tunedEdgeShift.coerceIn(-.18f, .18f),
            edgeCleanV44 = tunedEdgeClean.coerceIn(0f, 1f),
            dehaloV44 = tunedDehalo.coerceIn(0f, 1f),
            hairDetailV44 = tunedHairDetail.coerceIn(0f, 1f),
            temporalStabilityV44 = tunedTemporal.coerceIn(0f, .92f),
        )
    }
}

fun TimelineClip.resolvedCutoutV43(): ClipCutoutV43 =
    (cutoutV43 ?: ClipCutoutV43()).normalized()
