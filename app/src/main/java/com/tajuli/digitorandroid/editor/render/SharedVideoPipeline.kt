package com.tajuli.digitorandroid.editor.render

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import com.tajuli.digitorandroid.editor.model.NodeAnimationDomain
import com.tajuli.digitorandroid.editor.model.TimelineClip

/**
 * Keeps preview/export effect ordering identical: geometry first, then the resolved color graph,
 * then one graph-aware spatial FX compositor.
 *
 * Preview parameter values are intentionally not part of [previewPipelineKey] when the attached GPU
 * effect can read them from PreviewClipState. Only changes that alter effect topology/immutable
 * shader configuration require ExoPlayer to rebuild its effect chain.
 */
@UnstableApi
object SharedVideoPipeline {
    fun effectsFor(clip: TimelineClip): List<Effect> = buildList {
        ClipTransformEffect.forExport(clip)?.let(::add)
        addAll(SharedColorPipeline.effectsFor(clip))
        SpatialNodeGraphEffect.forClip(clip, preview = false)?.let(::add)
    }

    fun previewEffectsFor(clip: TimelineClip): List<Effect> = buildList {
        // Always attach the transform stage in preview so identity -> transformed edits stay live.
        add(ClipTransformEffect.forPreview(clip))
        addAll(SharedColorPipeline.previewEffectsFor(clip))
        SpatialNodeGraphEffect.forClip(clip, preview = true)?.let(::add)
    }

    /**
     * Hash of preview characteristics that still require rebuilding the Media3 effect chain.
     * Transform and ordinary Correction/Color values are excluded and update live on the GPU.
     * Qualifier feather shaders and static spatial FX currently carry immutable setup, so those
     * remain rebuild boundaries until they are moved to live uniforms as well.
     */
    fun previewPipelineKey(clip: TimelineClip): Int {
        val nodeTopology = clip.nodeGraph.nodes.map { it.id to it.kind }
        val edges = clip.nodeGraph.edges
        val qualifierConfiguration = clip.nodeGraph.nodes.map { node ->
            Triple(
                node.id,
                node.advancedColor.qualifier,
                clip.nodeAnimations.qualifierIsAnimated(node.id),
            )
        }
        val spatialConfiguration = clip.nodeGraph.nodes.map { node ->
            Triple(
                node.id,
                node.effects,
                clip.nodeAnimations.hasAnimation(node.id, NodeAnimationDomain.EFFECTS),
            )
        }
        return listOf(
            nodeTopology,
            edges,
            qualifierConfiguration,
            spatialConfiguration,
            clip.nodeAnimations.hasColorAnimation,
        ).hashCode()
    }
}
