package com.tajuli.digitorandroid.editor.processing

/**
 * V103 background-track quality model.
 *
 * Long-lived tracks that repeatedly agree with the dominant global camera model receive the
 * strongest influence. Fresh/short tracks can help bootstrap a shot, but cannot immediately
 * overpower established background geometry. Photometric quality contributes only a small part so
 * moving foreground texture does not become "background" merely because it is sharp.
 */
internal fun backgroundTrackWeightV103(
    ageFrames: Int,
    consensusFrames: Int,
    normalizedSad: Float,
): Float {
    val age = ((ageFrames.coerceAtLeast(1) - 1) / 24f).coerceIn(0f, 1f)
    val consensus = (consensusFrames.coerceAtLeast(0) / 14f).coerceIn(0f, 1f)
    val quality = (1f - normalizedSad.coerceIn(0f, 42f) / 42f).coerceIn(0f, 1f)
    // Lifespan alone is not evidence of background: a walking person may remain visible for
    // seconds. Age earns strong weight only when the track repeatedly agrees with the global model.
    val trustedHistory = age * (.25f + .75f * consensus)
    return (
        .12f +
            trustedHistory * .42f +
            consensus * .32f +
            quality * .14f
        ).coerceIn(.10f, 1f)
}

internal fun stableBackgroundTrackV103(
    ageFrames: Int,
    consensusFrames: Int,
    weight: Float,
): Boolean =
    ageFrames >= 6 &&
        consensusFrames >= 4 &&
        weight >= .46f
