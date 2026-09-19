package com.tajuli.digitorandroid.editor.processing

import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

class TripodDriftCorrectionV2Test {
    @Test
    fun removesLongTermPairwiseDriftWithoutInjectingAnchorJitter() {
        val n = 330
        val rawX = FloatArray(n) { i ->
            val shake = if (i % 2 == 0) .008f else -.008f
            shake + i * .00045f
        }
        val rawY = FloatArray(n) { i ->
            val shake = if (i % 3 == 0) .006f else -.003f
            shake - i * .00030f
        }
        val rawR = FloatArray(n) { i ->
            val shake = if (i % 2 == 0) .035f else -.035f
            shake + i * .006f
        }
        val rawS = FloatArray(n)

        val anchors = (12 until n step 12).map { i ->
            val noisyX = if (i % 24 == 0) .006f else -.004f
            val noisyR = if (i % 36 == 0) .08f else -.05f
            TripodReferenceAnchorV2(
                sampleIndex = i,
                x = (if (i % 2 == 0) .008f else -.008f) + noisyX,
                y = (if (i % 3 == 0) .006f else -.003f),
                rotationDegrees = (if (i % 2 == 0) .035f else -.035f) + noisyR,
                logScale = 0f,
                confidence = .9f,
            )
        }

        val corrected = correctTripodDriftV2(rawX, rawY, rawR, rawS, anchors)

        val rawTailX = rawX.takeLast(30).map { abs(it) }.average()
        val correctedTailX = corrected.x.takeLast(30).map { abs(it) }.average()
        val rawTailR = rawR.takeLast(30).map { abs(it) }.average()
        val correctedTailR = corrected.rotationDegrees.takeLast(30).map { abs(it) }.average()

        assertTrue(correctedTailX < rawTailX * .35)
        assertTrue(correctedTailR < rawTailR * .25)

        // Reference noise must remain low-frequency; adjacent correction deltas stay tiny.
        var maxCorrectionStep = 0f
        for (i in 1 until n) {
            val previousCorrection = corrected.x[i - 1] - rawX[i - 1]
            val currentCorrection = corrected.x[i] - rawX[i]
            maxCorrectionStep = maxOf(maxCorrectionStep, abs(currentCorrection - previousCorrection))
        }
        assertTrue(maxCorrectionStep < .006f)
    }

    @Test
    fun lowConfidenceReferenceOutlierIsIgnored() {
        val n = 60
        val rawX = FloatArray(n) { it * .001f }
        val zeros = FloatArray(n)
        val anchors = listOf(
            TripodReferenceAnchorV2(20, .01f, 0f, 0f, 0f, .9f),
            TripodReferenceAnchorV2(40, 1.0f, 0f, 0f, 0f, .2f),
        )

        val corrected = correctTripodDriftV2(rawX, zeros, zeros, zeros, anchors)

        assertTrue(abs(corrected.x[40]) < .08f)
    }
}
