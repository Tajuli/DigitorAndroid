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
 * Builds Media3 compositions for preview and export.
 *
 * Export flattens overlapping video tracks into one topmost visible stream. This keeps Transformer
 * from decoding hidden lower videos and reduces decoder/compositor pressure on phones.
 *
 * Preview deliberately keeps original video clips/sequences. CompositionPlayer has had runtime
 * issues with sequences made from synthetic overlap fragments that start at non-zero source
 * offsets. Using the original clip boundaries avoids the black/frozen preview regression while
 * preserving normal top-to-bottom video compositing and mixed A-track audio.
 */
@UnstableApi
class Media3CompositionBuilder {
    fun build(project: TimelineProject): Composition = buildExport(project)

    fun buildPreview(project: TimelineProject): Composition = buildPreviewInternal(project)

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

        // Keep original clip boundaries for CompositionPlayer. Project track order is UI order
        // (top to bottom), so the first active video sequence remains visually topmost.
        project.tracks
            .filter { it.kind == TrackKind.VIDEO && !it.muted && it.clips.isNotEmpty() }
            .forEach { track ->
                val videoBuilder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
                var cursorUs = 0L
                track.sortedClips().forEach { clip ->
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
        // Keep every A track independent so simultaneous A1/A2/etc. are mixed by Media3.
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
