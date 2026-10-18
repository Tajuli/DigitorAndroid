package com.tajuli.digitorandroid.editor.preview

/** Maps composition timestamps onto a monotonic playback clock for Surface presentation. */
internal class PreviewFrameScheduler(
    private val maxLateUs: Long = 45_000L,
) {
    private var anchorTimelineUs = 0L
    private var anchorRealtimeNs = 0L

    fun reset(timelineUs: Long, realtimeNs: Long) {
        anchorTimelineUs = timelineUs
        anchorRealtimeNs = realtimeNs
    }

    fun presentationTimeNs(timelineUs: Long): Long {
        if (anchorRealtimeNs == 0L) return System.nanoTime()
        return anchorRealtimeNs + (timelineUs - anchorTimelineUs) * 1_000L
    }

    fun isTooLate(timelineUs: Long, nowNs: Long): Boolean =
        presentationTimeNs(timelineUs) < nowNs - maxLateUs * 1_000L
}
