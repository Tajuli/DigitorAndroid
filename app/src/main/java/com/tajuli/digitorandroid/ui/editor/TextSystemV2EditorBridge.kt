package com.tajuli.digitorandroid.ui.editor

import com.tajuli.digitorandroid.editor.model.ProjectStore
import com.tajuli.digitorandroid.editor.model.TextOverlayClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.resolvedVideoTrackIdV3
import com.tajuli.digitorandroid.editor.model.textOverlaysForVideoTrackV3
import com.tajuli.digitorandroid.editor.model.videoTrackSlotAvailableV3

/**
 * Text-system bridge that keeps EditorViewModelV4's stable public API while adding Resolve-style
 * title-track behaviour. ProjectStore + loadProject() are reused so edits still participate in the
 * existing project history and autosave path.
 */
fun EditorViewModelV4.commitTextOverlayV2(updated: TextOverlayClip) {
    val state = state.value
    val selectedId = state.selectedTextId ?: return
    if (updated.id != selectedId) return
    val current = state.project.textOverlays.firstOrNull { it.id == selectedId } ?: return
    if (current == updated) return

    commitTextProjectV10(
        state.project.copy(
            textOverlays = state.project.textOverlays.map { item ->
                if (item.id == selectedId) updated else item
            },
        ),
        selectedTextId = selectedId,
        selectedTrackId = updated.resolvedVideoTrackIdV3(state.project),
    )
}

fun EditorViewModelV4.selectedVideoTrackForTextV10(): TimelineTrack? {
    val snapshot = state.value
    val selectedTrack = snapshot.project.track(snapshot.selectedTrackId)
    if (selectedTrack?.kind == TrackKind.VIDEO) return selectedTrack

    val selectedText = snapshot.project.textOverlays.firstOrNull { it.id == snapshot.selectedTextId }
    val textTrackId = selectedText?.resolvedVideoTrackIdV3(snapshot.project)
    val textTrack = snapshot.project.track(textTrackId)
    if (textTrack?.kind == TrackKind.VIDEO) return textTrack

    return snapshot.project.tracks.firstOrNull { it.kind == TrackKind.VIDEO && it.name == "V1" }
        ?: snapshot.project.tracks.lastOrNull { it.kind == TrackKind.VIDEO }
}

fun EditorViewModelV4.addTextAtSelectedVideoTrackV10(
    timelineUs: Long,
    caption: Boolean = false,
    template: TextTemplatePresetV10? = null,
) {
    val snapshot = state.value
    val track = selectedVideoTrackForTextV10() ?: return
    // Match media-import behaviour: once a V track contains timeline items, a newly added title is
    // appended after the last video/text item instead of being inserted into an earlier empty gap.
    // On an empty V track the playhead is still a useful insertion point.
    val startUs = snapshot.project.appendTextStartV11(track.id, timelineUs)
    val endUs = startUs + 3_000_000L
    var overlay = TextOverlayClip(
        text = when {
            template != null -> template.label
            caption -> "Caption"
            else -> "Text"
        },
        timelineStartUs = startUs,
        timelineEndUs = endUs,
        positionY = if (caption) .72f else 0f,
        sizeScale = if (caption) .78f else 1f,
        background = caption,
        videoTrackIdV3 = track.id,
    )
    if (template != null) overlay = template.applyTo(overlay)

    commitTextProjectV10(
        snapshot.project.copy(textOverlays = snapshot.project.textOverlays + overlay),
        selectedTextId = overlay.id,
        selectedTrackId = track.id,
    )
    TimelineTextSelectionBusV10.select(overlay.id)
}

/** Move a title horizontally like a normal timeline clip. */
fun EditorViewModelV4.moveTextOverlayV10(textId: String, deltaUs: Long) {
    if (deltaUs == 0L) return
    val snapshot = state.value
    val current = snapshot.project.textOverlays.firstOrNull { it.id == textId } ?: return
    val trackId = current.resolvedVideoTrackIdV3(snapshot.project) ?: return
    val startUs = (current.timelineStartUs + deltaUs).coerceAtLeast(0L)
    val endUs = startUs + current.durationUs
    if (!snapshot.project.videoTrackSlotAvailableV3(trackId, startUs, endUs, ignoreTextId = textId)) return

    val updated = current.copy(timelineStartUs = startUs, timelineEndUs = endUs)
    commitTextProjectV10(
        snapshot.project.copy(
            textOverlays = snapshot.project.textOverlays.map { if (it.id == textId) updated else it },
        ),
        selectedTextId = textId,
        selectedTrackId = trackId,
    )
    TimelineTextSelectionBusV10.select(textId)
}

/** Move a title vertically to another V track while keeping its timeline time unchanged. */
fun EditorViewModelV4.moveTextOverlayToVideoTrackV10(textId: String, trackId: String) {
    val snapshot = state.value
    val target = snapshot.project.track(trackId)?.takeIf { it.kind == TrackKind.VIDEO } ?: return
    val current = snapshot.project.textOverlays.firstOrNull { it.id == textId } ?: return
    val currentTrackId = current.resolvedVideoTrackIdV3(snapshot.project)
    if (currentTrackId == target.id && current.videoTrackIdV3 != null) return
    if (!snapshot.project.videoTrackSlotAvailableV3(
            target.id,
            current.timelineStartUs,
            current.timelineEndUs,
            ignoreTextId = textId,
        )
    ) return

    val updated = current.copy(videoTrackIdV3 = target.id)
    commitTextProjectV10(
        snapshot.project.copy(
            textOverlays = snapshot.project.textOverlays.map { if (it.id == textId) updated else it },
        ),
        selectedTextId = textId,
        selectedTrackId = target.id,
    )
    TimelineTextSelectionBusV10.select(textId)
}

fun EditorViewModelV4.moveSelectedTextToVideoTrackV10(trackId: String) {
    val selectedId = state.value.selectedTextId ?: return
    moveTextOverlayToVideoTrackV10(selectedId, trackId)
}

fun EditorViewModelV4.applyTemplateToSelectedTextV10(template: TextTemplatePresetV10) {
    val snapshot = state.value
    val selected = snapshot.project.textOverlays.firstOrNull { it.id == snapshot.selectedTextId } ?: return
    commitTextOverlayV2(template.applyTo(selected))
    TimelineTextSelectionBusV10.select(selected.id)
}

/**
 * Resolve-style append point for a new text/title item.
 * If the V track already has either media or text, append after whichever item ends last.
 * Otherwise keep the requested playhead position.
 */
internal fun TimelineProject.appendTextStartV11(trackId: String, requestedUs: Long): Long {
    val track = track(trackId)?.takeIf { it.kind == TrackKind.VIDEO } ?: return requestedUs.coerceAtLeast(0L)
    val mediaEndUs = track.clips.maxOfOrNull { it.timelineEndUs } ?: 0L
    val textEndUs = textOverlaysForVideoTrackV3(trackId).maxOfOrNull { it.timelineEndUs } ?: 0L
    val occupiedEndUs = maxOf(mediaEndUs, textEndUs)
    return if (occupiedEndUs > 0L) occupiedEndUs else requestedUs.coerceAtLeast(0L)
}

private fun EditorViewModelV4.commitTextProjectV10(
    nextProject: TimelineProject,
    selectedTextId: String?,
    selectedTrackId: String?,
) {
    ProjectStore(getApplication()).autoSave(nextProject)
    loadProject()
    selectedTrackId?.let(::selectTrack)
    selectedTextId?.let(::selectTextOverlay)
}
