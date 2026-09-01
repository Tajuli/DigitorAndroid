package com.tajuli.digitorandroid.ui.editor

import com.tajuli.digitorandroid.editor.model.TransitionStyleV22
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapCutTransitionCatalogV24Test {
    @Test
    fun catalogContainsExactlyRequestedFiftyTransitions() {
        val expected = listOf(
            "Pull In", "Pull Out", "Zoom In", "Zoom Out", "Zoom Lens", "Camera Zoom",
            "Swipe Left", "Swipe Right", "Swipe Up", "Swipe Down",
            "Slide Left", "Slide Right", "Slide Up", "Slide Down", "Spin", "Rotate",
            "Blur", "Motion Blur", "Vertical Blur", "Horizontal Blur", "Blur Zoom",
            "Fade", "Fade In", "Fade Out", "Black Fade", "White Fade", "Mix", "Dissolve", "Cross Fade",
            "Flash", "White Flash", "Camera Flash", "Flicker", "Light Leak", "Lens Flare", "Film Burn",
            "Glitch", "RGB Glitch", "Digital Glitch", "Distortion", "Shake", "Camera Shake", "Stretch", "Warp",
            "3D Cube", "Page Turn", "Mask Transition", "Split", "Velocity", "Beat Sync",
        )

        assertEquals(50, CAPCUT_TRANSITION_PRESETS_V24.size)
        assertEquals(expected, CAPCUT_TRANSITION_PRESETS_V24.map { it.label })
        assertEquals(50, CAPCUT_TRANSITION_PRESETS_V24.map { it.id }.toSet().size)
        assertEquals(50, CAPCUT_TRANSITION_PRESETS_V24.map { it.label }.toSet().size)
        assertTrue(CAPCUT_TRANSITION_PRESETS_V24.none { it.engineStyle == TransitionStyleV22.NONE })
    }

    @Test
    fun everyCategoryIsPopulated() {
        CapCutTransitionCategoryV24.entries.forEach { category ->
            assertTrue(category.label, presetsForCategoryV24(category).isNotEmpty())
        }
    }
}
