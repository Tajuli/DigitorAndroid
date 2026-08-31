package com.tajuli.digitorandroid.ui.editor

import android.net.Uri
import com.tajuli.digitorandroid.editor.model.TrackKind

/**
 * Makes media import obey the same Resolve-style single-lane append rule as titles.
 *
 * EditorViewModelV4's legacy importer appends after media clips, but it predates V-track text clips
 * and therefore cannot see a title that ends later than the last media clip. We keep that proven
 * importer for URI/metadata/audio-link handling, then shift only the newly imported linked groups
 * to the end of the selected V track when text extends that lane farther in time.
 */
fun EditorViewModelV4.importUrisAppendAwareV12(uris: List<Uri>) {
    if (uris.isEmpty()) return

    val before = state.value
    val selectedTrack = before.project.track(before.selectedTrackId)
    if (selectedTrack?.kind != TrackKind.VIDEO) {
        importUris(uris)
        TimelineTextSelectionBusV10.clear()
        state.value.selectedClipId?.let(::selectClip)
        return
    }

    val oldClipIds = selectedTrack.clips.mapTo(hashSetOf()) { it.id }
    // Same rule used by Text/Caption/template insertion: existing media OR text makes the next
    // item start after the last item on this V track.
    val appendFloorUs = before.project.appendTextStartV11(selectedTrack.id, requestedUs = 0L)

    importUris(uris)

    val importedState = state.value
    val importedTrack = importedState.project.track(selectedTrack.id) ?: return
    val newVideoClips = importedTrack.clips
        .filterNot { it.id in oldClipIds }
        .sortedBy { it.timelineStartUs }

    if (newVideoClips.isNotEmpty()) {
        // The old importer may already have moved the import later to stay aligned with a paired
        // audio track. Never pull it left; only move it right when a title occupies the V lane.
        val firstImportedStartUs = newVideoClips.first().timelineStartUs
        val shiftUs = (appendFloorUs - firstImportedStartUs).coerceAtLeast(0L)
        if (shiftUs > 0L) {
            // Move from right to left so each clip has free space before the preceding clip moves.
            // moveClip() also moves a linked source-audio clip by the same delta.
            newVideoClips.asReversed().forEach { clip ->
                moveClip(importedTrack.id, clip.id, shiftUs)
            }
        }
    }

    // Import selects the new media item. Clear any stale title selection from both state/UI buses
    // so a title and the newly imported video cannot appear selected at the same time.
    TimelineTextSelectionBusV10.clear()
    state.value.selectedClipId?.let(::selectClip)
}
