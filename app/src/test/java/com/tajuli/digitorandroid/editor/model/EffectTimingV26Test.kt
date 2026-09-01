package com.tajuli.digitorandroid.editor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectTimingV26Test {
    private val clip = TimelineClip(
        uri = "content://clip",
        label = "clip",
        timelineStartUs = 5_000_000L,
        sourceInUs = 2_000_000L,
        sourceOutUs = 8_000_000L,
    )

    @Test
    fun legacyNullBoundsResolveToFullVisibleClip() {
        val effect = NodeEffect(name = "Glow")
        assertEquals(2_000_000L, effect.resolvedSourceStartUsV26(clip))
        assertEquals(8_000_000L, effect.resolvedSourceEndUsV26(clip))
        assertTrue(effect.activeAtSourceTimeV26(clip, 2_000_000L))
        assertTrue(effect.activeAtSourceTimeV26(clip, 7_999_999L))
    }

    @Test
    fun explicitBarOnlyRendersInsideItsSourceSpan() {
        val effect = NodeEffect(
            name = "RGB Split",
            sourceStartUsV26 = 3_000_000L,
            sourceEndUsV26 = 4_000_000L,
        )
        assertFalse(effect.activeAtSourceTimeV26(clip, 2_999_999L))
        assertTrue(effect.activeAtSourceTimeV26(clip, 3_500_000L))
        assertFalse(effect.activeAtSourceTimeV26(clip, 4_000_000L))

        val inside = resolveTimedCreatorEffectsV26(listOf(effect), clip, 3_500_000L)
        val outside = resolveTimedCreatorEffectsV26(listOf(effect), clip, 4_500_000L)
        assertTrue(inside.rgbSplit > 0f)
        assertTrue(outside.isIdentity)
    }

    @Test
    fun normalizationClampsBarToVisibleSourceBounds() {
        val effect = NodeEffect(
            name = "VHS",
            sourceStartUsV26 = 1_000_000L,
            sourceEndUsV26 = 9_000_000L,
        ).normalizedForClipV26(clip)
        assertEquals(2_000_000L, effect.sourceStartUsV26)
        assertEquals(8_000_000L, effect.sourceEndUsV26)
    }
}
