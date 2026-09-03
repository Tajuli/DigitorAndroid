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
    val personThreshold: Float = .42f,
    val personFeather: Float = .12f,
    val keyRed: Float = 0f,
    val keyGreen: Float = 1f,
    val keyBlue: Float = 0f,
    val chromaSimilarity: Float = .10f,
    val chromaSoftness: Float = .08f,
    val spillSuppression: Float = .55f,
) {
    fun normalized(): ClipCutoutV43 = copy(
        personThreshold = personThreshold.coerceIn(.05f, .95f),
        personFeather = personFeather.coerceIn(.005f, .45f),
        keyRed = keyRed.coerceIn(0f, 1f),
        keyGreen = keyGreen.coerceIn(0f, 1f),
        keyBlue = keyBlue.coerceIn(0f, 1f),
        chromaSimilarity = chromaSimilarity.coerceIn(.01f, .40f),
        chromaSoftness = chromaSoftness.coerceIn(.005f, .30f),
        spillSuppression = spillSuppression.coerceIn(0f, 1f),
    )
}

fun TimelineClip.resolvedCutoutV43(): ClipCutoutV43 =
    (cutoutV43 ?: ClipCutoutV43()).normalized()
