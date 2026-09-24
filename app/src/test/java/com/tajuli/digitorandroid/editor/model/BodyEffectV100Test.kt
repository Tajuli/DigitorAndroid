package com.tajuli.digitorandroid.editor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyEffectV100Test {
    @Test
    fun genericCreatorVectorStripsAllTrackedFields() {
        val body = resolveCreatorEffectsV25(
            listOf(
                NodeEffect(name = "Current Passing", amount = 1f),
                NodeEffect(name = "Fire Eyes", amount = 1f),
                NodeEffect(name = "X Clone", amount = 1f),
            ),
        )
        assertFalse(body.bodyValuesV100().isIdentity)

        val generic = body.withoutBodyEffectsV100()
        assertEquals(0f, generic.clone)
        assertEquals(0f, generic.fireEyes)
        assertEquals(0f, generic.bodyElectric)
        assertEquals(0f, generic.bodyAura)
        assertEquals(0f, generic.electricEyes)
        assertEquals(0f, generic.laserEyes)
        assertEquals(0f, generic.stroke)
        assertEquals(0f, generic.bodyFire)
    }

    @Test
    fun requirementsMatchEffectFamily() {
        val eyes = CreatorEffectCatalogV25.find("Electric Eyes")!!.vector.bodyValuesV100().requirementsV100()
        assertTrue(eyes.face)
        assertFalse(eyes.pose)
        assertFalse(eyes.matte)

        val current = CreatorEffectCatalogV25.find("Current Passing")!!.vector.bodyValuesV100().requirementsV100()
        assertFalse(current.face)
        assertTrue(current.pose)
        assertTrue(current.matte)

        val clone = CreatorEffectCatalogV25.find("Triple Clone")!!.vector.bodyValuesV100().requirementsV100()
        assertFalse(clone.face)
        assertFalse(clone.pose)
        assertTrue(clone.matte)
    }

    @Test
    fun poseTrackInterpolatesLandmarksAtRenderTime() {
        val a = List(33) { index -> BodyLandmarkV100(index / 100f, .20f, 0f) }
        val b = List(33) { index -> BodyLandmarkV100(index / 100f + .10f, .40f, .20f) }
        val track = BodyPoseTrackV100(
            sourceUri = "content://test",
            analyzedStartUs = 0L,
            analyzedEndUs = 1_000_000L,
            samples = listOf(
                BodyPoseSampleV100(0L, a),
                BodyPoseSampleV100(1_000_000L, b),
            ),
        )

        val middle = track.landmarksAt(500_000L)!!
        assertEquals(33, middle.size)
        assertEquals(a[11].x + .05f, middle[11].x, .0001f)
        assertEquals(.30f, middle[11].y, .0001f)
        assertEquals(.10f, middle[11].z, .0001f)
        assertEquals(1f, track.detectedRatio(), .0001f)
    }

    @Test
    fun bodyFaceTrackInterpolatesEyeRectangles() {
        val leftA = BeautyRectV28(.20f, .20f, .30f, .30f)
        val rightA = BeautyRectV28(.40f, .20f, .50f, .30f)
        val leftB = BeautyRectV28(.30f, .30f, .40f, .40f)
        val rightB = BeautyRectV28(.50f, .30f, .60f, .40f)
        val track = BodyFaceTrackV100(
            sourceUri = "content://test",
            analyzedStartUs = 0L,
            analyzedEndUs = 1_000_000L,
            samples = listOf(
                BodyFaceSampleV100(0L, leftA, rightA),
                BodyFaceSampleV100(1_000_000L, leftB, rightB),
            ),
        )

        val eyes = track.eyesAt(500_000L)!!
        assertEquals(.25f, eyes.first.left, .0001f)
        assertEquals(.45f, eyes.second.left, .0001f)
        assertEquals(1f, track.detectedRatio(), .0001f)
    }

    @Test
    fun poseTrackRespectsMissingDetectionSamples() {
        val landmarks = List(33) { BodyLandmarkV100(.5f, .5f) }
        val track = BodyPoseTrackV100(
            sourceUri = "content://test",
            analyzedStartUs = 0L,
            analyzedEndUs = 2_000_000L,
            samples = listOf(
                BodyPoseSampleV100(0L, landmarks),
                BodyPoseSampleV100(1_000_000L, null),
                BodyPoseSampleV100(2_000_000L, landmarks),
            ),
        )

        assertTrue(track.landmarksAt(900_000L) == null)
        assertEquals(2f / 3f, track.detectedRatio(), .0001f)
    }
}
