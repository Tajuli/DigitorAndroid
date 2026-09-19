package com.tajuli.digitorandroid.editor.processing

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentTripodTrackerV3Test {
    @Test
    fun fixedReferencePoseUsesBackgroundAndRejectsMovingForeground() {
        val width = 480
        val height = 480
        val angleDegrees = 1.25f
        val scale = 1.012f
        val tx = 6.5f
        val ty = -4.0f
        val radians = Math.toRadians(angleDegrees.toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()

        val background = listOf(
            55f to 55f,
            240f to 55f,
            425f to 55f,
            65f to 225f,
            415f to 225f,
            60f to 420f,
            240f to 420f,
            420f to 420f,
        ).mapIndexed { index, (x, y) ->
            TripodTrackObservationV3(
                id = index + 1,
                referenceX = x,
                referenceY = y,
                currentX = scale * (c * x - s * y) + tx,
                currentY = scale * (s * x + c * y) + ty,
                ageFrames = 100,
                meanPatchError = 3f,
            )
        }

        // Foreground points deliberately move in a different direction.
        val foreground = listOf(
            TripodTrackObservationV3(101, 190f, 170f, 230f, 165f, 100, 4f),
            TripodTrackObservationV3(102, 230f, 180f, 270f, 174f, 100, 4f),
            TripodTrackObservationV3(103, 270f, 190f, 312f, 181f, 100, 4f),
        )
        val observations = background + foreground
        val ids = observations.mapTo(linkedSetOf()) { it.id }

        val pose = estimateTripodReferencePoseV3(observations, ids, width, height)
            ?: error("Expected a robust fixed-reference pose")

        assertTrue(abs(pose.rotationDegreesImage - angleDegrees) < .12f)
        assertTrue(abs(pose.scale - scale) < .004f)
        assertTrue(pose.inlierTrackIds.none { it >= 100 })

        val centerX = (width - 1) * .5f
        val centerY = (height - 1) * .5f
        val expectedCenterX = scale * (c * centerX - s * centerY) + tx - centerX
        val expectedCenterY = scale * (s * centerX + c * centerY) + ty - centerY
        assertTrue(abs(pose.centerDxPx - expectedCenterX) < .8f)
        assertTrue(abs(pose.centerDyPx - expectedCenterY) < .8f)
    }

    @Test
    fun finalFrameDefinesTracksThatStayedInsideForWholeSegment() {
        val first = listOf(
            TripodTrackObservationV3(1, 0f, 0f, 0f, 0f, 1, 0f),
            TripodTrackObservationV3(2, 0f, 0f, 0f, 0f, 1, 0f),
            TripodTrackObservationV3(3, 0f, 0f, 0f, 0f, 1, 0f),
        )
        val middle = listOf(
            TripodTrackObservationV3(1, 0f, 0f, 0f, 0f, 2, 0f),
            TripodTrackObservationV3(2, 0f, 0f, 0f, 0f, 2, 0f),
        )
        val last = listOf(
            TripodTrackObservationV3(2, 0f, 0f, 0f, 0f, 3, 0f),
        )

        assertEquals(setOf(2), survivingTripodTrackIdsV3(listOf(first, middle, last)))
    }
}
