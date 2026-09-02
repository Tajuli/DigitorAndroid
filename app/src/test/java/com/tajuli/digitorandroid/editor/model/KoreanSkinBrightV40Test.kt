package com.tajuli.digitorandroid.editor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class KoreanSkinBrightV40Test {
    @Test
    fun koreanSkinBright_isBeautyPresetUsingGlobalSkinQualifier() {
        val preset = creatorFilterPresetV36(KOREAN_SKIN_BRIGHT_FILTER_ID_V40)
        assertNotNull(preset)
        assertEquals("Korean Skin Bright", preset!!.name)
        assertEquals(CreatorFilterGroupV36.BEAUTY, preset.group)
        assertEquals(1f, preset.beautyWeights[BEAUTY_SKIN_BRIGHT_V28] ?: 0f, .0001f)
        assertEquals(.80f, preset.defaultIntensity, .0001f)

        val graph = ClipNodeGraph.default()
        val host = graph.nodes.first { it.kind == NodeKind.SERIAL }
        val markedGraph = graph.copy(
            nodes = graph.nodes.map { node ->
                if (node.id == host.id) {
                    node.copy(
                        effects = node.effects + NodeEffect(
                            name = creatorFilterMarkerNameV36(KOREAN_SKIN_BRIGHT_FILTER_ID_V40),
                            amount = .70f,
                        ),
                    )
                } else {
                    node
                }
            },
        )
        val clip = TimelineClip(
            uri = "file:///tmp/korean.mp4",
            label = "korean",
            timelineStartUs = 0L,
            sourceOutUs = 1_000_000L,
            nodeGraph = markedGraph,
        )

        assertEquals(.70f, clip.appliedCreatorFiltersV36()[KOREAN_SKIN_BRIGHT_FILTER_ID_V40] ?: 0f, .0001f)
        assertEquals(.70f, clip.skinQualifierStrengthV38(), .0001f)
        // Brightness remains global-color-qualified; it never returns to the spatial face shader.
        assertEquals(0f, clip.beautyStrengthsV28().skinBright, .0001f)
    }

    @Test
    fun skinSmooth_strengthRemainsAvailableForStrongerRefinement() {
        val graph = ClipNodeGraph.default()
        val host = graph.nodes.first { it.kind == NodeKind.SERIAL }
        val markedGraph = graph.copy(
            nodes = graph.nodes.map { node ->
                if (node.id == host.id) {
                    node.copy(
                        effects = node.effects + NodeEffect(
                            name = creatorFilterMarkerNameV36("skin_smooth"),
                            amount = .85f,
                        ),
                    )
                } else {
                    node
                }
            },
        )
        val clip = TimelineClip(
            uri = "file:///tmp/smooth.mp4",
            label = "smooth",
            timelineStartUs = 0L,
            sourceOutUs = 1_000_000L,
            nodeGraph = markedGraph,
        )

        assertEquals(.85f, clip.beautyStrengthsV28().skinSmooth, .0001f)
    }
}
