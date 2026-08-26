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
            previous.nodeAnimations == clip.nodeAnimations
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
        // Coalescing must only happen when presentation state is identical. This is fine at graph
        // construction time; later visual slider changes are read live by preview effects/compositor.
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
        compositorOwnsGeometry: Boolean = false,
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
            val videoEffects = when {
                forPreview && compositorOwnsGeometry -> SharedVideoPipeline.compositedPreviewEffectsFor(clip)
                forPreview -> SharedVideoPipeline.previewEffectsFor(clip)
                compositorOwnsGeometry -> SharedVideoPipeline.compositedExportEffectsFor(clip)
                else -> SharedVideoPipeline.effectsFor(clip)
            }
            builder.setEffects(Effects(emptyList(), videoEffects))
        }
        return builder.build()
    }
}
