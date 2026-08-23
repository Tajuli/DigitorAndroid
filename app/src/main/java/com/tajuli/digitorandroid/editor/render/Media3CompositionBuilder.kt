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
 * Coalesces adjacent clips that are still presentation-identical fragments of the same source.
 *
 * A timeline split creates exactly this shape: left.sourceOut == right.sourceIn and the copied
 * grading state is identical. Keeping that artificial boundary in CompositionPlayer is risky on
 * current Media3 releases because video items with non-zero start offsets can fail during preview.
 * The timeline model stays split; only the preview sequence is simplified back to a continuous
 * media item until the user makes the halves meaningfully different.
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
            previous.nodeGraph == clip.nodeGraph
        if (canMerge) {
            result[result.lastIndex] = previous!!.copy(sourceOutUs = clip.sourceOutUs)
        } else {
            result += clip
        }
    }
    return result
}

/**
 * Builds Media3 compositions for preview and export.
 *
 * Export flattens overlapping video tracks into one topmost visible stream. This keeps Transformer
 * from decoding hidden lower videos and reduces decoder/compositor pressure on phones.
 *
 * The current editor uses a single-clip ExoPlayer for video preview. CompositionPlayer is retained
 * only for audio preview, where independent A tracks are mixed without creating multiple video
 * decoders/compositors. [buildPreview] remains available for compatibility, while
 * [buildAudioPreview] is the stable editor playback path.
 */
@UnstableApi
class Media3CompositionBuilder {
    fun build(project: TimelineProject): Composition = buildExport(project)

    fun buildPreview(project: TimelineProject): Composition = buildPreviewInternal(project)

    fun buildAudioPreview(project: TimelineProject): Composition {
        val problems = project.validate()
        require(problems.isEmpty()) { problems.joinToString("; ") }

        val sequences = mutableListOf<EditedMediaItemSequence>()
        addAudioSequences(project, sequences, forPreview = true)
        require(sequences.isNotEmpty()) { "Timeline has no playable audio" }
        return Composition.Builder(sequences).build()
    }

    private fun buildExport(project: TimelineProject): Composition {
        val problems = project.validate()
        require(problems.isEmpty()) { problems.joinToString("; ") }

        val sequences = mutableListOf<EditedMediaItemSequence>()

        // Export only the video that is actually visible. Hidden lower tracks are trimmed out.
        val visibleVideo = project.visibleVideoSegments()
        if (visibleVideo.isNotEmpty()) {
            val videoBuilder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
            var cursorUs = 0L
            visibleVideo.forEach { segment ->
                if (segment.timelineStartUs > cursorUs) {
                    videoBuilder.addGap(segment.timelineStartUs - cursorUs)
                }
                val fragment = segment.asTimelineClip()
                videoBuilder.addItem(toEditedMediaItem(fragment, TrackKind.VIDEO, forPreview = false))
                cursorUs = segment.timelineEndUs
            }
            sequences += videoBuilder.build()
        }

        addAudioSequences(project, sequences, forPreview = false)

        require(sequences.isNotEmpty()) { "Timeline is empty" }
        return Composition.Builder(sequences).build()
    }

    private fun buildPreviewInternal(project: TimelineProject): Composition {
        val problems = project.validate()
        require(problems.isEmpty()) { problems.joinToString("; ") }

        val sequences = mutableListOf<EditedMediaItemSequence>()

        // Compatibility multitrack preview path. The editor itself no longer uses this for video.
        project.tracks
            .filter { it.kind == TrackKind.VIDEO && !it.muted && it.clips.isNotEmpty() }
            .forEach { track ->
                val videoBuilder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
                var cursorUs = 0L
                coalescePreviewClips(track.clips).forEach { clip ->
                    if (clip.timelineStartUs > cursorUs) {
                        videoBuilder.addGap(clip.timelineStartUs - cursorUs)
                    }
                    videoBuilder.addItem(toEditedMediaItem(clip, TrackKind.VIDEO, forPreview = true))
                    cursorUs = clip.timelineEndUs
                }
                sequences += videoBuilder.build()
            }

        addAudioSequences(project, sequences, forPreview = true)

        require(sequences.isNotEmpty()) { "Timeline is empty" }
        return Composition.Builder(sequences).build()
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
                    audioBuilder.addItem(toEditedMediaItem(clip, TrackKind.AUDIO, forPreview))
                    cursorUs = clip.timelineEndUs
                }
                sequences += audioBuilder.build()
            }
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
