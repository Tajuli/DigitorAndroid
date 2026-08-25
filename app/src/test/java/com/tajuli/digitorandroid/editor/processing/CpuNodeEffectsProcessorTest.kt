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
}
