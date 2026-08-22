package com.tajuli.digitorandroid.editor.render

import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.RgbAdjustment
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.tajuli.digitorandroid.editor.model.ColorGrade
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

        val effects = if (kind == TrackKind.VIDEO) buildVideoEffects(clip.colorGrade) else null
        val builder = EditedMediaItem.Builder(mediaItem)
            .setDurationUs(clip.durationUs)
        if (effects != null) builder.setEffects(effects)
        return builder.build()
    }

    private fun buildVideoEffects(grade: ColorGrade): Effects {
        val video = mutableListOf<Effect>()
        if (grade.redScale != 1f || grade.greenScale != 1f || grade.blueScale != 1f) {
            video += RgbAdjustment.Builder()
                .setRedScale(grade.redScale.coerceAtLeast(0f))
                .setGreenScale(grade.greenScale.coerceAtLeast(0f))
                .setBlueScale(grade.blueScale.coerceAtLeast(0f))
                .build()
        }
        if (grade.hueDegrees != 0f || grade.saturationDelta != 0f || grade.lightnessDelta != 0f) {
            video += HslAdjustment.Builder()
                .adjustHue(grade.hueDegrees)
                .adjustSaturation(grade.saturationDelta.coerceIn(-100f, 100f))
                .adjustLightness(grade.lightnessDelta.coerceIn(-100f, 100f))
                .build()
        }
        return Effects(emptyList(), video)
    }
}
