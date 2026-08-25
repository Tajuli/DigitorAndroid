package com.tajuli.digitorandroid.editor.model

private fun TimelineProject.findTopmostVideoClipAt(timeUs: Long): TimelineClip? = tracks
    .asSequence()
    .filter { it.kind == TrackKind.VIDEO && !it.muted }
    .flatMap { it.clips.asSequence() }
    .firstOrNull { timeUs in it.timelineStartUs until it.timelineEndUs }

/**
 * Returns the visible video clip at [timeUs]. TimelineProject.tracks is stored in the same order
 * it is drawn in the editor: first track is visually highest, so the first active video clip wins.
 *
 * This public preview lookup also updates [PreviewTransformClock]. Keeping the playhead clock here
 * means every seek and playback cursor update reaches the Media3 transform effect without coupling
 * the editor screen directly to render-thread timing state.
 */
fun TimelineProject.topmostVideoClipAt(timeUs: Long): TimelineClip? {
    val clip = findTopmostVideoClipAt(timeUs)
    PreviewTransformClock.update(clip, timeUs)
    return clip
}

/**
 * A non-overlapping interval of the video timeline where exactly one topmost clip is visible.
 *
 * Preview/export use these intervals to avoid decoding hidden lower video tracks. This is
 * especially important on phones where two or more simultaneous hardware video decoders plus
 * per-clip color effects can stall Media3 during timeline moves or overlap-heavy exports.
 */
data class VisibleVideoSegment(
    val clip: TimelineClip,
    val timelineStartUs: Long,
    val timelineEndUs: Long,
) {
    init {
        require(timelineEndUs > timelineStartUs)
        require(timelineStartUs >= clip.timelineStartUs)
        require(timelineEndUs <= clip.timelineEndUs)
    }

    val durationUs: Long get() = timelineEndUs - timelineStartUs

    /** Returns a source-trimmed fragment positioned at this segment's timeline start. */
    fun asTimelineClip(): TimelineClip {
        val sourceOffsetUs = timelineStartUs - clip.timelineStartUs
        val (_, rebasedTransform) = clip.transform.splitAt(sourceOffsetUs)
        val (segmentTransform, _) = rebasedTransform.splitAt(durationUs)
        return clip.copy(
            timelineStartUs = timelineStartUs,
            sourceInUs = clip.sourceInUs + sourceOffsetUs,
            sourceOutUs = clip.sourceInUs + sourceOffsetUs + durationUs,
            transform = segmentTransform,
        )
    }
}

/**
 * Flattens all unmuted video tracks into the one visible top-track stream.
 *
 * Project track order is top-to-bottom. At every overlap boundary only the highest active clip is
 * retained. Adjacent intervals from the same clip are merged so Media3 gets as few edits as
 * possible. Audio is intentionally not flattened; A tracks remain separate and are mixed.
 */
fun TimelineProject.visibleVideoSegments(): List<VisibleVideoSegment> {
    val videoClips = tracks
        .filter { it.kind == TrackKind.VIDEO && !it.muted }
        .flatMap { it.clips }
    if (videoClips.isEmpty()) return emptyList()

    val boundaries = videoClips
        .flatMap { listOf(it.timelineStartUs, it.timelineEndUs) }
        .distinct()
        .sorted()
    if (boundaries.size < 2) return emptyList()

    val segments = mutableListOf<VisibleVideoSegment>()
    for (index in 0 until boundaries.lastIndex) {
        val startUs = boundaries[index]
        val endUs = boundaries[index + 1]
        if (endUs <= startUs) continue
        // Export segmentation must stay pure and must not move the live preview clock.
        val visible = findTopmostVideoClipAt(startUs) ?: continue
        val previous = segments.lastOrNull()
        if (previous != null && previous.clip.id == visible.id && previous.timelineEndUs == startUs) {
            segments[segments.lastIndex] = previous.copy(timelineEndUs = endUs)
        } else {
            segments += VisibleVideoSegment(visible, startUs, endUs)
        }
    }
    return segments
}

fun TimelineProject.hasPlayableMedia(): Boolean = tracks.any { !it.muted && it.clips.isNotEmpty() }

fun TimelineProject.hasPlayableVideo(): Boolean = tracks.any {
    it.kind == TrackKind.VIDEO && !it.muted && it.clips.isNotEmpty()
}
