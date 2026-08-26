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
 * Coalesces adjacent clips that are still presentation-identical fragments of the same source.
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
 * Media3 composition builder shared by export and editor playback.
 *
 * Export and final GPU preview use the same Resolve-style layout: every visible V track is a
 * separate sequence, every sequence is padded to the project duration, color/node processing runs
 * before composition, and transform/opacity is applied by the same compositor math. The preview
 * variant only swaps immutable export snapshots for live [com.tajuli.digitorandroid.editor.model.PreviewClipState]
 * reads so ordinary edits can update without recreating the player.
 */
@UnstableApi
class Media3CompositionBuilder {
    fun build(project: TimelineProject): Composition = buildExport(project)

    /** Legacy lightweight preview retained for callers outside the final-output viewer. */
    fun buildPreview(project: TimelineProject): Composition = buildPreviewInternal(project)

    /**
     * Video-only CompositionPlayer input for the editor's final-output viewer.
     *
     * This intentionally mirrors [buildExport] instead of using a separate approximation. Audio is
     * excluded because the editor already owns a stable audio-only CompositionPlayer clock/mixer.
     */
    fun buildFinalVideoPreview(project: TimelineProject): Composition {
        val problems = project.validate()
        require(problems.isEmpty()) { problems.joinToString("; ") }

        val videoTracks = resolveCompositionVideoTracks(project)
        require(videoTracks.isNotEmpty()) { "Timeline has no playable video" }

        val sequences = videoTracks.map { track ->
            buildCompositedVideoSequence(
                project = project,
                track = track,
                finalOutputPreview = true,
            )
        }
        return Composition.Builder(sequences)
            .setVideoCompositorSettings(
                CompositionPreviewResolveVideoCompositorSettings(
                    outputWidth = project.width,
                    outputHeight = project.height,
                    videoTracks = videoTracks,
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
            sequences += buildCompositedVideoSequence(
                project = project,
                track = track,
                finalOutputPreview = false,
            )
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
        finalOutputPreview: Boolean,
    ): EditedMediaItemSequence {
        val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
        var cursorUs = 0L
        track.sortedClips().forEach { clip ->
            if (clip.timelineStartUs > cursorUs) {
                builder.addGap(clip.timelineStartUs - cursorUs)
            }
            builder.addItem(
                toEditedMediaItem(
                    clip = clip,
                    kind = TrackKind.VIDEO,
                    forPreview = false,
                    compositorOwnsGeometry = true,
                    finalOutputPreview = finalOutputPreview,
                ),
            )
            cursorUs = clip.timelineEndUs
        }
        if (project.durationUs > cursorUs) {
            // Keep this compositor input alive until project end. During gaps its alpha is 0.
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
        finalOutputPreview: Boolean = false,
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
                finalOutputPreview -> SharedVideoPipeline.finalOutputPreviewEffectsFor(clip)
                forPreview -> SharedVideoPipeline.previewEffectsFor(clip)
                compositorOwnsGeometry -> SharedVideoPipeline.compositedExportEffectsFor(clip)
                else -> SharedVideoPipeline.effectsFor(clip)
            }
            builder.setEffects(Effects(emptyList(), videoEffects))
        }
        return builder.build()
    }
}
