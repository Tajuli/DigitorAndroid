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
import com.tajuli.digitorandroid.editor.model.TransitionPairV22
import com.tajuli.digitorandroid.editor.model.TransitionStyleV22
import com.tajuli.digitorandroid.editor.model.transitionPairForIncomingV22
import com.tajuli.digitorandroid.editor.preview.PreviewProjectRegistry
import kotlin.math.abs
import kotlin.math.min

internal fun resolveCompositionVideoTracks(project: TimelineProject): List<TimelineTrack> =
    project.tracks.filter { track ->
        track.kind == TrackKind.VIDEO && !track.muted && track.clips.isNotEmpty()
    }

internal fun TimelineTrack.activeVideoClipAt(timelineUs: Long): TimelineClip? =
    clips.firstOrNull { clip -> timelineUs in clip.timelineStartUs until clip.timelineEndUs }

internal data class ResolveOverlayState(
    val alphaScale: Float,
    val backgroundX: Float,
    val backgroundY: Float,
    val scaleX: Float,
    val scaleY: Float,
    val rotationDegrees: Float,
)

/**
 * Media3 constrains both overlay and background anchors to [-1, 1]. A full-frame push needs roughly
 * two normalized units of center travel, so using backgroundAnchor alone is invalid. Split the
 * requested center position between the two legal anchors instead: effectiveCenter = background -
 * overlay * scale. This preserves the full push/slide travel without passing illegal values to
 * StaticOverlaySettings during Transformer export.
 */
internal data class Media3AnchorPlacementV22(
    val backgroundX: Float,
    val backgroundY: Float,
    val overlayX: Float,
    val overlayY: Float,
)

internal fun media3AnchorPlacementV22(state: ResolveOverlayState): Media3AnchorPlacementV22 {
    val x = media3AxisAnchorsV22(state.backgroundX, state.scaleX)
    val y = media3AxisAnchorsV22(state.backgroundY, state.scaleY)
    return Media3AnchorPlacementV22(
        backgroundX = x.first,
        backgroundY = y.first,
        overlayX = x.second,
        overlayY = y.second,
    )
}

private fun media3AxisAnchorsV22(position: Float, scale: Float): Pair<Float, Float> {
    if (position in -1f..1f) return position to 0f
    val background = if (position > 1f) 1f else -1f
    val safeScale = abs(scale).coerceAtLeast(0.001f)
    val overlay = ((background - position) / safeScale).coerceIn(-1f, 1f)
    return background to overlay
}

internal sealed class ResolveCompositorInputV22 {
    data class TrackInput(val snapshotTrack: TimelineTrack) : ResolveCompositorInputV22()
    data class TransitionGhostInput(
        val pair: TransitionPairV22,
        val ghostClip: TimelineClip,
    ) : ResolveCompositorInputV22()
    object BlankInput : ResolveCompositorInputV22()
}

/**
 * Resolve-style multilayer compositor shared by export and preview.
 *
 * Export is fully snapshot-based. GPU preview can resolve the latest immutable editor snapshot by
 * stable track/clip id, allowing transform, opacity and transition geometry to update without
 * rebuilding the MediaCodec/GL graph. V22 transition ghost inputs keep the outgoing frame alive
 * over the beginning of the incoming clip so same-track cuts can perform true two-source motion,
 * dissolve and wipe transitions even though normal timeline clips never overlap in one V lane.
 */
