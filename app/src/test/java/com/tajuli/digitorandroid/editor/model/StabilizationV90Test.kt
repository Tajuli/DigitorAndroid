package com.tajuli.digitorandroid.editor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StabilizationV90Test {
    @Test
    fun noAnalysis_isIdentity() {
        val evaluated = ClipStabilizationV90().evaluate(1_000_000L)
        assertEquals(0f, evaluated.offsetX, 0.0001f)
        assertEquals(0f, evaluated.offsetY, 0.0001f)
        assertEquals(0f, evaluated.rotationDegrees, 0.0001f)
        assertEquals(1f, evaluated.scale, 0.0001f)
    }

    @Test
    fun similarity_smoothsShortCameraJolt() {
        val stabilization = ClipStabilizationV90(
            strength = 1f,
            smoothRadiusUs = 250_000L,
            crop = 0f,
            samples = listOf(
                StabilizationPathSampleV90(0L, 0f, 0f, 0f),
                StabilizationPathSampleV90(100_000L, .20f, 0f, 0f),
                StabilizationPathSampleV90(200_000L, 0f, 0f, 0f),
            ),
        )
        val evaluated = stabilization.evaluate(100_000L)
        assertTrue(evaluated.offsetX < -0.02f)
        assertTrue(evaluated.offsetX > -0.20f)
        assertEquals(0f, evaluated.rotationDegrees, 0.0001f)
    }

    @Test
    fun cameraLock_removesMeasuredTranslationAndRotation() {
        val stabilization = ClipStabilizationV90(
            mode = StabilizationModeV90.CAMERA_LOCK,
            strength = 1f,
            crop = 0f,
            samples = listOf(
                StabilizationPathSampleV90(0L, 0f, 0f, 0f),
                StabilizationPathSampleV90(100_000L, .24f, -.12f, 6f),
            ),
        )
        val evaluated = stabilization.evaluate(100_000L)
        assertEquals(-.24f, evaluated.offsetX, 0.0001f)
        assertEquals(.12f, evaluated.offsetY, 0.0001f)
        assertEquals(-6f, evaluated.rotationDegrees, 0.0001f)
    }

    @Test
    fun translationMode_doesNotCorrectRotation() {
        val stabilization = ClipStabilizationV90(
            mode = StabilizationModeV90.TRANSLATION,
            strength = 1f,
            crop = 0f,
            samples = listOf(
                StabilizationPathSampleV90(0L, 0f, 0f, 0f),
                StabilizationPathSampleV90(100_000L, .10f, .05f, 12f),
                StabilizationPathSampleV90(200_000L, 0f, 0f, 0f),
            ),
        )
        val evaluated = stabilization.evaluate(100_000L)
        assertEquals(0f, evaluated.rotationDegrees, 0.0001f)
    }

    @Test
    fun displayTransform_composesManualAndStabilizedMotion() {
        val clip = TimelineClip(
            uri = "content://video",
            label = "clip",
            timelineStartUs = 0L,
            sourceOutUs = 200_000L,
            transform = ClipTransform(
                positionX = AnimatedFloat(.10f),
                positionY = AnimatedFloat(.05f),
                scaleX = AnimatedFloat(1.20f),
                scaleY = AnimatedFloat(1.20f),
                rotationDegrees = AnimatedFloat(4f),
            ),
            stabilizationV90 = ClipStabilizationV90(
                mode = StabilizationModeV90.CAMERA_LOCK,
                strength = 1f,
                crop = 0f,
                samples = listOf(
                    StabilizationPathSampleV90(0L, 0f, 0f, 0f),
                    StabilizationPathSampleV90(100_000L, .08f, -.04f, 2f),
                ),
            ),
        )

        val evaluated = clip.evaluatedDisplayTransformV90(100_000L)
        assertEquals(.02f, evaluated.positionX, 0.0001f)
        assertEquals(.09f, evaluated.positionY, 0.0001f)
        assertEquals(1.20f, evaluated.scaleX, 0.0001f)
        assertEquals(2f, evaluated.rotationDegrees, 0.0001f)
    }
}
