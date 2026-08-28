package com.tajuli.digitorandroid.editor

import com.tajuli.digitorandroid.editor.model.TextOverlayClip
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextOverlayTimingTest {
    @Test
    fun textUsesHalfOpenTimelineRange() {
        val text = TextOverlayClip(text = "x", timelineStartUs = 100L, timelineEndUs = 200L)
        assertFalse(text.activeAt(99L))
        assertTrue(text.activeAt(100L))
        assertTrue(text.activeAt(199L))
        assertFalse(text.activeAt(200L))
    }
}
