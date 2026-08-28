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
import kotlin.math.max
import kotlin.math.roundToInt

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
            previous.transform == clip.transform &&
            previous.nodeAnimations == clip.nodeAnimations &&
            previous.transition == clip.transition &&
            previous.audioMix == clip.audioMix
        if (canMerge) {
            result[result.lastIndex] = previous!!.copy(sourceOutUs = clip.sourceOutUs)
        } else {
            result += clip
        }
    }
    return result
}

internal fun resolvePreviewOutputSize(
    project: TimelineProject,
    maxLongEdge: Int,
): Pair<Int, Int> {
    val sourceWidth = project.width.coerceAtLeast(2)
    val sourceHeight = project.height.coerceAtLeast(2)
    val limit = maxLongEdge.coerceAtLeast(2)
    val longest = max(sourceWidth, sourceHeight)
    if (longest <= limit) return sourceWidth.evenAtLeastTwo() to sourceHeight.evenAtLeastTwo()

    val scale = limit.toFloat() / longest.toFloat()
    val width = (sourceWidth * scale).roundToInt().evenAtLeastTwo()
    val height = (sourceHeight * scale).roundToInt().evenAtLeastTwo()
    return width to height
}

private fun Int.evenAtLeastTwo(): Int {
    val safe = coerceAtLeast(2)
    return if (safe % 2 == 0) safe else (safe - 1).coerceAtLeast(2)
}

@UnstableApi
class Media3CompositionBuilder {
    fun build(project: TimelineProject): Composition = buildExport(project)

    fun buildPreview(project: TimelineProject): Composition = buildPreviewInternal(project)

    /**
     * Video-only real-time preview composition. The decoder/GL graph is long-lived; transform,
     * opacity and color state can resolve newer immutable editor snapshots without rebuilding it.
     */
    fun buildGpuPreview(
        project: TimelineProject,
        maxLongEdge: Int = 720,
    ): Composition {
        val problems = project.validate()
        require(problems.isEmpty()) { problems.joinToString("; ") }

        val videoTracks = resolveCompositionVideoTracks(project)
        require(videoTracks.isNotEmpty()) { "Timeline has no playable video" }
        val sequences = videoTracks.map { track ->
            buildCompositedVideoSequence(
                project = project,
                track = track,
                forPreview = true,
            )
        }
        val (outputWidth, outputHeight) = resolvePreviewOutputSize(project, maxLongEdge)
        return Composition.Builder(sequences)
            .setVideoCompositorSettings(
                ResolveVideoCompositorSettings(
                    outputWidth = outputWidth,
                    outputHeight = outputHeight,
                    videoTracks = videoTracks,
                    livePreview = true,
                ),
            )
            .build()
    }

    /** Legacy mixed-audio composition retained for export/tests. Realtime multitrack preview uses
     * [buildAudioTrackPreview] so CompositionPlayer only has to play one sequence per instance. */
    fun buildAudioPreview(project: TimelineProject): Composition {
        val problems = project.validate()
        require(problems.isEmpty()) { problems.joinToString("; ") }

        val sequences = mutableListOf<EditedMediaItemSequence>()
        addAudioSequences(project, sequences, forPreview = true)
        require(sequences.isNotEmpty()) { "Timeline has no playable audio" }
        return Composition.Builder(sequences).build()
    }

    /**
     * Builds exactly one audio sequence for one A track. This is the realtime-preview primitive:
     * every A track gets its own audio-only CompositionPlayer and Android mixes their outputs.
     */
    fun buildAudioTrackPreview(project: TimelineProject, trackId: String): Composition {
        val problems = project.validate()
        require(problems.isEmpty()) { problems.joinToString("; ") }

        val track = project.tracks.firstOrNull { candidate ->
            candidate.id == trackId &&
                candidate.kind == TrackKind.AUDIO &&
                !candidate.muted &&
                candidate.clips.isNotEmpty()
        } ?: error("Audio preview track is missing, muted, or empty")

        val sequence = buildAudioSequence(
            project = project,
            track = track,
            forPreview = true,
            extendToProjectDuration = true,
        )
        return Composition.Builder(listOf(sequence)).build()
    }

