package com.tajuli.digitorandroid.ui.editor

import com.tajuli.digitorandroid.editor.model.InputColorProfile
import com.tajuli.digitorandroid.editor.model.ProjectStore
import com.tajuli.digitorandroid.editor.model.TrackKind

/** Discrete clip-level input-profile edit using the existing project checkpoint/load path. */
fun EditorViewModelV4.commitInputColorProfile(profile: InputColorProfile) {
    val state = state.value
    val selectedId = state.selectedClipId ?: return
    val owner = state.project.trackContaining(selectedId) ?: return
    if (owner.kind != TrackKind.VIDEO) return
    val current = state.project.clip(selectedId) ?: return
    if (current.inputColorProfileV1 == profile) return

    val nextProject = state.project.copy(
        tracks = state.project.tracks.map { track ->
            if (track.id != owner.id) track else track.copy(
                clips = track.clips.map { clip ->
                    if (clip.id == selectedId) clip.copy(inputColorProfileV1 = profile) else clip
                },
            )
        },
    )
    ProjectStore(getApplication()).autoSave(nextProject)
    loadProject()
    selectClip(selectedId)
}
