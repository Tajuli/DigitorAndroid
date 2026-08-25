package com.tajuli.digitorandroid.editor.render

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind

/**
 * Coalesces adjacent clips that are still presentation-identical fragments of the same source.
 *
 * A timeline split creates exactly this shape: left.sourceOut == right.sourceIn and the copied
 * grading/transform state is identical. Keeping that artificial boundary in CompositionPlayer is
 * risky on some decoders because video items with non-zero start offsets can fail during preview.
 * Animated transforms intentionally prevent coalescing once their rebased keyframes differ.
 */
internal fun coalescePreviewClips(clips: List<TimelineClip>): List<TimelineClip> {
    if (clips.size < 2) return clips
    val sorted = clips.sortedBy { it.timelineStartUs }
    val result = mutableListOf<TimelineClip>()
    sorted.forEach { clip ->
        val previous = result.lastOrNull()
        val canMerge = previous != null &&
            previous.uri == clip.uri &&
            previous.timelineEndUs == clip.timelineStartUs &&
            previous.sourceOutUs == clip.sourceInUs &&
            previous.opacity == clip.opacity &&
            previous.colorGrade == clip.colorGrade &&
            previous.nodeGraph == clip.nodeGraph &&
            previous.transform == clip.transform
        if (canMerge) {
            result[result.lastIndex] = previous!!.copy(sourceOutUs = clip.sourceOutUs)
        } else {
            result += clip
        }
    }
    return result
}

/**
 * Builds one Media3 sequence per Digitor timeline track.
 *
 * Video tracks are deliberately NOT flattened. Every active V track is registered as an independent
 * composition input, so overlapping clips are decoded, transformed, graded and alpha-composited in
 * real time. The same sequence topology and compositor settings are shared by CompositionPlayer
 * preview and Transformer export.
 */
@UnstableApi
class Media3CompositionBuilder {
    fun build(project: TimelineProject): Composition = buildComposition(project, forPreview = false)

    fun buildPreview(project: TimelineProject): Composition = buildComposition(project, forPreview = true)

    fun buildAudioPreview(project: TimelineProject): Composition {
        val problems = project.validate()
        require(problems.isEmpty()) { problems.joinToString("; ") }

        val sequences = mutableListOf<EditedMediaItemSequence>()
        addAudioSequences(project, sequences, forPreview = true)
        require(sequences.isNotEmpty()) { "Timeline has no playable audio" }
        return Composition.Builder(sequences).build()
    }

    private fun buildComposition(project: TimelineProject, forPreview: Boolean): Composition {
        val problems = project.validate()
        require(problems.isEmpty()) { problems.joinToString("; ") }

        val sequences = mutableListOf<EditedMediaItemSequence>()
        val videoTracks = compositionVideoTracks(project)

        videoTracks.forEach { track ->
            sequences += buildVideoSequence(project, track, forPreview)
        }
        addAudioSequences(project, sequences, forPreview)

        require(sequences.isNotEmpty()) { "Timeline is empty" }
        val builder = Composition.Builder(sequences)
        if (videoTracks.isNotEmpty()) {
            builder.setVideoCompositorSettings(
                DigitorVideoCompositorSettings(project.width, project.height, videoTracks),
            )
        }
        return builder.build()
    }

    private fun buildVideoSequence(
        project: TimelineProject,
        track: TimelineTrack,
        forPreview: Boolean,
    ): EditedMediaItemSequence {
        val videoBuilder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
        var cursorUs = 0L
        val clips = if (forPreview) coalescePreviewClips(track.clips) else track.sortedClips()
        clips.forEach { clip ->
            if (clip.timelineStartUs > cursorUs) {
                videoBuilder.addGap(clip.timelineStartUs - cursorUs)
            }
            videoBuilder.addItem(toEditedMediaItem(project, clip, TrackKind.VIDEO, forPreview))
            cursorUs = clip.timelineEndUs
        }
        return videoBuilder.build()
    }

    private fun addAudioSequences(
        project: TimelineProject,
        sequences: MutableList<EditedMediaItemSequence>,
        forPreview: Boolean,
    ) {
        project.tracks
            .filter { it.kind == TrackKind.AUDIO && !it.muted && it.clips.isNotEmpty() }
            .forEach { track ->
                val audioBuilder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
                var cursorUs = 0L
                val clips = if (forPreview) coalescePreviewClips(track.clips) else track.sortedClips()
                clips.forEach { clip ->
                    if (clip.timelineStartUs > cursorUs) {
                        audioBuilder.addGap(clip.timelineStartUs - cursorUs)
                    }
                    audioBuilder.addItem(toEditedMediaItem(project, clip, TrackKind.AUDIO, forPreview))
                    cursorUs = clip.timelineEndUs
                }
                sequences += audioBuilder.build()
            }
    }

    private fun toEditedMediaItem(
        project: TimelineProject,
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
            val videoEffects = buildList {
                // Give every compositor input the same transparent project-sized canvas first.
                // The following ClipTransformEffect then operates in normalized project space.
                add(
                    Presentation.createForWidthAndHeight(
                        project.width,
                        project.height,
                        Presentation.LAYOUT_SCALE_TO_FIT,
                    ),
                )
                if (forPreview) {
                    addAll(SharedVideoPipeline.compositionPreviewEffectsFor(clip))
                } else {
                    addAll(SharedVideoPipeline.effectsFor(clip))
                }
            }
            builder.setEffects(Effects(emptyList(), videoEffects))
        }
        return builder.build()
    }
}
