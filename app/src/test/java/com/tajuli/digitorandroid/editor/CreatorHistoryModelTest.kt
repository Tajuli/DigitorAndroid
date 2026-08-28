package com.tajuli.digitorandroid.editor

import com.tajuli.digitorandroid.editor.model.TextOverlayClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import org.junit.Assert.assertEquals
import org.junit.Test

class CreatorHistoryModelTest {
    @Test
    fun immutableProjectCopiesKeepEarlierSnapshotIndependent() {
        val before = TimelineProject()
        val after = before.copy(textOverlays = listOf(TextOverlayClip(text = "A", timelineStartUs = 0L, timelineEndUs = 1_000_000L)))
        assertEquals(0, before.textOverlays.size)
        assertEquals(1, after.textOverlays.size)
    }
}
