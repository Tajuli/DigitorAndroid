package com.tajuli.digitorandroid.editor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CreatorEffectV25Test {
    @Test
    fun catalogHasFiftyUniqueCreatorEffects() {
        val presets = CreatorEffectCatalogV25.presets
        assertEquals(50, presets.size)
        assertEquals(50, presets.map { it.name.lowercase() }.toSet().size)
        assertEquals(listOf("Basic", "Glitch", "Retro", "Lens", "Motion"), CreatorEffectCatalogV25.categories)
        CreatorEffectCatalogV25.categories.forEach { category ->
            assertEquals(10, CreatorEffectCatalogV25.inCategory(category).size)
        }
    }

    @Test
    fun resolverScalesPresetByEffectAmount() {
        val full = resolveCreatorEffectsV25(listOf(NodeEffect(name = "RGB Split", amount = 1f)))
        val half = resolveCreatorEffectsV25(listOf(NodeEffect(name = "RGB Split", amount = .5f)))
        assertTrue(full.rgbSplit > 0f)
        assertTrue(half.rgbSplit > 0f)
        assertTrue(half.rgbSplit < full.rgbSplit)
    }

    @Test
    fun disabledAndUnknownEffectsStayIdentity() {
        val disabled = resolveCreatorEffectsV25(listOf(NodeEffect(name = "Glow", amount = 1f, enabled = false)))
        val unknown = resolveCreatorEffectsV25(listOf(NodeEffect(name = "Unknown FX", amount = 1f)))
        assertTrue(disabled.isIdentity)
        assertTrue(unknown.isIdentity)
    }

    @Test
    fun representativeFamiliesProduceDistinctVectors() {
        val vhs = CreatorEffectCatalogV25.find("VHS")!!.vector
        val fisheye = CreatorEffectCatalogV25.find("Fisheye")!!.vector
        val zoomBlur = CreatorEffectCatalogV25.find("Zoom Blur")!!.vector
        val film = CreatorEffectCatalogV25.find("Old Film")!!.vector
        assertFalse(vhs == fisheye)
        assertFalse(fisheye == zoomBlur)
        assertFalse(zoomBlur == film)
        assertTrue(vhs.scanlines > 0f && vhs.grain > 0f)
        assertTrue(fisheye.lens > 0f)
        assertTrue(zoomBlur.zoomBlur > 0f)
        assertTrue(film.vignette > 0f && film.grain > 0f)
    }
}
