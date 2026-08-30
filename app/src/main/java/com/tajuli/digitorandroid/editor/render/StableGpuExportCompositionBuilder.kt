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

/**
 * Stable export composition builder.
 *
 * A normal one-layer edit does not need a video compositor. Keeping that export on Media3's
 * SingleInputVideoGraph is materially safer for camera Log media because it avoids the multi-input
 * GL compositor and sentinel video inputs while still running Digitor's per-clip transform, color,
 * qualifier and node effects.
 *
 * Imported camera clips normally have a linked A-track mirror of the same source file. The direct
 * path folds that linked embedded audio back into the same AV EditedMediaItem so Media3 opens one
 * source asset-loader instead of independently loading the same camera URI as video and audio.
 */
@UnstableApi
internal class StableGpuExportCompositionBuilder(
    private val sharedBuilder: Media3CompositionBuilder = Media3CompositionBuilder(),
) {
    fun build(project: TimelineProject): Composition =
        if (canUseDirectSingleInputExport(project) && textOverlaysAreCoveredByRealVideoV14(project)) {
            buildDirectSingleInput(project)
        } else {
            // A composition-level text overlay that extends into a video gap (for example a title
            // after the last clip) needs actual blank video frames for that interval. Some devices
            // fail the SingleInputVideoGraph path when the sequence ends with a pure gap + overlay.
            // The shared compositor path already owns full-duration blank-gap generation and is the
            // Resolve-style path used for layered video, so route text-only gaps through it too.
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

        // Digitor owns Log/HDR -> working-space conversion. If a camera file carries HDR-style
        // metadata, keep decoder output as editable SDR code values instead of asking Media3 to
        // preserve an HDR pipeline that ultimately targets H.264 SDR output.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setHdrMode(Composition.HDR_MODE_EXPERIMENTAL_FORCE_INTERPRET_HDR_AS_SDR)
        }

        // The old compositor path always fixed output to the project canvas. Direct single-input
        // export must do the same; otherwise a 4K source silently becomes a 4K H.264 export even
        // when the project is 1080p, which substantially increases codec memory pressure.
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
            builder.addItem(videoItem(clip))
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

    private fun videoItem(clip: TimelineClip): EditedMediaItem {
        val mediaItem = clippedMediaItem(clip)
        return EditedMediaItem.Builder(mediaItem)
            .setDurationUs(clip.durationUs)
            // Direct single-input path owns geometry in the clip effect instead of the compositor.
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

/**
 * Returns the normal imported A-track mirror when every video clip has exactly one linked audio clip
 * from the same source with identical timeline/source boundaries. Edited or independent audio stays
 * on its own sequence so separate-track behavior is preserved.
 */
internal fun findLinkedEmbeddedAudioMirror(
    project: TimelineProject,
    videoTrack: TimelineTrack,
): TimelineTrack? {
    if (videoTrack.clips.isEmpty() || videoTrack.clips.any { it.linkGroupId == null }) return null
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
 * Direct SingleInputVideoGraph export is safe only while every title frame sits on top of a real
 * decoded video frame. If a title crosses a blank V-track interval, especially after the final
 * media clip, use the compositor path so Media3 produces full-duration blank video frames first.
 */
internal fun textOverlaysAreCoveredByRealVideoV14(project: TimelineProject): Boolean {
    if (project.textOverlays.isEmpty()) return true
    val clips = resolveCompositionVideoTracks(project).flatMap { it.clips }
    if (clips.isEmpty()) return false
    return project.textOverlays.all { overlay ->
        clips.any { clip ->
            overlay.timelineStartUs >= clip.timelineStartUs &&
                overlay.timelineEndUs <= clip.timelineEndUs
        }
    }
}

/**
 * SingleInputVideoGraph is safe when compositor-only alpha/fades are not needed. Clip transforms,
 * node effects, HSL qualifier, RGB curves and Input Color remain per-item effects and are supported.
 */
internal fun canUseDirectSingleInputExport(project: TimelineProject): Boolean {
    val videoTracks = resolveCompositionVideoTracks(project)
    if (videoTracks.size != 1) return false
    return videoTracks.single().clips.all { clip ->
        clip.opacity >= .9999f && clip.transition.isIdentity
    }
}
