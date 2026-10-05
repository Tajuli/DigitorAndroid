package com.tajuli.digitorandroid.editor.model

/** Pure snapshot edit. Ripple subsequent items together; reject collisions before publishing. */
internal fun TimelineProject.withClipSpeed(clipId: String, curve: SpeedCurveSpec): TimelineProject {
    val video = clip(clipId) ?: return this
    val linked = linkedClipIds(clipId)
    val normalized = curve.normalized()
    if (video.retime?.curve == normalized) return this
    val retime = if (!normalized.smoothSlowMotion && normalized.points.all { it.speed == 1f }) null else {
        val previous = video.retime
        if (previous != null && previous.curve.normalized().points == normalized.points)
            previous.copy(curve = normalized)
        else ClipRetime(normalized, video.sourceInUs, video.sourceOutUs)
    }
    val changed = video.copy(retime = retime)
    val delta = changed.durationUs - video.durationUs
    val tracks = tracks.map { track ->
        val clips = track.clips.map { item ->
            when {
                item.id in linked -> {
                    require(item.sourceDurationUs == video.sourceDurationUs && item.timelineStartUs == video.timelineStartUs) {
                        "Linked sound must cover the same source duration before changing speed"
                    }
                    val offsetUs = item.sourceInUs - video.sourceInUs
                    item.copy(retime = retime?.copy(
                        sourceStartUs = retime.sourceStartUs + offsetUs,
                        sourceEndUs = retime.sourceEndUs + offsetUs,
                    ))
                }
                item.timelineStartUs >= video.timelineEndUs -> item.copy(timelineStartUs = item.timelineStartUs + delta)
                else -> item
            }
        }
        require(clips.sortedBy { it.timelineStartUs }.zipWithNext().all { (a, b) -> a.timelineEndUs <= b.timelineStartUs }) {
            "Speed change would overlap clips on ${track.name}"
        }
        track.copy(clips = clips)
    }
    return copy(tracks = tracks,
        textOverlays = textOverlays.map { if (it.timelineStartUs >= video.timelineEndUs)
            it.copy(timelineStartUs = it.timelineStartUs + delta, timelineEndUs = it.timelineEndUs + delta) else it },
        visualOverlaysV19 = visualOverlaysV19?.map { if (it.timelineStartUs >= video.timelineEndUs)
            it.copy(timelineStartUs = it.timelineStartUs + delta, timelineEndUs = it.timelineEndUs + delta) else it })
}
