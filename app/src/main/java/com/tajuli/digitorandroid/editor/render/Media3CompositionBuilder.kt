package com.tajuli.digitorandroid.editor.render

import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.Effect
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
import com.tajuli.digitorandroid.editor.model.hasCompositionOverlaysV19
import kotlin.math.max
import kotlin.math.roundToInt

private const val SINGLE_LAYER_GAP_SENTINEL_ID = "__digitor_gap_sentinel"
private const val BLANK_PNG_DATA_URI =
    "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEElEQVR4nGNgYGD4D8UQBgAd9AP9yOH2qAAAAABJRU5ErkJggg=="

internal fun coalescePreviewClips(clips: List<TimelineClip>): List<TimelineClip> {
    if (clips.size < 2) return clips
    val sorted = clips.sortedBy { it.timelineStartUs }
    val result = mutableListOf<TimelineClip>()
    sorted.forEach { clip ->
        val previous = result.lastOrNull()
        val canMerge = previous != null &&
            !previous.isImageV21 && !clip.isImageV21 &&
            previous.uri == clip.uri &&
            previous.timelineEndUs == clip.timelineStartUs &&
            previous.sourceOutUs == clip.sourceInUs &&
            previous.opacity == clip.opacity &&
            previous.colorGrade == clip.colorGrade &&
            previous.nodeGraph == clip.nodeGraph &&
            previous.transform == clip.transform &&
            previous.nodeAnimations == clip.nodeAnimations &&
            previous.transition == clip.transition &&
            previous.audioMix == clip.audioMix &&
            previous.inputColorProfileV1 == clip.inputColorProfileV1 &&
            previous.visualMediaV21 == clip.visualMediaV21 &&
            previous.sourceMimeTypeV21 == clip.sourceMimeTypeV21
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

/**
 * Composition-level overlays are effects, not source streams. If a project contains text/images/
 * stickers/shapes but no playable video clips, Transformer still needs a real video source to
 * produce frames/timestamps for those overlays. Historical function name retained for tests/API.
 */
internal fun needsPureTextVideoSourceV18(project: TimelineProject): Boolean =
    project.hasCompositionOverlaysV19() && resolveCompositionVideoTracks(project).isEmpty()

@UnstableApi
class Media3CompositionBuilder(
    private val blankFrameUri: String = BLANK_PNG_DATA_URI,
) {
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
        val pureTextVideo = needsPureTextVideoSourceV18(project)
        val needsBlankFrameSentinel = videoTracks.isNotEmpty() &&
            (videoTracks.size == 1 || !textOverlaysAreCoveredByRealVideoV14(project))
        val compositorTracks = if (needsBlankFrameSentinel) {
            videoTracks + TimelineTrack(
                id = SINGLE_LAYER_GAP_SENTINEL_ID,
                name = "Compositor blank-frame sentinel",
                kind = TrackKind.VIDEO,
                clips = emptyList(),
            )
        } else {
            videoTracks
        }

        videoTracks.forEach { track ->
            sequences += buildCompositedVideoSequence(project, track, forPreview = false)
        }

        if (needsBlankFrameSentinel || pureTextVideo) {
            sequences += buildVideoBlankFrameSentinelSequence(project)
        }

        addAudioSequences(project, sequences, forPreview = false)

        require(sequences.isNotEmpty()) { "Timeline is empty" }
        val builder = Composition.Builder(sequences)
        when {
            videoTracks.isNotEmpty() -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    builder.setHdrMode(Composition.HDR_MODE_EXPERIMENTAL_FORCE_INTERPRET_HDR_AS_SDR)
                }
                builder.setVideoCompositorSettings(
                    ResolveVideoCompositorSettings(
                        outputWidth = project.width,
                        outputHeight = project.height,
                        videoTracks = compositorTracks,
                    ),
                )
                val textEffects = projectTextEffects(project)
                if (textEffects.isNotEmpty()) {
                    builder.setEffects(Effects(emptyList(), textEffects))
                }
            }

            pureTextVideo -> {
                val videoEffects = buildList<Effect> {
                    add(
                        Presentation.createForWidthAndHeight(
                            project.width.coerceAtLeast(2),
                            project.height.coerceAtLeast(2),
                            Presentation.LAYOUT_SCALE_TO_FIT,
                        ),
                    )
                    addAll(projectTextEffects(project))
                }
                builder.setEffects(Effects(emptyList(), videoEffects))
            }
        }
        return builder.build()
    }

    private fun buildVideoBlankFrameSentinelSequence(project: TimelineProject): EditedMediaItemSequence {
        val durationUs = project.durationUs.coerceAtLeast(1L)
        val durationMs = ((durationUs + 999L) / 1000L).coerceAtLeast(1L)
        val imageMediaItem = MediaItem.Builder()
            .setUri(blankFrameUri)
            .setMimeType("image/png")
            .setImageDurationMs(durationMs)
            .build()
        val imageItem = EditedMediaItem.Builder(imageMediaItem)
            .setFrameRate(project.frameRate.coerceAtLeast(1))
            .build()
        return EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
            .addItem(imageItem)
            .build()
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
                    projectFrameRate = project.frameRate,
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
                    videoBuilder.addItem(
                        toEditedMediaItem(
                            clip = clip,
                            kind = TrackKind.VIDEO,
                            forPreview = true,
                            projectFrameRate = project.frameRate,
                        ),
                    )
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
            audioBuilder.addItem(
                toEditedMediaItem(
                    clip = clip,
                    kind = TrackKind.AUDIO,
                    forPreview = forPreview,
                    projectFrameRate = project.frameRate,
                ),
            )
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
        projectFrameRate: Int,
    ): EditedMediaItem {
        val mediaItem = if (kind == TrackKind.VIDEO && clip.isImageV21) {
            val durationMs = ((clip.durationUs + 999L) / 1000L).coerceAtLeast(1L)
            MediaItem.Builder()
                .setUri(clip.uri)
                .apply { clip.sourceMimeTypeV21?.takeIf { it.isNotBlank() }?.let(::setMimeType) }
                .setImageDurationMs(durationMs)
                .build()
        } else {
            val clipping = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionUs(clip.sourceInUs)
                .setEndPositionUs(clip.sourceOutUs)
                .build()
            MediaItem.Builder()
                .setUri(clip.uri)
                .setClippingConfiguration(clipping)
                .build()
        }

        val builder = EditedMediaItem.Builder(mediaItem)
            .setDurationUs(clip.durationUs)
        if (kind == TrackKind.VIDEO) {
            if (clip.isImageV21) {
                builder.setFrameRate(projectFrameRate.coerceAtLeast(1))
            }
            // This is intentionally identical for image and video TimelineClip items. Corrections,
            // Resolve node color, effects, transform/keyframes and opacity therefore use one path.
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
