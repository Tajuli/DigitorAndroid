package com.tajuli.digitorandroid.editor.processing

import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualCameraSolverV1Test {
    @Test
    fun removesHandheldJitterWhilePreservingTravel() {
        val n = 180
        val raw = FloatArray(n) { i ->
            i * .004f + if (i % 2 == 0) .045f else -.045f
        }
        val confidence = FloatArray(n) { 1f }
        val solved = solveVirtualCameraPathV1(raw, confidence, lambda = 180f)

        assertTrue(virtualPathImprovementV1(raw, solved) > .92f)
        val rawTravel = raw.last() - raw.first()
        val solvedTravel = solved.last() - solved.first()
        assertTrue(abs(solvedTravel - rawTravel) < .10f)
    }

    @Test
    fun lowConfidenceSpikeDoesNotPullVirtualCamera() {
        val raw = FloatArray(90) { i -> i * .002f }
        raw[45] += .8f
        val confidence = FloatArray(90) { 1f }
        confidence[45] = 0f

        val solved = solveVirtualCameraPathV1(raw, confidence, lambda = 140f)

        val expected = 45 * .002f
        assertTrue(abs(solved[45] - expected) < .08f)
    }
}
