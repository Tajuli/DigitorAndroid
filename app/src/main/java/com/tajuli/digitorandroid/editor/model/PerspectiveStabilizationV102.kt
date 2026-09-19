package com.tajuli.digitorandroid.editor.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

internal data class PerspectiveTransformV102(
    val matrixValues: FloatArray,
    val autoZoom: Float,
)

internal fun ClipStabilizationV90.evaluatePerspectiveV102(
    sourceTimeUs: Long,
): PerspectiveTransformV102? {
    val state = normalized()
    if (
        !state.enabled ||
        state.mode != StabilizationModeV90.PERSPECTIVE ||
        state.analysisVersionV93 < 102 ||
        state.samples.none { it.perspectivePathV102 != null }
    ) {
        return null
    }

    val matrix = state.perspectiveCorrectionMatrixV102(sourceTimeUs) ?: return null
    val autoZoom = when {
        !state.zoomEnabledV102 -> 1f
        state.cameraLockV102 ->
            state.cameraLockPerspectiveCoverScaleV102.coerceIn(1f, MAX_PROJECTIVE_ZOOM_V102)
        else -> state.perspectiveZoomEnvelopeV102(sourceTimeUs, matrix)
    }
    return PerspectiveTransformV102(
        matrixValues = matrix.scaledOutputV102(autoZoom),
        autoZoom = autoZoom,
    )
}

internal fun ClipStabilizationV90.computeCameraLockPerspectiveCoverScaleV102(): Float {
    val state = normalized().copy(
        mode = StabilizationModeV90.PERSPECTIVE,
        cameraLockV102 = true,
        croppingRatioV102 = 0f,
        strength = 1f,
    )
    if (state.samples.none { it.cameraLockPerspectivePathV102 != null }) return 1f
    var required = 1f
    for (sample in state.samples) {
        val matrix = state.perspectiveCorrectionMatrixV102(sample.sourceTimeUs) ?: continue
        required = max(required, requiredProjectiveCoverScaleV102(matrix))
    }
    return required.coerceIn(1f, MAX_PROJECTIVE_ZOOM_V102)
}

private fun ClipStabilizationV90.perspectiveCorrectionMatrixV102(
    sourceTimeUs: Long,
): FloatArray? {
    val raw = if (cameraLockV102) {
        cameraLockPerspectiveAtV102(sourceTimeUs)
    } else {
        perspectivePathAtV102(sourceTimeUs)
    } ?: return null

    val desired = if (cameraLockV102) {
        PerspectiveQuadV102.IDENTITY
    } else {
        smoothedPerspectivePathV102(sourceTimeUs)
    }

    // Resolve Cropping Ratio limits how hard stabilization is allowed to work.
    // 1.0 -> no stabilization; lower values progressively allow a stronger correction.
    val cropAggression = if (cameraLockV102) {
        1f
    } else {
        sqrt((1f - croppingRatioV102.coerceIn(0f, 1f)).toDouble()).toFloat()
    }
    val amount = (strength.coerceIn(0f, 1f) * cropAggression).coerceIn(0f, 1f)
    if (amount <= .0001f) return identityHomographyV102()

    val src = raw.asPoints()
    val target = desired.asPoints()
    val dst = FloatArray(8) { index ->
        src[index] + (target[index] - src[index]) * amount
    }
    return solveHomographyV102(src, dst)
}

private fun ClipStabilizationV90.perspectivePathAtV102(
    sourceTimeUs: Long,
): PerspectiveQuadV102? =
    interpolateQuadV102(sourceTimeUs, cameraLock = false)

private fun ClipStabilizationV90.cameraLockPerspectiveAtV102(
    sourceTimeUs: Long,
): PerspectiveQuadV102? =
    interpolateQuadV102(sourceTimeUs, cameraLock = true)

