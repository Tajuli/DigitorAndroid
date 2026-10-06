package com.tajuli.digitorandroid.editor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SpeedCurveTimeMapTest {
    @Test fun halfSpeedMapsOutputSamplesBackToOriginalSound() {
        val map = SpeedCurveTimeMap(SpeedCurveSpec.constant(.5f).sampledSchedule(2_000_000L))
        assertEquals(1_000_000L, map.sourceTimeUs(2_000_000L))
        assertEquals(4_000_000L, map.outputTimeUs(2_000_000L))
    }

    @Test fun everyPresetRoundTripsAcrossSegmentBoundaries() {
        SpeedCurvePreset.entries.forEach { preset ->
            val schedule = SpeedCurveSpec.preset(preset).sampledSchedule(7_000_000L)
            val map = SpeedCurveTimeMap(schedule)
            val positions = schedule.startTimesUs + (0..1000).map { it * 7_000L }
            positions.forEach { source ->
                assertTrue("$preset at $source", abs(source - map.sourceTimeUs(map.outputTimeUs(source))) <= 100L)
            }
            assertEquals(0L, map.sourceTimeUs(-100L))
            assertTrue(abs(schedule.durationUs - map.sourceTimeUs(Long.MAX_VALUE)) <= 100L)
        }
    }
}
