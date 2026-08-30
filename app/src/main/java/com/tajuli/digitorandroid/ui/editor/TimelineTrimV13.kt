package com.tajuli.digitorandroid.ui.editor

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.tajuli.digitorandroid.editor.model.ProjectStore
import com.tajuli.digitorandroid.editor.model.TextOverlayClip
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.resolvedVideoTrackIdV3
import com.tajuli.digitorandroid.editor.model.textOverlaysForVideoTrackV3
import java.util.concurrent.ConcurrentHashMap

private const val MIN_TRIM_DURATION_US_V13 = 100_000L
private val sourceDurationCacheV13 = ConcurrentHashMap<String, Long>()

/** Resize the left edge of a title while keeping its right edge fixed. */
fun EditorViewModelV4.resizeTextStartV13(textId: String, requestedStartUs: Long) {
    val snapshot = state.value
    val current = snapshot.project.textOverlays.firstOrNull { it.id == textId } ?: return
    val trackId = current.resolvedVideoTrackIdV3(snapshot.project) ?: return
    val track = snapshot.project.track(trackId)?.takeIf { it.kind == TrackKind.VIDEO } ?: return

    val previousEndUs = buildList<Long> {
        track.clips.filter { it.timelineEndUs <= current.timelineStartUs }.forEach { add(it.timelineEndUs) }
        snapshot.project.textOverlaysForVideoTrackV3(trackId)
            .filter { it.id != textId && it.timelineEndUs <= current.timelineStartUs }
            .forEach { add(it.timelineEndUs) }
    }.maxOrNull() ?: 0L

    val latestStartUs = (current.timelineEndUs - MIN_TRIM_DURATION_US_V13).coerceAtLeast(previousEndUs)
    val newStartUs = requestedStartUs.coerceIn(previousEndUs, latestStartUs)
    if (newStartUs == current.timelineStartUs) return

    val updated = current.copy(timelineStartUs = newStartUs)
    commitTrimProjectV13(
        project = snapshot.project.copy(
            textOverlays = snapshot.project.textOverlays.map { if (it.id == textId) updated else it },
        ),
        selectedTextId = textId,
        selectedTrackId = trackId,
    )
}

/** Resize the right edge of a title with no fixed maximum duration. */
fun EditorViewModelV4.resizeTextEndV13(textId: String, requestedEndUs: Long) {
    val snapshot = state.value
    val current = snapshot.project.textOverlays.firstOrNull { it.id == textId } ?: return
    val trackId = current.resolvedVideoTrackIdV3(snapshot.project) ?: return
    val track = snapshot.project.track(trackId)?.takeIf { it.kind == TrackKind.VIDEO } ?: return

    val nextStartUs = buildList<Long> {
        track.clips.filter { it.timelineStartUs >= current.timelineEndUs }.forEach { add(it.timelineStartUs) }
        snapshot.project.textOverlaysForVideoTrackV3(trackId)
            .filter { it.id != textId && it.timelineStartUs >= current.timelineEndUs }
            .forEach { add(it.timelineStartUs) }
    }.minOrNull()

    val minEndUs = current.timelineStartUs + MIN_TRIM_DURATION_US_V13
    val maxEndUs = nextStartUs ?: Long.MAX_VALUE / 4
    val newEndUs = requestedEndUs.coerceIn(minEndUs, maxEndUs)
    if (newEndUs == current.timelineEndUs) return

    val updated = current.copy(timelineEndUs = newEndUs)
    commitTrimProjectV13(
        project = snapshot.project.copy(
            textOverlays = snapshot.project.textOverlays.map { if (it.id == textId) updated else it },
        ),
        selectedTextId = textId,
        selectedTrackId = trackId,
    )
}

/**
 * Trim/extend the left edge of a VIDEO clip. A split clip can be pulled back into source media that
 * still exists before sourceInUs. The timeline end stays fixed.
 */
