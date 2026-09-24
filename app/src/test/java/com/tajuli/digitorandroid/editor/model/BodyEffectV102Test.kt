package com.tajuli.digitorandroid.editor.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyEffectV102Test {
    private val clip = TimelineClip(
        uri = "content://body-test",
        label = "body",
        timelineStartUs = 0L,
        sourceInUs = 1_000_000L,
        sourceOutUs = 5_000_000L,
    )

    @Test
    fun bodyPresetsStayOutOfFullFrameV25Vector() {
        val effect = NodeEffect(name = "Neon Outline", amount = 1f)
        assertTrue(resolveCreatorEffectsV25(listOf(effect)).isIdentity)
        assertFalse(resolveBodyEffectsV102(listOf(effect)).isIdentity)
    }

    @Test
    fun bodyEffectUsesSameV26SourceTimeBarContract() {
        val effect = NodeEffect(
            name = "Body RGB Split",
            amount = .8f,
            sourceStartUsV26 = 2_000_000L,
            sourceEndUsV26 = 3_000_000L,
        )
        assertTrue(resolveTimedBodyEffectsV102(listOf(effect), clip, 2_500_000L).rgbSplit > 0f)
        assertTrue(resolveTimedBodyEffectsV102(listOf(effect), clip, 3_500_000L).isIdentity)
    }

    @Test
    fun catalogContainsCreatorFacingBodyFamily() {
        assertTrue(BodyEffectCatalogV102.names.contains("Body Glow"))
        assertTrue(BodyEffectCatalogV102.names.contains("Neon Outline"))
        assertTrue(BodyEffectCatalogV102.names.contains("Body Aura"))
        assertTrue(BodyEffectCatalogV102.names.contains("Body RGB Split"))
        assertTrue(BodyEffectCatalogV102.names.contains("Body Silhouette"))
        assertTrue(BodyEffectCatalogV102.names.contains("Body Pulse"))
    }
}
