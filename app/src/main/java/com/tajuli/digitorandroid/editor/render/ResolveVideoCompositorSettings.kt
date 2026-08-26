package com.tajuli.digitorandroid.editor.render

import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.StaticOverlaySettings
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind

internal fun resolveCompositionVideoTracks(project: TimelineProject): List<TimelineTrack> =
    project.tracks.filter { track ->
        track.kind == TrackKind.VIDEO && !track.muted && track.clips.isNotEmpty()
    }

internal fun TimelineTrack.activeVideoClipAt(timelineUs: Long): TimelineClip? =
    clips.firstOrNull { clip -> timelineUs in clip.timelineStartUs until clip.timelineEndUs }

/**
 * Resolve-style final export compositor.
 *
 * Each Digitor V track stays as an independent video sequence. Color/node processing happens on
 * that layer before composition; transform and opacity happen here while the layer is still a
 * texture, so the area outside a scaled/positioned V2 remains transparent and V1 can show through.
 *
 * Digitor stores the first video track as the top track. Media3's compositor treats the first video
 * input as the foreground, so preserving project track order preserves the timeline z-order.
 */
@UnstableApi
internal class ResolveVideoCompositorSettings(
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val videoTracks: List<TimelineTrack>,
) : VideoCompositorSettings {

    override fun getOutputSize(inputSizes: List<Size>): Size =
        Size(outputWidth.coerceAtLeast(1), outputHeight.coerceAtLeast(1))

    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
        val clip = videoTracks.getOrNull(inputId)?.activeVideoClipAt(presentationTimeUs)
            ?: return StaticOverlaySettings.Builder().setAlphaScale(0f).build()

        val localUs = (presentationTimeUs - clip.timelineStartUs)
            .coerceIn(0L, clip.durationUs.coerceAtLeast(0L))
        val transform = clip.transform.evaluate(localUs)

        return StaticOverlaySettings.Builder()
            .setAlphaScale(clip.opacity.coerceIn(0f, 1f))
            .setOverlayFrameAnchor(0f, 0f)
            .setBackgroundFrameAnchor(transform.positionX, -transform.positionY)
            .setScale(transform.scaleX, transform.scaleY)
            .setRotationDegrees(transform.rotationDegrees)
            .build()
    }
}
