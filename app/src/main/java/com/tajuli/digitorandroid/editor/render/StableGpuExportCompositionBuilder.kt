package com.tajuli.digitorandroid.editor.render

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
 * Conservative export composition builder.
 *
 * A single video track does not need Media3's multi-input VideoCompositor at all. On devices where
 * the multi-input compositor/driver path is fragile, routing a one-layer timeline through it adds
 * GPU surfaces and synchronization without any visual benefit. The single-track path below uses
 * the long-standing item-effect pipeline instead: decode -> shared color/spatial/transform effects
 * -> encoder. Two or more video tracks still delegate to the Resolve-style compositor builder.
 */
@UnstableApi
internal class StableGpuExportCompositionBuilder(
    private val multiTrackBuilder: Media3CompositionBuilder = Media3CompositionBuilder(),
) {
    fun build(project: TimelineProject): Composition {
        val problems = project.validate()
        require(problems.isEmpty()) { problems.joinToString("; ") }

        val videoTracks = project.tracks.filter { track ->
            track.kind == TrackKind.VIDEO && !track.muted && track.clips.isNotEmpty()
        }
        if (videoTracks.size != 1) return multiTrackBuilder.build(project)

        val sequences = mutableListOf<EditedMediaItemSequence>()
        sequences += buildSingleVideoSequence(project, videoTracks.single())
        project.tracks
            .filter { track -> track.kind == TrackKind.AUDIO && !track.muted && track.clips.isNotEmpty() }
            .forEach { track -> sequences += buildAudioSequence(project, track) }

        return Composition.Builder(sequences).build()
    }

    private fun buildSingleVideoSequence(
        project: TimelineProject,
        track: TimelineTrack,
    ): EditedMediaItemSequence {
        val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
        var cursorUs = 0L
        track.sortedClips().forEach { clip ->
            if (clip.timelineStartUs > cursorUs) builder.addGap(clip.timelineStartUs - cursorUs)
            builder.addItem(videoItem(clip))
            cursorUs = clip.timelineEndUs
        }
        if (project.durationUs > cursorUs) builder.addGap(project.durationUs - cursorUs)
        return builder.build()
    }

    private fun buildAudioSequence(
        project: TimelineProject,
        track: TimelineTrack,
    ): EditedMediaItemSequence {
        val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
        var cursorUs = 0L
        track.sortedClips().forEach { clip ->
            if (clip.timelineStartUs > cursorUs) builder.addGap(clip.timelineStartUs - cursorUs)
            builder.addItem(baseItem(clip))
            cursorUs = clip.timelineEndUs
        }
        if (project.durationUs > cursorUs) builder.addGap(project.durationUs - cursorUs)
        return builder.build()
    }

    private fun videoItem(clip: TimelineClip): EditedMediaItem =
        EditedMediaItem.Builder(mediaItem(clip))
            .setDurationUs(clip.durationUs)
            .setEffects(Effects(emptyList(), SharedVideoPipeline.effectsFor(clip)))
            .build()

    private fun baseItem(clip: TimelineClip): EditedMediaItem =
        EditedMediaItem.Builder(mediaItem(clip))
            .setDurationUs(clip.durationUs)
            .build()

    private fun mediaItem(clip: TimelineClip): MediaItem = MediaItem.Builder()
        .setUri(clip.uri)
        .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(clip.sourceInUs / 1000L)
                .setEndPositionMs(clip.sourceOutUs / 1000L)
                .build(),
        )
        .build()
}
