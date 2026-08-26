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
import com.tajuli.digitorandroid.editor.preview.PreviewProjectRegistry

internal fun resolveCompositionVideoTracks(project: TimelineProject): List<TimelineTrack> =
    project.tracks.filter { track ->
        track.kind == TrackKind.VIDEO && !track.muted && track.clips.isNotEmpty()
    }

internal fun TimelineTrack.activeVideoClipAt(timelineUs: Long): TimelineClip? =
    clips.firstOrNull { clip -> timelineUs in clip.timelineStartUs until clip.timelineEndUs }

/**
 * Resolve-style multilayer compositor shared by export and preview.
 *
 * Export is fully snapshot-based. GPU preview can resolve the latest immutable editor snapshot by
 * stable track/clip id, allowing transform and opacity sliders to update without rebuilding the
 * MediaCodec/GL graph.
 */
@UnstableApi
internal class ResolveVideoCompositorSettings(
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val videoTracks: List<TimelineTrack>,
    private val livePreview: Boolean = false,
) : VideoCompositorSettings {

    private val trackIds = videoTracks.map { it.id }

    override fun getOutputSize(inputSizes: List<Size>): Size =
        Size(outputWidth.coerceAtLeast(1), outputHeight.coerceAtLeast(1))

    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
        val snapshotTrack = videoTracks.getOrNull(inputId)
            ?: return StaticOverlaySettings.Builder().setAlphaScale(0f).build()

        val track = if (livePreview) {
            val id = trackIds.getOrNull(inputId)
            PreviewProjectRegistry.project()?.tracks?.firstOrNull { it.id == id } ?: snapshotTrack
        } else {
            snapshotTrack
        }

        val clip = track.activeVideoClipAt(presentationTimeUs)
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
