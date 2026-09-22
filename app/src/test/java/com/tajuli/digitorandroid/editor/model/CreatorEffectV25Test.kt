package com.tajuli.digitorandroid.editor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CreatorEffectV25Test {
    @Test
    fun catalogHasSeventyTwoUniqueCreatorEffects() {
        val presets = CreatorEffectCatalogV25.presets
        assertEquals(72, presets.size)
        assertEquals(72, presets.map { it.name.lowercase() }.toSet().size)
        assertEquals(
            listOf("Trending", "Basic", "Glitch", "Retro", "Lens", "Motion", "Body", "Clone", "Glow"),
            CreatorEffectCatalogV25.categories,
        )
        assertEquals(6, CreatorEffectCatalogV25.inCategory("Trending").size)
        assertEquals(11, CreatorEffectCatalogV25.inCategory("Basic").size)
        listOf("Glitch", "Retro", "Lens", "Motion").forEach { category ->
            assertEquals(10, CreatorEffectCatalogV25.inCategory(category).size)
        }
        assertEquals(5, CreatorEffectCatalogV25.inCategory("Body").size)
        assertEquals(5, CreatorEffectCatalogV25.inCategory("Clone").size)
        assertEquals(5, CreatorEffectCatalogV25.inCategory("Glow").size)
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
    fun videoDenoiseScalesAndParticipatesInIdentity() {
        val preset = CreatorEffectCatalogV25.find("Video Denoise")
        assertTrue(preset != null)
        assertEquals("Basic", preset!!.category)
        assertTrue(preset.vector.denoise > 0f)

        val off = resolveCreatorEffectsV25(listOf(NodeEffect(name = "Video Denoise", amount = 0f)))
        val half = resolveCreatorEffectsV25(listOf(NodeEffect(name = "Video Denoise", amount = .5f)))
        val full = resolveCreatorEffectsV25(listOf(NodeEffect(name = "Video Denoise", amount = 1f)))
        assertTrue(off.isIdentity)
        assertFalse(half.isIdentity)
        assertTrue(half.denoise > 0f)
        assertTrue(half.denoise < full.denoise)
    }

    @Test
    fun newTrendingCloneAndGlowPrimitivesScaleWithAmount() {
        val crossFull = resolveCreatorEffectsV25(listOf(NodeEffect(name = "Cross Shift", amount = 1f)))
        val crossHalf = resolveCreatorEffectsV25(listOf(NodeEffect(name = "Cross Shift", amount = .5f)))
        val clone = resolveCreatorEffectsV25(listOf(NodeEffect(name = "X Clone", amount = 1f)))
        val smear = resolveCreatorEffectsV25(listOf(NodeEffect(name = "Luminous Smear", amount = 1f)))
        val electric = resolveCreatorEffectsV25(listOf(NodeEffect(name = "Electric Current", amount = 1f)))
        val outline = resolveCreatorEffectsV25(listOf(NodeEffect(name = "Neon Outline", amount = 1f)))
        val fireEyes = resolveCreatorEffectsV25(listOf(NodeEffect(name = "Fire Eyes", amount = 1f)))
        val current = resolveCreatorEffectsV25(listOf(NodeEffect(name = "Current Passing", amount = 1f)))
        val aura = resolveCreatorEffectsV25(listOf(NodeEffect(name = "Neon Body", amount = 1f)))

        assertTrue(crossFull.crossShift > crossHalf.crossShift && crossHalf.crossShift > 0f)
        assertTrue(clone.clone > 0f)
        assertTrue(smear.smear > 0f)
        assertTrue(electric.electric > 0f && electric.edgeGlow > 0f)
        assertTrue(outline.edgeGlow > 0f)
        assertTrue(fireEyes.fireEyes > 0f)
        assertTrue(current.bodyElectric > 0f)
        assertTrue(aura.bodyAura > 0f)
        assertFalse(crossFull.isIdentity)
        assertFalse(clone.isIdentity)
        assertFalse(smear.isIdentity)
        assertFalse(electric.isIdentity)
        assertFalse(fireEyes.isIdentity)
        assertFalse(current.isIdentity)
        assertFalse(aura.isIdentity)
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
