package com.tajuli.digitorandroid.editor.processing

import com.tajuli.digitorandroid.editor.model.PerspectiveQuadV102
import com.tajuli.digitorandroid.editor.model.StabilizationPathSampleV90
import kotlin.math.abs

/**
 * V105 drift fusion for normal Perspective stabilization.
 *
 * A fixed-reference homography is valuable for correcting long-term integration drift, but it is
 * too noisy to replace the live per-frame path directly. Doing so converts reference-fit noise into
 * visible render jitter. Keep the incremental path (which carries real high-frequency camera
 * motion) and use robust, low-frequency fixed-reference residuals only to bend that path back toward
 * the global scene reference.
 */
internal data class PerspectiveDriftAnchorV105(
    val sampleIndex: Int,
    val segment: Int,
    val quad: PerspectiveQuadV102,
)

internal fun applyGlobalPerspectiveDriftV105(
    samples: List<StabilizationPathSampleV90>,
    anchors: List<PerspectiveDriftAnchorV105>,
    medianHalfWindow: Int = PERSPECTIVE_DRIFT_MEDIAN_HALF_WINDOW_V105,
): List<StabilizationPathSampleV90> {
    if (samples.size < 2 || anchors.isEmpty()) return samples

    data class Residual(
        val sampleIndex: Int,
        val segment: Int,
        val values: FloatArray,
    )

    val residualsBySegment = LinkedHashMap<Int, MutableList<Residual>>()

    // Pin the first frame of every scene to zero drift. This prevents a later noisy reference
    // observation from moving the whole segment.
    samples.forEachIndexed { index, sample ->
        if (samples.getOrNull(index - 1)?.segmentV93 != sample.segmentV93) {
            residualsBySegment.getOrPut(sample.segmentV93) { ArrayList() } +=
                Residual(index, sample.segmentV93, FloatArray(8))
        }
    }

    for (anchor in anchors) {
        val rawSample = samples.getOrNull(anchor.sampleIndex) ?: continue
        if (rawSample.segmentV93 != anchor.segment) continue
        val raw = rawSample.perspectivePathV102 ?: continue
        val rawPoints = raw.asPoints()
        val anchorPoints = anchor.quad.asPoints()
        val residual = FloatArray(8) { point ->
            (anchorPoints[point] - rawPoints[point])
                .coerceIn(-MAX_PERSPECTIVE_DRIFT_RESIDUAL_NDC_V105, MAX_PERSPECTIVE_DRIFT_RESIDUAL_NDC_V105)
        }
        residualsBySegment.getOrPut(anchor.segment) { ArrayList() } +=
            Residual(anchor.sampleIndex, anchor.segment, residual)
    }

    val cleanedBySegment = residualsBySegment.mapValues { (_, values) ->
        val sorted = values
            .distinctBy { it.sampleIndex }
            .sortedBy { it.sampleIndex }

        sorted.mapIndexed { index, value ->
            if (sorted.size <= 2 || value.sampleIndex == sorted.first().sampleIndex) {
                value
            } else {
                val from = (index - medianHalfWindow).coerceAtLeast(0)
                val to = (index + medianHalfWindow).coerceAtMost(sorted.lastIndex)
                val neighborhood = sorted.subList(from, to + 1)
                val cleaned = FloatArray(8) { coordinate ->
                    medianV105(neighborhood.map { it.values[coordinate] })
                }
                Residual(value.sampleIndex, value.segment, cleaned)
            }
        }
    }

    return samples.mapIndexed { index, sample ->
        val raw = sample.perspectivePathV102 ?: return@mapIndexed sample
        val residuals = cleanedBySegment[sample.segmentV93].orEmpty()
        if (residuals.isEmpty()) return@mapIndexed sample

        var left = residuals.first()
        var right = residuals.last()
        for (candidate in residuals) {
            if (candidate.sampleIndex <= index) left = candidate
            if (candidate.sampleIndex >= index) {
                right = candidate
                break
            }
        }

        val correction = if (left.sampleIndex == right.sampleIndex) {
            left.values
        } else {
            val t = ((index - left.sampleIndex).toFloat() /
                (right.sampleIndex - left.sampleIndex).toFloat()).coerceIn(0f, 1f)
            val smoothT = t * t * (3f - 2f * t)
            FloatArray(8) { coordinate ->
                left.values[coordinate] +
                    (right.values[coordinate] - left.values[coordinate]) * smoothT
            }
        }

        val rawPoints = raw.asPoints()
        val corrected = FloatArray(8) { coordinate ->
            (rawPoints[coordinate] + correction[coordinate]).coerceIn(-1.80f, 1.80f)
        }
        sample.copy(
            perspectivePathV102 = PerspectiveQuadV102(
                topLeftX = corrected[0],
                topLeftY = corrected[1],
                topRightX = corrected[2],
                topRightY = corrected[3],
                bottomRightX = corrected[4],
                bottomRightY = corrected[5],
                bottomLeftX = corrected[6],
                bottomLeftY = corrected[7],
            ),
        )
    }
}

private fun medianV105(values: List<Float>): Float {
    if (values.isEmpty()) return 0f
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        sorted[middle]
    } else {
        (sorted[middle - 1] + sorted[middle]) * .5f
    }
}

private const val PERSPECTIVE_DRIFT_MEDIAN_HALF_WINDOW_V105 = 10
private const val MAX_PERSPECTIVE_DRIFT_RESIDUAL_NDC_V105 = .30f
