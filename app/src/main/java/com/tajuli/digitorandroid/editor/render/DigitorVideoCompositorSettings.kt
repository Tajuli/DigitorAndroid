package com.tajuli.digitorandroid.editor.render

import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.StaticOverlaySettings
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind

internal fun compositionVideoTracks(project: TimelineProject): List<TimelineTrack> =
    project.tracks.filter { track ->
        track.kind == TrackKind.VIDEO && !track.muted && track.clips.isNotEmpty()
    }

/**
 * Returns the clip alpha for this instant, or fully transparent when this track has no active clip.
 * Returning 0 outside clip bounds is essential for unequal-length layers: a finished short overlay
 * must not leave its last decoded texture visible above a longer background clip.
 */
internal fun compositionOpacityAt(track: TimelineTrack, timelineUs: Long): Float =
    track.clips
        .firstOrNull { clip -> timelineUs in clip.timelineStartUs until clip.timelineEndUs }
        ?.opacity
        ?.coerceIn(0f, 1f)
        ?: 0f

/**
 * Shared layer settings for Media3 preview and Transformer export.
 *
 * Digitor keeps the first V track at the top of the timeline. Media3's default compositor also
 * draws the first registered video input last/on top, so preserving project track order gives the
 * same z-order in preview, GPU export and the CPU fallback compositor.
 *
 * Clip geometry is handled by the per-item [SharedVideoPipeline]. This class owns the canvas size
 * and per-clip alpha at the composition stage, where alpha blending between independent V tracks
 * actually happens.
 */
@UnstableApi
internal class DigitorVideoCompositorSettings(
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val videoTracks: List<TimelineTrack>,
) : VideoCompositorSettings {

    override fun getOutputSize(inputSizes: List<Size>): Size =
        Size(outputWidth.coerceAtLeast(1), outputHeight.coerceAtLeast(1))

    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
        val track = videoTracks.getOrNull(inputId)
        val alpha = if (track == null) 0f else compositionOpacityAt(track, presentationTimeUs)
        return StaticOverlaySettings.Builder()
            .setAlphaScale(alpha)
            .build()
    }
}
