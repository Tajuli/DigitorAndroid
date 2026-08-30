package com.tajuli.digitorandroid.ui.editor

import com.tajuli.digitorandroid.editor.model.ProjectStore
import com.tajuli.digitorandroid.editor.model.TextOverlayClip

/**
 * Small bridge for Text System V2 while the V4 editor state keeps its existing public surface.
 *
 * It intentionally reuses ProjectStore + loadProject(): loadProject() checkpoints the current
 * project before replacing it, so each committed V2 style/animation edit participates in the
 * existing undo/redo history without exposing EditorViewModelV4 internals.
 */
fun EditorViewModelV4.commitTextOverlayV2(updated: TextOverlayClip) {
    val state = state.value
    val selectedId = state.selectedTextId ?: return
    if (updated.id != selectedId) return
    val current = state.project.textOverlays.firstOrNull { it.id == selectedId } ?: return
    if (current == updated) return

    val nextProject = state.project.copy(
        textOverlays = state.project.textOverlays.map { item ->
            if (item.id == selectedId) updated else item
        },
    )
    ProjectStore(getApplication()).autoSave(nextProject)
    loadProject()
    selectTextOverlay(selectedId)
}
