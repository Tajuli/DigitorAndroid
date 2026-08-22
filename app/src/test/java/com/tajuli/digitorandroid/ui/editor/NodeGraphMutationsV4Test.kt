package com.tajuli.digitorandroid.ui.editor

import com.tajuli.digitorandroid.editor.model.ClipNodeGraph
import com.tajuli.digitorandroid.editor.model.ColorNode
import com.tajuli.digitorandroid.editor.model.NodeEdge
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.NodePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeGraphMutationsV4Test {
    private fun node(id: String, kind: NodeKind, label: String = id) = ColorNode(
        id = id,
        kind = kind,
        label = label,
        position = NodePosition(0f, 0f),
    )

    @Test
    fun deletingSerialSplicesPreviousAndNext() {
        val graph = ClipNodeGraph(
            nodes = listOf(
                node("in", NodeKind.IMPORT),
                node("a", NodeKind.SERIAL, "01"),
                node("b", NodeKind.SERIAL, "02"),
                node("out", NodeKind.OUTPUT),
            ),
            edges = listOf(
                NodeEdge("in", "a"),
                NodeEdge("a", "b"),
                NodeEdge("b", "out"),
            ),
            selectedNodeId = "b",
        )

        val result = graph.deleteEditableNodeV4("b")

        assertFalse(result.nodes.any { it.id == "b" })
        assertTrue(result.edges.contains(NodeEdge("a", "out")))
        assertFalse(result.edges.any { it.fromId == "b" || it.toId == "b" })
        assertEquals("a", result.selectedNodeId)
    }

    @Test
    fun deletingParallelFromPairCollapsesMixAndKeepsSerialPath() {
        val graph = ClipNodeGraph(
            nodes = listOf(
                node("in", NodeKind.IMPORT),
                node("a", NodeKind.SERIAL, "01"),
                node("p2", NodeKind.PARALLEL, "P2"),
                node("mix", NodeKind.MIX, "Mix"),
                node("out", NodeKind.OUTPUT),
            ),
            edges = listOf(
                NodeEdge("in", "a"),
                NodeEdge("in", "p2"),
                NodeEdge("a", "mix"),
                NodeEdge("p2", "mix"),
                NodeEdge("mix", "out"),
            ),
            selectedNodeId = "p2",
        )

        val result = graph.deleteEditableNodeV4("p2")

        assertFalse(result.nodes.any { it.id == "p2" || it.id == "mix" })
        assertTrue(result.edges.contains(NodeEdge("in", "a")))
        assertTrue(result.edges.contains(NodeEdge("a", "out")))
        assertFalse(result.edges.any { it.fromId == "mix" || it.toId == "mix" })
        assertEquals(NodeKind.SERIAL, result.nodes.first { it.id == "a" }.kind)
        assertEquals("a", result.selectedNodeId)
    }

    @Test
    fun deletingOneOfThreeParallelBranchesKeepsMix() {
        val graph = ClipNodeGraph(
            nodes = listOf(
                node("in", NodeKind.IMPORT),
                node("a", NodeKind.SERIAL, "01"),
                node("p2", NodeKind.PARALLEL, "P2"),
                node("p3", NodeKind.PARALLEL, "P3"),
                node("mix", NodeKind.MIX, "Mix"),
                node("out", NodeKind.OUTPUT),
            ),
            edges = listOf(
                NodeEdge("in", "a"),
                NodeEdge("in", "p2"),
                NodeEdge("in", "p3"),
                NodeEdge("a", "mix"),
                NodeEdge("p2", "mix"),
                NodeEdge("p3", "mix"),
                NodeEdge("mix", "out"),
            ),
            selectedNodeId = "p3",
        )

        val result = graph.deleteEditableNodeV4("p3")

        assertTrue(result.nodes.any { it.id == "mix" })
        assertTrue(result.edges.contains(NodeEdge("a", "mix")))
        assertTrue(result.edges.contains(NodeEdge("p2", "mix")))
        assertFalse(result.edges.any { it.fromId == "p3" || it.toId == "p3" })
    }
}
