package com.tajuli.digitorandroid.ui.editor

import android.net.Uri
import com.tajuli.digitorandroid.editor.model.ShapePresetV19
import com.tajuli.digitorandroid.editor.model.StickerPresetV19
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.VisualOverlayClipV19
import com.tajuli.digitorandroid.editor.model.VisualOverlayKindV19
import com.tajuli.digitorandroid.editor.model.resolvedVideoTrackIdV19
import com.tajuli.digitorandroid.editor.model.resolvedVisualOverlaysV19
import com.tajuli.digitorandroid.editor.model.textOverlaysForVideoTrackV3
import com.tajuli.digitorandroid.editor.model.visualOverlaySlotAvailableV19
import com.tajuli.digitorandroid.editor.model.visualOverlaysForVideoTrackV19
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max

object VisualOverlaySelectionBusV19 {
    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    fun select(id: String?) { _selectedId.value = id }
    fun clear(id: String? = null) {
        if (id == null || _selectedId.value == id) _selectedId.value = null
    }
}

fun EditorViewModelV4.selectedVideoTrackForVisualV19(): TimelineTrack? {
    val snapshot = state.value
    val selected = snapshot.project.track(snapshot.selectedTrackId)
    return selected?.takeIf { it.kind == TrackKind.VIDEO }
        ?: snapshot.project.tracks.firstOrNull { it.kind == TrackKind.VIDEO && it.name == "V1" }
        ?: snapshot.project.tracks.firstOrNull { it.kind == TrackKind.VIDEO }
}

fun EditorViewModelV4.addImageOverlayV19(uri: Uri, cursorUs: Long) {
    addVisualOverlayV19(
        kind = VisualOverlayKindV19.IMAGE,
        cursorUs = cursorUs,
        label = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Image",
        imageUri = uri.toString(),
    )
}

fun EditorViewModelV4.addStickerOverlayV19(preset: StickerPresetV19, cursorUs: Long) {
    addVisualOverlayV19(
        kind = VisualOverlayKindV19.STICKER,
        cursorUs = cursorUs,
        label = preset.name.lowercase().replaceFirstChar { it.uppercase() },
        stickerPreset = preset,
        scale = .18f,
    )
}

fun EditorViewModelV4.addShapeOverlayV19(preset: ShapePresetV19, cursorUs: Long) {
    addVisualOverlayV19(
        kind = VisualOverlayKindV19.SHAPE,
        cursorUs = cursorUs,
        label = preset.name.lowercase().replaceFirstChar { it.uppercase() },
        shapePreset = preset,
        scale = .24f,
    )
}

private fun EditorViewModelV4.addVisualOverlayV19(
    kind: VisualOverlayKindV19,
    cursorUs: Long,
    label: String,
    imageUri: String? = null,
    stickerPreset: StickerPresetV19? = null,
    shapePreset: ShapePresetV19? = null,
    scale: Float = .30f,
) {
    val snapshot = state.value
    val project = snapshot.project
    val track = selectedVideoTrackForVisualV19() ?: run {
        setEditorStatusV19("Add a video track first")
        return
    }
    val laneEnd = maxOf(
        track.clips.maxOfOrNull { it.timelineEndUs } ?: 0L,
        project.textOverlaysForVideoTrackV3(track.id).maxOfOrNull { it.timelineEndUs } ?: 0L,
        project.visualOverlaysForVideoTrackV19(track.id).maxOfOrNull { it.timelineEndUs } ?: 0L,
    )
    val laneEmpty = track.clips.isEmpty() &&
        project.textOverlaysForVideoTrackV3(track.id).isEmpty() &&
        project.visualOverlaysForVideoTrackV19(track.id).isEmpty()
    val startUs = if (laneEmpty) cursorUs.coerceAtLeast(0L) else laneEnd
    val overlay = VisualOverlayClipV19(
        kind = kind,
        label = label,
        timelineStartUs = startUs,
        timelineEndUs = startUs + DEFAULT_VISUAL_DURATION_US,
        imageUri = imageUri,
        stickerPreset = stickerPreset,
        shapePreset = shapePreset,
        scale = scale,
        videoTrackIdV19 = track.id,
    ).normalized()
    val next = project.copy(visualOverlaysV19 = project.resolvedVisualOverlaysV19() + overlay)
    commitProjectV19("add-visual-overlay", next, status = "${overlay.label} added to ${track.name}")
    VisualOverlaySelectionBusV19.select(overlay.id)
    focusVisualOverlayV19(track.id)
}

fun EditorViewModelV4.selectVisualOverlayV19(id: String) {
    val project = state.value.project
    val overlay = project.resolvedVisualOverlaysV19().firstOrNull { it.id == id } ?: return
    VisualOverlaySelectionBusV19.select(id)
    focusVisualOverlayV19(overlay.resolvedVideoTrackIdV19(project))
}

fun EditorViewModelV4.updateSelectedVisualV19(
    positionX: Float? = null,
    positionY: Float? = null,
    scale: Float? = null,
    rotationDegrees: Float? = null,
    opacity: Float? = null,
    colorArgb: Long? = null,
) {
    val id = VisualOverlaySelectionBusV19.selectedId.value ?: return
    val project = state.value.project
    val current = project.resolvedVisualOverlaysV19().firstOrNull { it.id == id } ?: return
    val updated = current.copy(
        positionX = positionX ?: current.positionX,
        positionY = positionY ?: current.positionY,
        scale = scale ?: current.scale,
        rotationDegrees = rotationDegrees ?: current.rotationDegrees,
        opacity = opacity ?: current.opacity,
        colorArgb = colorArgb ?: current.colorArgb,
    ).normalized()
    replaceVisualV19(project, updated, "visual-transform", "Overlay updated", coalesce = true)
}

