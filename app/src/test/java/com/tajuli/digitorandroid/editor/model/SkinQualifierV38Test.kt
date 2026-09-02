package com.tajuli.digitorandroid.editor.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SkinQualifierV38Test {
    @Test
    fun skinBrightRoutesToGlobalQualifierNotSpatialBeauty() {
        val graph = ClipNodeGraph.default()
        val host = graph.nodes.first { it.kind == NodeKind.SERIAL }
        val clip = TimelineClip(
            uri = "file:///tmp/source.mp4",
            label = "source",
            timelineStartUs = 0L,
            sourceOutUs = 1_000_000L,
            nodeGraph = graph.copy(
                nodes = graph.nodes.map { node ->
                    if (node.id != host.id) node else node.copy(
                        effects = listOf(
                            NodeEffect(name = creatorFilterMarkerNameV36("skin_bright"), amount = .80f),
                        ),
                    )
                },
            ),
        )

        assertEquals(.80f, clip.skinQualifierStrengthV38(), .0001f)
        assertEquals(0f, clip.beautyStrengthsV28().skinBright, .0001f)
    }

    @Test
    fun portraitGlowKeepsSmoothSpatialButBrightnessQualifierGlobal() {
        val graph = ClipNodeGraph.default()
        val host = graph.nodes.first { it.kind == NodeKind.SERIAL }
        val clip = TimelineClip(
            uri = "file:///tmp/source.mp4",
            label = "source",
            timelineStartUs = 0L,
            sourceOutUs = 1_000_000L,
            nodeGraph = graph.copy(
                nodes = graph.nodes.map { node ->
                    if (node.id != host.id) node else node.copy(
                        effects = listOf(
                            NodeEffect(name = creatorFilterMarkerNameV36("portrait_glow"), amount = .75f),
                        ),
                    )
                },
            ),
        )

        val spatial = clip.beautyStrengthsV28()
        assertEquals(.75f, clip.skinQualifierStrengthV38(), .0001f)
        assertEquals(0f, spatial.skinBright, .0001f)
        assertEquals(.34f * .75f, spatial.skinSmooth, .0001f)
        assertEquals(.38f * .75f, spatial.pinkLip, .0001f)
    }

    @Test
    fun legacyDirectSkinBrightAlsoRoutesToQualifier() {
        val graph = ClipNodeGraph.default()
        val host = graph.nodes.first { it.kind == NodeKind.SERIAL }
        val clip = TimelineClip(
            uri = "file:///tmp/source.mp4",
            label = "source",
            timelineStartUs = 0L,
            sourceOutUs = 1_000_000L,
            nodeGraph = graph.copy(
                nodes = graph.nodes.map { node ->
                    if (node.id != host.id) node else node.copy(
                        effects = listOf(NodeEffect(name = BEAUTY_SKIN_BRIGHT_V28, amount = .55f)),
                    )
                },
            ),
        )

        assertEquals(.55f, clip.skinQualifierStrengthV38(), .0001f)
        assertEquals(0f, clip.beautyStrengthsV28().skinBright, .0001f)
    }
}
