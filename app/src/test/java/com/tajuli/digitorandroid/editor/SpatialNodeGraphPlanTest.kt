package com.tajuli.digitorandroid.editor

import com.tajuli.digitorandroid.editor.model.ClipNodeGraph
import com.tajuli.digitorandroid.editor.model.ColorNode
import com.tajuli.digitorandroid.editor.model.NodeEdge
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.NodePosition
import com.tajuli.digitorandroid.editor.model.SpatialNodeGraphPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialNodeGraphPlanTest {
    private fun node(id: String, kind: NodeKind, x: Float, y: Float) = ColorNode(
        id = id,
        kind = kind,
        label = id,
        position = NodePosition(x, y),
    )

    @Test
    fun parallelMixerUsesNearestCommonBaseAndIndependentBranchSlots() {
        val graph = ClipNodeGraph(
            nodes = listOf(
                node("in", NodeKind.IMPORT, 0f, 0f),
                node("a", NodeKind.SERIAL, 100f, 0f),
                node("p2", NodeKind.PARALLEL, 100f, 80f),
                node("mix", NodeKind.MIX, 220f, 40f),
                node("out", NodeKind.OUTPUT, 340f, 40f),
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

        val plan = SpatialNodeGraphPlan.compile(graph)
        val byId = plan.operations.associateBy { it.node.id }
        val mix = byId.getValue("mix")

        assertEquals(byId.getValue("in").slot, mix.mixerBaseSlot)
        assertEquals(
            setOf(byId.getValue("a").slot, byId.getValue("p2").slot),
            mix.mixerInputSlots.toSet(),
        )
        assertEquals(byId.getValue("out").slot, plan.outputSlot)
    }

    @Test
    fun nestedSerialInsideParallelBranchKeepsOriginalForkAsMixerBase() {
        val graph = ClipNodeGraph(
            nodes = listOf(
                node("in", NodeKind.IMPORT, 0f, 0f),
                node("a", NodeKind.SERIAL, 100f, 0f),
                node("p2", NodeKind.PARALLEL, 100f, 80f),
                node("p2b", NodeKind.SERIAL, 190f, 80f),
                node("mix", NodeKind.MIX, 300f, 40f),
                node("out", NodeKind.OUTPUT, 420f, 40f),
            ),
            edges = listOf(
                NodeEdge("in", "a"),
                NodeEdge("in", "p2"),
                NodeEdge("p2", "p2b"),
                NodeEdge("a", "mix"),
                NodeEdge("p2b", "mix"),
                NodeEdge("mix", "out"),
            ),
            selectedNodeId = "p2b",
        )

        val plan = SpatialNodeGraphPlan.compile(graph)
        val byId = plan.operations.associateBy { it.node.id }
        val mix = byId.getValue("mix")

        assertEquals(byId.getValue("in").slot, mix.mixerBaseSlot)
        assertTrue(mix.mixerInputSlots.contains(byId.getValue("a").slot))
        assertTrue(mix.mixerInputSlots.contains(byId.getValue("p2b").slot))
    }
}
