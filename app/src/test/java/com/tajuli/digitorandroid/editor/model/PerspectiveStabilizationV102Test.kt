package com.tajuli.digitorandroid.editor.model

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerspectiveStabilizationV102Test {

    @Test
    fun homography_mapsAllFourCornersExactly() {
        val src = PerspectiveQuadV102.IDENTITY.asPoints()
        val dst = floatArrayOf(
            -.90f, .92f,
            .96f, .84f,
            .88f, -.94f,
            -1.02f, -.86f,
        )
        val matrix = solveHomographyV102(src, dst)
        assertNotNull(matrix)

        for (index in 0 until 4) {
            val mapped = map(matrix!!, src[index * 2], src[index * 2 + 1])
            assertEquals(dst[index * 2], mapped.first, .001f)
            assertEquals(dst[index * 2 + 1], mapped.second, .001f)
        }
    }

    @Test
    fun perspectiveCameraLock_mapsFixedReferencePoseBackToTripod() {
        val raw = PerspectiveQuadV102(
            topLeftX = -.84f,
            topLeftY = .90f,
            topRightX = 1.05f,
            topRightY = .78f,
            bottomRightX = .92f,
            bottomRightY = -.98f,
            bottomLeftX = -.98f,
            bottomLeftY = -.84f,
        )
        val samples = listOf(
            sample(0L, raw),
            sample(100_000L, raw),
            sample(200_000L, raw),
        )
        val stabilization = ClipStabilizationV90(
            mode = StabilizationModeV90.PERSPECTIVE,
            cameraLockV102 = true,
            zoomEnabledV102 = false,
            croppingRatioV102 = .5f,
            strength = 1f,
            samples = samples,
            analysisVersionV93 = 102,
        )

        val evaluated = stabilization.evaluatePerspectiveV102(100_000L)
        assertNotNull(evaluated)
        val source = raw.asPoints()
        val target = PerspectiveQuadV102.IDENTITY.asPoints()
        for (index in 0 until 4) {
            val mapped = map(
                evaluated!!.matrixValues,
                source[index * 2],
                source[index * 2 + 1],
            )
            assertEquals(target[index * 2], mapped.first, .002f)
            assertEquals(target[index * 2 + 1], mapped.second, .002f)
        }
        assertEquals(1f, evaluated.autoZoom, .0001f)
    }

    @Test
    fun croppingRatioOne_disablesPerspectiveStabilization() {
        val raw = PerspectiveQuadV102(
            topLeftX = -.88f,
            topLeftY = .94f,
            topRightX = 1.02f,
            topRightY = .86f,
            bottomRightX = .95f,
            bottomRightY = -.94f,
            bottomLeftX = -.96f,
            bottomLeftY = -.87f,
        )
        val samples = listOf(
            sample(0L, PerspectiveQuadV102.IDENTITY),
            sample(100_000L, raw),
            sample(200_000L, PerspectiveQuadV102.IDENTITY),
        )
        val stabilization = ClipStabilizationV90(
            mode = StabilizationModeV90.PERSPECTIVE,
            cameraLockV102 = false,
            zoomEnabledV102 = false,
            croppingRatioV102 = 1f,
            strength = 1f,
            samples = samples,
            analysisVersionV93 = 102,
        )

        val matrix = stabilization.evaluatePerspectiveV102(100_000L)!!.matrixValues
        assertIdentity(matrix, .0001f)
    }

    @Test
    fun legacyCameraLockMode_migratesToSimilarityPlusCameraLockOption() {
        val normalized = ClipStabilizationV90(
            mode = StabilizationModeV90.CAMERA_LOCK,
        ).normalized()

        assertEquals(StabilizationModeV90.SIMILARITY, normalized.mode)
        assertTrue(normalized.cameraLockV102)
    }

    @Test
    fun zoomOff_neverAddsPerspectiveAutoZoom() {
        val raw = PerspectiveQuadV102(
            topLeftX = -.72f,
            topLeftY = .82f,
            topRightX = .92f,
            topRightY = .78f,
            bottomRightX = .86f,
            bottomRightY = -.86f,
            bottomLeftX = -.82f,
            bottomLeftY = -.76f,
        )
        val stabilization = ClipStabilizationV90(
            mode = StabilizationModeV90.PERSPECTIVE,
            cameraLockV102 = true,
            zoomEnabledV102 = false,
            samples = listOf(sample(0L, raw), sample(100_000L, raw)),
            analysisVersionV93 = 102,
            cameraLockPerspectiveCoverScaleV102 = 2f,
        )

        assertEquals(1f, stabilization.evaluatePerspectiveV102(50_000L)!!.autoZoom, .0001f)
    }

    private fun sample(
        timeUs: Long,
        quad: PerspectiveQuadV102,
    ) = StabilizationPathSampleV90(
        sourceTimeUs = timeUs,
        pathX = 0f,
        pathY = 0f,
        rotationDegrees = 0f,
        confidence = 1f,
        rotationScaleConfidenceV99 = 1f,
        perspectivePathV102 = quad,
        cameraLockPerspectivePathV102 = quad,
    )

    private fun map(
        matrix: FloatArray,
        x: Float,
        y: Float,
    ): Pair<Float, Float> {
        val w = matrix[6] * x + matrix[7] * y + matrix[8]
        return (
            (matrix[0] * x + matrix[1] * y + matrix[2]) / w
            ) to (
            (matrix[3] * x + matrix[4] * y + matrix[5]) / w
            )
    }

    private fun assertIdentity(matrix: FloatArray, tolerance: Float) {
        val identity = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f,
        )
        assertEquals(9, matrix.size)
        for (index in identity.indices) {
            assertTrue(abs(matrix[index] - identity[index]) <= tolerance)
        }
    }
}
