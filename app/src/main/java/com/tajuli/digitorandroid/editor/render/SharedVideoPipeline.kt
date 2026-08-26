package com.tajuli.digitorandroid.editor.render

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import com.tajuli.digitorandroid.editor.model.NodeAnimationDomain
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.visibleEffects

/**
 * Shared video processing stages.
 *
 * The legacy export path applies clip geometry as an item effect. Resolve-style multilayer export
 * moves geometry to VideoCompositorSettings so scaled/positioned upper tracks remain transparent
 * outside their image and lower tracks stay visible there.
 */
@UnstableApi
object SharedVideoPipeline {
    fun effectsFor(clip: TimelineClip): List<Effect> = buildList {
        ClipTransformEffect.forExport(clip)?.let(::add)
        addAll(SharedColorPipeline.effectsFor(clip))
        SpatialNodeGraphEffect.forClip(clip, preview = false)?.let(::add)
    }

    /** Per-layer effects used before the final multilayer compositor. Geometry is compositor-owned. */
    fun compositedExportEffectsFor(clip: TimelineClip): List<Effect> = buildList {
        addAll(SharedColorPipeline.effectsFor(clip))
        SpatialNodeGraphEffect.forClip(clip, preview = false)?.let(::add)
    }

    fun previewEffectsFor(clip: TimelineClip): List<Effect> = buildList {
        ClipTransformEffect.forPreview(clip)?.let(::add)
        addAll(SharedColorPipeline.previewEffectsFor(clip))
        SpatialNodeGraphEffect.forClip(clip, preview = true)?.let(::add)
    }

    /**
     * Per-layer effects for the final-output viewer.
     *
     * Geometry is deliberately omitted here because the same Resolve compositor used by export owns
     * transform/opacity. Color uses the export 33^3 LUT and spatial FX use the same shader graph;
     * only timestamp/live-state lookup differs from Transformer export.
     */
    fun finalOutputPreviewEffectsFor(clip: TimelineClip): List<Effect> = buildList {
        addAll(SharedColorPipeline.finalOutputPreviewEffectsFor(clip))
        SpatialNodeGraphEffect.forClip(
            clip = clip,
            preview = true,
            timelineMappedPreview = true,
        )?.let(::add)
    }

    /**
     * Characteristics that genuinely require rebuilding a long-lived final-output preview graph.
     *
     * Normal transform, opacity, Correction/Color values and already-active spatial FX amounts are
     * intentionally excluded. They are read live by GPU callbacks. Qualifier feather configuration,
     * node topology and spatial-stage enable/disable still change immutable shader topology.
     */
    fun finalOutputPreviewPipelineKey(clip: TimelineClip): Int {
        val nodeTopology = clip.nodeGraph.nodes.map { node -> node.id to node.kind }
        val qualifierConfiguration = clip.nodeGraph.nodes.map { node ->
            Triple(
                node.id,
                node.advancedColor.qualifier,
                clip.nodeAnimations.qualifierIsAnimated(node.id),
            )
        }
        val hasSpatialFx = clip.nodeGraph.nodes.any { node ->
            if (node.kind != NodeKind.SERIAL && node.kind != NodeKind.PARALLEL) return@any false
            node.visibleEffects().any { it.enabled && it.amount > 0f } ||
                clip.nodeAnimations.hasAnimation(node.id, NodeAnimationDomain.EFFECTS)
        }
        return listOf(
            nodeTopology,
            clip.nodeGraph.edges,
            qualifierConfiguration,
            hasSpatialFx,
        ).hashCode()
    }
}
