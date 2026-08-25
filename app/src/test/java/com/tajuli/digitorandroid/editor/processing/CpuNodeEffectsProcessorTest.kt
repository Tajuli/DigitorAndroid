package com.tajuli.digitorandroid.editor.processing

import com.tajuli.digitorandroid.editor.model.ClipNodeGraph
import com.tajuli.digitorandroid.editor.model.NodeEffect
import com.tajuli.digitorandroid.editor.model.TimelineClip
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
        val clip = TimelineClip(
            uri = "video",
            label = "video",
            timelineStartUs = 0L,
            sourceOutUs = 1_000_000L,
            nodeGraph = gradedGraph,
        )
        val pixels = IntArray(9) { 0xFF000000.toInt() }
        pixels[4] = 0xFFFFFFFF.toInt()

        CpuNodeEffectsProcessor(workerCount = 1).use { processor ->
            processor.processClipArgb8888(pixels, 3, 3, clip, 0L)
        }

        val center = (pixels[4] ushr 16) and 0xFF
        val edge = (pixels[1] ushr 16) and 0xFF
        assertTrue("blur should reduce the isolated white center", center < 255)
        assertTrue("blur should spread light into a neighboring pixel", edge > 0)
    }
}
