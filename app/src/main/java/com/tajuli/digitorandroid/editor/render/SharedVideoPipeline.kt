package com.tajuli.digitorandroid.editor.render

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import com.tajuli.digitorandroid.editor.model.TimelineClip

/**
 * Shared video processing stages.
 *
 * Legacy single-stream paths apply clip geometry as an item effect. Resolve-style multilayer
 * preview/export move geometry to VideoCompositorSettings so scaled/positioned upper tracks remain
 * transparent outside their image and lower tracks stay visible there.
 */
@UnstableApi
object SharedVideoPipeline {
    fun effectsFor(clip: TimelineClip): List<Effect> = buildList {
        ClipTransformEffect.forExport(clip)?.let(::add)
        addAll(SharedColorPipeline.effectsFor(clip))
        SpatialNodeGraphEffect.forClip(clip, preview = false)?.let(::add)
    }

    /** Per-layer effects used before the final multilayer export compositor. */
    fun compositedExportEffectsFor(clip: TimelineClip): List<Effect> = buildList {
        addAll(SharedColorPipeline.effectsFor(clip))
        SpatialNodeGraphEffect.forClip(clip, preview = false)?.let(::add)
    }

    /**
     * GPU preview equivalent of [compositedExportEffectsFor]. Geometry/opacity are owned by the
     * compositor while the lighter preview LUT/effect variants stay on the GL path.
     */
    fun compositedPreviewEffectsFor(clip: TimelineClip): List<Effect> = buildList {
        addAll(SharedColorPipeline.previewEffectsFor(clip))
        SpatialNodeGraphEffect.forClip(clip, preview = true)?.let(::add)
    }

    fun previewEffectsFor(clip: TimelineClip): List<Effect> = buildList {
        ClipTransformEffect.forPreview(clip)?.let(::add)
        addAll(SharedColorPipeline.previewEffectsFor(clip))
        SpatialNodeGraphEffect.forClip(clip, preview = true)?.let(::add)
    }
}
