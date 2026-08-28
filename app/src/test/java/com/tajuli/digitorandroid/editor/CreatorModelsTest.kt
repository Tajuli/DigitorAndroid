package com.tajuli.digitorandroid.editor

import com.tajuli.digitorandroid.editor.model.AudioMix
import com.tajuli.digitorandroid.editor.model.ClipTransition
import com.tajuli.digitorandroid.editor.model.TextOverlayClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CreatorModelsTest {
    @Test
    fun transitionClampsToHalfClipDuration() {
        val result = ClipTransition(8_000_000L, 9_000_000L).normalizedFor(10_000_000L)
        assertEquals(5_000_000L, result.fadeInUs)
        assertEquals(5_000_000L, result.fadeOutUs)
    }

    @Test
    fun audioMixNormalizesGainAndFades() {
        val result = AudioMix(1.8f, 8_000_000L, 9_000_000L).normalizedFor(4_000_000L)
        assertEquals(1f, result.volume, 0f)
        assertEquals(4_000_000L, result.fadeInUs)
        assertEquals(4_000_000L, result.fadeOutUs)
    }

    @Test
    fun projectDurationIncludesTextTail() {
        val text = TextOverlayClip(text = "Caption", timelineStartUs = 5_000_000L, timelineEndUs = 9_000_000L)
        assertEquals(9_000_000L, TimelineProject(textOverlays = listOf(text)).durationUs)
        assertFalse(text.activeAt(4_999_999L))
        assertTrue(text.activeAt(5_000_000L))
        assertFalse(text.activeAt(9_000_000L))
    }
}
