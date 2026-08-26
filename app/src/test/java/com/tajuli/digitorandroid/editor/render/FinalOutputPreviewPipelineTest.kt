package com.tajuli.digitorandroid.editor.render

import com.tajuli.digitorandroid.editor.model.AnimatedFloat
import com.tajuli.digitorandroid.editor.model.ClipTransform
import com.tajuli.digitorandroid.editor.model.HslQualifier
import com.tajuli.digitorandroid.editor.model.NodeCorrections
import com.tajuli.digitorandroid.editor.model.NodeEffect
import com.tajuli.digitorandroid.editor.model.TimelineClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FinalOutputPreviewPipelineTest {
    private fun clip(): TimelineClip = TimelineClip(
        uri = "video",
        label = "video",
        timelineStartUs = 0L,
        sourceOutUs = 5_000_000L,
    )

    @Test
    fun liveTransformOpacityAndColorDoNotRebuildFinalPreviewGraph() {
        val base = clip()
        val selected = base.nodeGraph.selectedNodeId
        val baseKey = SharedVideoPipeline.finalOutputPreviewPipelineKey(base)

        val transformed = base.copy(
            opacity = .55f,
            transform = ClipTransform(positionX = AnimatedFloat(.35f)),
        )
        assertEquals(baseKey, SharedVideoPipeline.finalOutputPreviewPipelineKey(transformed))

        val graded = base.copy(
            nodeGraph = base.nodeGraph.copy(
                nodes = base.nodeGraph.nodes.map { node ->
                    if (node.id == selected) {
                        node.copy(corrections = NodeCorrections(exposure = .7f, saturation = 22f))
                    } else {
                        node
                    }
                },
            ),
        )
        assertEquals(baseKey, SharedVideoPipeline.finalOutputPreviewPipelineKey(graded))
    }

    @Test
    fun activeSpatialAmountChangesStayOnPersistentGraph() {
        val base = clip()
        val selected = base.nodeGraph.selectedNodeId

        fun withBlur(amount: Float): TimelineClip = base.copy(
            nodeGraph = base.nodeGraph.copy(
                nodes = base.nodeGraph.nodes.map { node ->
                    if (node.id == selected) {
                        node.copy(effects = listOf(NodeEffect(id = "blur", name = "Blur", amount = amount)))
                    } else {
                        node
                    }
                },
            ),
        )

        val low = withBlur(.2f)
        val high = withBlur(.9f)
        assertEquals(
            SharedVideoPipeline.finalOutputPreviewPipelineKey(low),
            SharedVideoPipeline.finalOutputPreviewPipelineKey(high),
        )
        assertNotEquals(
            SharedVideoPipeline.finalOutputPreviewPipelineKey(base),
            SharedVideoPipeline.finalOutputPreviewPipelineKey(low),
        )
    }

    @Test
    fun immutableQualifierConfigurationStillRebuildsGraph() {
        val base = clip()
        val selected = base.nodeGraph.selectedNodeId
        val qualified = base.copy(
            nodeGraph = base.nodeGraph.copy(
                nodes = base.nodeGraph.nodes.map { node ->
                    if (node.id == selected) {
                        node.copy(
                            advancedColor = node.advancedColor.copy(
                                qualifier = HslQualifier(enabled = true),
                            ),
                        )
                    } else {
                        node
                    }
                },
            ),
        )

        assertNotEquals(
            SharedVideoPipeline.finalOutputPreviewPipelineKey(base),
            SharedVideoPipeline.finalOutputPreviewPipelineKey(qualified),
        )
    }
}
