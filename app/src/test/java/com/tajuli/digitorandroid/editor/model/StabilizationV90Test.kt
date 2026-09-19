package com.tajuli.digitorandroid.editor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

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
    fun requiredCoverScale_growsForTranslationAndRotation() {
        assertEquals(1f, requiredCoverScaleV92(0f, 0f, 0f), 0.0001f)
        assertTrue(requiredCoverScaleV92(.20f, 0f, 0f) > 1.19f)
        assertTrue(requiredCoverScaleV92(0f, 0f, 8f) > 1.12f)
        assertTrue(requiredCoverScaleV92(.15f, -.10f, 6f) > 1.20f)
    }

    @Test
    fun fullCrop_addsEnoughZoomForCameraLock() {
        val stabilization = ClipStabilizationV90(
            mode = StabilizationModeV90.CAMERA_LOCK,
            strength = 1f,
            crop = 1f,
            samples = listOf(
                StabilizationPathSampleV90(0L, 0f, 0f, 0f),
                StabilizationPathSampleV90(100_000L, .20f, -.10f, 6f),
            ),
        )
        val evaluated = stabilization.evaluate(100_000L)
        val required = requiredCoverScaleV92(
            evaluated.offsetX,
            evaluated.offsetY,
            evaluated.rotationDegrees,
        )
        assertTrue(evaluated.scale >= required)
        assertTrue(evaluated.scale > 1.20f)
    }

    @Test
    fun sceneCutSegments_doNotSmoothAcrossHardCut() {
        val stabilization = ClipStabilizationV90(
            strength = 1f,
            smoothRadiusUs = 500_000L,
            crop = 0f,
            samples = listOf(
                StabilizationPathSampleV90(0L, 0f, 0f, 0f, segmentV93 = 0),
                StabilizationPathSampleV90(100_000L, .40f, 0f, 0f, segmentV93 = 0),
                StabilizationPathSampleV90(120_000L, 0f, 0f, 0f, segmentV93 = 1),
                StabilizationPathSampleV90(220_000L, .02f, 0f, 0f, segmentV93 = 1),
            ),
        )

        val afterCut = stabilization.evaluate(120_000L)
        assertTrue(abs(afterCut.offsetX) < .05f)
    }

    @Test
    fun v94CorrectionFilter_reducesTrackingMicroJitter() {
        val samples = listOf(
            StabilizationPathSampleV90(0L, 0f, 0f, 0f, confidence = 1f),
            StabilizationPathSampleV90(50_000L, .08f, 0f, 0f, confidence = .9f),
            StabilizationPathSampleV90(100_000L, .01f, 0f, 0f, confidence = .9f),
            StabilizationPathSampleV90(150_000L, .09f, 0f, 0f, confidence = .9f),
            StabilizationPathSampleV90(200_000L, .02f, 0f, 0f, confidence = .9f),
            StabilizationPathSampleV90(250_000L, .10f, 0f, 0f, confidence = .9f),
            StabilizationPathSampleV90(300_000L, .03f, 0f, 0f, confidence = .9f),
        )
        val legacy = ClipStabilizationV90(
            strength = 1f,
            smoothRadiusUs = 300_000L,
            crop = 0f,
            samples = samples,
            analysisVersionV93 = 0,
        )
        val pro = legacy.copy(analysisVersionV93 = 93)

        val times = samples.map { it.sourceTimeUs }
        val legacyJitter = times.zipWithNext().sumOf { (a, b) ->
            abs(legacy.evaluate(b).offsetX - legacy.evaluate(a).offsetX).toDouble()
        }
        val proJitter = times.zipWithNext().sumOf { (a, b) ->
            abs(pro.evaluate(b).offsetX - pro.evaluate(a).offsetX).toDouble()
        }
        assertTrue("V94 correction should reduce micro-jitter", proJitter < legacyJitter)
    }

    @Test
    fun v94ZoomEnvelope_preZoomsBeforeLargeCorrection() {
        val stabilization = ClipStabilizationV90(
            mode = StabilizationModeV90.SIMILARITY,
            strength = 1f,
            smoothRadiusUs = 250_000L,
            crop = 1f,
            analysisVersionV93 = 93,
            samples = listOf(
                StabilizationPathSampleV90(0L, 0f, 0f, 0f),
                StabilizationPathSampleV90(300_000L, 0f, 0f, 0f),
                StabilizationPathSampleV90(600_000L, .30f, 0f, 0f),
                StabilizationPathSampleV90(900_000L, .30f, 0f, 0f),
                StabilizationPathSampleV90(1_200_000L, .30f, 0f, 0f),
            ),
        )

        val early = stabilization.evaluate(300_000L)
        val peak = stabilization.evaluate(600_000L)
        assertTrue("zoom envelope should begin before the peak correction", early.scale > 1.01f)
        assertTrue("peak must still be fully covered", peak.scale >= requiredCoverScaleV92(
            peak.offsetX,
            peak.offsetY,
            peak.rotationDegrees,
        ))
    }

    @Test
    fun v95IsolatedTrackingSpike_doesNotForceHugeZoom() {
        val samples = (0..24).map { index ->
            val timeUs = index * 50_000L
            val normal = index * .002f
            val pathX = if (index == 12) .95f else normal
            StabilizationPathSampleV90(
                sourceTimeUs = timeUs,
                pathX = pathX,
                pathY = 0f,
                rotationDegrees = if (index == 12) 9f else 0f,
                confidence = .9f,
            )
        }
        val stabilization = ClipStabilizationV90(
            mode = StabilizationModeV90.SIMILARITY,
            strength = 1f,
            smoothRadiusUs = 500_000L,
            crop = 1f,
            samples = samples,
            analysisVersionV93 = 93,
        )

        val before = stabilization.evaluate(550_000L)
        val spike = stabilization.evaluate(600_000L)
        val after = stabilization.evaluate(650_000L)

        assertTrue("isolated outlier must not create a 150%+ zoom spike", spike.scale < 1.30f)
        assertTrue("zoom should stay continuous entering the outlier", abs(spike.scale - before.scale) < .12f)
        assertTrue("zoom should stay continuous leaving the outlier", abs(spike.scale - after.scale) < .12f)
        assertTrue("render correction itself must be clipped", abs(spike.offsetX) < .20f)
    }

    @Test
    fun v95PersistentCorrection_isNotRejectedAsAnOutlier() {
        val samples = (0..24).map { index ->
            val timeUs = index * 50_000L
            val pathX = when {
                index < 8 -> 0f
                index < 18 -> .28f
                else -> .30f
            }
            StabilizationPathSampleV90(
                sourceTimeUs = timeUs,
                pathX = pathX,
                pathY = 0f,
                rotationDegrees = 0f,
                confidence = .95f,
            )
        }
        val stabilization = ClipStabilizationV90(
            mode = StabilizationModeV90.SIMILARITY,
            strength = 1f,
            smoothRadiusUs = 650_000L,
            crop = 1f,
            samples = samples,
            analysisVersionV93 = 93,
        )

        val onset = stabilization.evaluate(450_000L)
        assertTrue("sustained camera correction should still be applied", abs(onset.offsetX) > .025f)
        assertTrue("sustained correction should still request cover zoom", onset.scale > 1.02f)
    }

    @Test
    fun v95CameraLock_rejectsSingleTrackingFailureWithoutSofteningLock() {
        val samples = (0..20).map { index ->
            StabilizationPathSampleV90(
                sourceTimeUs = index * 50_000L,
                pathX = if (index == 10) .90f else .10f,
                pathY = 0f,
                rotationDegrees = if (index == 10) 12f else 1f,
                confidence = .95f,
            )
        }
        val stabilization = ClipStabilizationV90(
            mode = StabilizationModeV90.CAMERA_LOCK,
            strength = 1f,
            smoothRadiusUs = 500_000L,
            crop = 1f,
            samples = samples,
            analysisVersionV93 = 93,
        )

        val normal = stabilization.evaluate(450_000L)
        val badSample = stabilization.evaluate(500_000L)
        assertTrue("camera lock outlier guard must prevent a huge translation jump", abs(badSample.offsetX) < .20f)
        assertTrue("camera lock zoom must not explode on one bad sample", badSample.scale < 1.35f)
        assertTrue("hard-lock correction should remain close around the rejected sample", abs(badSample.offsetX - normal.offsetX) < .10f)
    }

    @Test
    fun v96Translation_turnsHandheldJitterIntoGimbalPath() {
        val samples = (0..90).map { index ->
            val trend = index * .0035f
            val jitter = when (index % 4) {
                0 -> .022f
                1 -> -.018f
                2 -> .014f
                else -> -.020f
            }
            StabilizationPathSampleV90(
                sourceTimeUs = index * 33_333L,
                pathX = trend + jitter,
                pathY = -trend * .35f - jitter * .55f,
                rotationDegrees = 0f,
                confidence = .95f,
            )
        }
        val stabilization = ClipStabilizationV90(
            mode = StabilizationModeV90.TRANSLATION,
            strength = 1f,
            smoothRadiusUs = 900_000L,
            crop = 0f,
            samples = samples,
            analysisVersionV93 = 96,
        )

        val rawX = samples.map { it.pathX }
        val stabilizedX = samples.map { sample ->
            sample.pathX + stabilization.evaluate(sample.sourceTimeUs).offsetX
        }
        fun accelerationEnergy(values: List<Float>): Double =
            values.windowed(3).sumOf { triple ->
                abs(triple[2] - 2f * triple[1] + triple[0]).toDouble()
            }

        val rawAcceleration = accelerationEnergy(rawX)
        val stabilizedAcceleration = accelerationEnergy(stabilizedX)
        assertTrue(
            "V96 Translation should strongly reduce handheld acceleration/jitter",
            stabilizedAcceleration < rawAcceleration * .20,
        )
    }

    @Test
    fun v96Translation_preservesIntentionalPanAsSmoothConstantVelocity() {
        val samples = (0..120).map { index ->
            val pan = index * .004f
            val jitter = if (index % 2 == 0) .012f else -.012f
            StabilizationPathSampleV90(
                sourceTimeUs = index * 33_333L,
                pathX = pan + jitter,
                pathY = 0f,
                rotationDegrees = 0f,
                confidence = .98f,
            )
        }
        val stabilization = ClipStabilizationV90(
            mode = StabilizationModeV90.TRANSLATION,
            strength = 1f,
            smoothRadiusUs = 900_000L,
            crop = 0f,
            samples = samples,
            analysisVersionV93 = 96,
        )

        val stabilized = samples.map { sample ->
            sample.pathX + stabilization.evaluate(sample.sourceTimeUs).offsetX
        }
        val rawTravel = samples.last().pathX - samples.first().pathX
        val stabilizedTravel = stabilized.last() - stabilized.first()

        assertTrue(
            "deliberate pan should be preserved instead of frozen",
            abs(stabilizedTravel) > abs(rawTravel) * .70f,
        )
        val velocities = stabilized.zipWithNext { a, b -> b - a }
        val velocityJitter = velocities.zipWithNext { a, b -> abs(b - a) }.average()
        assertTrue("gimbal pan velocity should be very smooth", velocityJitter < .0025)
    }

    @Test
    fun v97CameraLock_isExactInverseOfSimilarityPose() {
        val rotationDegrees = 12f
        val scale = 1.12f
        val pathX = .24f
        val pathY = -.16f
        val samples = (0..8).map { index ->
            StabilizationPathSampleV90(
                sourceTimeUs = index * 50_000L,
                pathX = pathX,
                pathY = pathY,
                rotationDegrees = rotationDegrees,
                logScale = kotlin.math.ln(scale),
                confidence = .99f,
            )
        }
        val stabilization = ClipStabilizationV90(
            mode = StabilizationModeV90.CAMERA_LOCK,
            strength = 1f,
            crop = 0f,
            samples = samples,
            analysisVersionV93 = 97,
        )

        val correction = stabilization.evaluate(200_000L)
        val cameraRadians = Math.toRadians(rotationDegrees.toDouble())
        val cameraCos = kotlin.math.cos(cameraRadians).toFloat()
        val cameraSin = kotlin.math.sin(cameraRadians).toFloat()
        // Evaluated rotation is render/NDC-space (+Y up). Convert it back to image-space
        // (+Y down) before composing with the analyzer's image-space camera pose.
        val correctionImageRotation = -correction.rotationDegrees
        val correctionRadians = Math.toRadians(correctionImageRotation.toDouble())
        val correctionCos = kotlin.math.cos(correctionRadians).toFloat()
        val correctionSin = kotlin.math.sin(correctionRadians).toFloat()

        // Compose image-space correction ∘ camera in the same center-space similarity convention.
        val composedScale = correction.scale * scale
        val composedRotation = correctionImageRotation + rotationDegrees
        val transformedPathX = correction.scale * (
            correctionCos * pathX - correctionSin * pathY
        ) + correction.offsetX
        val transformedPathY = correction.scale * (
            correctionSin * pathX + correctionCos * pathY
        ) + correction.offsetY

        assertEquals(1f, composedScale, .0005f)
        assertEquals(0f, composedRotation, .0005f)
        assertEquals(0f, transformedPathX, .001f)
        assertEquals(0f, transformedPathY, .001f)
    }

    @Test
    fun v98CameraLock_convertsImageRotationSignAtRenderBoundary() {
        val samples = (0..8).map { index ->
            StabilizationPathSampleV90(
                sourceTimeUs = index * 50_000L,
                pathX = 0f,
                pathY = 0f,
                rotationDegrees = 7f,
                logScale = 0f,
                confidence = .99f,
            )
        }
        val stabilization = ClipStabilizationV90(
            mode = StabilizationModeV90.CAMERA_LOCK,
            strength = 1f,
            crop = 0f,
            samples = samples,
            analysisVersionV93 = 97,
        )

        val correction = stabilization.evaluate(200_000L)
        // +7° in image coordinates is clockwise on screen. Its inverse is -7° image-space,
        // which is +7° in Media3/NDC render coordinates because Y is inverted.
        assertEquals(7f, correction.rotationDegrees, .001f)
    }

    @Test
    fun v97CameraLock_rejectsIsolatedPoseSpike() {
        val samples = (0..16).map { index ->
            StabilizationPathSampleV90(
                sourceTimeUs = index * 50_000L,
                pathX = if (index == 8) .85f else .12f,
                pathY = if (index == 8) -.65f else -.06f,
                rotationDegrees = if (index == 8) 18f else 1.5f,
                logScale = if (index == 8) kotlin.math.ln(1.25f) else kotlin.math.ln(1.01f),
                confidence = .95f,
            )
        }
        val stabilization = ClipStabilizationV90(
            mode = StabilizationModeV90.CAMERA_LOCK,
            strength = 1f,
            crop = 1f,
            samples = samples,
            analysisVersionV93 = 97,
        )

        val normal = stabilization.evaluate(350_000L)
        val spike = stabilization.evaluate(400_000L)
        assertTrue(abs(spike.offsetX - normal.offsetX) < .12f)
        assertTrue(abs(spike.offsetY - normal.offsetY) < .12f)
        assertTrue(abs(spike.rotationDegrees - normal.rotationDegrees) < 2f)
        assertTrue(spike.scale < 1.40f)
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
