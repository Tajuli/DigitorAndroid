package com.tajuli.digitorandroid.editor.processing

import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveCameraSolverV4Test {
    @Test
    fun deliberatePanTravelIsPreservedWhileAlternatingShakeFalls() {
        val n = 180
        val raw = FloatArray(n) { i ->
            i * .0045f + if (i % 2 == 0) .035f else -.035f
        }
        val confidence = FloatArray(n) { .95f }

        val solved = solveAdaptiveVirtualCameraPathV4(
            raw = raw,
            confidence = confidence,
            baseLambda = 170f,
            intentVelocityThreshold = .0018f,
        )

        assertTrue(virtualPathImprovementV1(raw, solved) > .88f)
        val rawTravel = raw.last() - raw.first()
        val solvedTravel = solved.last() - solved.first()
        assertTrue(abs(solvedTravel - rawTravel) < .09f)
    }

    @Test
    fun uncertainRotationCannotReceiveFullCorrectionAtHundredPercentUiStrength() {
        val raw = FloatArray(80) { i ->
            if (i % 2 == 0) .8f else -.8f
        }
        val solved = FloatArray(80) { 0f }
        val confidence = FloatArray(80) { .22f }

        val gated = confidenceGatedTargetV4(
            raw = raw,
            solved = solved,
            confidence = confidence,
            minimumTrust = .42f,
        )

        val rawMagnitude = raw.map { abs(it) }.average()
        val correctionMagnitude = raw.indices
            .map { abs(gated[it] - raw[it]) }
            .average()
        assertTrue(correctionMagnitude < rawMagnitude * .10)
    }

    @Test
    fun oneFrameConfidenceDropDoesNotPumpCorrection() {
        val confidence = FloatArray(31) { .9f }
        confidence[15] = .05f

        val rolling = rollingConfidenceV4(confidence)

        assertTrue(rolling[15] > .8f)
        assertTrue(abs(rolling[14] - rolling[15]) < .1f)
        assertTrue(abs(rolling[15] - rolling[16]) < .1f)
    }
}
