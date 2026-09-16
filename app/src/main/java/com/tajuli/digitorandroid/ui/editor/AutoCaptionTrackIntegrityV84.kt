package com.tajuli.digitorandroid.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.processing.autoCaptionTrackIdV84
import com.tajuli.digitorandroid.editor.processing.deleteAutoCaptionTrackV84

/**
 * TimelineEditorV4 already gives every track (including CC) the normal V-track long-press Delete UI.
 * Its historical generic delete path removes the TimelineTrack first. This guard remembers the CC id
 * and immediately removes text that belonged to that deleted track, so captions cannot fall through
 * onto V1 after CC is deleted.
 */
@Composable
internal fun AutoCaptionTrackIntegrityV84(vm: EditorViewModelV4) {
    val state by vm.state.collectAsState()
    val currentCcTrackId = state.project.autoCaptionTrackIdV84()
    var lastCcTrackId by remember { mutableStateOf(currentCcTrackId) }

    LaunchedEffect(currentCcTrackId, state.project.textOverlays) {
        if (currentCcTrackId != null) {
            lastCcTrackId = currentCcTrackId
            return@LaunchedEffect
        }

        val deletedTrackId = lastCcTrackId ?: return@LaunchedEffect
        val cleaned = state.project.deleteAutoCaptionTrackV84(previousTrackId = deletedTrackId)
        if (cleaned != state.project) {
            TimelineTextSelectionBusV10.clear()
            vm.commitProjectV19(
                label = "cleanup-deleted-cc-track-v84",
                project = cleaned,
                status = "CC track and captions deleted",
            )
            val fallback = cleaned.tracks.firstOrNull { it.kind == TrackKind.VIDEO }?.id
                ?: cleaned.tracks.firstOrNull()?.id
            vm.focusVisualOverlayV19(fallback)
        }
        lastCcTrackId = null
    }
}
