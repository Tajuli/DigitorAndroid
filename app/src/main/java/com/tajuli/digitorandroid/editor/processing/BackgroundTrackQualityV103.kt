package com.tajuli.digitorandroid.editor.processing

import kotlin.math.abs

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


/**
 * V104: map a feature observed in the current frame back into the global scene reference.
 * The supplied matrix maps reference NDC -> current NDC.
 */
internal fun mapCurrentPointToReferenceV104(
    currentX: Float,
    currentY: Float,
    width: Int,
    height: Int,
    referenceToCurrent: FloatArray,
): Pair<Float, Float>? {
    if (width <= 1 || height <= 1 || referenceToCurrent.size < 9) return null
    val inverse = invertHomographyV104(referenceToCurrent) ?: return null
    val x = currentX / (width - 1).toFloat() * 2f - 1f
    val y = 1f - currentY / (height - 1).toFloat() * 2f
    val w = inverse[6] * x + inverse[7] * y + inverse[8]
    if (abs(w) < 1e-6f) return null
    val rx = (inverse[0] * x + inverse[1] * y + inverse[2]) / w
    val ry = (inverse[3] * x + inverse[4] * y + inverse[5]) / w
    return (
        (rx + 1f) * .5f * (width - 1).toFloat()
        ) to (
        (1f - ry) * .5f * (height - 1).toFloat()
        )
}

internal fun invertHomographyV104(m: FloatArray): FloatArray? {
    if (m.size < 9) return null
    val a = m[0].toDouble(); val b = m[1].toDouble(); val c = m[2].toDouble()
    val d = m[3].toDouble(); val e = m[4].toDouble(); val f = m[5].toDouble()
    val g = m[6].toDouble(); val hh = m[7].toDouble(); val i = m[8].toDouble()

    val co00 = e * i - f * hh
    val co01 = -(d * i - f * g)
    val co02 = d * hh - e * g
    val co10 = -(b * i - c * hh)
    val co11 = a * i - c * g
    val co12 = -(a * hh - b * g)
    val co20 = b * f - c * e
    val co21 = -(a * f - c * d)
    val co22 = a * e - b * d
    val determinant = a * co00 + b * co01 + c * co02
    if (abs(determinant) < 1e-10) return null
    val invDet = 1.0 / determinant
    return floatArrayOf(
        (co00 * invDet).toFloat(), (co10 * invDet).toFloat(), (co20 * invDet).toFloat(),
        (co01 * invDet).toFloat(), (co11 * invDet).toFloat(), (co21 * invDet).toFloat(),
        (co02 * invDet).toFloat(), (co12 * invDet).toFloat(), (co22 * invDet).toFloat(),
    )
}