fun EditorViewModelV4.setSelectedVisualDurationV19(durationUs: Long) {
    val id = VisualOverlaySelectionBusV19.selectedId.value ?: return
    val project = state.value.project
    val current = project.resolvedVisualOverlaysV19().firstOrNull { it.id == id } ?: return
    val trackId = current.resolvedVideoTrackIdV19(project) ?: return
    val endUs = current.timelineStartUs + durationUs.coerceIn(MIN_VISUAL_DURATION_US, MAX_VISUAL_DURATION_US)
    if (!project.visualOverlaySlotAvailableV19(trackId, current.timelineStartUs, endUs, id)) {
        setEditorStatusV19("Cannot resize: ${project.track(trackId)?.name ?: "V track"} is occupied")
        return
    }
    replaceVisualV19(project, current.copy(timelineEndUs = endUs), "visual-duration", "Overlay duration updated", coalesce = true)
}

fun EditorViewModelV4.moveVisualOverlayV19(id: String, deltaUs: Long) {
    if (deltaUs == 0L) return
    val project = state.value.project
    val current = project.resolvedVisualOverlaysV19().firstOrNull { it.id == id } ?: return
    val trackId = current.resolvedVideoTrackIdV19(project) ?: return
    val startUs = (current.timelineStartUs + deltaUs).coerceAtLeast(0L)
    val endUs = startUs + current.durationUs
    if (!project.visualOverlaySlotAvailableV19(trackId, startUs, endUs, id)) return
    replaceVisualV19(project, current.copy(timelineStartUs = startUs, timelineEndUs = endUs), "move-visual", "Overlay moved", coalesce = true)
}

fun EditorViewModelV4.moveVisualOverlayToTrackV19(id: String, targetTrackId: String) {
    val project = state.value.project
    val current = project.resolvedVisualOverlaysV19().firstOrNull { it.id == id } ?: return
    val target = project.track(targetTrackId)?.takeIf { it.kind == TrackKind.VIDEO } ?: return
    if (current.resolvedVideoTrackIdV19(project) == target.id) return
    if (!project.visualOverlaySlotAvailableV19(target.id, current.timelineStartUs, current.timelineEndUs, id)) {
        setEditorStatusV19("Cannot drop: ${target.name} is occupied")
        return
    }
    replaceVisualV19(project, current.copy(videoTrackIdV19 = target.id), "move-visual-track", "Moved to ${target.name}")
    focusVisualOverlayV19(target.id)
}

fun EditorViewModelV4.resizeVisualStartV19(id: String, targetStartUs: Long) {
    val project = state.value.project
    val current = project.resolvedVisualOverlaysV19().firstOrNull { it.id == id } ?: return
    val trackId = current.resolvedVideoTrackIdV19(project) ?: return
    val start = targetStartUs.coerceIn(0L, current.timelineEndUs - MIN_VISUAL_DURATION_US)
    if (!project.visualOverlaySlotAvailableV19(trackId, start, current.timelineEndUs, id)) return
    replaceVisualV19(project, current.copy(timelineStartUs = start), "resize-visual", "Overlay resized", coalesce = true)
}

fun EditorViewModelV4.resizeVisualEndV19(id: String, targetEndUs: Long) {
    val project = state.value.project
    val current = project.resolvedVisualOverlaysV19().firstOrNull { it.id == id } ?: return
    val trackId = current.resolvedVideoTrackIdV19(project) ?: return
    val end = targetEndUs.coerceAtLeast(current.timelineStartUs + MIN_VISUAL_DURATION_US)
    if (!project.visualOverlaySlotAvailableV19(trackId, current.timelineStartUs, end, id)) return
    replaceVisualV19(project, current.copy(timelineEndUs = end), "resize-visual", "Overlay resized", coalesce = true)
}

fun EditorViewModelV4.deleteSelectedVisualV19() {
    val id = VisualOverlaySelectionBusV19.selectedId.value ?: return
    val project = state.value.project
    if (project.resolvedVisualOverlaysV19().none { it.id == id }) return
    val next = project.copy(visualOverlaysV19 = project.resolvedVisualOverlaysV19().filterNot { it.id == id })
    commitProjectV19("delete-visual", next, status = "Overlay deleted")
    VisualOverlaySelectionBusV19.clear(id)
}

private fun EditorViewModelV4.replaceVisualV19(
    project: TimelineProject,
    updated: VisualOverlayClipV19,
    label: String,
    status: String,
    coalesce: Boolean = false,
) {
    val next = project.copy(
        visualOverlaysV19 = project.resolvedVisualOverlaysV19().map { if (it.id == updated.id) updated.normalized() else it },
    )
    commitProjectV19(label, next, status = status, coalesce = coalesce)
}

private const val DEFAULT_VISUAL_DURATION_US = 3_000_000L
private const val MIN_VISUAL_DURATION_US = 100_000L
private const val MAX_VISUAL_DURATION_US = 60_000_000L
