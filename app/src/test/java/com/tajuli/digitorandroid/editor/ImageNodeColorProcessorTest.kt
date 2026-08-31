package com.tajuli.digitorandroid.editor

import com.tajuli.digitorandroid.editor.model.ClipNodeGraph
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.VisualOverlayClipV19
import com.tajuli.digitorandroid.editor.model.VisualOverlayKindV19
import com.tajuli.digitorandroid.editor.processing.CpuColorProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageNodeColorProcessorTest {
    @Test
    fun identityImageNodeGraphLeavesPixelsUnchanged() {
        val pixels = intArrayOf(0x7F204060, 0xFF8090A0.toInt())
        val expected = pixels.copyOf()

        CpuColorProcessor(workerCount = 1).use { processor ->
            processor.processNodeGraphArgb8888(pixels, width = 2, height = 1, nodeGraph = ClipNodeGraph.default())
        }

        assertArrayEquals(expected, pixels)
    }

    @Test
    fun imageNodeCorrectionUsesSharedGraphAndPreservesAlpha() {
        val base = ClipNodeGraph.default()
        val serialId = base.nodes.first { it.kind == NodeKind.SERIAL }.id
        val graded = base.copy(
            nodes = base.nodes.map { node ->
                if (node.id == serialId) {
                    node.copy(corrections = node.corrections.copy(exposure = 1f, saturation = 25f))
                } else {
                    node
                }
            },
        )
        val pixels = intArrayOf(0x7F404040)

        CpuColorProcessor(workerCount = 1).use { processor ->
            processor.processNodeGraphArgb8888(pixels, width = 1, height = 1, nodeGraph = graded)
        }

        val output = pixels.single()
        assertEquals(0x7F, (output ushr 24) and 0xFF)
        assertTrue(((output ushr 16) and 0xFF) > 0x40)
    }

    @Test
    fun imageGradeGraphSurvivesOverlayNormalization() {
        val graph = ClipNodeGraph.default()
        val overlay = VisualOverlayClipV19(
            kind = VisualOverlayKindV19.IMAGE,
            label = "Photo",
            timelineStartUs = -10L,
            timelineEndUs = 0L,
            imageUri = "content://image",
            imageNodeGraphV20 = graph,
        ).normalized()

        assertEquals(graph, overlay.imageNodeGraphV20)
        assertEquals(0L, overlay.timelineStartUs)
        assertTrue(overlay.timelineEndUs >= 100_000L)
    }
}
