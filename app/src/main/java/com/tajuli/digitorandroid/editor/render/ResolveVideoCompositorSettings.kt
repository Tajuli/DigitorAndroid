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
 * Returns the compositor scale required to contain an input frame inside the output canvas without
 * stretching or cropping. One axis stays at 1 while the other axis is reduced when aspect ratios
 * differ. User/keyframed transform scale is applied on top of this base fit in
 * [ResolveVideoCompositorSettings].
 */
internal fun aspectFitScale(
    inputWidth: Int,
    inputHeight: Int,
    outputWidth: Int,
    outputHeight: Int,
): Pair<Float, Float> {
    if (inputWidth <= 0 || inputHeight <= 0 || outputWidth <= 0 || outputHeight <= 0) {
        return 1f to 1f
    }
    val inputAspect = inputWidth.toFloat() / inputHeight.toFloat()
    val outputAspect = outputWidth.toFloat() / outputHeight.toFloat()
    return if (inputAspect > outputAspect) {
        // Input is wider: fit width, letterbox top/bottom.
        1f to (outputAspect / inputAspect).coerceIn(0f, 1f)
    } else if (inputAspect < outputAspect) {
        // Input is taller/narrower: fit height, pillarbox left/right.
        (inputAspect / outputAspect).coerceIn(0f, 1f) to 1f
    } else {
        1f to 1f
    }
}

/**
 * Resolve-style multilayer compositor shared by export and preview.
 *
 * Export is fully snapshot-based. GPU preview can resolve the latest immutable editor snapshot by
 * stable track/clip id, allowing transform and opacity sliders to update without rebuilding the
 * MediaCodec/GL graph.
 *
 * Media3's compositor scale is expressed relative to the output frame. A raw scale of 1x1 therefore
 * does not by itself preserve a source frame whose aspect ratio differs from the project canvas.
 * We cache the real decoded input sizes delivered to [getOutputSize] and apply a contain-fit base
 * scale before the user's transform. This keeps preview and export geometry identical and prevents
 * portrait/wide sources from being stretched or visually zoomed into the project frame.
 */
@UnstableApi
internal class ResolveVideoCompositorSettings(
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val videoTracks: List<TimelineTrack>,
    private val livePreview: Boolean = false,
) : VideoCompositorSettings {

    private val trackIds = videoTracks.map { it.id }

    @Volatile
    private var decodedInputSizes: List<Size> = emptyList()

    override fun getOutputSize(inputSizes: List<Size>): Size {
        decodedInputSizes = inputSizes.toList()
        return Size(outputWidth.coerceAtLeast(1), outputHeight.coerceAtLeast(1))
    }

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

        val inputSize = decodedInputSizes.getOrNull(inputId)
        val (fitScaleX, fitScaleY) = if (inputSize != null) {
            aspectFitScale(
                inputWidth = inputSize.width,
                inputHeight = inputSize.height,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
            )
        } else {
            1f to 1f
        }

        return StaticOverlaySettings.Builder()
            .setAlphaScale(clip.opacity.coerceIn(0f, 1f))
            .setOverlayFrameAnchor(0f, 0f)
            .setBackgroundFrameAnchor(transform.positionX, -transform.positionY)
            .setScale(
                fitScaleX * transform.scaleX,
                fitScaleY * transform.scaleY,
            )
            .setRotationDegrees(transform.rotationDegrees)
            .build()
    }
}
