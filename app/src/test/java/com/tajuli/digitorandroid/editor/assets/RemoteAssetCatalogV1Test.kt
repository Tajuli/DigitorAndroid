package com.tajuli.digitorandroid.editor.assets

import com.tajuli.digitorandroid.editor.model.CreatorEffectCatalogV25
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
