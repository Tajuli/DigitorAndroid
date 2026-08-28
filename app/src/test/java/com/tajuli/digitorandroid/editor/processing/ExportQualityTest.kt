package com.tajuli.digitorandroid.editor.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportQualityTest {
    @Test
    fun qualityBitratesAreOrdered() {
        val low = ExportQuality.LOW.videoBitrate(1920, 1080, 30)
        val medium = ExportQuality.MEDIUM.videoBitrate(1920, 1080, 30)
        val high = ExportQuality.HIGH.videoBitrate(1920, 1080, 30)

        assertTrue(high > medium)
        assertTrue(medium > low)
    }

    @Test
    fun bitrateIsClampedToSafeBounds() {
        assertEquals(
            ExportQuality.MIN_VIDEO_BITRATE,
            ExportQuality.LOW.videoBitrate(2, 2, 1),
        )
        assertEquals(
            ExportQuality.MAX_VIDEO_BITRATE,
            ExportQuality.HIGH.videoBitrate(7680, 4320, 120),
        )
    }
}
