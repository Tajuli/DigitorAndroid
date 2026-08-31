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
import com.tajuli.digitorandroid.editor.model.resolvedVisualOverlaysV19

/**
 * Stable export composition builder.
 *
 * A normal one-layer edit does not need a video compositor. Keeping that export on Media3's
 * SingleInputVideoGraph is materially safer for camera Log media because it avoids the multi-input
 * GL compositor and sentinel video inputs while still running Digitor's per-clip transform, color,
 * qualifier and node effects.
 *
 * Native still images are TimelineClip items too, but they are not encoded video sources. They must
 * use Media3's image contract (MediaItem.imageDurationMs + EditedMediaItem.frameRate) instead of a
 * video ClippingConfiguration. This keeps single-V-track image projects on the stable path without
 * creating zero-byte exports.
 *
 * Composition overlays (text/images/stickers/shapes) render after the decoded video. Their V-track
 * assignment controls editor semantics without creating another decoder. If every overlay frame is
 * covered by the single real video stream, export stays on the stable single-input path. Otherwise
 * the shared compositor path supplies continuous blank frames through overlay-only timeline gaps.
 */
@UnstableApi
internal class StableGpuExportCompositionBuilder(
    private val sharedBuilder: Media3CompositionBuilder = Media3CompositionBuilder(),
) {
    fun build(project: TimelineProject): Composition =
        if (shouldUseStableSingleInputExportV17(project)) {
            buildDirectSingleInput(project)
        } else {
            sharedBuilder.build(project)
        }

    private fun buildDirectSingleInput(project: TimelineProject): Composition {
        val problems = project.validate()
        require(problems.isEmpty()) { problems.joinToString("; ") }

        val videoTrack = resolveCompositionVideoTracks(project).single()
        val embeddedAudioTrack = findLinkedEmbeddedAudioMirror(project, videoTrack)
        val sequences = mutableListOf<EditedMediaItemSequence>()

        if (embeddedAudioTrack != null) {
            sequences += buildAvSequence(project, videoTrack, embeddedAudioTrack)
        } else {
            sequences += buildVideoSequence(project, videoTrack)
        }

        project.tracks
            .filter { track ->
                track.kind == TrackKind.AUDIO &&
                    !track.muted &&
                    track.clips.isNotEmpty() &&
                    track.id != embeddedAudioTrack?.id
            }
            .forEach { track -> sequences += buildAudioSequence(project, track) }

        val builder = Composition.Builder(sequences)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setHdrMode(Composition.HDR_MODE_EXPERIMENTAL_FORCE_INTERPRET_HDR_AS_SDR)
        }

        val compositionVideoEffects = buildList<Effect> {
            add(
                Presentation.createForWidthAndHeight(
                    project.width.coerceAtLeast(2),
                    project.height.coerceAtLeast(2),
                    Presentation.LAYOUT_SCALE_TO_FIT,
                ),
            )
            addAll(projectTextEffects(project))
        }
        builder.setEffects(Effects(emptyList(), compositionVideoEffects))
        return builder.build()
    }

    private fun buildAvSequence(
        project: TimelineProject,
        videoTrack: TimelineTrack,
        audioTrack: TimelineTrack,
    ): EditedMediaItemSequence {
        val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO))
        val audioByGroup = audioTrack.clips
            .mapNotNull { clip -> clip.linkGroupId?.let { group -> group to clip } }
            .toMap()
        var cursorUs = 0L
        videoTrack.sortedClips().forEach { videoClip ->
            if (videoClip.timelineStartUs > cursorUs) {
                builder.addGap(videoClip.timelineStartUs - cursorUs)
            }
            require(!videoClip.isImageV21) { "Still images cannot carry embedded source audio" }
            val audioClip = videoClip.linkGroupId?.let(audioByGroup::get)
                ?: error("Linked embedded audio mirror disappeared during export build")
            builder.addItem(avItem(videoClip, audioClip))
            cursorUs = videoClip.timelineEndUs
        }
        if (project.durationUs > cursorUs) {
            builder.addGap(project.durationUs - cursorUs)
        }
        return builder.build()
    }

    private fun buildVideoSequence(
        project: TimelineProject,
        track: TimelineTrack,
    ): EditedMediaItemSequence {
        val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
        var cursorUs = 0L
        track.sortedClips().forEach { clip ->
            if (clip.timelineStartUs > cursorUs) {
                builder.addGap(clip.timelineStartUs - cursorUs)
            }
            builder.addItem(videoItem(project, clip))
            cursorUs = clip.timelineEndUs
        }
        if (project.durationUs > cursorUs) {
            builder.addGap(project.durationUs - cursorUs)
        }
        return builder.build()
    }

    private fun buildAudioSequence(
        project: TimelineProject,
        track: TimelineTrack,
    ): EditedMediaItemSequence {
        val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
        var cursorUs = 0L
        track.sortedClips().forEach { clip ->
            if (clip.timelineStartUs > cursorUs) {
                builder.addGap(clip.timelineStartUs - cursorUs)
            }
            builder.addItem(audioItem(clip))
            cursorUs = clip.timelineEndUs
        }
        if (project.durationUs > cursorUs) {
            builder.addGap(project.durationUs - cursorUs)
        }
        return builder.build()
    }

    private fun avItem(videoClip: TimelineClip, audioClip: TimelineClip): EditedMediaItem {
        val mediaItem = clippedMediaItem(videoClip)
        return EditedMediaItem.Builder(mediaItem)
            .setDurationUs(videoClip.durationUs)
            .setEffects(
                Effects(
                    audioProcessorsFor(audioClip),
                    SharedVideoPipeline.effectsFor(videoClip),
                ),
            )
            .build()
    }

    private fun videoItem(project: TimelineProject, clip: TimelineClip): EditedMediaItem {
        val mediaItem = if (clip.isImageV21) imageMediaItem(clip) else clippedMediaItem(clip)
        val builder = EditedMediaItem.Builder(mediaItem)
        if (clip.isImageV21) {
            builder.setFrameRate(project.frameRate.coerceAtLeast(1))
        } else {
            builder.setDurationUs(clip.durationUs)
        }
        return builder
            .setEffects(Effects(emptyList(), SharedVideoPipeline.effectsFor(clip)))
            .build()
    }

    private fun audioItem(clip: TimelineClip): EditedMediaItem {
        val mediaItem = clippedMediaItem(clip)
        val builder = EditedMediaItem.Builder(mediaItem).setDurationUs(clip.durationUs)
        val processors = audioProcessorsFor(clip)
        if (processors.isNotEmpty()) {
            builder.setEffects(Effects(processors, emptyList()))
        }
        return builder.build()
    }

    private fun imageMediaItem(clip: TimelineClip): MediaItem {
        val durationMs = ((clip.durationUs + 999L) / 1000L).coerceAtLeast(1L)
        return MediaItem.Builder()
            .setUri(clip.uri)
            .apply {
                clip.sourceMimeTypeV21
                    ?.takeIf { it.startsWith("image/") }
                    ?.let(::setMimeType)
            }
            .setImageDurationMs(durationMs)
            .build()
    }

    private fun clippedMediaItem(clip: TimelineClip): MediaItem {
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionUs(clip.sourceInUs)
            .setEndPositionUs(clip.sourceOutUs)
            .build()
        return MediaItem.Builder()
            .setUri(clip.uri)
            .setClippingConfiguration(clipping)
            .build()
    }
}

