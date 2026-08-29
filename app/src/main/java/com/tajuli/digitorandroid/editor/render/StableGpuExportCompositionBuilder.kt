package com.tajuli.digitorandroid.editor.render

import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import com.tajuli.digitorandroid.editor.model.TimelineProject

/**
 * Pixel-parity export composition builder.
 *
 * Single-layer compositor stabilization now lives inside [Media3CompositionBuilder], where a
 * transparent gap-only video sequence can force Media3's MultipleInputVideoGraph without opening a
 * second decoder for the source clip. Duplicating the real source here was especially expensive for
 * HEVC/Log camera media and could exhaust vendor codec resources during export.
 */
@UnstableApi
internal class StableGpuExportCompositionBuilder(
    private val sharedBuilder: Media3CompositionBuilder = Media3CompositionBuilder(),
) {
    fun build(project: TimelineProject): Composition = sharedBuilder.build(project)
}
