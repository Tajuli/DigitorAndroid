package com.tajuli.digitorandroid.editor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CreatorFilterNodeBindingV41Test {
    @Test
    fun selectedSerialNodeIsFilterHost() {
        val graph = ClipNodeGraph.default()
        val first = graph.selectedNode()!!
        val second = ColorNode(
            id = "node-02",
            kind = NodeKind.SERIAL,
            label = "02",
            position = NodePosition(260f, 88f),
        )
        val clip = TimelineClip(
            uri = "content://clip",
            label = "clip",
            timelineStartUs = 0L,
            sourceOutUs = 1_000_000L,
            nodeGraph = graph.copy(
                nodes = graph.nodes + second,
                selectedNodeId = second.id,
            ),
        )

        assertEquals(second.id, clip.selectedCreatorFilterHostV41()?.id)
        assertEquals(first.id, graph.selectedNode()?.id)
    }

    @Test
    fun importOrOutputSelectionDoesNotRedirectFilterToAnotherNode() {
        val graph = ClipNodeGraph.default()
        val importNode = graph.nodes.first { it.kind == NodeKind.IMPORT }
        val clip = TimelineClip(
            uri = "content://clip",
            label = "clip",
            timelineStartUs = 0L,
            sourceOutUs = 1_000_000L,
            nodeGraph = graph.copy(selectedNodeId = importNode.id),
        )

        assertNull(clip.selectedCreatorFilterHostV41())
    }

    @Test
    fun appliedStateIsLocalToOneNode() {
        val marker = NodeEffect(name = creatorFilterMarkerNameV36("moody_cinema"), amount = .7f)
        val nodeA = ColorNode(
            id = "a",
            kind = NodeKind.SERIAL,
            label = "01",
            position = NodePosition(0f, 0f),
            effects = listOf(marker),
        )
        val nodeB = nodeA.copy(id = "b", label = "02", effects = emptyList())

        assertEquals(.7f, nodeA.appliedCreatorFiltersV41()["moody_cinema"]!!, .0001f)
        assertEquals(emptyMap<String, Float>(), nodeB.appliedCreatorFiltersV41())
    }
}
