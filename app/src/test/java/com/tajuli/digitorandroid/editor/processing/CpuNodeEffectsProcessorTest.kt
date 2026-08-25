package com.tajuli.digitorandroid.editor.processing

import com.tajuli.digitorandroid.editor.model.ClipNodeGraph
import com.tajuli.digitorandroid.editor.model.ColorNode
import com.tajuli.digitorandroid.editor.model.NodeEdge
import com.tajuli.digitorandroid.editor.model.NodeEffect
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.NodePosition
import com.tajuli.digitorandroid.editor.model.TimelineClip
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuNodeEffectsProcessorTest {
    @Test
    fun blurSpreadsAHighContrastPixel() {
        val graph = ClipNodeGraph.default()
        val selectedId = graph.selectedNodeId!!
        val gradedGraph = graph.copy(
            nodes = graph.nodes.map { node ->
                if (node.id == selectedId) {
                    node.copy(effects = listOf(NodeEffect(id = "blur", name = "Blur", amount = 1f)))
                } else {
                    node
                }
            },
        )
        val clip = clip(gradedGraph)
        val width = 15
        val height = 15
        val centerX = 7
        val centerY = 7
        val pixels = IntArray(width * height) { 0xFF000000.toInt() }
        pixels[centerY * width + centerX] = 0xFFFFFFFF.toInt()

        CpuNodeEffectsProcessor(workerCount = 1).use { processor ->
            processor.processClipArgb8888(pixels, width, height, clip, 0L)
        }

        val center = (pixels[centerY * width + centerX] ushr 16) and 0xFF
        val spread = (pixels[(centerY - 5) * width + centerX] ushr 16) and 0xFF
        assertTrue("blur should reduce the isolated white center", center < 255)
        assertTrue("blur should spread light along its sampling radius", spread > 0)
    }

    @Test
    fun parallelSpatialBranchesDoNotCollapseIntoSerialExecution() {
        val source = testPattern(7, 7)
        val parallelPixels = source.copyOf()
        val serialPixels = source.copyOf()

        CpuNodeEffectsProcessor(workerCount = 1).use { processor ->
            processor.processClipArgb8888(parallelPixels, 7, 7, clip(parallelGraph()), 0L)
            processor.processClipArgb8888(serialPixels, 7, 7, clip(serialGraph()), 0L)
        }

        assertFalse(
            "parallel Blur + Sharpen must not equal the old serially flattened result",
            parallelPixels.contentEquals(serialPixels),
        )
    }

    @Test
    fun parallelMixerIsIndependentOfBranchEdgeOrder() {
        val source = testPattern(7, 7)
        val normal = source.copyOf()
        val reversed = source.copyOf()

        CpuNodeEffectsProcessor(workerCount = 1).use { processor ->
            processor.processClipArgb8888(normal, 7, 7, clip(parallelGraph(reverseMixInputs = false)), 0L)
            processor.processClipArgb8888(reversed, 7, 7, clip(parallelGraph(reverseMixInputs = true)), 0L)
        }

        assertTrue(
            "parallel branch order must not change additive mixer output",
            normal.contentEquals(reversed),
        )
    }

    private fun clip(graph: ClipNodeGraph) = TimelineClip(
        uri = "video",
        label = "video",
        timelineStartUs = 0L,
        sourceOutUs = 1_000_000L,
        nodeGraph = graph,
    )

    private fun node(
        id: String,
        kind: NodeKind,
        x: Float,
        y: Float,
        effect: NodeEffect? = null,
    ) = ColorNode(
        id = id,
        kind = kind,
        label = id,
        position = NodePosition(x, y),
        effects = effect?.let(::listOf).orEmpty(),
    )

    private fun parallelGraph(reverseMixInputs: Boolean = false): ClipNodeGraph {
        val input = node("in", NodeKind.IMPORT, 0f, 0f)
        val blur = node(
            "blur",
            NodeKind.SERIAL,
            100f,
            0f,
            NodeEffect(id = "fx-blur", name = "Blur", amount = .75f),
        )
        val sharpen = node(
            "sharp",
            NodeKind.PARALLEL,
            100f,
            80f,
            NodeEffect(id = "fx-sharp", name = "Sharpen", amount = .65f),
        )
        val mix = node("mix", NodeKind.MIX, 220f, 40f)
        val output = node("out", NodeKind.OUTPUT, 340f, 40f)
        val mixEdges = if (reverseMixInputs) {
            listOf(NodeEdge("sharp", "mix"), NodeEdge("blur", "mix"))
        } else {
            listOf(NodeEdge("blur", "mix"), NodeEdge("sharp", "mix"))
        }
        return ClipNodeGraph(
            nodes = listOf(input, blur, sharpen, mix, output),
            edges = listOf(
                NodeEdge("in", "blur"),
                NodeEdge("in", "sharp"),
            ) + mixEdges + NodeEdge("mix", "out"),
            selectedNodeId = "sharp",
        )
    }

    private fun serialGraph(): ClipNodeGraph {
        val input = node("in", NodeKind.IMPORT, 0f, 0f)
        val blur = node(
            "blur",
            NodeKind.SERIAL,
            100f,
            0f,
            NodeEffect(id = "fx-blur", name = "Blur", amount = .75f),
        )
        val sharpen = node(
            "sharp",
            NodeKind.SERIAL,
            200f,
            0f,
            NodeEffect(id = "fx-sharp", name = "Sharpen", amount = .65f),
        )
        val output = node("out", NodeKind.OUTPUT, 320f, 0f)
        return ClipNodeGraph(
            nodes = listOf(input, blur, sharpen, output),
            edges = listOf(
                NodeEdge("in", "blur"),
                NodeEdge("blur", "sharp"),
                NodeEdge("sharp", "out"),
            ),
            selectedNodeId = "sharp",
        )
    }

    private fun testPattern(width: Int, height: Int): IntArray = IntArray(width * height) { index ->
        val x = index % width
        val y = index / width
        val value = when {
            x == width / 2 && y == height / 2 -> 255
            (x + y) % 3 == 0 -> 180
            (x * 2 + y) % 4 == 0 -> 70
            else -> 15
        }
        (0xFF shl 24) or (value shl 16) or ((value / 2) shl 8) or (255 - value)
    }
}
