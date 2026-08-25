package com.tajuli.digitorandroid.editor.render

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.TimelineClip

/**
 * Keeps preview/export effect ordering identical: geometry first, then color, then spatial node FX.
 * Preview geometry/effects are playhead-clock anchored; export remains deterministic from the
 * edited media item's timestamps.
 */
@UnstableApi
object SharedVideoPipeline {
    fun effectsFor(clip: TimelineClip): List<Effect> = buildList {
        ClipTransformEffect.forExport(clip)?.let(::add)
        addAll(SharedColorPipeline.effectsFor(clip))
        addAll(nodeEffectsFor(clip, preview = false))
    }

    fun previewEffectsFor(clip: TimelineClip): List<Effect> = buildList {
        ClipTransformEffect.forPreview(clip)?.let(::add)
        addAll(SharedColorPipeline.previewEffectsFor(clip))
        addAll(nodeEffectsFor(clip, preview = true))
    }

    private fun nodeEffectsFor(clip: TimelineClip, preview: Boolean): List<Effect> =
        clip.nodeGraph.nodes
            .asSequence()
            .filter { it.kind == NodeKind.SERIAL || it.kind == NodeKind.PARALLEL }
            .sortedWith(compareBy({ it.position.x }, { it.position.y }))
            .mapNotNull { node -> AnimatedNodeEffectsEffect.forNode(clip, node.id, preview) }
            .toList()
}
