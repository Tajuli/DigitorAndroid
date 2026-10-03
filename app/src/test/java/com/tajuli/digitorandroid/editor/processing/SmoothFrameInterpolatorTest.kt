package com.tajuli.digitorandroid.editor.processing

import org.junit.Assert.assertEquals
import org.junit.Test

class SmoothFrameInterpolatorTest {
    @Test
    fun midpointBlendsArgbChannels() {
        val first = intArrayOf(0xFF000000.toInt())
        val second = intArrayOf(0xFFFFFFFF.toInt())
        val out = SmoothFrameInterpolator.blendArgb(first, second, .5f)
        val pixel = out[0]
        assertEquals(255, (pixel ushr 24) and 0xFF)
        assertEquals(127, (pixel ushr 16) and 0xFF)
        assertEquals(127, (pixel ushr 8) and 0xFF)
        assertEquals(127, pixel and 0xFF)
    }

    @Test
    fun endpointCopiesAreExact() {
        val first = intArrayOf(0xFF123456.toInt(), 0xFFABCDEF.toInt())
        val second = intArrayOf(0xFF654321.toInt(), 0xFFFEDCBA.toInt())
        assertEquals(first.toList(), SmoothFrameInterpolator.blendArgb(first, second, 0f).toList())
        assertEquals(second.toList(), SmoothFrameInterpolator.blendArgb(first, second, 1f).toList())
    }
}
