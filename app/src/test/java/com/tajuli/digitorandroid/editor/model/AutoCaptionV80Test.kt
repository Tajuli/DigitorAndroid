package com.tajuli.digitorandroid.editor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCaptionV80Test {
    @Test
    fun autoLanguageUsesWhisperAutoCode() {
        assertEquals("auto", AutoCaptionLanguageV80.AUTO.whisperCode)
        assertEquals("Auto Detect", AutoCaptionLanguageV80.AUTO.label)
    }

    @Test
    fun runtimeLanguageCanRepresentAnyWhisperCode() {
        val spanish = AutoCaptionLanguageV80("Spanish", "es")
        assertEquals("Spanish", spanish.label)
        assertEquals("es", spanish.whisperCode)
    }

    @Test
    fun bengaliManualHintUsesNativeWhisperCode() {
        assertEquals("bn", AutoCaptionLanguageV80.BENGALI.whisperCode)
        assertEquals("Bengali", AutoCaptionLanguageV80.BENGALI.label)
    }

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
