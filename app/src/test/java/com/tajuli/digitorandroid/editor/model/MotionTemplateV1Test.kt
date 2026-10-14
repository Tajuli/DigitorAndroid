package com.tajuli.digitorandroid.editor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTemplateV1Test {
    private val clip = TimelineClip(
        uri = "content://test/media.mp4",
        label = "Test",
        timelineStartUs = 0L,
        sourceInUs = 0L,
        sourceOutUs = 4_000_000L,
    )

    @Test fun templateGroupsHaveUniqueNamesAndMediaSpecificStyles() {
        val all = MotionTemplateCatalogV1.presets
        assertEquals(14, all.size)
        assertEquals(14, all.map { it.name.lowercase() }.distinct().size)
        assertEquals(7, MotionTemplateCatalogV1.forCategory(MotionTemplateCatalogV1.VIDEO).size)
        assertEquals(7, MotionTemplateCatalogV1.forCategory(MotionTemplateCatalogV1.IMAGE).size)
        assertTrue(all.filter { it.category == MotionTemplateCatalogV1.VIDEO }.all { it.style in 1..7 })
        assertTrue(all.filter { it.category == MotionTemplateCatalogV1.IMAGE }.all { it.style in 11..17 })
        assertTrue(all.all { CreatorEffectCatalogV25.find(it.name) != null })
    }

    @Test fun motionAnimatesOnlyInsideItsTimelineBar() {
        val effect = NodeEffect(
            name = "Ken Burns",
            amount = .75f,
            sourceStartUsV26 = 500_000L,
            sourceEndUsV26 = 2_500_000L,
        )
        assertFalse(MotionTemplateCatalogV1.atSourceTime(listOf(effect), clip, 100_000L).active)
        val frame = MotionTemplateCatalogV1.atSourceTime(listOf(effect), clip, 1_500_000L)
        assertTrue(frame.active)
        assertEquals(11, frame.style)
        assertEquals(.75f, frame.amount, .0001f)
        assertEquals(.5f, frame.progress, .0001f)
        assertEquals(1f, frame.elapsedSeconds, .0001f)
        assertFalse(MotionTemplateCatalogV1.atSourceTime(listOf(effect), clip, 3_000_000L).active)
    }

    @Test fun disabledTemplatesDoNotRenderAndLastActiveTemplateWins() {
        val first = NodeEffect(name = "Beat Zoom")
        val last = NodeEffect(name = "Whip Pan", amount = .5f)
        assertEquals(2, MotionTemplateCatalogV1.atSourceTime(listOf(first, last), clip, 1_000_000L).style)
        val disabled = last.copy(enabled = false)
        assertEquals(1, MotionTemplateCatalogV1.atSourceTime(listOf(first, disabled), clip, 1_000_000L).style)
        assertFalse(MotionTemplateCatalogV1.atSourceTime(listOf(first.copy(amount = 0f)), clip, 1_000_000L).active)
    }
}
