package com.tajuli.digitorandroid.editor.assets

import com.tajuli.digitorandroid.editor.model.CreatorEffectCatalogV25
import com.tajuli.digitorandroid.editor.model.CreatorFilterGroupV36
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteAssetFoldersV2Test {
    @Test fun allEffectAndFilterTabsMapToASingleGitHubFolder() {
        assertEquals(CreatorEffectCatalogV25.categories, RemoteAssetFoldersV2.effects)
        assertEquals(
            CreatorFilterGroupV36.entries.map { if (it.name == "LOOKS") "Looks" else "Beauty" },
            RemoteAssetFoldersV2.filters,
        )
        assertEquals(listOf("Basic", "Camera", "Effect"), RemoteAssetFoldersV2.transitions)
        assertEquals(18, listOf("effects", "filters", "transitions")
            .sumOf { RemoteAssetFoldersV2.folders(it).size })
    }

    @Test fun discoveryIsMetadataOnlyAndIgnoresUnknownFoldersAndPlaceholderFiles() {
        val oldRevision = RemoteAssetCatalogV1.state.value.revision
        val json = """{"sha":"head","truncated":false,"tree":[
            {"path":"effects/Motion/.gitkeep","type":"blob","sha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"},
            {"path":"effects/Motion/presets.json","type":"blob","sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
            {"path":"filters/Looks/presets.json","type":"blob","sha":"cccccccccccccccccccccccccccccccccccccccc"},
            {"path":"effects/Unknown/presets.json","type":"blob","sha":"dddddddddddddddddddddddddddddddddddddddd"},
            {"path":"effects/presets.json","type":"blob","sha":"eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"},
            {"path":"transitions/Effect/.gitkeep","type":"blob","sha":"ffffffffffffffffffffffffffffffffffffffff"}
        ]}"""
        val found = RemoteAssetFoldersV2.discover(json)
        assertEquals(setOf("effects/Motion", "filters/Looks"), found.keys)
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", found["effects/Motion"])
        assertFalse(found.containsKey("transitions/Effect"))
        // Discovering a new catalog NEVER installs any effect before explicit confirmation.
        assertEquals(oldRevision, RemoteAssetCatalogV1.state.value.revision)
    }

    @Test fun refusesTruncatedTreesAndPathTraversal() {
        val truncated = """{"truncated":true,"tree":[]}"""
        assertTrue(runCatching { RemoteAssetFoldersV2.discover(truncated) }.isFailure)
        assertTrue(runCatching { RemoteAssetFoldersV2.key("effects", "../Motion") }.isFailure)
        assertTrue(runCatching { RemoteAssetFoldersV2.key("other", "Motion") }.isFailure)
    }
}
