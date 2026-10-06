package com.tajuli.digitorandroid.editor.processing

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import kotlin.math.abs

class MotionFrameInterpolatorTest {
    private val width = 96
    private val height = 64
    private fun image(offset: Int) = IntArray(width * height) { i ->
        val x = (i % width - offset).coerceIn(0, width - 1)
        val y = i / width
        val value = ((x * 17 + y * 31 + x * y * 3) % 190) + 32
        (0xff000000L or (value.toLong() * 0x010101)).toInt()
    }

    @Test fun translatedTextureIsWarpedToIntermediatePosition() {
        val a = image(0); val b = image(8); val expected = image(4)
        val actual = MotionFrameInterpolator().prepare(a, b, width, height).render(.5f, IntArray(a.size))
        var motionError = 0L; var blendError = 0L
        for (y in 12 until height - 12) for (x in 16 until width - 16) {
            val i = y * width + x
            motionError += abs((actual[i] and 255) - (expected[i] and 255))
            blendError += abs(((a[i] and 255) + (b[i] and 255)) / 2 - (expected[i] and 255))
        }
        assertTrue("Motion error $motionError, blend error $blendError", motionError < blendError / 2)
    }

    @Test fun endpointsAndSceneCutsUseSourceFrames() {
        val a = IntArray(width * height) { 0xff000000.toInt() }
        val b = IntArray(a.size) { -1 }
        val pair = MotionFrameInterpolator().prepare(a, b, width, height)
        assertArrayEquals(a, pair.render(0f, IntArray(a.size)))
        assertArrayEquals(b, pair.render(1f, IntArray(a.size)))
        assertArrayEquals(a, pair.render(.25f, IntArray(a.size)))
        assertArrayEquals(b, pair.render(.75f, IntArray(a.size)))
    }

    @Test(expected = CancellationException::class)
    fun cancelledEstimationStops() {
        MotionFrameInterpolator().prepare(image(0), image(8), width, height) { true }
    }

    @Test(expected = CancellationException::class)
    fun cancelledSynthesisStops() {
        MotionFrameInterpolator().prepare(image(0), image(8), width, height)
            .render(.5f, IntArray(width * height)) { true }
    }
}
