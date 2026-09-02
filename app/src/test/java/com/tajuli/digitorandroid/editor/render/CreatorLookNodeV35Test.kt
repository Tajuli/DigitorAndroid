package com.tajuli.digitorandroid.editor.render

import com.tajuli.digitorandroid.editor.model.AdvancedColorGrade
import com.tajuli.digitorandroid.editor.model.BEAUTY_SKIN_SMOOTH_V28
import com.tajuli.digitorandroid.editor.model.ColorWheelValue
import com.tajuli.digitorandroid.editor.model.ColorNode
import com.tajuli.digitorandroid.editor.model.LogWheels
import com.tajuli.digitorandroid.editor.model.NodeCorrections
import com.tajuli.digitorandroid.editor.model.NodeEffect
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.NodePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CreatorLookNodeV35Test {
    @Test
    fun lookNodeIsManagedButBeautyFilterIsNot() {
        val look = ColorNode(
            kind = NodeKind.SERIAL,
            label = "FilterV28 · natural_portrait · Natural Portrait",
            position = NodePosition(100f, 0f),
            corrections = NodeCorrections(exposure = .08f),
        )
        val beauty = ColorNode(
            kind = NodeKind.SERIAL,
            label = "FilterV28 · skin_smooth · Skin Smooth",
            position = NodePosition(200f, 0f),
            effects = listOf(NodeEffect(name = BEAUTY_SKIN_SMOOTH_V28, amount = .5f)),
        )

        assertTrue(CreatorLookNodeV35.isManagedLookNode(look))
        assertFalse(CreatorLookNodeV35.isManagedLookNode(beauty))
        assertEquals(listOf(look.id), CreatorLookNodeV35.managedLookNodes(listOf(beauty, look)).map { it.id })
    }

    @Test
    fun lutNeutralisationRemovesOnlyV35RecipeFields() {
        val primary = AdvancedColorGrade().primary.copy(
            lift = ColorWheelValue(red = .02f),
        )
        val node = ColorNode(
            kind = NodeKind.SERIAL,
            label = "FilterV28 · moody_cinema · Moody Cinema",
            position = NodePosition(100f, 0f),
            corrections = NodeCorrections(
                exposure = -.16f,
                contrast = 21f,
                saturation = -12f,
                highlights = -23f,
            ),
            advancedColor = AdvancedColorGrade(
                primary = primary,
                log = LogWheels(
                    shadows = ColorWheelValue(red = -.035f, blue = .06f),
                    highlights = ColorWheelValue(red = .03f, blue = -.022f),
                ),
            ),
        )

        val neutral = CreatorLookNodeV35.neutralizeRecipeForLut(node)

        assertEquals(NodeCorrections(), neutral.corrections)
        assertEquals(LogWheels(), neutral.advancedColor.log)
        assertEquals(primary, neutral.advancedColor.primary)
        assertEquals(node.advancedColor.curves, neutral.advancedColor.curves)
        assertEquals(node.advancedColor.qualifier, neutral.advancedColor.qualifier)
        assertTrue(CreatorLookNodeV35.lookStrength(node) > .9f)
    }

    @Test
    fun ordinaryColorNodeIsReturnedUnchanged() {
        val ordinary = ColorNode(
            kind = NodeKind.SERIAL,
            label = "Grade 1",
            position = NodePosition(50f, 20f),
            corrections = NodeCorrections(contrast = 12f),
        )

        assertSame(ordinary, CreatorLookNodeV35.neutralizeRecipeForLut(ordinary))
    }
}
