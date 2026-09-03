package com.tajuli.digitorandroid.editor.model

/** Clip-level cutout mode. Nullable TimelineClip persistence keeps legacy projects readable. */
enum class CutoutModeV43 { NONE, PERSON, CHROMA_KEY }

/**
 * Creator-facing cutout settings shared by preview and export.
 *
 * Person values are applied to the semantic foreground confidence matte. Chroma values operate in
 * Cb/Cr space so brightness changes in a green/blue screen do not destroy the key as easily as a
 * raw RGB-distance key.
 */
data class ClipCutoutV43(
    val mode: CutoutModeV43 = CutoutModeV43.NONE,
    val personThreshold: Float = .50f,
    val personFeather: Float = .06f,
    val keyRed: Float = 0f,
    val keyGreen: Float = 1f,
    val keyBlue: Float = 0f,
    val chromaSimilarity: Float = .10f,
    val chromaSoftness: Float = .08f,
    val spillSuppression: Float = .55f,
) {
    fun normalized(): ClipCutoutV43 {
        // V43 initially shipped with .42/.12. On bright backgrounds that wide confidence transition
        // leaves a visible pale fringe around hijab/hair/shoulders. Migrate only the untouched old
        // default pair; any user-adjusted threshold/feather values remain exactly user-controlled.
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
        )
    }
}

fun TimelineClip.resolvedCutoutV43(): ClipCutoutV43 =
    (cutoutV43 ?: ClipCutoutV43()).normalized()
