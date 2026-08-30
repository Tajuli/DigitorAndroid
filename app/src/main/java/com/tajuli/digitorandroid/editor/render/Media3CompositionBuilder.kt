package com.tajuli.digitorandroid.editor.render

import android.os.Build
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
            previous.inputColorProfileV1 == clip.inputColorProfileV1
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
        val compositorTracks = if (videoTracks.size == 1) {
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

        // A pure Media3 video gap contains no source frames. Composition-level text effects need a
        // continuous frame stream even when the timeline is in a video-free region (for example,
        // video 0-60s followed by a title at 60-63s). Feed a tiny static black image for the whole
        // project and keep its compositor alpha at zero via the empty sentinel track. This creates
        // encoder timestamps/frames without holding the previous video's last frame or opening a
        // second hardware video decoder.
        if (videoTracks.size == 1) {
            sequences += buildVideoBlankFrameSentinelSequence(project)
        }

        addAudioSequences(project, sequences, forPreview = false)

        require(sequences.isNotEmpty()) { "Timeline is empty" }
        val builder = Composition.Builder(sequences)
        if (videoTracks.isNotEmpty()) {
            // Digitor's camera-log workflow intentionally treats decoder output as editable code
            // values. Media3 defaults HDR-tagged input to KEEP_HDR, which can switch camera files
            // onto a device HDR decoder/encoder path even though Digitor outputs SDR H.264 and owns
            // the Log/HDR -> working-space conversion itself. On Android 10+ interpret such metadata
            // as SDR so None/Bypass stays flat and selected Input Color profiles receive raw values.
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
        return builder.build()
    }

    private fun buildVideoBlankFrameSentinelSequence(project: TimelineProject): EditedMediaItemSequence {
        val durationUs = project.durationUs.coerceAtLeast(1L)
        val durationMs = ((durationUs + 999L) / 1000L).coerceAtLeast(1L)
        val imageMediaItem = MediaItem.Builder()
            .setUri(BLANK_PNG_DATA_URI)
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
