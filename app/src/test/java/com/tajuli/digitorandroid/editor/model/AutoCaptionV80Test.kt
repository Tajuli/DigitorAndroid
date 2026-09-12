package com.tajuli.digitorandroid.editor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCaptionV80Test {
    @Test
    fun normalizerTrimsTextAndRemovesLaneOverlap() {
        val result = normalizeAutoCaptionSegmentsV80(
            listOf(
                AutoCaptionSegmentV80(1_000_000L, 2_000_000L, "  Hello   world  "),
                AutoCaptionSegmentV80(1_800_000L, 3_000_000L, "Next line"),
            ),
        )

        assertEquals(2, result.size)
        assertEquals("Hello world", result[0].text)
        assertEquals(2_000_000L, result[1].startUs)
        assertEquals(3_000_000L, result[1].endUs)
    }

    @Test
    fun normalizerDropsTinySegmentsAfterOverlapTrim() {
        val result = normalizeAutoCaptionSegmentsV80(
            listOf(
                AutoCaptionSegmentV80(0L, 1_000_000L, "First"),
                AutoCaptionSegmentV80(900_000L, 1_050_000L, "Too short"),
            ),
        )

        assertEquals(1, result.size)
        assertTrue(result.single().text == "First")
    }
}
