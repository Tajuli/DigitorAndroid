package com.tajuli.digitorandroid.editor.processing

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * V4 adaptive whole-shot camera solver.
 *
 * A constant second-derivative penalty is excellent for shake but can make the start/stop of a
 * deliberate pan feel rubbery. V4 lowers regularization only where a short temporal window shows
 * consistent signed velocity (camera intent). Alternating/high-frequency velocity keeps the full
 * smoothing penalty.
 */
internal fun solveAdaptiveVirtualCameraPathV4(
    raw: FloatArray,
    confidence: FloatArray,
    baseLambda: Float,
    intentVelocityThreshold: Float,
    iterations: Int = 140,
): FloatArray {
    val n = raw.size
    if (n <= 2) return raw.copyOf()
    require(confidence.size == n)

    val trust = rollingConfidenceV4(confidence)
    val dataWeights = FloatArray(n) { i ->
        (.05f + .95f * trust[i].coerceIn(0f, 1f)).coerceAtLeast(.04f)
    }
    val curvatureLambda = FloatArray(n) { baseLambda.coerceAtLeast(0f) }

    for (i in 2 until n - 2) {
        val velocities = floatArrayOf(
            raw[i - 1] - raw[i - 2],
            raw[i] - raw[i - 1],
            raw[i + 1] - raw[i],
            raw[i + 2] - raw[i + 1],
        )
        val medianVelocity = medianFloatV4(velocities)
        val speed = abs(medianVelocity)
        val sameDirection = velocities.count { v ->
            abs(v) <= intentVelocityThreshold * .25f ||
                (v > 0f) == (medianVelocity > 0f)
        } / velocities.size.toFloat()

        // Intent requires sustained one-direction movement, not one large noisy step.
        val speedTrust = (
            (speed - intentVelocityThreshold) /
                (intentVelocityThreshold * 2.5f).coerceAtLeast(1e-6f)
            ).coerceIn(0f, 1f)
        val directionTrust = ((sameDirection - .55f) / .45f).coerceIn(0f, 1f)
        val intent = smoothstepV4(speedTrust * directionTrust)

        // Keep at least 28% of the global regularization so deliberate motion is still cinematic.
        curvatureLambda[i] = baseLambda * (1f - .72f * intent)
    }

    val b = FloatArray(n) { dataWeights[it] * raw[it] }
    val x = raw.copyOf()
    var residual = subtractV4(
        b,
        applyAdaptiveOperatorV4(x, dataWeights, curvatureLambda),
    )
    var direction = residual.copyOf()
    var residualEnergy = dotV4(residual, residual)
    if (residualEnergy <= 1e-12f) return x

    repeat(iterations) {
        val applied = applyAdaptiveOperatorV4(direction, dataWeights, curvatureLambda)
        val denominator = dotV4(direction, applied)
        if (abs(denominator) <= 1e-12f) return@repeat
        val alpha = residualEnergy / denominator

        for (i in 0 until n) {
            x[i] += alpha * direction[i]
            residual[i] -= alpha * applied[i]
        }

        val nextEnergy = dotV4(residual, residual)
        if (sqrt(nextEnergy.toDouble()) < 1e-5) return x
        val beta = nextEnergy / residualEnergy.coerceAtLeast(1e-12f)
        for (i in 0 until n) {
            direction[i] = residual[i] + beta * direction[i]
        }
        residualEnergy = nextEnergy
    }
    return x
}

/**
 * Per-DOF confidence gate. UI strength 100% is still bounded by measurement trust: an uncertain
 * rotation/scale estimate cannot be promoted into a full render correction.
 */
internal fun confidenceGatedTargetV4(
    raw: FloatArray,
    solved: FloatArray,
    confidence: FloatArray,
    minimumTrust: Float,
): FloatArray {
    require(raw.size == solved.size && raw.size == confidence.size)
    val rolling = rollingConfidenceV4(confidence)
    return FloatArray(raw.size) { i ->
        val normalized = (
            (rolling[i] - minimumTrust) /
                (1f - minimumTrust).coerceAtLeast(.01f)
            ).coerceIn(0f, 1f)
        val trust = smoothstepV4(normalized)
        raw[i] + (solved[i] - raw[i]) * trust
    }
}

/**
 * A noisy confidence measurement should not pump correction on/off frame-by-frame.
 */
internal fun rollingConfidenceV4(
    confidence: FloatArray,
    halfWindow: Int = 3,
): FloatArray {
    if (confidence.isEmpty()) return confidence.copyOf()
    return FloatArray(confidence.size) { i ->
        val from = (i - halfWindow).coerceAtLeast(0)
        val to = (i + halfWindow).coerceAtMost(confidence.lastIndex)
        val window = FloatArray(to - from + 1) { j ->
            confidence[from + j].coerceIn(0f, 1f)
        }
        // Median resists a one-frame false high/low tracker score.
        medianFloatV4(window)
    }
}

/**
 * If a solved channel does not reduce measured acceleration enough, leave that channel untouched.
 */
internal fun safeSolvedPathV4(
    raw: FloatArray,
    candidate: FloatArray,
    minimumImprovement: Float,
): FloatArray {
    if (raw.size != candidate.size || raw.size < 4) return raw.copyOf()
    return if (virtualPathImprovementV1(raw, candidate) >= minimumImprovement) {
        candidate
    } else {
        raw.copyOf()
    }
}

private fun applyAdaptiveOperatorV4(
    x: FloatArray,
    dataWeights: FloatArray,
    curvatureLambda: FloatArray,
): FloatArray {
    val output = FloatArray(x.size) { dataWeights[it] * x[it] }
    for (i in 1 until x.lastIndex) {
        val lambda = curvatureLambda[i].coerceAtLeast(0f)
        if (lambda <= 0f) continue
        val secondDifference = x[i - 1] - 2f * x[i] + x[i + 1]
        output[i - 1] += lambda * secondDifference
        output[i] -= 2f * lambda * secondDifference
        output[i + 1] += lambda * secondDifference
    }
    return output
}

private fun medianFloatV4(values: FloatArray): Float {
    if (values.isEmpty()) return 0f
    val sorted = values.sortedArray()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        sorted[middle]
    } else {
        (sorted[middle - 1] + sorted[middle]) * .5f
    }
}

private fun smoothstepV4(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun subtractV4(a: FloatArray, b: FloatArray): FloatArray =
    FloatArray(a.size) { a[it] - b[it] }

private fun dotV4(a: FloatArray, b: FloatArray): Float {
    var sum = 0f
    for (i in a.indices) sum += a[i] * b[i]
    return sum
}
