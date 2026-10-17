package com.tajuli.digitorandroid.editor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositeTemplateV2Test {
    private val video = TimelineClip(
        uri = "content://test/example.mp4",
        label = "clip",
        timelineStartUs = 0L,
        sourceInUs = 0L,
        sourceOutUs = 6_000_000L,
    )
    private val photo = video.copy(visualMediaV21 = TimelineVisualMediaV21.IMAGE)

    @Test fun allTemplatesHaveMultipleActualEffectsAndThreeOrderedPhases() {
        val all = CompositeTemplateCatalogV2.presets
        assertEquals(14, all.size)
        assertEquals(all.size, all.map { it.name.lowercase() }.distinct().size)
        assertEquals(7, CompositeTemplateCatalogV2.forCategory(CompositeTemplateCatalogV2.VIDEO).size)
        assertEquals(7, CompositeTemplateCatalogV2.forCategory(CompositeTemplateCatalogV2.IMAGE).size)
        all.forEach { template ->
            assertTrue(template.name, template.stages.size >= 3)
            assertEquals(template.name, 1f, template.stages.last().end, .00001f)
            assertTrue(template.name, template.stages.zipWithNext().all { (a, b) -> a.end < b.end })
            template.stages.forEach { stage ->
                assertTrue("${template.name} missing combined effects", stage.layers.size >= 3)
                assertTrue("${template.name} repeats same effect", stage.layers.map { it.effectName }.distinct().size >= 3)
                assertTrue(stage.motionStyle != 0 && stage.motionAmount > 0f)
                assertTrue(stage.layers.all { CreatorEffectCatalogV25.find(it.effectName) != null })
            }
        }
    }

    @Test fun oneTimelineBarDrivesSeveralDistinctEffectsInDifferentPhases() {
        val effect = NodeEffect(name = "Fashion Velocity", amount = .8f,
            sourceStartUsV26 = 1_000_000L, sourceEndUsV26 = 5_000_000L)
        assertFalse(CompositeTemplateCatalogV2.atSourceTime(listOf(effect), video, 600_000L).active)
        val opening = CompositeTemplateCatalogV2.atSourceTime(listOf(effect), video, 1_200_000L)
        val middle = CompositeTemplateCatalogV2.atSourceTime(listOf(effect), video, 3_000_000L)
        val finish = CompositeTemplateCatalogV2.atSourceTime(listOf(effect), video, 4_800_000L)
        assertTrue(opening.active && middle.active && finish.active)
        assertEquals(3, opening.layers.size)
        assertNotEquals(opening.style, middle.style)
        assertNotEquals(middle.style, finish.style)
        assertNotEquals(opening.layers.map { it.effectName }, middle.layers.map { it.effectName })
        assertFalse(CompositeTemplateCatalogV2.atSourceTime(listOf(effect), video, 5_300_000L).active)
        val firstVector = resolveTimedCreatorEffectsV26(listOf(effect), video, 1_200_000L)
        val middleVector = resolveTimedCreatorEffectsV26(listOf(effect), video, 3_000_000L)
        assertFalse(firstVector.isIdentity)
        assertFalse(middleVector.isIdentity)
        assertTrue(firstVector.zoomBlur > 0f && firstVector.rgbSplit > 0f)
        assertTrue(middleVector.sharpen > 0f && middleVector.glow > 0f)
    }

    @Test fun templateAmountScalesEveryEffectAndTurnsFullyOffAtZero() {
        val full = NodeEffect(name = "Fashion Velocity", amount = 1f)
        val half = full.copy(amount = .5f)
        val fullV = resolveTimedCreatorEffectsV26(listOf(full), video, 500_000L)
        val halfV = resolveTimedCreatorEffectsV26(listOf(half), video, 500_000L)
        assertTrue(halfV.rgbSplit > 0f && halfV.rgbSplit < fullV.rgbSplit)
        val motion = CompositeTemplateCatalogV2.atSourceTime(listOf(half), video, 500_000L)
        assertTrue(motion.amount < CompositeTemplateCatalogV2.atSourceTime(listOf(full), video, 500_000L).amount)
        assertFalse(CompositeTemplateCatalogV2.atSourceTime(listOf(full.copy(amount = 0f)), video, 500_000L).active)
        assertTrue(resolveTimedCreatorEffectsV26(listOf(full.copy(amount = 0f)), video, 500_000L).isIdentity)
    }

    @Test fun photoRecipesAreNotAppliedToVideoAndViceVersa() {
        val imageEffect = NodeEffect(name = "Cinematic Photo Story")
        val videoEffect = NodeEffect(name = "Beat Drop Cinema")
        assertFalse(CompositeTemplateCatalogV2.atSourceTime(listOf(imageEffect), video, 500_000L).active)
        assertTrue(CompositeTemplateCatalogV2.atSourceTime(listOf(imageEffect), photo, 500_000L).active)
        assertFalse(CompositeTemplateCatalogV2.atSourceTime(listOf(videoEffect), photo, 500_000L).active)
        assertTrue(CompositeTemplateCatalogV2.atSourceTime(listOf(videoEffect), video, 500_000L).active)
    }

    @Test fun onlyTheLastActiveTemplateOnNodeIsUsed() {
        val first = NodeEffect(name = "Fashion Velocity")
        val second = NodeEffect(name = "Retro Music Video")
        val chosen = CompositeTemplateCatalogV2.atSourceTime(listOf(first, second), video, 400_000L)
        val expected = CompositeTemplateCatalogV2.atSourceTime(listOf(second), video, 400_000L)
        assertEquals(expected.style, chosen.style)
        assertEquals(expected.layers, chosen.layers)
        assertEquals(first.name, CompositeTemplateCatalogV2.atSourceTime(listOf(first, second.copy(enabled = false)), video, 400_000L)
            .let { if (it.style == CompositeTemplateCatalogV2.atSourceTime(listOf(first), video, 400_000L).style) first.name else "unknown" })
    }
}
