package com.tajuli.digitorandroid.editor.preview

import androidx.media3.common.util.UnstableApi
import com.tajuli.digitorandroid.editor.model.*
import org.junit.Assert.*
import org.junit.Test

@UnstableApi
class LiveEffectSessionTest {
    private val clip = TimelineClip(uri="content://video", label="video", timelineStartUs=0L, sourceOutUs=1_000_000L)
    private fun effects(vararg effects: NodeEffect) = clip.copy(nodeGraph=clip.nodeGraph.copy(
        nodes=clip.nodeGraph.nodes.map { node ->
            if(node.id==clip.nodeGraph.selectedNodeId) node.copy(effects=effects.toList()) else node
        }))

    @Test fun presetSelectionAndTimingKeepDecoderSession() {
        val original = staticSpatialHash(clip)
        for(name in listOf("Flame Eyes", "Electric Eyes", "Body Glow", "Angel Wings")) {
            assertEquals(original, staticSpatialHash(effects(NodeEffect(name=name))))
            assertEquals(original, staticSpatialHash(effects(NodeEffect(name=name, enabled=false))))
            assertEquals(original, staticSpatialHash(effects(NodeEffect(name=name, amount=.3f,
                sourceStartUsV26=200_000L, sourceEndUsV26=800_000L))))
        }
        assertEquals(original, staticSpatialHash(effects(NodeEffect(name="Flame Eyes"),NodeEffect(name="Body Glow"))))
    }

    @Test fun graphNodeChangeStillRebuildsSession() {
        val changed = clip.copy(nodeGraph=clip.nodeGraph.copy(nodes=clip.nodeGraph.nodes.map {
            if(it.id==clip.nodeGraph.selectedNodeId) it.copy(id="replacement-node") else it
        }))
        assertNotEquals(staticSpatialHash(clip), staticSpatialHash(changed))
    }
}
