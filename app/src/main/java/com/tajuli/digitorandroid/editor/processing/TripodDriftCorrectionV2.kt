package com.tajuli.digitorandroid.editor.processing

import kotlin.math.abs

/**
 * V2 tripod drift correction.
 *
 * Pairwise motion is excellent for high-frequency shake but accumulates slow numerical/tracking
 * drift. Sparse direct reference->current observations estimate that drift without replacing the
 * per-frame path. We robustly clean only the residual and interpolate it slowly across time.
 */
internal data class TripodReferenceAnchorV2(
    val sampleIndex: Int,
    val x: Float,
    val y: Float,
    val rotationDegrees: Float,
    val logScale: Float,
    val confidence: Float,
)

internal data class TripodCorrectedPathV2(
    val x: FloatArray,
    val y: FloatArray,
    val rotationDegrees: FloatArray,
    val logScale: FloatArray,
)

internal fun correctTripodDriftV2(
    rawX: FloatArray,
    rawY: FloatArray,
    rawRotationDegrees: FloatArray,
    rawLogScale: FloatArray,
    anchors: List<TripodReferenceAnchorV2>,
): TripodCorrectedPathV2 {
    val n = rawX.size
    require(rawY.size == n && rawRotationDegrees.size == n && rawLogScale.size == n)
    if (n == 0 || anchors.isEmpty()) {
        return TripodCorrectedPathV2(
            rawX.copyOf(),
            rawY.copyOf(),
            rawRotationDegrees.copyOf(),
            rawLogScale.copyOf(),
        )
    }

    data class Residual(
        val index: Int,
        val x: Float,
        val y: Float,
        val rotation: Float,
        val logScale: Float,
        val confidence: Float,
    )

    val residuals = ArrayList<Residual>()
    // The segment start is the tripod reference by definition.
    residuals += Residual(0, 0f, 0f, 0f, 0f, 1f)

    for (anchor in anchors) {
        val i = anchor.sampleIndex
        if (i !in 0 until n || anchor.confidence < MIN_REFERENCE_CONFIDENCE_V2) continue
        residuals += Residual(
            index = i,
            x = (anchor.x - rawX[i]).coerceIn(-MAX_TRANSLATION_RESIDUAL_V2, MAX_TRANSLATION_RESIDUAL_V2),
            y = (anchor.y - rawY[i]).coerceIn(-MAX_TRANSLATION_RESIDUAL_V2, MAX_TRANSLATION_RESIDUAL_V2),
            rotation = shortestAngleDeltaV2(
                rawRotationDegrees[i],
                anchor.rotationDegrees,
            ).coerceIn(-MAX_ROTATION_RESIDUAL_V2, MAX_ROTATION_RESIDUAL_V2),
            logScale = (anchor.logScale - rawLogScale[i])
                .coerceIn(-MAX_LOG_SCALE_RESIDUAL_V2, MAX_LOG_SCALE_RESIDUAL_V2),
            confidence = anchor.confidence.coerceIn(0f, 1f),
        )
    }

    val sorted = residuals
        .distinctBy { it.index }
        .sortedBy { it.index }
    if (sorted.size == 1) {
        return TripodCorrectedPathV2(
            rawX.copyOf(),
            rawY.copyOf(),
            rawRotationDegrees.copyOf(),
            rawLogScale.copyOf(),
        )
    }

    val cleaned = sorted.mapIndexed { index, value ->
        if (index == 0) {
            value
        } else {
            val from = (index - REFERENCE_MEDIAN_HALF_WINDOW_V2).coerceAtLeast(0)
            val to = (index + REFERENCE_MEDIAN_HALF_WINDOW_V2).coerceAtMost(sorted.lastIndex)
            val neighborhood = sorted.subList(from, to + 1)
                .filter { it.confidence >= MIN_REFERENCE_CONFIDENCE_V2 }
                .ifEmpty { listOf(value) }
            value.copy(
                x = medianV2(neighborhood.map { it.x }),
                y = medianV2(neighborhood.map { it.y }),
                rotation = medianV2(neighborhood.map { it.rotation }),
                logScale = medianV2(neighborhood.map { it.logScale }),
            )
        }
    }

    val outX = rawX.copyOf()
    val outY = rawY.copyOf()
    val outR = rawRotationDegrees.copyOf()
    val outS = rawLogScale.copyOf()

    for (i in 0 until n) {
        var left = cleaned.first()
        var right = cleaned.last()
        for (candidate in cleaned) {
            if (candidate.index <= i) left = candidate
            if (candidate.index >= i) {
                right = candidate
                break
            }
        }

        val t = if (left.index == right.index) {
            0f
        } else {
            ((i - left.index).toFloat() / (right.index - left.index).toFloat())
                .coerceIn(0f, 1f)
        }
        // Slow C1 interpolation avoids anchor-to-anchor correction velocity jumps.
        val smoothT = t * t * (3f - 2f * t)
        val dx = lerpV2(left.x, right.x, smoothT)
        val dy = lerpV2(left.y, right.y, smoothT)
        val dr = lerpV2(left.rotation, right.rotation, smoothT)
        val ds = lerpV2(left.logScale, right.logScale, smoothT)

        outX[i] = rawX[i] + dx
        outY[i] = rawY[i] + dy
        outR[i] = rawRotationDegrees[i] + dr
        outS[i] = rawLogScale[i] + ds
    }

    return TripodCorrectedPathV2(outX, outY, outR, outS)
}

private fun shortestAngleDeltaV2(from: Float, to: Float): Float {
    var delta = to - from
    while (delta > 180f) delta -= 360f
    while (delta < -180f) delta += 360f
    return delta
}

private fun medianV2(values: List<Float>): Float {
    if (values.isEmpty()) return 0f
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        sorted[middle]
    } else {
        (sorted[middle - 1] + sorted[middle]) * .5f
    }
}

private fun lerpV2(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private const val MIN_REFERENCE_CONFIDENCE_V2 = .48f
private const val REFERENCE_MEDIAN_HALF_WINDOW_V2 = 2
private const val MAX_TRANSLATION_RESIDUAL_V2 = .35f
private const val MAX_ROTATION_RESIDUAL_V2 = 4f
private const val MAX_LOG_SCALE_RESIDUAL_V2 = .08f
