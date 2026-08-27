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
     * Pixel-parity realtime path. Color uses the same 33^3 LUT resolution as export and spatial
     * effects use the exact same shader implementation. The preview flag only anchors animated
     * effect time to the editor playhead; it does not select a lower-quality shader.
     */
    fun compositedExactPreviewEffectsFor(clip: TimelineClip): List<Effect> = buildList {
        addAll(SharedColorPipeline.exactPreviewEffectsFor(clip))
        SpatialNodeGraphEffect.forClip(clip, preview = true)?.let(::add)
    }

    /**
     * Lightweight legacy GPU preview path retained for fallback/experimentation. Geometry/opacity
     * are owned by the compositor while lower-resolution preview LUT/effects stay on the GL path.
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
