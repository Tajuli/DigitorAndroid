package com.tajuli.digitorandroid.editor.processing

import com.tajuli.digitorandroid.editor.model.PerspectiveQuadV102
import com.tajuli.digitorandroid.editor.model.StabilizationPathSampleV90
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

class PerspectiveDriftV105Test {
    @Test
    fun noisyGlobalAnchorsCorrectDriftWithoutReplacingIncrementalMotion() {
        val samples = (0 until 90).map { index ->
            val trueX = if (index % 2 == 0) .012f else -.012f
            val drift = index * .0012f
            StabilizationPathSampleV90(
                sourceTimeUs = index * 33_333L,
                pathX = 0f,
                pathY = 0f,
                rotationDegrees = 0f,
                segmentV93 = 0,
                perspectivePathV102 = translatedQuad(trueX + drift),
            )
        }
        val anchors = (0 until 90).map { index ->
            val trueX = if (index % 2 == 0) .012f else -.012f
            val fitNoise = when (index % 5) {
                0 -> .020f
                1 -> -.016f
                else -> 0f
            }
            PerspectiveDriftAnchorV105(
                sampleIndex = index,
                segment = 0,
                quad = translatedQuad(trueX + fitNoise),
            )
        }

        val corrected = applyGlobalPerspectiveDriftV105(samples, anchors)
        val rawTailError = samples.takeLast(30).mapIndexed { local, sample ->
            val index = 60 + local
            val trueX = if (index % 2 == 0) .012f else -.012f
            abs(sample.perspectivePathV102!!.topLeftX - (-1f + trueX))
        }.average()
        val correctedTailError = corrected.takeLast(30).mapIndexed { local, sample ->
            val index = 60 + local
            val trueX = if (index % 2 == 0) .012f else -.012f
            abs(sample.perspectivePathV102!!.topLeftX - (-1f + trueX))
        }.average()

        // Global anchors should remove most accumulated drift even though the anchors themselves
        // contain large one-frame fit noise.
        assertTrue(correctedTailError < rawTailError * .45)

        // The alternating high-frequency camera component belongs to the incremental path and must
        // remain visible to the later raw->smoothed stabilization solve instead of being replaced
        // by a per-frame reference fit.
        val rawDelta = samples[81].perspectivePathV102!!.topLeftX -
            samples[80].perspectivePathV102!!.topLeftX
        val correctedDelta = corrected[81].perspectivePathV102!!.topLeftX -
            corrected[80].perspectivePathV102!!.topLeftX
        assertTrue(abs(correctedDelta - rawDelta) < .006f)
    }

    @Test
    fun sceneSegmentsDoNotShareDriftResiduals() {
        val samples = listOf(
            sample(0, 0, 0f),
            sample(1, 0, .05f),
            sample(2, 1, 0f),
            sample(3, 1, -.04f),
        )
        val anchors = listOf(
            PerspectiveDriftAnchorV105(1, 0, translatedQuad(0f)),
            PerspectiveDriftAnchorV105(3, 1, translatedQuad(0f)),
        )

        val corrected = applyGlobalPerspectiveDriftV105(samples, anchors, medianHalfWindow = 0)

        assertTrue(abs(corrected[0].perspectivePathV102!!.topLeftX + 1f) < .0001f)
        assertTrue(abs(corrected[2].perspectivePathV102!!.topLeftX + 1f) < .0001f)
    }

    private fun sample(index: Int, segment: Int, x: Float) =
        StabilizationPathSampleV90(
            sourceTimeUs = index * 33_333L,
            pathX = 0f,
            pathY = 0f,
            rotationDegrees = 0f,
            segmentV93 = segment,
            perspectivePathV102 = translatedQuad(x),
        )

    private fun translatedQuad(x: Float) = PerspectiveQuadV102(
        topLeftX = -1f + x,
        topLeftY = 1f,
        topRightX = 1f + x,
        topRightY = 1f,
        bottomRightX = 1f + x,
        bottomRightY = -1f,
        bottomLeftX = -1f + x,
        bottomLeftY = -1f,
    )
}
