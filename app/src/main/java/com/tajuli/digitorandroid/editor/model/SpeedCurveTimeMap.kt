package com.tajuli.digitorandroid.editor.model

/** Bidirectional sample clock, precomputed once instead of integrating the curve per PCM sample. */
internal class SpeedCurveTimeMap(private val schedule: SpeedCurveSchedule) {
    private val outputStarts = DoubleArray(schedule.startTimesUs.size).apply {
        for (i in 1 until size) {
            this[i] = this[i - 1] + (schedule.startTimesUs[i] - schedule.startTimesUs[i - 1]) /
                schedule.speeds[i - 1].toDouble()
        }
    }

    fun outputTimeUs(sourceUs: Long): Long = schedule.outputTimeForSourceTime(sourceUs)

    fun sourceTimeUs(outputUs: Long): Long {
        val time = outputUs.coerceIn(0L, schedule.outputDurationUs).toDouble()
        var lo = 0
        var hi = outputStarts.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (outputStarts[mid] <= time) lo = mid else hi = mid - 1
        }
        return (schedule.startTimesUs[lo] + (time - outputStarts[lo]) * schedule.speeds[lo])
            .toLong().coerceIn(0L, schedule.durationUs)
    }
}
