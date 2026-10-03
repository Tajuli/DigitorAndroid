package com.tajuli.digitorandroid.editor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedCurveTest {
    @Test
    fun constantCurveHasExpectedDuration() {
        val schedule = SpeedCurveSpec.constant(.5f).sampledSchedule(2_000_000L)
        assertEquals(4_000_000L, schedule.outputDurationUs, 30_000L)
        assertEquals(.5f, schedule.speedAtSourceTime(1_000_000L), .01f)
    }

    @Test
    fun presetScheduleIsMonotonicAndBounded() {
        val schedule = SpeedCurveSpec.preset(SpeedCurvePreset.HERO).sampledSchedule(8_000_000L)
        assertEquals(0L, schedule.startTimesUs.first())
        assertTrue(schedule.startTimesUs.zipWithNext().all { (a, b) -> b > a })
        assertTrue(schedule.speeds.all { it in MIN_SPEED_CURVE_SPEED..MAX_SPEED_CURVE_SPEED })
        assertTrue(schedule.outputDurationUs > 0L)
    }

    @Test
    fun sourceToOutputMappingNeverMovesBackwards() {
        val schedule = SpeedCurveSpec.preset(SpeedCurvePreset.MONTAGE).sampledSchedule(5_000_000L)
        val mapped = (0..100).map { schedule.outputTimeForSourceTime(it * 50_000L) }
        assertTrue(mapped.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(schedule.outputDurationUs, schedule.outputTimeForSourceTime(5_000_000L))
    }

    @Test
    fun normalizationAddsEndpointsAndSorts() {
        val curve = SpeedCurveSpec(
            listOf(
                SpeedCurvePoint(.8f, 2f),
                SpeedCurvePoint(.2f, .2f),
            ),
        ).normalized()
        assertEquals(0f, curve.points.first().position, 0f)
        assertEquals(1f, curve.points.last().position, 0f)
        assertTrue(curve.points.zipWithNext().all { (a, b) -> b.position > a.position })
    }
}
