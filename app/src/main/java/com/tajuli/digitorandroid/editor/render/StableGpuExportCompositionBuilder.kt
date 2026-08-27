package com.tajuli.digitorandroid.editor.render

import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import com.tajuli.digitorandroid.editor.model.TimelineProject

/**
 * Pixel-parity export composition builder.
 *
 * Preview always renders video through [ResolveVideoCompositorSettings], including a one-layer
 * timeline. Export must do the same. Keeping a separate single-stream export shortcut makes
 * transform/opacity rasterization happen in a different stage and breaks the preview/export pixel
 * contract (most visibly because compositor-owned opacity is skipped by item-only effects).
 *
 * Delegate every project to [Media3CompositionBuilder] so one-track and multi-track exports share
 * the exact same per-layer effects, project-resolution compositor geometry and z/alpha semantics
 * as the realtime render core. Codec/bitstream loss remains outside this render-stage contract.
 */
@UnstableApi
internal class StableGpuExportCompositionBuilder(
    private val sharedBuilder: Media3CompositionBuilder = Media3CompositionBuilder(),
) {
    fun build(project: TimelineProject): Composition = sharedBuilder.build(project)
}
