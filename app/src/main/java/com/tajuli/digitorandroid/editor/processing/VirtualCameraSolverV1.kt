package com.tajuli.digitorandroid.editor.processing

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Global virtual-camera solver.
 *
 * Minimizes:
 *   data fidelity + lambda * second-derivative energy
 *
 * Unlike frame-local smoothing, the whole scene segment is solved together. The rendered motion is
 * the solved virtual-camera path; correction is derived later as target * inverse(measured).
 */
internal fun solveVirtualCameraPathV1(
    raw: FloatArray,
    confidence: FloatArray,
    lambda: Float,
    iterations: Int = 120,
): FloatArray {
    val n = raw.size
    if (n <= 2) return raw.copyOf()
    require(confidence.size == n)

    val weights = FloatArray(n) { index ->
        (.08f + .92f * confidence[index].coerceIn(0f, 1f)).coerceAtLeast(.05f)
    }
    val b = FloatArray(n) { weights[it] * raw[it] }
    val x = raw.copyOf()
    var r = subtractV1(b, applyOperatorV1(x, weights, lambda))
    var p = r.copyOf()
    var rsOld = dotV1(r, r)
    if (rsOld <= 1e-12f) return x

    repeat(iterations) {
        val ap = applyOperatorV1(p, weights, lambda)
        val denominator = dotV1(p, ap)
        if (abs(denominator) <= 1e-12f) return@repeat
        val alpha = rsOld / denominator
        for (i in 0 until n) {
            x[i] += alpha * p[i]
            r[i] -= alpha * ap[i]
        }
        val rsNew = dotV1(r, r)
        if (sqrt(rsNew.toDouble()) < 1e-5) return x
        val beta = rsNew / rsOld.coerceAtLeast(1e-12f)
        for (i in 0 until n) p[i] = r[i] + beta * p[i]
        rsOld = rsNew
    }
    return x
}

internal fun virtualPathImprovementV1(raw: FloatArray, solved: FloatArray): Float {
    if (raw.size < 4 || raw.size != solved.size) return 1f
    val rawJerk = secondDifferenceEnergyV1(raw)
    val solvedJerk = secondDifferenceEnergyV1(solved)
    if (rawJerk <= 1e-8f) return 1f
    return (1f - solvedJerk / rawJerk).coerceIn(0f, 1f)
}

private fun applyOperatorV1(
    x: FloatArray,
    weights: FloatArray,
    lambda: Float,
): FloatArray {
    val y = FloatArray(x.size) { weights[it] * x[it] }
    val l = max(0f, lambda)
    if (l <= 0f) return y

    for (i in 1 until x.lastIndex) {
        val d2 = x[i - 1] - 2f * x[i] + x[i + 1]
        y[i - 1] += l * d2
        y[i] -= 2f * l * d2
        y[i + 1] += l * d2
    }
    return y
}

private fun secondDifferenceEnergyV1(values: FloatArray): Float {
    var sum = 0f
    for (i in 1 until values.lastIndex) {
        val d = values[i - 1] - 2f * values[i] + values[i + 1]
        sum += d * d
    }
    return sum
}

private fun subtractV1(a: FloatArray, b: FloatArray): FloatArray =
    FloatArray(a.size) { a[it] - b[it] }

private fun dotV1(a: FloatArray, b: FloatArray): Float {
    var sum = 0f
    for (i in a.indices) sum += a[i] * b[i]
    return sum
}
