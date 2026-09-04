package com.tajuli.digitorandroid.editor.processing

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SpatialFlowTemporalV45Test {
    @Test
    fun estimatesKnownTranslation() {
        val width = 84
        val height = 60
        val previous = texturedFrame(width, height)
        val currentValues = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                // Current pixel came from x-3, y+2 in the previous frame.
                val px = (x - 3).coerceIn(0, width - 1)
                val py = (y + 2).coerceIn(0, height - 1)
                currentValues[y * width + x] = previous.values[py * width + px]
            }
        }
        val current = LumaFrameV45(width, height, currentValues)
        val flow = SpatialMotionFieldV45.estimate(current, previous)
        val sample = flow.sample(42f, 30f)
        assertTrue("dx=${sample.dx}", abs(sample.dx - (-3f)) < 1.6f)
        assertTrue("dy=${sample.dy}", abs(sample.dy - 2f) < 1.6f)
        assertTrue("confidence=${sample.confidence}", sample.confidence > .18f)
    }

    @Test
    fun keepsDifferentLocalMotionsSeparate() {
        val width = 96
        val height = 60
        val previous = texturedFrame(width, height)
        val currentValues = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val sourceX = if (x < width / 2) x - 3 else x + 3
                val px = sourceX.coerceIn(0, width - 1)
                currentValues[y * width + x] = previous.values[y * width + px]
            }
        }
        val current = LumaFrameV45(width, height, currentValues)
        val flow = SpatialMotionFieldV45.estimate(current, previous)
        val left = flow.sample(24f, 30f)
        val right = flow.sample(72f, 30f)
        assertTrue("left dx=${left.dx}", left.dx < -1.2f)
        assertTrue("right dx=${right.dx}", right.dx > 1.2f)
    }

    private fun texturedFrame(width: Int, height: Int): LumaFrameV45 {
        val values = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                values[y * width + x] = (
                    x * 17 + y * 23 + (x * y * 7) % 61 + ((x xor y) * 11)
                ) and 0xff
            }
        }
        return LumaFrameV45(width, height, values)
    }
}