    private fun buildExport(project: TimelineProject): Composition {
        val problems = project.validate()
        require(problems.isEmpty()) { problems.joinToString("; ") }

        val sequences = mutableListOf<EditedMediaItemSequence>()
        val videoTracks = resolveCompositionVideoTracks(project)

        videoTracks.forEach { track ->
            sequences += buildCompositedVideoSequence(project, track, forPreview = false)
        }
        addAudioSequences(project, sequences, forPreview = false)

        require(sequences.isNotEmpty()) { "Timeline is empty" }
        val builder = Composition.Builder(sequences)
        if (videoTracks.isNotEmpty()) {
            builder.setVideoCompositorSettings(
                ResolveVideoCompositorSettings(
                    outputWidth = project.width,
                    outputHeight = project.height,
                    videoTracks = videoTracks,
                ),
            )
            val textEffects = projectTextEffects(project)
            if (textEffects.isNotEmpty()) {
                builder.setEffects(Effects(emptyList(), textEffects))
            }
        }
        return builder.build()
    }

    private fun buildCompositedVideoSequence(
        project: TimelineProject,
        track: TimelineTrack,
        forPreview: Boolean,
    ): EditedMediaItemSequence {
        val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
        var cursorUs = 0L
        val clips = if (forPreview) coalescePreviewClips(track.clips) else track.sortedClips()
        clips.forEach { clip ->
            if (clip.timelineStartUs > cursorUs) {
                builder.addGap(clip.timelineStartUs - cursorUs)
            }
            builder.addItem(
                toEditedMediaItem(
                    clip = clip,
                    kind = TrackKind.VIDEO,
                    forPreview = forPreview,
                    compositorOwnsGeometry = true,
                ),
            )
            cursorUs = clip.timelineEndUs
        }
        if (project.durationUs > cursorUs) {
            builder.addGap(project.durationUs - cursorUs)
        }
        return builder.build()
    }

    private fun buildPreviewInternal(project: TimelineProject): Composition {
        val problems = project.validate()
        require(problems.isEmpty()) { problems.joinToString("; ") }

        val sequences = mutableListOf<EditedMediaItemSequence>()
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
        val builder = Composition.Builder(sequences)
        val textEffects = projectTextEffects(project)
        if (textEffects.isNotEmpty() && project.tracks.any { it.kind == TrackKind.VIDEO && it.clips.isNotEmpty() }) {
            builder.setEffects(Effects(emptyList(), textEffects))
        }
        return builder.build()
    }

    private fun addAudioSequences(
        project: TimelineProject,
        sequences: MutableList<EditedMediaItemSequence>,
        forPreview: Boolean,
    ) {
        project.tracks
            .filter { it.kind == TrackKind.AUDIO && !it.muted && it.clips.isNotEmpty() }
            .forEach { track ->
                sequences += buildAudioSequence(
                    project = project,
                    track = track,
                    forPreview = forPreview,
                    extendToProjectDuration = false,
                )
            }
    }

    private fun buildAudioSequence(
        project: TimelineProject,
        track: TimelineTrack,
        forPreview: Boolean,
        extendToProjectDuration: Boolean,
    ): EditedMediaItemSequence {
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
        if (extendToProjectDuration && project.durationUs > cursorUs) {
            audioBuilder.addGap(project.durationUs - cursorUs)
        }
        return audioBuilder.build()
    }

    private fun toEditedMediaItem(
        clip: TimelineClip,
        kind: TrackKind,
        forPreview: Boolean,
        compositorOwnsGeometry: Boolean = false,
    ): EditedMediaItem {
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionUs(clip.sourceInUs)
            .setEndPositionUs(clip.sourceOutUs)
            .build()
        val mediaItem = MediaItem.Builder()
            .setUri(clip.uri)
            .setClippingConfiguration(clipping)
            .build()

        val builder = EditedMediaItem.Builder(mediaItem)
            .setDurationUs(clip.durationUs)
        if (kind == TrackKind.VIDEO) {
            val videoEffects = when {
                forPreview && compositorOwnsGeometry -> SharedVideoPipeline.compositedPreviewEffectsFor(clip)
                forPreview -> SharedVideoPipeline.previewEffectsFor(clip)
                compositorOwnsGeometry -> SharedVideoPipeline.compositedExportEffectsFor(clip)
                else -> SharedVideoPipeline.effectsFor(clip)
            }
            builder.setEffects(Effects(emptyList(), videoEffects))
        } else {
            val audioEffects = audioProcessorsFor(clip)
            if (audioEffects.isNotEmpty()) {
                builder.setEffects(Effects(audioEffects, emptyList()))
            }
        }
        return builder.build()
    }
}
