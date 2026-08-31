package com.tajuli.digitorandroid.ui.editor

import android.app.Application
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.tajuli.digitorandroid.editor.model.AnimatedFloat
import com.tajuli.digitorandroid.editor.model.ClipTransform
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineVisualMediaV21
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.VisualOverlayKindV19
import com.tajuli.digitorandroid.editor.model.resolvedVideoTrackIdV19
import com.tajuli.digitorandroid.editor.model.resolvedVisualOverlaysV19
import com.tajuli.digitorandroid.editor.model.textOverlaysForVideoTrackV3
import com.tajuli.digitorandroid.editor.model.visualOverlaysForVideoTrackV19
import java.util.UUID
import kotlin.math.max

private const val IMAGE_DEFAULT_DURATION_US_V21 = 5_000_000L

/** DaVinci-style V tracks accept moving video and still-image media; A tracks accept audio only. */
fun EditorViewModelV4.selectedImportMimeTypesV21(): Array<String> {
    val kind = state.value.project.track(state.value.selectedTrackId)?.kind
    return when (kind) {
        TrackKind.VIDEO -> arrayOf("video/*", "image/*")
        TrackKind.AUDIO -> arrayOf("audio/*")
        null -> arrayOf("video/*", "image/*", "audio/*")
    }
}

/**
 * V21 import contract:
 * - video and image are both real TimelineClip items on V tracks;
 * - image defaults to five seconds and owns the same nodeGraph/transform fields as video;
 * - existing video/audio metadata/link behaviour is retained by delegating video/audio to V4;
 * - every new V item appends after media, text, sticker and shape items already occupying the lane.
 */
