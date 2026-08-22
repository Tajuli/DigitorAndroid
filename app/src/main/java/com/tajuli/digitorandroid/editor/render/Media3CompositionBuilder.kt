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

@UnstableApi
class Media3CompositionBuilder {
    fun build(project: TimelineProject): Composition {
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
                    builder.addItem(toEditedMediaItem(clip, track.kind))
                    cursorUs = clip.timelineEndUs
                }
                builder.build()
            }

        require(sequences.isNotEmpty()) { "Timeline is empty" }
        return Composition.Builder(sequences).build()
    }

    private fun toEditedMediaItem(clip: TimelineClip, kind: TrackKind): EditedMediaItem {
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
            builder.setEffects(
                Effects(
                    emptyList(),
                    SharedColorPipeline.effectsFor(clip),
                ),
            )
        }
        return builder.build()
    }
}
