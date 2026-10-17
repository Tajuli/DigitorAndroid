package com.tajuli.digitorandroid.editor.assets

import com.tajuli.digitorandroid.editor.model.CreatorEffectCatalogV25
import com.tajuli.digitorandroid.editor.model.CompositeTemplateCatalogV2
import com.tajuli.digitorandroid.editor.model.NodeEffect
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.creatorFilterPresetV36
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteAssetCatalogV1Test {
    // Keep the process-wide remote catalog empty between tests so built-in catalog
    // cardinality tests are order independent.
    @After
    fun resetRemotePresets() {
        val empty = """{"schemaVersion":1,"presets":[]}"""
        RemoteAssetCatalogV1.install("transitions", empty)
        RemoteAssetCatalogV1.install("effects", empty)
        RemoteAssetCatalogV1.install("filters", empty)
    }

    @Test
    fun remoteEffectsBecomeNativeRenderRecipes() {
        val json = """{"schemaVersion":1,"presets":[
          {"name":"Downloaded Halo V1","category":"Basic","vector":{"glow":0.6,"blur":0.2}}
        ]}"""
        assertTrue(RemoteAssetCatalogV1.install("effects", json))
        val effect = CreatorEffectCatalogV25.find("Downloaded Halo V1")
        assertNotNull(effect)
        assertEquals(0.6f, effect!!.vector.glow, 0.0001f)
        assertTrue(CreatorEffectCatalogV25.inCategory("Basic").any { it.name == "Downloaded Halo V1" })
    }

    @Test
    fun beatRippleEffectIsDownloadableAndFollowsTimeAndBeatStrength() {
        val testJson = """{"schemaVersion":1,"presets":[{
            "name":"Test","category":"Motion",
            "motion":{"type":"BEAT_CIRCLE_V1","blurSeconds":2,"blurStrength":0.95,
            "shakeStrength":0.18,"zoomStrength":0.018,"zoomPeriodSeconds":10,
            "rippleStrength":0.18,"rippleIntervalSeconds":4.8}
        }]}"""
        assertTrue(RemoteAssetCatalogV1.install("effects", testJson))
        assertNotNull(CreatorEffectCatalogV25.find("Test")?.remoteMotion)
        assertTrue(CreatorEffectCatalogV25.inCategory("Motion").any { it.name == "Test" })
        val clip = TimelineClip(
            uri = "content://video/1", label = "Test", timelineStartUs = 0L,
            sourceOutUs = 15_000_000L,
        )
        fun frame(timeUs: Long, beat: Float) = CompositeTemplateCatalogV2.atSourceTime(
            listOf(NodeEffect(name = "Test")), clip, timeUs, beat,
        )
        val opening = frame(0L, 0f)
        val middle = frame(1_000_000L, 0f)
        val clear = frame(2_000_000L, 0f)
        assertEquals(20, opening.style)
        assertTrue(opening.blurReveal > middle.blurReveal)
        assertEquals(0f, clear.blurReveal, .00001f)
        assertEquals(0f, clear.shakeStrength, 0f)
        assertTrue(frame(3_400_000L, 0f).waveStrength > 0f)
        assertTrue(frame(3_400_000L, 0f).zoomScale > 1f)
        assertTrue(frame(5_000_000L, 0f).waveStrength == 0f)
        assertEquals(.18f, frame(5_000_000L, 1f).shakeStrength, .0001f)
    }

    @Test
    fun untrustedRemoteMotionTypeAndExcessiveShakeAreRejected() {
        val unknown = """{"schemaVersion":1,"presets":[{
            "name":"Test","category":"Motion","motion":{"type":"SCRIPT","shakeStrength":0.2}
        }]}"""
        val excessive = """{"schemaVersion":1,"presets":[{
            "name":"Test","category":"Motion",
            "motion":{"type":"BEAT_CIRCLE_V1","shakeStrength":900}
        }]}"""
        assertFalse(RemoteAssetCatalogV1.isValid("effects", unknown))
        assertFalse(RemoteAssetCatalogV1.isValid("effects", excessive))
    }

    @Test
    fun remoteFiltersUseExistingLookPipeline() {
        val json = """{"schemaVersion":1,"presets":[
          {"id":"downloaded_look_v1","name":"Downloaded Look","description":"Soft warm",
           "group":"LOOKS","corrections":{"temperature":13,"exposure":0.15}}
        ]}"""
        assertTrue(RemoteAssetCatalogV1.install("filters", json))
        assertEquals(13f, creatorFilterPresetV36("downloaded_look_v1")!!.corrections.temperature, 0.001f)
    }

    @Test
    fun unsupportedCodeOrOutOfRangeValuesCannotReplaceLastValidCatalog() {
        val good = """{"schemaVersion":1,"presets":[
          {"name":"Safe Effect V1","category":"Basic","vector":{"grain":0.4}}
        ]}"""
        assertTrue(RemoteAssetCatalogV1.install("effects", good))
        val revision = RemoteAssetCatalogV1.state.value.revision
        val bad = """{"schemaVersion":1,"presets":[
          {"name":"Unsafe","category":"Basic","vector":{"glow":900}}
        ]}"""
        assertFalse(RemoteAssetCatalogV1.install("effects", bad))
        assertEquals(revision, RemoteAssetCatalogV1.state.value.revision)
        assertNotNull(CreatorEffectCatalogV25.find("Safe Effect V1"))
        assertNull(CreatorEffectCatalogV25.find("Unsafe"))
    }

    @Test
    fun unknownTransitionsAreRejected() {
        assertFalse(RemoteAssetCatalogV1.isValid("transitions",
            """{"schemaVersion":1,"presets":[
              {"id":"bad_shader","label":"New Shader","category":"EFFECT","engineStyle":"CUSTOM_SHADER"}
            ]}"""))
        assertTrue(RemoteAssetCatalogV1.isValid("transitions",
            """{"schemaVersion":1,"presets":[
              {"id":"good_zoom","label":"Good Zoom","category":"CAMERA","engineStyle":"ZOOM_IN"}
            ]}"""))
    }

    @Test
    fun oldSchemaIsRejected() {
        assertFalse(RemoteAssetCatalogV1.isValid("filters",
            """{"schemaVersion":3,"presets":[]}"""))
    }
}
