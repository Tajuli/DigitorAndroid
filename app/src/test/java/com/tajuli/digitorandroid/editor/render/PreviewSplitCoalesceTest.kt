package com.tajuli.digitorandroid.editor.render

import com.tajuli.digitorandroid.editor.model.NodeCorrections
import com.tajuli.digitorandroid.editor.model.TimelineClip
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewSplitCoalesceTest {
    private fun splitPair(): Pair<TimelineClip, TimelineClip> {
        val original = TimelineClip(
            uri = "video",
            label = "video",
            timelineStartUs = 1_000_000L,
            sourceInUs = 0L,
            sourceOutUs = 6_000_000L,
        )
        val splitTimelineUs = 4_000_000L
        val splitSourceUs = original.sourceInUs + splitTimelineUs - original.timelineStartUs
        val left = original.copy(sourceOutUs = splitSourceUs)
        val right = original.copy(
            id = "right",
            timelineStartUs = splitTimelineUs,
            sourceInUs = splitSourceUs,
        )
        return left to right
    }

    @Test fun untouchedSplitHalvesCoalesceForPreview() {
        val (left, right) = splitPair()
        val result = coalescePreviewClips(listOf(left, right))

        assertEquals(1, result.size)
        assertEquals(left.timelineStartUs, result.single().timelineStartUs)
        assertEquals(left.sourceInUs, result.single().sourceInUs)
        assertEquals(right.sourceOutUs, result.single().sourceOutUs)
        assertEquals(right.timelineEndUs, result.single().timelineEndUs)
    }

    @Test fun independentlyGradedSplitHalvesStaySeparate() {
        val (left, right) = splitPair()
        val selected = right.nodeGraph.selectedNodeId
        val changedGraph = right.nodeGraph.copy(nodes = right.nodeGraph.nodes.map { node ->
            if (node.id == selected) node.copy(corrections = NodeCorrections(exposure = 1f)) else node
        })
        val changedRight = right.copy(nodeGraph = changedGraph)

        val result = coalescePreviewClips(listOf(left, changedRight))
        assertEquals(2, result.size)
    }

    @Test fun sourceDiscontinuityDoesNotCoalesce() {
        val (left, right) = splitPair()
        val jumped = right.copy(sourceInUs = right.sourceInUs + 500_000L)
        val result = coalescePreviewClips(listOf(left, jumped))
        assertEquals(2, result.size)
    }
}
