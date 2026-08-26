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

class PersistentPreviewPipelineTest {
    private fun clip(): TimelineClip = TimelineClip(
        uri = "video",
        label = "video",
        timelineStartUs = 0L,
        sourceOutUs = 5_000_000L,
    )

    @Test fun transformAndOrdinaryColorEditsDoNotRebuildPipeline() {
        val base = clip()
        val selected = base.nodeGraph.selectedNodeId
        val baseKey = SharedVideoPipeline.previewPipelineKey(base)

        val transformed = base.copy(
            transform = ClipTransform(positionX = AnimatedFloat(.35f)),
        )
        assertEquals(baseKey, SharedVideoPipeline.previewPipelineKey(transformed))

        val graded = base.copy(
            nodeGraph = base.nodeGraph.copy(nodes = base.nodeGraph.nodes.map { node ->
                if (node.id == selected) node.copy(corrections = NodeCorrections(exposure = .7f)) else node
            }),
        )
        assertEquals(baseKey, SharedVideoPipeline.previewPipelineKey(graded))
    }

    @Test fun activeSpatialFxAmountDoesNotRebuildPipeline() {
        val base = clip()
        val selected = base.nodeGraph.selectedNodeId
        val effectId = "blur"
        fun withBlur(amount: Float): TimelineClip = base.copy(
            nodeGraph = base.nodeGraph.copy(nodes = base.nodeGraph.nodes.map { node ->
                if (node.id == selected) {
                    node.copy(effects = listOf(NodeEffect(id = effectId, name = "Blur", amount = amount)))
                } else node
            }),
        )

        val low = withBlur(.25f)
        val high = withBlur(.85f)
        assertEquals(
            SharedVideoPipeline.previewPipelineKey(low),
            SharedVideoPipeline.previewPipelineKey(high),
        )
    }

    @Test fun qualifierAndSpatialStageToggleRemainSafeRebuildBoundaries() {
        val base = clip()
        val selected = base.nodeGraph.selectedNodeId
        val baseKey = SharedVideoPipeline.previewPipelineKey(base)

        val qualified = base.copy(
            nodeGraph = base.nodeGraph.copy(nodes = base.nodeGraph.nodes.map { node ->
                if (node.id == selected) {
                    node.copy(advancedColor = node.advancedColor.copy(qualifier = HslQualifier(enabled = true)))
                } else node
            }),
        )
        assertNotEquals(baseKey, SharedVideoPipeline.previewPipelineKey(qualified))

        val withBlur = base.copy(
            nodeGraph = base.nodeGraph.copy(nodes = base.nodeGraph.nodes.map { node ->
                if (node.id == selected) node.copy(effects = listOf(NodeEffect(name = "Blur", amount = .4f))) else node
            }),
        )
        assertNotEquals(baseKey, SharedVideoPipeline.previewPipelineKey(withBlur))
    }
}
