package com.tajuli.digitorandroid.editor

import com.tajuli.digitorandroid.editor.model.ClipNodeGraph
import com.tajuli.digitorandroid.editor.model.ColorGraphEvaluator
import com.tajuli.digitorandroid.editor.model.ColorNode
import com.tajuli.digitorandroid.editor.model.NodeCorrections
import com.tajuli.digitorandroid.editor.model.NodeEdge
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.NodePosition
import com.tajuli.digitorandroid.editor.model.QualifiedColorMath
import org.junit.Assert.assertEquals
import org.junit.Test

class ColorGraphEvaluatorTest {
    private fun node(
        id: String,
        kind: NodeKind,
        x: Float,
        y: Float = 0f,
        corrections: NodeCorrections = NodeCorrections(),
    ) = ColorNode(
        id = id,
        kind = kind,
        label = id,
        position = NodePosition(x, y),
        corrections = corrections,
    )

    private val transform: (ColorNode, Float, Float, Float) -> FloatArray = { n, r, g, b ->
        QualifiedColorMath.applyNode(n, r, g, b)
    }

    private fun apply(graph: ClipNodeGraph, source: FloatArray): FloatArray =
        ColorGraphEvaluator.compile(graph).apply(source[0], source[1], source[2], transform)

    private fun assertRgb(expected: FloatArray, actual: FloatArray, tolerance: Float = .0001f) {
        assertEquals(expected[0], actual[0], tolerance)
        assertEquals(expected[1], actual[1], tolerance)
        assertEquals(expected[2], actual[2], tolerance)
    }

    private fun additiveMix(base: FloatArray, vararg branches: FloatArray): FloatArray {
        var r = base[0]
        var g = base[1]
        var b = base[2]
        branches.forEach { branch ->
            r += branch[0] - base[0]
            g += branch[1] - base[1]
            b += branch[2] - base[2]
        }
        return floatArrayOf(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
    }

    @Test
    fun serialNodesStillProcessInConnectionOrder() {
        val input = node("in", NodeKind.IMPORT, 0f)
        val first = node("a", NodeKind.SERIAL, 100f, corrections = NodeCorrections(exposure = .35f))
        val second = node("b", NodeKind.SERIAL, 200f, corrections = NodeCorrections(contrast = 25f, temperature = 20f))
        val output = node("out", NodeKind.OUTPUT, 300f)
        val graph = ClipNodeGraph(
            nodes = listOf(input, first, second, output),
            edges = listOf(NodeEdge("in", "a"), NodeEdge("a", "b"), NodeEdge("b", "out")),
            selectedNodeId = "b",
        )
        val source = floatArrayOf(.18f, .27f, .39f)
        val afterFirst = QualifiedColorMath.applyNode(first, source[0], source[1], source[2])
        val expected = QualifiedColorMath.applyNode(second, afterFirst[0], afterFirst[1], afterFirst[2])

        assertRgb(expected, apply(graph, source))
    }

    @Test
    fun untouchedParallelBranchDoesNotDiluteExistingGrade() {
        val input = node("in", NodeKind.IMPORT, 0f)
        val graded = node("a", NodeKind.SERIAL, 100f, corrections = NodeCorrections(exposure = .45f, saturation = 15f))
        val neutral = node("p2", NodeKind.PARALLEL, 100f, 80f)
        val mix = node("mix", NodeKind.MIX, 220f, 40f)
        val output = node("out", NodeKind.OUTPUT, 320f)
        val graph = ClipNodeGraph(
            nodes = listOf(input, graded, neutral, mix, output),
            edges = listOf(
                NodeEdge("in", "a"), NodeEdge("in", "p2"),
                NodeEdge("a", "mix"), NodeEdge("p2", "mix"), NodeEdge("mix", "out"),
            ),
            selectedNodeId = "p2",
        )
        val source = floatArrayOf(.16f, .29f, .37f)
        val expected = QualifiedColorMath.applyNode(graded, source[0], source[1], source[2])

        assertRgb(expected, apply(graph, source))
    }

    @Test
    fun parallelBranchesUseSameSourceAndCombineEqualPriorityDeltas() {
        val input = node("in", NodeKind.IMPORT, 0f)
        val warm = node("warm", NodeKind.SERIAL, 100f, corrections = NodeCorrections(temperature = 45f))
        val contrast = node("contrast", NodeKind.PARALLEL, 100f, 80f, corrections = NodeCorrections(contrast = 18f))
        val mix = node("mix", NodeKind.MIX, 220f, 40f)
        val output = node("out", NodeKind.OUTPUT, 320f)
        val source = floatArrayOf(.20f, .31f, .43f)
        val warmOut = QualifiedColorMath.applyNode(warm, source[0], source[1], source[2])
        val contrastOut = QualifiedColorMath.applyNode(contrast, source[0], source[1], source[2])
        val expected = additiveMix(source, warmOut, contrastOut)

        val graphA = ClipNodeGraph(
            nodes = listOf(input, warm, contrast, mix, output),
            edges = listOf(
                NodeEdge("in", "warm"), NodeEdge("in", "contrast"),
                NodeEdge("warm", "mix"), NodeEdge("contrast", "mix"), NodeEdge("mix", "out"),
            ),
            selectedNodeId = "contrast",
        )
        val graphB = graphA.copy(
            edges = listOf(
                NodeEdge("in", "contrast"), NodeEdge("in", "warm"),
                NodeEdge("contrast", "mix"), NodeEdge("warm", "mix"), NodeEdge("mix", "out"),
            ),
        )

        assertRgb(expected, apply(graphA, source))
        assertRgb(expected, apply(graphB, source))
    }

    @Test
    fun parallelMixerUsesNearestCommonCorrectedUpstreamInput() {
        val input = node("in", NodeKind.IMPORT, 0f)
        val pre = node("pre", NodeKind.SERIAL, 80f, corrections = NodeCorrections(exposure = .20f))
        val branchA = node("a", NodeKind.SERIAL, 180f, corrections = NodeCorrections(temperature = 35f))
        val branchB = node("b", NodeKind.PARALLEL, 180f, 80f, corrections = NodeCorrections(saturation = 20f))
        val mix = node("mix", NodeKind.MIX, 300f, 40f)
        val output = node("out", NodeKind.OUTPUT, 400f)
        val graph = ClipNodeGraph(
            nodes = listOf(input, pre, branchA, branchB, mix, output),
            edges = listOf(
                NodeEdge("in", "pre"), NodeEdge("pre", "a"), NodeEdge("pre", "b"),
                NodeEdge("a", "mix"), NodeEdge("b", "mix"), NodeEdge("mix", "out"),
            ),
            selectedNodeId = "b",
        )
        val source = floatArrayOf(.14f, .24f, .34f)
        val base = QualifiedColorMath.applyNode(pre, source[0], source[1], source[2])
        val aOut = QualifiedColorMath.applyNode(branchA, base[0], base[1], base[2])
        val bOut = QualifiedColorMath.applyNode(branchB, base[0], base[1], base[2])
        val expected = additiveMix(base, aOut, bOut)

        assertRgb(expected, apply(graph, source))
    }
}
