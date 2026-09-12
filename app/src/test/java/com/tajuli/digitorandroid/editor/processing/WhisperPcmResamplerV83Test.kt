package com.tajuli.digitorandroid.editor.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WhisperPcmResamplerV83Test {
    @Test
    fun downsample48kTo16kAveragesEachThreeSampleWindow() {
        val input = floatArrayOf(
            0f, 0.3f, 0.6f,
            0.9f, 0.6f, 0.3f,
            -0.3f, -0.6f, -0.9f,
        )

        val output = resampleMonoForWhisperV83(input, inputRate = 48_000, outputRate = 16_000)

        assertEquals(3, output.size)
        assertTrue(abs(output[0] - 0.3f) < 0.0001f)
        assertTrue(abs(output[1] - 0.6f) < 0.0001f)
        assertTrue(abs(output[2] + 0.6f) < 0.0001f)
    }

    @Test
    fun sameRateReturnsEquivalentSamples() {
        val input = floatArrayOf(-1f, -0.25f, 0.25f, 1f)
        val output = resampleMonoForWhisperV83(input, inputRate = 16_000, outputRate = 16_000)
        assertTrue(input.contentEquals(output))
    }
}
