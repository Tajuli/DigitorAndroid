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

/**
 * Builds the Media3 multitrack composition used by both preview and export.
 *
 * TimelineProject.tracks is deliberately kept in UI order (top to bottom). Media3's video
 * compositor treats the first video source as topmost, while separate audio sequences are mixed.
 */
@UnstableApi
class Media3CompositionBuilder {
    fun build(project: TimelineProject): Composition = buildInternal(project, forPreview = false)

    fun buildPreview(project: TimelineProject): Composition = buildInternal(project, forPreview = true)

    private fun buildInternal(project: TimelineProject, forPreview: Boolean): Composition {
        val problems = project.validate()
        require(problems.isEmpty()) { problems.joinToString("; ") }

        val sequences = project.tracks
            .filter { !it.muted && it.clips.isNotEmpty() }
            .map { track ->
                val trackTypes = when (track.kind) {
                    TrackKind.VIDEO -> setOf(C.TRACK_TYPE_VIDEO)
                    TrackKind.AUDIO -> setOf(C.TRACK_TYPE_AUDIO)
                }
                val builder = EditedMediaItemSequence.Builder(trackTypes)
                var cursorUs = 0L
                track.sortedClips().forEach { clip ->
                    if (clip.timelineStartUs > cursorUs) {
                        builder.addGap(clip.timelineStartUs - cursorUs)
                    }
                    builder.addItem(toEditedMediaItem(clip, track.kind, forPreview))
                    cursorUs = clip.timelineEndUs
                }
                builder.build()
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
