package com.tajuli.digitorandroid.editor.render

import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TrackKind

private const val SINGLE_LAYER_SENTINEL_SUFFIX = "__digitor_compositor_sentinel"

/**
 * Pixel-parity export composition builder.
 *
 * Preview always renders video through [ResolveVideoCompositorSettings]. Media3 Transformer,
 * however, automatically chooses SingleInputVideoGraph when only one video sequence is present.
 * SingleInputVideoGraph rejects any non-default VideoCompositorSettings, so directly attaching our
 * Resolve compositor to a one-layer export fails before the first frame is rendered.
 *
 * For a one-layer video project we therefore add a second, invisible copy of the same video track
 * only for the export composition. The sentinel has identical timing/source media and zero opacity,
 * which makes Transformer select MultipleInputVideoGraph while contributing no pixels. The real
 * layer consequently keeps the exact same project-resolution compositor geometry/alpha path used
 * by realtime preview. Multi-layer projects are passed through unchanged.
 *
 * Codec/bitstream loss remains outside this render-stage parity contract.
 */
@UnstableApi
internal class StableGpuExportCompositionBuilder(
    private val sharedBuilder: Media3CompositionBuilder = Media3CompositionBuilder(),
) {
    fun build(project: TimelineProject): Composition =
        sharedBuilder.build(withSingleLayerCompositorSentinel(project))
}

/**
 * Forces Media3 Transformer onto MultipleInputVideoGraph for exactly-one-video-track exports.
 *
 * The sentinel is intentionally ephemeral: it is never written back to editor state. It mirrors
 * every source/timing boundary of the real track so both graph inputs are concurrent for the whole
 * visible interval, while opacity=0 makes the compositor blend a mathematically transparent layer.
 */
internal fun withSingleLayerCompositorSentinel(project: TimelineProject): TimelineProject {
    val videoTracks = project.tracks.filter { track ->
        track.kind == TrackKind.VIDEO && !track.muted && track.clips.isNotEmpty()
    }
    if (videoTracks.size != 1) return project

    val sourceTrack = videoTracks.single()
    val sentinelTrack = sourceTrack.copy(
        id = sourceTrack.id + SINGLE_LAYER_SENTINEL_SUFFIX,
        name = sourceTrack.name + " · compositor sentinel",
        clips = sourceTrack.clips.map { clip ->
            clip.copy(
                id = clip.id + SINGLE_LAYER_SENTINEL_SUFFIX,
                opacity = 0f,
                linkGroupId = null,
            )
        },
    )
    return project.copy(tracks = project.tracks + sentinelTrack)
}