fun EditorViewModelV4.resizeVideoClipStartV13(clipId: String, requestedStartUs: Long) {
    val snapshot = state.value
    val clip = snapshot.project.clip(clipId) ?: return
    val track = snapshot.project.trackContaining(clipId)?.takeIf { it.kind == TrackKind.VIDEO } ?: return
    val linkedIds = snapshot.project.linkedClipIds(clipId)

    val previousEndUs = buildList<Long> {
        track.clips.filter { it.id !in linkedIds && it.timelineEndUs <= clip.timelineStartUs }
            .forEach { add(it.timelineEndUs) }
        snapshot.project.textOverlaysForVideoTrackV3(track.id)
            .filter { it.timelineEndUs <= clip.timelineStartUs }
            .forEach { add(it.timelineEndUs) }
    }.maxOrNull() ?: 0L

    val sourceEarliestTimelineUs = (clip.timelineStartUs - clip.sourceInUs).coerceAtLeast(0L)
    val earliestUs = maxOf(previousEndUs, sourceEarliestTimelineUs)
    val latestUs = clip.timelineEndUs - MIN_TRIM_DURATION_US_V13
    val newStartUs = requestedStartUs.coerceIn(earliestUs, latestUs)
    if (newStartUs == clip.timelineStartUs) return

    val timelineDeltaUs = newStartUs - clip.timelineStartUs
    val updatedVideo = clip.copy(
        timelineStartUs = newStartUs,
        sourceInUs = (clip.sourceInUs + timelineDeltaUs).coerceAtLeast(0L),
    )

    val tracks = snapshot.project.tracks.map { candidate ->
        candidate.copy(clips = candidate.clips.map { item ->
            when {
                item.id == clipId -> updatedVideo
                item.id in linkedIds && candidate.kind == TrackKind.AUDIO -> {
                    val audioSourceIn = (item.sourceInUs + timelineDeltaUs).coerceAtLeast(0L)
                    item.copy(timelineStartUs = newStartUs, sourceInUs = audioSourceIn)
                }
                else -> item
            }
        })
    }
    commitTrimProjectV13(snapshot.project.copy(tracks = tracks), selectedClipId = clipId, selectedTrackId = track.id)
}

/**
 * Trim/extend the right edge of a VIDEO clip. For a split clip this can reveal source frames that
 * were trimmed away by the split, up to the original media duration. Linked source audio follows.
 */
fun EditorViewModelV4.resizeVideoClipEndV13(clipId: String, requestedEndUs: Long) {
    val snapshot = state.value
    val clip = snapshot.project.clip(clipId) ?: return
    val track = snapshot.project.trackContaining(clipId)?.takeIf { it.kind == TrackKind.VIDEO } ?: return
    val linkedIds = snapshot.project.linkedClipIds(clipId)

    val sourceDurationUs = sourceDurationUsV13(clip)
    val maxBySourceUs = clip.timelineStartUs + (sourceDurationUs - clip.sourceInUs).coerceAtLeast(MIN_TRIM_DURATION_US_V13)
    val nextStartUs = buildList<Long> {
        track.clips.filter { it.id !in linkedIds && it.timelineStartUs >= clip.timelineEndUs }
            .forEach { add(it.timelineStartUs) }
        snapshot.project.textOverlaysForVideoTrackV3(track.id)
            .filter { it.timelineStartUs >= clip.timelineEndUs }
            .forEach { add(it.timelineStartUs) }
    }.minOrNull()

    val minEndUs = clip.timelineStartUs + MIN_TRIM_DURATION_US_V13
    val maxEndUs = minOf(maxBySourceUs, nextStartUs ?: Long.MAX_VALUE / 4)
    val newEndUs = requestedEndUs.coerceIn(minEndUs, maxEndUs)
    if (newEndUs == clip.timelineEndUs) return

    val newDurationUs = newEndUs - clip.timelineStartUs
    val updatedVideo = clip.copy(sourceOutUs = clip.sourceInUs + newDurationUs)

    val tracks = snapshot.project.tracks.map { candidate ->
        candidate.copy(clips = candidate.clips.map { item ->
            when {
                item.id == clipId -> updatedVideo
                item.id in linkedIds && candidate.kind == TrackKind.AUDIO -> {
                    val audioDuration = sourceDurationUsV13(item)
                    val requestedSourceOut = item.sourceInUs + newDurationUs
                    item.copy(sourceOutUs = requestedSourceOut.coerceAtMost(audioDuration).coerceAtLeast(item.sourceInUs + 1L))
                }
                else -> item
            }
        })
    }
    commitTrimProjectV13(snapshot.project.copy(tracks = tracks), selectedClipId = clipId, selectedTrackId = track.id)
}

private fun EditorViewModelV4.sourceDurationUsV13(clip: TimelineClip): Long =
    sourceDurationCacheV13.getOrPut(clip.uri) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(getApplication<Application>(), Uri.parse(clip.uri))
            ((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000L)
                .coerceAtLeast(clip.sourceOutUs)
        } catch (_: Throwable) {
            clip.sourceOutUs
        } finally {
            retriever.release()
        }
    }.coerceAtLeast(clip.sourceOutUs)

private fun EditorViewModelV4.commitTrimProjectV13(
    project: TimelineProject,
    selectedTextId: String? = null,
    selectedClipId: String? = null,
    selectedTrackId: String? = null,
) {
    ProjectStore(getApplication()).autoSave(project)
    loadProject()
    selectedTrackId?.let(::selectTrack)
    when {
        selectedTextId != null -> {
            selectTextOverlay(selectedTextId)
            TimelineTextSelectionBusV10.select(selectedTextId)
        }
        selectedClipId != null -> {
            TimelineTextSelectionBusV10.clear()
            selectClip(selectedClipId)
        }
    }
}
