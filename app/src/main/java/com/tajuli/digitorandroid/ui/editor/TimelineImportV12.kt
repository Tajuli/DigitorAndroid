package com.tajuli.digitorandroid.ui.editor

import android.app.Application
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.tajuli.digitorandroid.editor.model.AnimatedFloat
import com.tajuli.digitorandroid.editor.model.ClipTransform
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TimelineVisualMediaV21
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.VisualOverlayKindV19
import com.tajuli.digitorandroid.editor.model.resolvedVideoTrackIdV19
import com.tajuli.digitorandroid.editor.model.resolvedVisualOverlaysV19
import com.tajuli.digitorandroid.editor.model.textOverlaysForVideoTrackV3
import com.tajuli.digitorandroid.editor.model.visualOverlaysForVideoTrackV19
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

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
 * V21 import contract with V72 source probing:
 * - video and image are both real TimelineClip items on V tracks;
 * - image defaults to five seconds and owns the same nodeGraph/transform fields as video;
 * - video duration is read from the real video track, never replaced by a silent one-second guess;
 * - the first moving video adopts its native canvas/FPS so "Original" export is meaningful;
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
            mime.startsWith("video/") -> importVideoAppendAwareV72(uri, mime)
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

private data class VideoSourceProbeV72(
    val durationUs: Long,
    val width: Int?,
    val height: Int?,
    val frameRate: Int?,
    val hasAudio: Boolean,
)

/**
 * Imports video without going through EditorViewModelV4's legacy 1000 ms metadata fallback.
 * MediaExtractor is authoritative when available and sample PTS is the final duration fallback.
 */
private fun EditorViewModelV4.importVideoAppendAwareV72(uri: Uri, mime: String) {
    val snapshot = state.value
    val selectedTrack = snapshot.project.track(snapshot.selectedTrackId)
        ?.takeIf { it.kind == TrackKind.VIDEO }
        ?: return
    val app = getApplication<Application>()
    val probe = runCatching { probeVideoSourceV72(app, uri) }
        .getOrElse { error ->
            setEditorStatusV19("Could not read video metadata · ${error.message ?: "unsupported file"}")
            return
        }
    if (probe.durationUs <= 0L) {
        setEditorStatusV19("Could not determine video duration")
        return
    }

    var project = snapshot.project
    val firstMovingVideo = project.tracks
        .asSequence()
        .filter { it.kind == TrackKind.VIDEO }
        .flatMap { it.clips.asSequence() }
        .none { !it.isImageV21 }

    val videoTrackNumber = selectedTrack.name.takeIf { it.startsWith("V") }
        ?.removePrefix("V")
        ?.toIntOrNull()
    var audioTrack = if (probe.hasAudio && videoTrackNumber != null) {
        project.tracks.firstOrNull { it.kind == TrackKind.AUDIO && it.name == "A$videoTrackNumber" }
    } else {
        null
    }
    if (probe.hasAudio && audioTrack == null && videoTrackNumber != null) {
        audioTrack = TimelineTrack(name = "A$videoTrackNumber", kind = TrackKind.AUDIO)
        project = project.copy(tracks = project.tracks + audioTrack)
    }

    val visualFloorUs = project.vLaneAppendFloorV21(selectedTrack.id)
    val audioFloorUs = audioTrack?.clips?.maxOfOrNull { it.timelineEndUs } ?: 0L
    val startUs = max(visualFloorUs, audioFloorUs)
    val label = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Video"
    val group = audioTrack?.let { UUID.randomUUID().toString() }
    val video = TimelineClip(
        uri = uri.toString(),
        label = label,
        timelineStartUs = startUs,
        sourceInUs = 0L,
        sourceOutUs = probe.durationUs,
        linkGroupId = group,
        visualMediaV21 = TimelineVisualMediaV21.VIDEO,
        sourceMimeTypeV21 = mime.takeIf { it.isNotBlank() },
    )
    val audio = if (audioTrack != null && group != null) {
        TimelineClip(
            uri = uri.toString(),
            label = "$label · audio",
            timelineStartUs = startUs,
            sourceInUs = 0L,
            sourceOutUs = probe.durationUs,
            linkGroupId = group,
            sourceMimeTypeV21 = mime.takeIf { it.isNotBlank() },
        )
    } else {
        null
    }

    val tracks = project.tracks.map { track ->
        when (track.id) {
            selectedTrack.id -> track.copy(clips = track.clips + video)
            audioTrack?.id -> if (audio != null) track.copy(clips = track.clips + audio) else track
            else -> track
        }
    }
    var importedProject = project.copy(tracks = tracks)
    if (firstMovingVideo) {
        val sourceWidth = probe.width?.takeIf { it >= 2 } ?: importedProject.width
        val sourceHeight = probe.height?.takeIf { it >= 2 } ?: importedProject.height
        val sourceFps = probe.frameRate?.takeIf { it in 1..120 } ?: importedProject.frameRate
        importedProject = importedProject.copy(
            width = sourceWidth.evenCanvasV72(),
            height = sourceHeight.evenCanvasV72(),
            frameRate = sourceFps,
        )
    }

    commitProjectV19(
        "import-video-v72",
        importedProject,
        status = "Video imported · ${probe.durationUs / 1_000_000f}s · ${importedProject.width}×${importedProject.height} · ${importedProject.frameRate} fps",
    )
    selectClip(video.id)
}