fun EditorViewModelV4.importUrisAppendAwareV12(uris: List<Uri>) {
    if (uris.isEmpty()) return

    migrateLegacyImageOverlaysV21()
    val selected = state.value.project.track(state.value.selectedTrackId) ?: return
    if (selected.kind == TrackKind.AUDIO) {
        importUris(uris)
        TimelineTextSelectionBusV10.clear()
        VisualOverlaySelectionBusV19.clear()
        state.value.selectedClipId?.let(::selectClip)
        return
    }

    uris.forEach { uri ->
        val app = getApplication<Application>()
        runCatching {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val mime = app.contentResolver.getType(uri).orEmpty()
        when {
            mime.startsWith("image/") -> importImageAsTimelineClipV21(uri, mime)
            mime.startsWith("video/") -> importVideoAppendAwareV21(uri)
            else -> setEditorStatusV19("V track accepts video or image files")
        }
    }

    TimelineTextSelectionBusV10.clear()
    VisualOverlaySelectionBusV19.clear()
    state.value.selectedClipId?.let(::selectClip)
}

private fun EditorViewModelV4.importImageAsTimelineClipV21(uri: Uri, mime: String) {
    val snapshot = state.value
    val track = snapshot.project.track(snapshot.selectedTrackId)?.takeIf { it.kind == TrackKind.VIDEO } ?: return
    val startUs = snapshot.project.vLaneAppendFloorV21(track.id)
    val label = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Image"
    val clip = TimelineClip(
        uri = uri.toString(),
        label = label,
        timelineStartUs = startUs,
        sourceInUs = 0L,
        sourceOutUs = IMAGE_DEFAULT_DURATION_US_V21,
        visualMediaV21 = TimelineVisualMediaV21.IMAGE,
        sourceMimeTypeV21 = mime.takeIf { it.isNotBlank() },
    )
    val tracks = snapshot.project.tracks.map { candidate ->
        if (candidate.id == track.id) candidate.copy(clips = candidate.clips + clip) else candidate
    }
    commitProjectV19("import-image-clip", snapshot.project.copy(tracks = tracks), status = "Image added to ${track.name}")
    TimelineTextSelectionBusV10.clear()
    VisualOverlaySelectionBusV19.clear()
    selectClip(clip.id)
}

private fun EditorViewModelV4.importVideoAppendAwareV21(uri: Uri) {
    val before = state.value
    val selectedTrack = before.project.track(before.selectedTrackId)?.takeIf { it.kind == TrackKind.VIDEO } ?: return
    val oldIds = selectedTrack.clips.mapTo(hashSetOf()) { it.id }
    val appendFloorUs = before.project.vLaneAppendFloorV21(selectedTrack.id)

    importUris(listOf(uri))

    val imported = state.value
    val importedTrack = imported.project.track(selectedTrack.id) ?: return
    val newVideoClips = importedTrack.clips.filterNot { it.id in oldIds }.sortedBy { it.timelineStartUs }
    if (newVideoClips.isEmpty()) return

    // Explicitly tag moving-video clips without changing legacy-project semantics.
    val taggedIds = newVideoClips.mapTo(hashSetOf()) { it.id }
    val taggedTracks = imported.project.tracks.map { track ->
        track.copy(clips = track.clips.map { clip ->
            if (clip.id in taggedIds && track.kind == TrackKind.VIDEO) {
                clip.copy(
                    visualMediaV21 = TimelineVisualMediaV21.VIDEO,
                    sourceMimeTypeV21 = getApplication<Application>().contentResolver.getType(Uri.parse(clip.uri)),
                )
            } else clip
        })
    }
    var taggedProject = imported.project.copy(tracks = taggedTracks)

    val firstStartUs = newVideoClips.first().timelineStartUs
    val shiftUs = (appendFloorUs - firstStartUs).coerceAtLeast(0L)
    if (shiftUs > 0L) {
        // Rebuild all clips in the newly imported linked group together; this avoids transient
        // collision checks while preserving source-audio sync.
        val movingIds = newVideoClips.flatMap { clip -> taggedProject.linkedClipIds(clip.id) }.toSet()
        val shiftedTracks = taggedProject.tracks.map { track ->
            track.copy(clips = track.clips.map { clip ->
                if (clip.id in movingIds) clip.copy(timelineStartUs = clip.timelineStartUs + shiftUs) else clip
            })
        }
        taggedProject = taggedProject.copy(tracks = shiftedTracks)
    }

    commitProjectV19("tag-video-v21", taggedProject, status = "Video imported")
    val selectedId = newVideoClips.first().id
    selectClip(selectedId)
}

/** End of the occupied Resolve-style V lane, including titles and composition overlays. */
private fun TimelineProject.vLaneAppendFloorV21(trackId: String): Long = maxOf(
    track(trackId)?.clips?.maxOfOrNull { it.timelineEndUs } ?: 0L,
    textOverlaysForVideoTrackV3(trackId).maxOfOrNull { it.timelineEndUs } ?: 0L,
    visualOverlaysForVideoTrackV19(trackId).maxOfOrNull { it.timelineEndUs } ?: 0L,
)

/**
 * One-time compatibility migration for projects created by PR #42/#43 where a user-imported image
 * was stored as VisualOverlayClipV19. Stickers/shapes remain overlays; images become native V clips.
 */
fun EditorViewModelV4.migrateLegacyImageOverlaysV21() {
    val snapshot = state.value
    val project = snapshot.project
    val legacyImages = project.resolvedVisualOverlaysV19().filter { it.kind == VisualOverlayKindV19.IMAGE }
    if (legacyImages.isEmpty()) return

    var tracks = project.tracks
    legacyImages.forEach { overlay ->
        val trackId = overlay.resolvedVideoTrackIdV19(project) ?: return@forEach
        val owner = tracks.firstOrNull { it.id == trackId && it.kind == TrackKind.VIDEO } ?: return@forEach
        val durationUs = overlay.durationUs.coerceAtLeast(1L)
        val migrated = TimelineClip(
            id = overlay.id,
            uri = overlay.imageUri.orEmpty(),
            label = overlay.label,
            timelineStartUs = overlay.timelineStartUs,
            sourceInUs = 0L,
            sourceOutUs = durationUs,
            opacity = overlay.opacity,
            transform = ClipTransform(
                positionX = AnimatedFloat(overlay.positionX),
                positionY = AnimatedFloat(overlay.positionY),
                scaleX = AnimatedFloat(overlay.scale),
                scaleY = AnimatedFloat(overlay.scale),
                rotationDegrees = AnimatedFloat(overlay.rotationDegrees),
            ),
            visualMediaV21 = TimelineVisualMediaV21.IMAGE,
        )
        tracks = tracks.map { track -> if (track.id == owner.id) track.copy(clips = track.clips + migrated) else track }
    }

    val next = project.copy(
        tracks = tracks,
        visualOverlaysV19 = project.resolvedVisualOverlaysV19().filterNot { it.kind == VisualOverlayKindV19.IMAGE },
    )
    commitProjectV19("migrate-image-overlays-v21", next, status = "Images migrated to V-track clips")
}
