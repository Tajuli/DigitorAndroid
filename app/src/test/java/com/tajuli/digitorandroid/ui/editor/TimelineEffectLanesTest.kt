package com.tajuli.digitorandroid.ui.editor

import com.tajuli.digitorandroid.editor.model.*
import org.junit.Assert.*
import org.junit.Test

class TimelineEffectLanesTest {
    @Test fun overlappingEffectsHaveDistinctLanesAndStableSelectionAfterDelete() {
        val effects = listOf(NodeEffect(name = "Fire"), NodeEffect(name = "Glow"), NodeEffect(name = "Blur"))
        val graph = ClipNodeGraph.default().let { graph ->
            graph.copy(nodes = graph.nodes.map { node ->
                if (node.kind == NodeKind.SERIAL) node.copy(effects = effects + NodeEffect(name = "__qualifier_clean_black")) else node
            })
        }
        val clip = TimelineClip(uri = "test.mp4", label = "test", timelineStartUs = 5_000_000, sourceOutUs = 10_000_000, nodeGraph = graph)
        val project = TimelineProject(tracks = listOf(
            TimelineTrack(name = "V1", kind = TrackKind.VIDEO, clips = listOf(clip)),
            TimelineTrack(name = "A1", kind = TrackKind.AUDIO, clips = listOf(clip.copy(id = "audio"))),
        ))
        val lanes = project.timelineEffectLanes()
        assertEquals(3, lanes.size)
        assertEquals(3, lanes.map { it.selection }.toSet().size)
        assertTrue(lanes.all { it.clip.timelineStartUs == 5_000_000L })
        val edited = clip.copy(nodeGraph = graph.copy(nodes = graph.nodes.map { node ->
            node.copy(effects = node.effects.filterNot { it.id == effects.first().id })
        }))
        val after = project.copy(tracks = listOf(project.tracks.first().copy(clips = listOf(edited)))).timelineEffectLanes()
        assertEquals(lanes.drop(1).map { it.selection }, after.map { it.selection })
    }
}
