package com.tajuli.digitorandroid.editor.model

/** Clip-level cutout mode. Nullable TimelineClip persistence keeps legacy projects readable. */
enum class CutoutModeV43 { NONE, PERSON, CHROMA_KEY }

/**
 * V45 Pro Cutout settings. The historical V43 type name is intentionally retained so projects
 * created while the experimental cutout branches were being tested remain readable.
 *
 * PERSON means portrait alpha matting, not binary person segmentation. MODNet supplies a soft alpha
 * matte; hair fusion and V45 local spatial-flow temporal stabilization happen during analysis,
 * while the renderer keeps edge/dehalo controls realtime and shared by preview/export.
 */
data class ClipCutoutV43(
    val mode: CutoutModeV43 = CutoutModeV43.NONE,
    // Legacy controls retained for project compatibility. V45 maps them to matte alpha shaping.
    val personThreshold: Float = .50f,
    val personFeather: Float = .06f,
    val keyRed: Float = 0f,
    val keyGreen: Float = 1f,
    val keyBlue: Float = 0f,
    val chromaSimilarity: Float = .10f,
    val chromaSoftness: Float = .08f,
    val spillSuppression: Float = .55f,
    /** Negative shrinks the matte; positive grows it. */
    val edgeShiftV44: Float = -.008f,
    /** Contrast applied only to the uncertain alpha band. */
    val edgeCleanV44: Float = .30f,
    /** Pull edge RGB toward an interior foreground sample to remove bright/green halos. */
    val dehaloV44: Float = .46f,
    /** Strength used when MediaPipe HairSegmenter is fused into the MODNet alpha. */
    val hairDetailV44: Float = .84f,
    /** Local-flow previous-matte stabilization; 0 = none, 1 = strongest. */
    val temporalStabilityV44: Float = .62f,
) {
    fun normalized(): ClipCutoutV43 {
        val legacyPersonDefaults = personThreshold == .42f && personFeather == .12f
        val tunedThreshold = if (legacyPersonDefaults) .50f else personThreshold
        val tunedFeather = if (legacyPersonDefaults) .06f else personFeather

        // Migrate only the exact untouched V44 tuple. If the creator adjusted any V44 quality
        // control, preserve their choices instead of silently retuning the project.
        val untouchedV44QualityDefaults =
            edgeShiftV44 == -.015f &&
            edgeCleanV44 == .34f &&
            dehaloV44 == .62f &&
            hairDetailV44 == .72f &&
            temporalStabilityV44 == .68f
        val tunedEdgeShift = if (untouchedV44QualityDefaults) -.008f else edgeShiftV44
        val tunedEdgeClean = if (untouchedV44QualityDefaults) .30f else edgeCleanV44
        val tunedDehalo = if (untouchedV44QualityDefaults) .46f else dehaloV44
        val tunedHairDetail = if (untouchedV44QualityDefaults) .84f else hairDetailV44
        val tunedTemporal = if (untouchedV44QualityDefaults) .62f else temporalStabilityV44

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
