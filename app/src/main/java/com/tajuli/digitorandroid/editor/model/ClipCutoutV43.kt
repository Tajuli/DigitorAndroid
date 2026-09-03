package com.tajuli.digitorandroid.editor.model

/** Clip-level cutout mode. Nullable TimelineClip persistence keeps legacy projects readable. */
enum class CutoutModeV43 { NONE, PERSON, CHROMA_KEY }

/**
 * V44 Pro Cutout settings. The historical V43 type name is intentionally retained so projects
 * created while the experimental cutout branch was being tested remain readable.
 *
 * PERSON now means portrait alpha matting, not binary person segmentation. MODNet supplies a soft
 * alpha matte; hair fusion and temporal stabilization happen during analysis, while the renderer
 * keeps edge/dehalo controls realtime and shared by preview/export.
 */
data class ClipCutoutV43(
    val mode: CutoutModeV43 = CutoutModeV43.NONE,
    // Legacy controls retained for project compatibility. V44 maps them to matte edge shaping.
    val personThreshold: Float = .50f,
    val personFeather: Float = .06f,
    val keyRed: Float = 0f,
    val keyGreen: Float = 1f,
    val keyBlue: Float = 0f,
    val chromaSimilarity: Float = .10f,
    val chromaSoftness: Float = .08f,
    val spillSuppression: Float = .55f,
    /** Negative shrinks the matte; positive grows it. */
    val edgeShiftV44: Float = -.015f,
    /** Contrast applied only to the uncertain alpha band. */
    val edgeCleanV44: Float = .34f,
    /** Pull edge RGB toward an interior foreground sample to remove bright/green halos. */
    val dehaloV44: Float = .62f,
    /** Strength used when MediaPipe HairSegmenter is fused into the MODNet alpha. */
    val hairDetailV44: Float = .72f,
    /** Motion-aware previous-matte stabilization; 0 = none, 1 = strongest. */
    val temporalStabilityV44: Float = .68f,
) {
    fun normalized(): ClipCutoutV43 {
        val legacyPersonDefaults = personThreshold == .42f && personFeather == .12f
        val tunedThreshold = if (legacyPersonDefaults) .50f else personThreshold
        val tunedFeather = if (legacyPersonDefaults) .06f else personFeather
        return copy(
            personThreshold = tunedThreshold.coerceIn(.05f, .95f),
            personFeather = tunedFeather.coerceIn(.005f, .45f),
            keyRed = keyRed.coerceIn(0f, 1f),
            keyGreen = keyGreen.coerceIn(0f, 1f),
            keyBlue = keyBlue.coerceIn(0f, 1f),
            chromaSimilarity = chromaSimilarity.coerceIn(.01f, .40f),
            chromaSoftness = chromaSoftness.coerceIn(.005f, .30f),
            spillSuppression = spillSuppression.coerceIn(0f, 1f),
            edgeShiftV44 = edgeShiftV44.coerceIn(-.18f, .18f),
            edgeCleanV44 = edgeCleanV44.coerceIn(0f, 1f),
            dehaloV44 = dehaloV44.coerceIn(0f, 1f),
            hairDetailV44 = hairDetailV44.coerceIn(0f, 1f),
            temporalStabilityV44 = temporalStabilityV44.coerceIn(0f, .92f),
        )
    }
}

fun TimelineClip.resolvedCutoutV43(): ClipCutoutV43 =
    (cutoutV43 ?: ClipCutoutV43()).normalized()
