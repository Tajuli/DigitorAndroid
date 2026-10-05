package com.tajuli.digitorandroid.editor.model

/** Precomputed bidirectional clock. Every query is O(log n), without cumulative playback deltas. */
internal class SpeedCurveTimeMap(private val schedule: SpeedCurveSchedule) {
    private val sourceStarts = schedule.startTimesUs
    private val outputStarts = DoubleArray(sourceStarts.size).apply {
        for (i in 1 until size) {
            this[i] = this[i - 1] + (sourceStarts[i] - sourceStarts[i - 1]) / schedule.speeds[i - 1].toDouble()
        }
    }
    private val outputEnd = outputStarts.last() +
        (schedule.durationUs - sourceStarts.last()) / schedule.speeds.last().toDouble()

    fun speedAtSourceTime(sourceUs: Long): Float = schedule.speedAtSourceTime(sourceUs)
    fun outputTimeUs(sourceUs: Long): Long = outputUnbounded(sourceUs.coerceIn(0L, schedule.durationUs)).toLong()
    fun sourceTimeUs(outputUs: Long): Long = sourceUnbounded(outputUs.coerceIn(0L, outputEnd.toLong()).toDouble())
        .toLong().coerceIn(0L, schedule.durationUs)

    /** Extend trims beyond the original curve window at the nearest endpoint speed. */
    fun outputUnbounded(sourceUs: Long): Double {
        if (sourceUs < 0L) return sourceUs / schedule.speeds.first().toDouble()
        if (sourceUs > schedule.durationUs) return outputEnd +
            (sourceUs - schedule.durationUs) / schedule.speeds.last().toDouble()
        val i = floorIndex { sourceStarts[it] <= sourceUs }
        return outputStarts[i] + (sourceUs - sourceStarts[i]) / schedule.speeds[i].toDouble()
    }

    fun sourceUnbounded(outputUs: Double): Double {
        if (outputUs < 0.0) return outputUs * schedule.speeds.first()
        if (outputUs > outputEnd) return schedule.durationUs +
            (outputUs - outputEnd) * schedule.speeds.last()
        val i = floorIndex { outputStarts[it] <= outputUs }
        return sourceStarts[i] + (outputUs - outputStarts[i]) * schedule.speeds[i]
    }

    private inline fun floorIndex(before: (Int) -> Boolean): Int {
        var lo = 0
        var hi = sourceStarts.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (before(mid)) lo = mid else hi = mid - 1
        }
        return lo
    }
}
