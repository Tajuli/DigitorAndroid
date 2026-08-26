package com.tajuli.digitorandroid.editor.render

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import com.tajuli.digitorandroid.editor.model.TimelineClip

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
}
