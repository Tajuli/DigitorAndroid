package com.tajuli.digitorandroid.ui.editor

import com.tajuli.digitorandroid.editor.model.TransitionStyleV22
import com.tajuli.digitorandroid.editor.model.TrackKind

/** Commit V22 cut-transition metadata through the existing project/undo pipeline. */
fun EditorViewModelV4.setSelectedTransitionV22(style: TransitionStyleV22, durationUs: Long) {
    val state = state.value
    val id = state.selectedClipId ?: return
    val clip = state.project.clip(id) ?: return
    val track = state.project.trackContaining(id)?.takeIf { it.kind == TrackKind.VIDEO } ?: return
    val clips = track.sortedClips()
    val index = clips.indexOfFirst { it.id == id }
    val previous = clips.getOrNull(index - 1)
    if (style != TransitionStyleV22.NONE && (previous == null || previous.timelineEndUs != clip.timelineStartUs)) {
        setEditorStatusV19("Transition needs a contiguous clip before the selected clip")
        return
    }

    val safeDuration = if (style == TransitionStyleV22.NONE) {
        0L
    } else {
        minOf(
            durationUs.coerceAtLeast(100_000L),
            clip.durationUs / 2L,
            previous?.durationUs ?: clip.durationUs,
            3_000_000L,
        ).coerceAtLeast(1L)
    }
    val nextTransition = clip.transition.copy(
        styleV22 = style.takeUnless { it == TransitionStyleV22.NONE },
        durationUsV22 = safeDuration,
    )
    if (nextTransition == clip.transition) return

    val nextProject = state.project.copy(
        tracks = state.project.tracks.map { candidate ->
            if (candidate.id != track.id) candidate
            else candidate.copy(
                clips = candidate.clips.map { item ->
                    if (item.id == clip.id) item.copy(transition = nextTransition) else item
                },
            )
        },
    )
    commitProjectV19(
        label = "transition-v22",
        project = nextProject,
        status = if (style == TransitionStyleV22.NONE) "Transition removed" else "${style.label} · ${safeDuration / 1000L} ms",
        coalesce = true,
    )
}