private fun ClipStabilizationV90.interpolateQuadV102(
    sourceTimeUs: Long,
    cameraLock: Boolean,
): PerspectiveQuadV102? {
    val available = samples.filter {
        if (cameraLock) it.cameraLockPerspectivePathV102 != null else it.perspectivePathV102 != null
    }
    if (available.isEmpty()) return null

    fun quad(sample: StabilizationPathSampleV90): PerspectiveQuadV102 =
        if (cameraLock) {
            sample.cameraLockPerspectivePathV102 ?: PerspectiveQuadV102.IDENTITY
        } else {
            sample.perspectivePathV102 ?: PerspectiveQuadV102.IDENTITY
        }

    if (sourceTimeUs <= available.first().sourceTimeUs) return quad(available.first())
    if (sourceTimeUs >= available.last().sourceTimeUs) return quad(available.last())

    var lo = 0
    var hi = available.lastIndex
    while (hi - lo > 1) {
        val mid = (lo + hi) ushr 1
        if (available[mid].sourceTimeUs <= sourceTimeUs) lo = mid else hi = mid
    }
    val a = available[lo]
    val b = available[hi]
    if (a.segmentV93 != b.segmentV93) {
        val midpoint = a.sourceTimeUs + (b.sourceTimeUs - a.sourceTimeUs) / 2L
        return quad(if (sourceTimeUs < midpoint) a else b)
    }

    val span = (b.sourceTimeUs - a.sourceTimeUs).coerceAtLeast(1L)
    val t = ((sourceTimeUs - a.sourceTimeUs).toDouble() / span.toDouble())
        .toFloat()
        .coerceIn(0f, 1f)
    return lerpQuadV102(quad(a), quad(b), t)
}

private fun ClipStabilizationV90.smoothedPerspectivePathV102(
    sourceTimeUs: Long,
): PerspectiveQuadV102 {
    val center = perspectivePathAtV102(sourceTimeUs) ?: PerspectiveQuadV102.IDENTITY
    val segment = samples.minByOrNull { abs(it.sourceTimeUs - sourceTimeUs) }?.segmentV93 ?: 0
    val radius = smoothRadiusUs.coerceAtLeast(80_000L)
    val centerPoints = center.asPoints()
    val sums = DoubleArray(8) { centerPoints[it].toDouble() }
    var sumWeight = 1.0

    for (sample in samples) {
        if (sample.segmentV93 != segment) continue
        val quad = sample.perspectivePathV102 ?: continue
        val distance = abs(sample.sourceTimeUs - sourceTimeUs)
        if (distance > radius || distance == 0L) continue
        val normalized = distance.toDouble() / radius.toDouble()
        val kernel = (1.0 - normalized * normalized).coerceAtLeast(0.0)
        val weight = kernel * kernel * (.20 + .80 * sample.confidence.coerceIn(0f, 1f))
        if (weight <= 1e-8) continue
        val points = quad.asPoints()
        for (index in 0 until 8) sums[index] += points[index] * weight
        sumWeight += weight
    }

    val output = FloatArray(8) { (sums[it] / sumWeight).toFloat() }
    return PerspectiveQuadV102(
        topLeftX = output[0],
        topLeftY = output[1],
        topRightX = output[2],
        topRightY = output[3],
        bottomRightX = output[4],
        bottomRightY = output[5],
        bottomLeftX = output[6],
        bottomLeftY = output[7],
    )
}

private fun ClipStabilizationV90.perspectiveZoomEnvelopeV102(
    sourceTimeUs: Long,
    currentMatrix: FloatArray,
): Float {
    val radius = PERSPECTIVE_ZOOM_ENVELOPE_RADIUS_US_V102
    var required = requiredProjectiveCoverScaleV102(currentMatrix)
    var index = 0

    for (sample in samples) {
        if (abs(sample.sourceTimeUs - sourceTimeUs) > radius) continue
        if (sample.perspectivePathV102 == null) continue
        if (index++ % PERSPECTIVE_ZOOM_PROBE_STRIDE_V102 != 0) continue
        val matrix = perspectiveCorrectionMatrixV102(sample.sourceTimeUs) ?: continue
        val probe = requiredProjectiveCoverScaleV102(matrix)
        val normalized = abs(sample.sourceTimeUs - sourceTimeUs).toFloat() / radius.toFloat()
        val feather = (1f - normalized * normalized).coerceAtLeast(0f)
        val feathered = 1f + (probe - 1f).coerceAtLeast(0f) * feather
        required = max(required, feathered)
    }
    return required.coerceIn(1f, MAX_PROJECTIVE_ZOOM_V102)
}

private fun lerpQuadV102(
    a: PerspectiveQuadV102,
    b: PerspectiveQuadV102,
    t: Float,
): PerspectiveQuadV102 {
    val ap = a.asPoints()
    val bp = b.asPoints()
    val out = FloatArray(8) { ap[it] + (bp[it] - ap[it]) * t }
    return PerspectiveQuadV102(
        out[0], out[1],
        out[2], out[3],
        out[4], out[5],
        out[6], out[7],
    )
}

