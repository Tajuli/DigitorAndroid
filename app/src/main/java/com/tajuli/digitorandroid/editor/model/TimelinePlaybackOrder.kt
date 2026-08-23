package com.tajuli.digitorandroid.editor.model

/**
 * Returns the visible video clip at [timeUs]. TimelineProject.tracks is stored in the same order
 * it is drawn in the editor: first track is visually highest, so the first active video clip wins.
 */
fun TimelineProject.topmostVideoClipAt(timeUs: Long): TimelineClip? = tracks
    .asSequence()
    .filter { it.kind == TrackKind.VIDEO && !it.muted }
    .flatMap { it.clips.asSequence() }
    .firstOrNull { timeUs in it.timelineStartUs until it.timelineEndUs }

fun TimelineProject.hasPlayableMedia(): Boolean = tracks.any { !it.muted && it.clips.isNotEmpty() }

fun TimelineProject.hasPlayableVideo(): Boolean = tracks.any {
    it.kind == TrackKind.VIDEO && !it.muted && it.clips.isNotEmpty()
}