private fun probeVideoSourceV72(app: Application, uri: Uri): VideoSourceProbeV72 {
    var retrieverDurationUs: Long? = null
    var retrieverWidth: Int? = null
    var retrieverHeight: Int? = null
    var retrieverFps: Int? = null
    var retrieverRotation = 0
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(app, uri)
        retrieverDurationUs = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?.times(1000L)
        retrieverWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull()?.takeIf { it > 0 }
        retrieverHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull()?.takeIf { it > 0 }
        retrieverRotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull() ?: 0
        retrieverFps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            ?.toFloatOrNull()
            ?.takeIf { it > 0f }
            ?.roundToInt()
            ?.takeIf { it in 1..120 }
    } catch (_: Throwable) {
        // Extractor below is the robust path for camera/editor files that retriever cannot parse.
    } finally {
        retriever.release()
    }

    val extractor = MediaExtractor()
    var videoTrack = -1
    var extractorDurationUs: Long? = null
    var extractorWidth: Int? = null
    var extractorHeight: Int? = null
    var extractorFps: Int? = null
    var extractorRotation = 0
    var hasAudio = false
    try {
        extractor.setDataSource(app, uri, null)
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val trackMime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            when {
                trackMime.startsWith("video/") && videoTrack < 0 -> {
                    videoTrack = index
                    extractorDurationUs = format.longOrNullV72(MediaFormat.KEY_DURATION)?.takeIf { it > 0L }
                    extractorWidth = format.intOrNullV72(MediaFormat.KEY_WIDTH)?.takeIf { it > 0 }
                    extractorHeight = format.intOrNullV72(MediaFormat.KEY_HEIGHT)?.takeIf { it > 0 }
                    extractorFps = format.intOrNullV72(MediaFormat.KEY_FRAME_RATE)?.takeIf { it in 1..120 }
                    extractorRotation = format.intOrNullV72(MediaFormat.KEY_ROTATION) ?: 0
                }
                trackMime.startsWith("audio/") -> hasAudio = true
            }
        }
        require(videoTrack >= 0) { "No video track found" }

        if (extractorDurationUs == null) {
            extractor.selectTrack(videoTrack)
            extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            var lastSampleUs = -1L
            while (true) {
                val sampleUs = extractor.sampleTime
                if (sampleUs < 0L) break
                if (sampleUs > lastSampleUs) lastSampleUs = sampleUs
                if (!extractor.advance()) break
            }
            if (lastSampleUs >= 0L) {
                val fpsForTail = extractorFps ?: retrieverFps ?: 30
                extractorDurationUs = lastSampleUs + (1_000_000L / fpsForTail.coerceAtLeast(1))
            }
        }
    } finally {
        extractor.release()
    }

    val durationUs = listOfNotNull(extractorDurationUs, retrieverDurationUs)
        .maxOrNull()
        ?.takeIf { it > 0L }
        ?: error("Duration unavailable")
    var width = extractorWidth ?: retrieverWidth
    var height = extractorHeight ?: retrieverHeight
    val rotation = if (extractorRotation != 0) extractorRotation else retrieverRotation
    if ((rotation % 180 + 180) % 180 == 90) {
        val swap = width
        width = height
        height = swap
    }

    return VideoSourceProbeV72(
        durationUs = durationUs,
        width = width,
        height = height,
        frameRate = extractorFps ?: retrieverFps,
        hasAudio = hasAudio,
    )
}

private fun MediaFormat.longOrNullV72(key: String): Long? =
    if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null

private fun MediaFormat.intOrNullV72(key: String): Int? =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

private fun Int.evenCanvasV72(): Int {
    val safe = coerceAtLeast(2)
    return if (safe % 2 == 0) safe else safe - 1
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
