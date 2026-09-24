package com.tajuli.digitorandroid.editor.render

import androidx.media3.common.util.UnstableApi
import com.tajuli.digitorandroid.editor.model.NodeEffect
import com.tajuli.digitorandroid.editor.model.TimelineClip
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@UnstableApi
class BodyEffectGraphV102Test {
    @Test
    fun exportGraphExistsOnlyWhenBodyEffectIsPresent() {
        val plain = TimelineClip(
            uri = "content://plain",
            label = "plain",
            timelineStartUs = 0L,
            sourceOutUs = 1_000_000L,
        )
        assertNull(BodyEffectGraphV102.forClip(plain, preview = false))

        val selected = plain.nodeGraph.selectedNodeId
        val body = plain.copy(
            nodeGraph = plain.nodeGraph.copy(
                nodes = plain.nodeGraph.nodes.map { node ->
                    if (node.id == selected) node.copy(effects = listOf(NodeEffect(name = "Body Glow"))) else node
                },
            ),
        )
        assertNotNull(BodyEffectGraphV102.forClip(body, preview = false))
    }

    @Test
    fun previewGraphIsResidentForLiveBodyEffectToggles() {
        val plain = TimelineClip(
            uri = "content://plain",
            label = "plain",
            timelineStartUs = 0L,
            sourceOutUs = 1_000_000L,
        )
        assertNotNull(BodyEffectGraphV102.forClip(plain, preview = true))
    }
}