internal fun solveHomographyV102(
    src: FloatArray,
    dst: FloatArray,
): FloatArray? {
    if (src.size < 8 || dst.size < 8) return null

    val a = Array(8) { DoubleArray(9) }
    for (point in 0 until 4) {
        val x = src[point * 2].toDouble()
        val y = src[point * 2 + 1].toDouble()
        val u = dst[point * 2].toDouble()
        val v = dst[point * 2 + 1].toDouble()

        val r0 = point * 2
        a[r0][0] = x
        a[r0][1] = y
        a[r0][2] = 1.0
        a[r0][6] = -x * u
        a[r0][7] = -y * u
        a[r0][8] = u

        val r1 = r0 + 1
        a[r1][3] = x
        a[r1][4] = y
        a[r1][5] = 1.0
        a[r1][6] = -x * v
        a[r1][7] = -y * v
        a[r1][8] = v
    }

    for (column in 0 until 8) {
        var pivot = column
        var best = abs(a[pivot][column])
        for (row in column + 1 until 8) {
            val candidate = abs(a[row][column])
            if (candidate > best) {
                best = candidate
                pivot = row
            }
        }
        if (best < 1e-10) return null
        if (pivot != column) {
            val tmp = a[column]
            a[column] = a[pivot]
            a[pivot] = tmp
        }

        val divisor = a[column][column]
        for (j in column until 9) a[column][j] /= divisor

        for (row in 0 until 8) {
            if (row == column) continue
            val factor = a[row][column]
            if (abs(factor) < 1e-12) continue
            for (j in column until 9) {
                a[row][j] -= factor * a[column][j]
            }
        }
    }

    return floatArrayOf(
        a[0][8].toFloat(), a[1][8].toFloat(), a[2][8].toFloat(),
        a[3][8].toFloat(), a[4][8].toFloat(), a[5][8].toFloat(),
        a[6][8].toFloat(), a[7][8].toFloat(), 1f,
    )
}

internal fun requiredProjectiveCoverScaleV102(matrix: FloatArray): Float {
    val inverse = invertHomographyV102(matrix) ?: return MAX_PROJECTIVE_ZOOM_V102

    fun covers(scale: Float): Boolean {
        val corners = floatArrayOf(
            -1f / scale, 1f / scale,
            1f / scale, 1f / scale,
            1f / scale, -1f / scale,
            -1f / scale, -1f / scale,
        )
        for (index in 0 until 4) {
            val mapped = mapHomographyV102(
                inverse,
                corners[index * 2],
                corners[index * 2 + 1],
            ) ?: return false
            if (abs(mapped.first) > 1.0005f || abs(mapped.second) > 1.0005f) return false
        }
        return true
    }

    if (covers(1f)) return 1f
    var low = 1f
    var high = MAX_PROJECTIVE_ZOOM_V102
    repeat(24) {
        val mid = (low + high) * .5f
        if (covers(mid)) high = mid else low = mid
    }
    return high
}

private fun invertHomographyV102(m: FloatArray): FloatArray? {
    if (m.size < 9) return null
    val a = m[0].toDouble(); val b = m[1].toDouble(); val c = m[2].toDouble()
    val d = m[3].toDouble(); val e = m[4].toDouble(); val f = m[5].toDouble()
    val g = m[6].toDouble(); val h = m[7].toDouble(); val i = m[8].toDouble()

    val co00 = e * i - f * h
    val co01 = -(d * i - f * g)
    val co02 = d * h - e * g
    val co10 = -(b * i - c * h)
    val co11 = a * i - c * g
    val co12 = -(a * h - b * g)
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

private fun mapHomographyV102(
    m: FloatArray,
    x: Float,
    y: Float,
): Pair<Float, Float>? {
    val w = m[6] * x + m[7] * y + m[8]
    if (abs(w) < 1e-6f) return null
    return (
        (m[0] * x + m[1] * y + m[2]) / w
        ) to (
        (m[3] * x + m[4] * y + m[5]) / w
        )
}

private fun FloatArray.scaledOutputV102(scale: Float): FloatArray {
    if (size < 9 || abs(scale - 1f) < 1e-5f) return copyOf()
    return floatArrayOf(
        this[0] * scale, this[1] * scale, this[2] * scale,
        this[3] * scale, this[4] * scale, this[5] * scale,
        this[6], this[7], this[8],
    )
}

private fun identityHomographyV102(): FloatArray =
    floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f,
    )

private const val PERSPECTIVE_ZOOM_ENVELOPE_RADIUS_US_V102 = 1_000_000L
private const val PERSPECTIVE_ZOOM_PROBE_STRIDE_V102 = 4
private const val MAX_PROJECTIVE_ZOOM_V102 = 2.5f