/** Final export router decision kept separate so overlay regressions are unit-testable. */
internal fun shouldUseStableSingleInputExportV17(project: TimelineProject): Boolean =
    canUseDirectSingleInputExport(project) && textOverlaysAreCoveredByRealVideoV14(project)

internal fun findLinkedEmbeddedAudioMirror(
    project: TimelineProject,
    videoTrack: TimelineTrack,
): TimelineTrack? {
    if (videoTrack.clips.isEmpty() || videoTrack.clips.any { it.linkGroupId == null || it.isImageV21 }) return null
    return project.tracks
        .asSequence()
        .filter { it.kind == TrackKind.AUDIO && !it.muted && it.clips.size == videoTrack.clips.size }
        .firstOrNull { audioTrack ->
            val audioByGroup = audioTrack.clips
                .mapNotNull { clip -> clip.linkGroupId?.let { group -> group to clip } }
                .toMap()
            audioByGroup.size == videoTrack.clips.size && videoTrack.clips.all { video ->
                val audio = video.linkGroupId?.let(audioByGroup::get) ?: return@all false
                audio.uri == video.uri &&
                    audio.timelineStartUs == video.timelineStartUs &&
                    audio.sourceInUs == video.sourceInUs &&
                    audio.sourceOutUs == video.sourceOutUs
            }
        }
}

/**
 * Historical name retained for existing tests. Direct SingleInputVideoGraph export is safe only
 * while every composition overlay frame sits on top of a real decoded video frame.
 */
internal fun textOverlaysAreCoveredByRealVideoV14(project: TimelineProject): Boolean {
    val ranges = buildList {
        project.textOverlays.forEach { add(it.timelineStartUs to it.timelineEndUs) }
        project.resolvedVisualOverlaysV19().forEach { add(it.timelineStartUs to it.timelineEndUs) }
    }
    if (ranges.isEmpty()) return true
    val clips = resolveCompositionVideoTracks(project).flatMap { it.clips }
    if (clips.isEmpty()) return false
    return ranges.all { (startUs, endUs) ->
        clips.any { clip -> startUs >= clip.timelineStartUs && endUs <= clip.timelineEndUs }
    }
}

internal fun canUseDirectSingleInputExport(project: TimelineProject): Boolean {
    val videoTracks = resolveCompositionVideoTracks(project)
    if (videoTracks.size != 1) return false
    return videoTracks.single().clips.all { clip ->
        clip.opacity >= .9999f && clip.transition.isIdentity
    }
}
