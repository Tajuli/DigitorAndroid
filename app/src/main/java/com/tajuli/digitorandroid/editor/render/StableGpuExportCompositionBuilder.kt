package com.tajuli.digitorandroid.editor.render

import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind

/**
 * Stable export composition builder.
 *
 * A normal one-layer edit does not need a video compositor. Keeping that export on Media3's
 * SingleInputVideoGraph is materially safer for camera Log/HEVC media because it avoids the
 * multi-input GL compositor and any sentinel video input while still running Digitor's per-clip
 * transform, color, qualifier and node effects.
 *
 * We only fall back to the shared compositor export when the timeline really needs compositor-owned
 * alpha/fade behavior or contains multiple visible video tracks.
 */
@UnstableApi
internal class StableGpuExportCompositionBuilder(
    private val sharedBuilder: Media3CompositionBuilder = Media3CompositionBuilder(),
) {
    fun build(project: TimelineProject): Composition =
        if (canUseDirectSingleInputExport(project)) {
            buildDirectSingleInput(project)
        } else {
            sharedBuilder.build(project)
        }

    private fun buildDirectSingleInput(project: TimelineProject): Composition {
        val problems = project.validate()
        require(problems.isEmpty()) { problems.joinToString("; ") }

        val videoTrack = resolveCompositionVideoTracks(project).single()
        val sequences = mutableListOf<EditedMediaItemSequence>()
        sequences += buildVideoSequence(project, videoTrack)

        project.tracks
            .filter { it.kind == TrackKind.AUDIO && !it.muted && it.clips.isNotEmpty() }
            .forEach { track -> sequences += buildAudioSequence(project, track) }

        val builder = Composition.Builder(sequences)

        // Digitor owns Log/HDR -> working-space conversion. If a camera file carries HDR-style
        // metadata, keep the decoder output as editable SDR code values instead of asking Media3 to
        // preserve an HDR pipeline that ultimately targets H.264 SDR output.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setHdrMode(Composition.HDR_MODE_EXPERIMENTAL_FORCE_INTERPRET_HDR_AS_SDR)
        }

        val textEffects = projectTextEffects(project)
        if (textEffects.isNotEmpty()) {
            builder.setEffects(Effects(emptyList(), textEffects))
        }
        return builder.build()
    }

    private fun buildVideoSequence(
        project: TimelineProject,
        track: TimelineTrack,
    ): EditedMediaItemSequence {
        val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
        var cursorUs = 0L
        track.sortedClips().forEach { clip ->
            if (clip.timelineStartUs > cursorUs) {
                builder.addGap(clip.timelineStartUs - cursorUs)
            }
            builder.addItem(videoItem(clip))
            cursorUs = clip.timelineEndUs
        }
        if (project.durationUs > cursorUs) {
            builder.addGap(project.durationUs - cursorUs)
        }
        return builder.build()
    }

    private fun buildAudioSequence(
        project: TimelineProject,
        track: TimelineTrack,
    ): EditedMediaItemSequence {
        val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
        var cursorUs = 0L
        track.sortedClips().forEach { clip ->
            if (clip.timelineStartUs > cursorUs) {
                builder.addGap(clip.timelineStartUs - cursorUs)
            }
            builder.addItem(audioItem(clip))
            cursorUs = clip.timelineEndUs
        }
        if (project.durationUs > cursorUs) {
            builder.addGap(project.durationUs - cursorUs)
        }
        return builder.build()
    }

    private fun videoItem(clip: TimelineClip): EditedMediaItem {
        val mediaItem = clippedMediaItem(clip)
        return EditedMediaItem.Builder(mediaItem)
            .setDurationUs(clip.durationUs)
            // Direct single-input path owns geometry in the clip effect instead of the compositor.
            .setEffects(Effects(emptyList(), SharedVideoPipeline.effectsFor(clip)))
            .build()
    }

    private fun audioItem(clip: TimelineClip): EditedMediaItem {
        val mediaItem = clippedMediaItem(clip)
        val builder = EditedMediaItem.Builder(mediaItem).setDurationUs(clip.durationUs)
        val processors = audioProcessorsFor(clip)
        if (processors.isNotEmpty()) {
            builder.setEffects(Effects(processors, emptyList()))
        }
        return builder.build()
    }

    private fun clippedMediaItem(clip: TimelineClip): MediaItem {
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionUs(clip.sourceInUs)
            .setEndPositionUs(clip.sourceOutUs)
            .build()
        return MediaItem.Builder()
            .setUri(clip.uri)
            .setClippingConfiguration(clipping)
            .build()
    }
}

/**
 * SingleInputVideoGraph is safe when compositor-only alpha/fades are not needed. Clip transforms,
 * node effects, HSL qualifier, RGB curves and Input Color remain per-item effects and are supported.
 */
internal fun canUseDirectSingleInputExport(project: TimelineProject): Boolean {
    val videoTracks = resolveCompositionVideoTracks(project)
    if (videoTracks.size != 1) return false
    return videoTracks.single().clips.all { clip ->
        clip.opacity >= .9999f && clip.transition.isIdentity
    }
}
