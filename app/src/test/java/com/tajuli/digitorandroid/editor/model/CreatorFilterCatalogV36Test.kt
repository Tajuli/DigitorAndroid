package com.tajuli.digitorandroid.editor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CreatorFilterCatalogV36Test {
    @Test
    fun markerFilterUsesExistingNodeWithoutTopologyMetadata() {
        val graph = ClipNodeGraph.default()
        val host = graph.nodes.first { it.kind == NodeKind.SERIAL }
        val markedGraph = graph.copy(
            nodes = graph.nodes.map { node ->
                if (node.id == host.id) node.copy(
                    effects = node.effects + NodeEffect(
                        name = creatorFilterMarkerNameV36("skin_bright"),
                        amount = .8f,
                    ),
                ) else node
            },
        )
        val clip = TimelineClip(
            uri = "file:///tmp/source.mp4",
            label = "source",
            timelineStartUs = 0L,
            sourceOutUs = 1_000_000L,
            nodeGraph = markedGraph,
        )

        assertEquals(.8f, clip.appliedCreatorFiltersV36()["skin_bright"] ?: 0f, .0001f)
        // V39 keeps Skin Bright out of the spatial face-mask contract. The amount is consumed by the
        // global color qualifier that now handles brightness/paleness/texture response together.
        assertEquals(0f, clip.beautyStrengthsV28().skinBright, .0001f)
        assertEquals(.8f, clip.skinQualifierStrengthV38(), .0001f)
    }

    @Test
    fun combinedNaturalPortraitMatchesCatalogAtFullIntensity() {
        val graph = ClipNodeGraph.default()
        val host = graph.nodes.first { it.kind == NodeKind.SERIAL }
        val markedGraph = graph.copy(
            nodes = graph.nodes.map { node ->
                if (node.id == host.id) node.copy(
                    effects = node.effects + NodeEffect(
                        name = creatorFilterMarkerNameV36("natural_portrait"),
                        amount = 1f,
                    ),
                ) else node
            },
        )
        val clip = TimelineClip(
            uri = "file:///tmp/source.mp4",
            label = "source",
            timelineStartUs = 0L,
            sourceOutUs = 1_000_000L,
            nodeGraph = markedGraph,
        )
        val preset = creatorFilterPresetV36("natural_portrait")!!
        val combined = clip.combinedCreatorLookV36()

        assertEquals(preset.corrections.exposure, combined.corrections.exposure, .0001f)
        assertEquals(preset.corrections.highlights, combined.corrections.highlights, .0001f)
        assertEquals(1f, combined.strength, .0001f)
        assertTrue(combined.corrections.exposure > .10f)
    }
}