@UnstableApi
internal class ResolveVideoCompositorSettings(
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val videoTracks: List<TimelineTrack>,
    private val livePreview: Boolean = false,
    private val inputsV22: List<ResolveCompositorInputV22>? = null,
) : VideoCompositorSettings {

    private val inputs: List<ResolveCompositorInputV22> = inputsV22
        ?: videoTracks.map { ResolveCompositorInputV22.TrackInput(it) }

    override fun getOutputSize(inputSizes: List<Size>): Size =
        Size(outputWidth.coerceAtLeast(1), outputHeight.coerceAtLeast(1))

    internal fun resolveOverlayState(inputId: Int, presentationTimeUs: Long): ResolveOverlayState? =
        when (val input = inputs.getOrNull(inputId) ?: return null) {
            is ResolveCompositorInputV22.TrackInput -> resolveTrackState(input, presentationTimeUs)
            is ResolveCompositorInputV22.TransitionGhostInput -> resolveGhostState(input, presentationTimeUs)
            ResolveCompositorInputV22.BlankInput -> null
        }

    private fun resolveTrackState(
        input: ResolveCompositorInputV22.TrackInput,
        presentationTimeUs: Long,
    ): ResolveOverlayState? {
        val snapshotTrack = input.snapshotTrack
        val track = if (livePreview) {
            PreviewProjectRegistry.project()?.tracks?.firstOrNull { it.id == snapshotTrack.id } ?: snapshotTrack
        } else {
            snapshotTrack
        }
        val clip = track.activeVideoClipAt(presentationTimeUs) ?: return null
        val localUs = (presentationTimeUs - clip.timelineStartUs)
            .coerceIn(0L, clip.durationUs.coerceAtLeast(0L))
        val transform = clip.transform.evaluate(localUs)
        var state = ResolveOverlayState(
            alphaScale = (clip.opacity.coerceIn(0f, 1f) * legacyTransitionAlpha(clip, localUs)).coerceIn(0f, 1f),
            backgroundX = transform.positionX,
            backgroundY = -transform.positionY,
            scaleX = transform.scaleX,
            scaleY = transform.scaleY,
            rotationDegrees = transform.rotationDegrees,
        )

        val pair = track.transitionPairForIncomingV22(clip.id)
        if (pair != null && presentationTimeUs in pair.startUs until pair.endUs) {
            val progress = ((presentationTimeUs - pair.startUs).toDouble() / pair.durationUs.toDouble())
                .toFloat()
                .coerceIn(0f, 1f)
            state = applyIncomingTransition(state, pair.style, progress)
        }
        return state
    }

    private fun resolveGhostState(
        input: ResolveCompositorInputV22.TransitionGhostInput,
        presentationTimeUs: Long,
    ): ResolveOverlayState? {
        val snapshotPair = input.pair
        val liveTrack = if (livePreview) {
            PreviewProjectRegistry.project()?.tracks?.firstOrNull { it.id == snapshotPair.trackId }
        } else {
            null
        }
        val pair = liveTrack?.transitionPairForIncomingV22(snapshotPair.incoming.id) ?: snapshotPair
        if (presentationTimeUs !in pair.startUs until pair.endUs) return null

        val outgoing = liveTrack?.clips?.firstOrNull { it.id == pair.outgoing.id } ?: pair.outgoing
        val elapsedUs = (presentationTimeUs - pair.startUs).coerceIn(0L, pair.durationUs)
        val outgoingLocalUs = (outgoing.durationUs - pair.durationUs + elapsedUs)
            .coerceIn(0L, outgoing.durationUs.coerceAtLeast(0L))
        val transform = outgoing.transform.evaluate(outgoingLocalUs)
        val progress = (elapsedUs.toDouble() / pair.durationUs.toDouble()).toFloat().coerceIn(0f, 1f)
        val base = ResolveOverlayState(
            alphaScale = outgoing.opacity.coerceIn(0f, 1f),
            backgroundX = transform.positionX,
            backgroundY = -transform.positionY,
            scaleX = transform.scaleX,
            scaleY = transform.scaleY,
            rotationDegrees = transform.rotationDegrees,
        )
        return applyOutgoingTransition(base, pair.style, progress)
    }

    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
        val state = resolveOverlayState(inputId, presentationTimeUs)
            ?: return StaticOverlaySettings.Builder().setAlphaScale(0f).build()
        val anchors = media3AnchorPlacementV22(state)

        return StaticOverlaySettings.Builder()
            .setAlphaScale(state.alphaScale)
            .setOverlayFrameAnchor(anchors.overlayX, anchors.overlayY)
            .setBackgroundFrameAnchor(anchors.backgroundX, anchors.backgroundY)
            .setScale(state.scaleX, state.scaleY)
            .setRotationDegrees(state.rotationDegrees)
            .build()
    }

    private fun legacyTransitionAlpha(clip: TimelineClip, localUs: Long): Float {
        val transition = clip.transition.normalizedFor(clip.durationUs)
        var alpha = 1f
        if (transition.fadeInUs > 0L) {
            alpha = min(alpha, localUs.toFloat() / transition.fadeInUs.toFloat())
        }
        if (transition.fadeOutUs > 0L) {
            val remainingUs = (clip.durationUs - localUs).coerceAtLeast(0L)
            alpha = min(alpha, remainingUs.toFloat() / transition.fadeOutUs.toFloat())
        }
        return alpha.coerceIn(0f, 1f)
    }

    private fun applyIncomingTransition(
        base: ResolveOverlayState,
        style: TransitionStyleV22,
        rawProgress: Float,
    ): ResolveOverlayState {
        val p = eased(rawProgress)
        return when (style) {
            TransitionStyleV22.NONE,
            TransitionStyleV22.CROSS_DISSOLVE,
            TransitionStyleV22.DIP_TO_BLACK,
            TransitionStyleV22.DIP_TO_WHITE,
            TransitionStyleV22.BLUR,
            TransitionStyleV22.FLASH,
            TransitionStyleV22.MASK_WIPE,
            TransitionStyleV22.CIRCLE_WIPE,
            TransitionStyleV22.SPLIT,
            TransitionStyleV22.LIGHT_LEAK -> base

            TransitionStyleV22.SMOOTH_CUT -> base.copy(
                scaleX = base.scaleX * (0.96f + 0.04f * p),
                scaleY = base.scaleY * (0.96f + 0.04f * p),
            )

            TransitionStyleV22.FADE -> base.copy(alphaScale = base.alphaScale * p)
            TransitionStyleV22.PUSH_LEFT -> base.copy(backgroundX = base.backgroundX + 2f * (1f - p))
            TransitionStyleV22.PUSH_RIGHT -> base.copy(backgroundX = base.backgroundX - 2f * (1f - p))
            TransitionStyleV22.PUSH_UP -> base.copy(backgroundY = base.backgroundY - 2f * (1f - p))
            TransitionStyleV22.PUSH_DOWN -> base.copy(backgroundY = base.backgroundY + 2f * (1f - p))
            TransitionStyleV22.SLIDE -> base.copy(backgroundX = base.backgroundX + 2f * (1f - p))
            TransitionStyleV22.ZOOM_IN -> base.copy(
                scaleX = base.scaleX * (0.78f + 0.22f * p),
                scaleY = base.scaleY * (0.78f + 0.22f * p),
            )
            TransitionStyleV22.ZOOM_OUT -> base.copy(
                scaleX = base.scaleX * (1.22f - 0.22f * p),
                scaleY = base.scaleY * (1.22f - 0.22f * p),
            )
            TransitionStyleV22.WHIP -> base.copy(
                backgroundX = base.backgroundX + 2f * (1f - p),
                rotationDegrees = base.rotationDegrees + 3.5f * (1f - p),
            )
            TransitionStyleV22.SPIN -> base.copy(
                scaleX = base.scaleX * (0.84f + 0.16f * p),
                scaleY = base.scaleY * (0.84f + 0.16f * p),
                rotationDegrees = base.rotationDegrees - 120f * (1f - p),
            )
        }
    }

    private fun applyOutgoingTransition(
        base: ResolveOverlayState,
        style: TransitionStyleV22,
        rawProgress: Float,
    ): ResolveOverlayState {
        val p = eased(rawProgress)
        val fade = (1f - p).coerceIn(0f, 1f)
        return when (style) {
            TransitionStyleV22.NONE -> base.copy(alphaScale = 0f)
            TransitionStyleV22.CROSS_DISSOLVE -> base.copy(alphaScale = base.alphaScale * fade)
            TransitionStyleV22.SMOOTH_CUT -> base.copy(
                alphaScale = base.alphaScale * fade,
                scaleX = base.scaleX * (1f + 0.05f * p),
                scaleY = base.scaleY * (1f + 0.05f * p),
            )
            TransitionStyleV22.DIP_TO_BLACK,
            TransitionStyleV22.DIP_TO_WHITE -> base.copy(
                alphaScale = base.alphaScale * if (p < 0.5f) 1f else ((1f - p) * 2f).coerceIn(0f, 1f),
            )
            TransitionStyleV22.FADE -> base.copy(alphaScale = base.alphaScale * fade)
            TransitionStyleV22.PUSH_LEFT -> base.copy(backgroundX = base.backgroundX - 2f * p)
            TransitionStyleV22.PUSH_RIGHT -> base.copy(backgroundX = base.backgroundX + 2f * p)
            TransitionStyleV22.PUSH_UP -> base.copy(backgroundY = base.backgroundY + 2f * p)
            TransitionStyleV22.PUSH_DOWN -> base.copy(backgroundY = base.backgroundY - 2f * p)
            TransitionStyleV22.SLIDE -> base
            TransitionStyleV22.ZOOM_IN -> base.copy(
                alphaScale = base.alphaScale * fade,
                scaleX = base.scaleX * (1f + 0.14f * p),
                scaleY = base.scaleY * (1f + 0.14f * p),
            )
            TransitionStyleV22.ZOOM_OUT -> base.copy(
                alphaScale = base.alphaScale * fade,
                scaleX = base.scaleX * (1f - 0.14f * p),
                scaleY = base.scaleY * (1f - 0.14f * p),
            )
            TransitionStyleV22.BLUR -> base.copy(alphaScale = base.alphaScale * fade)
            TransitionStyleV22.WHIP -> base.copy(
                backgroundX = base.backgroundX - 2f * p,
                rotationDegrees = base.rotationDegrees - 3.5f * p,
            )
            TransitionStyleV22.SPIN -> base.copy(
                alphaScale = base.alphaScale * fade,
                scaleX = base.scaleX * (1f - 0.16f * p),
                scaleY = base.scaleY * (1f - 0.16f * p),
                rotationDegrees = base.rotationDegrees + 120f * p,
            )
            TransitionStyleV22.FLASH -> base.copy(alphaScale = base.alphaScale * fade)
            TransitionStyleV22.MASK_WIPE,
            TransitionStyleV22.CIRCLE_WIPE,
            TransitionStyleV22.SPLIT -> base
            TransitionStyleV22.LIGHT_LEAK -> base.copy(alphaScale = base.alphaScale * fade)
        }
    }

    private fun eased(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
