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
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.visibleVideoSegments

/**
 * Builds the Media3 composition used by both preview and export.
 *
 * Video is flattened to one non-overlapping visible sequence before it reaches Media3: project
 * track order is top-to-bottom, so an active upper clip hides lower clips for that interval. This
 * avoids simultaneously decoding/rendering video that can never be seen and prevents overlap-heavy
 * timelines from exhausting decoder/compositor resources on mobile devices.
 *
 * Audio stays as one sequence per unmuted A track, so overlapping audio tracks are mixed by Media3.
 */
@UnstableApi
class Media3CompositionBuilder {
    fun build(project: TimelineProject): Composition = buildInternal(project, forPreview = false)

    fun buildPreview(project: TimelineProject): Composition = buildInternal(project, forPreview = true)

    private fun buildInternal(project: TimelineProject, forPreview: Boolean): Composition {
        val problems = project.validate()
        require(problems.isEmpty()) { problems.joinToString("; ") }

        val sequences = mutableListOf<EditedMediaItemSequence>()

        // One decoder-visible video stream. Hidden lower tracks are trimmed out at overlap bounds.
        val visibleVideo = project.visibleVideoSegments()
        if (visibleVideo.isNotEmpty()) {
            val videoBuilder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
            var cursorUs = 0L
            visibleVideo.forEach { segment ->
                if (segment.timelineStartUs > cursorUs) {
                    videoBuilder.addGap(segment.timelineStartUs - cursorUs)
                }
                val fragment = segment.asTimelineClip()
                videoBuilder.addItem(toEditedMediaItem(fragment, TrackKind.VIDEO, forPreview))
                cursorUs = segment.timelineEndUs
            }
            sequences += videoBuilder.build()
        }

        // Keep every audio track independent. Media3 mixes simultaneous audio sequences.
        project.tracks
            .filter { it.kind == TrackKind.AUDIO && !it.muted && it.clips.isNotEmpty() }
            .forEach { track ->
                val audioBuilder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
                var cursorUs = 0L
                track.sortedClips().forEach { clip ->
                    if (clip.timelineStartUs > cursorUs) {
                        audioBuilder.addGap(clip.timelineStartUs - cursorUs)
                    }
                    audioBuilder.addItem(toEditedMediaItem(clip, TrackKind.AUDIO, forPreview))
                    cursorUs = clip.timelineEndUs
                }
                sequences += audioBuilder.build()
            }

        require(sequences.isNotEmpty()) { "Timeline is empty" }
        return Composition.Builder(sequences).build()
    }

    private fun toEditedMediaItem(
        clip: TimelineClip,
        kind: TrackKind,
        forPreview: Boolean,
    ): EditedMediaItem {
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(clip.sourceInUs / 1000L)
            .setEndPositionMs(clip.sourceOutUs / 1000L)
            .build()
        val mediaItem = MediaItem.Builder()
            .setUri(clip.uri)
            .setClippingConfiguration(clipping)
            .build()

        val builder = EditedMediaItem.Builder(mediaItem)
            .setDurationUs(clip.durationUs)
        if (kind == TrackKind.VIDEO) {
            val videoEffects = if (forPreview) {
                SharedColorPipeline.previewEffectsFor(clip)
            } else {
                SharedColorPipeline.effectsFor(clip)
            }
            builder.setEffects(Effects(emptyList(), videoEffects))
        }
        return builder.build()
    }
}
