package com.tajuli.digitorandroid.editor.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewFrameSchedulerTest {
    @Test
    fun mapsCompositionPtsToMonotonicPresentationTime() {
        val scheduler = PreviewFrameScheduler()
        scheduler.reset(timelineUs = 2_000_000L, realtimeNs = 8_000_000_000L)

        assertEquals(8_033_366_000L, scheduler.presentationTimeNs(2_033_366L))
    }

    @Test
    fun keepsSlightlyLateFrameButDropsFramePastLateThreshold() {
        val scheduler = PreviewFrameScheduler(maxLateUs = 40_000L)
        scheduler.reset(timelineUs = 0L, realtimeNs = 1_000_000_000L)

        assertFalse(scheduler.isTooLate(950_000L, 1_980_000_000L))
        assertTrue(scheduler.isTooLate(950_000L, 1_991_000_000L))
    }

    @Test
    fun resetAfterSeekStartsNewClockEpoch() {
        val scheduler = PreviewFrameScheduler()
        scheduler.reset(timelineUs = 0L, realtimeNs = 1_000_000_000L)
        scheduler.reset(timelineUs = 5_000_000L, realtimeNs = 9_000_000_000L)

        assertEquals(9_016_667_000L, scheduler.presentationTimeNs(5_016_667L))
    }
}
