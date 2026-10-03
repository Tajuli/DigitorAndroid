package com.tajuli.digitorandroid.editor.model

import kotlin.math.abs
import kotlin.math.ceil

const val MIN_SPEED_CURVE_SPEED = 0.1f
const val MAX_SPEED_CURVE_SPEED = 100f

enum class SpeedCurvePreset(val label: String) {
    MONTAGE("Montage"),
    HERO("Hero"),
    BULLET("Bullet"),
    JUMP_CUT("Jump Cut"),
    FLASH_IN("Flash In"),
    FLASH_OUT("Flash Out"),
    CUSTOM("Custom"),
}

data class SpeedCurvePoint(
    val position: Float,
    val speed: Float,
)

data class SpeedCurveSpec(
    val points: List<SpeedCurvePoint>,
    val preset: SpeedCurvePreset = SpeedCurvePreset.CUSTOM,
    val smoothSlowMotion: Boolean = true,
) {
    fun normalized(): SpeedCurveSpec {
        val sanitized = points
            .map {
                SpeedCurvePoint(
                    position = it.position.coerceIn(0f, 1f),
                    speed = it.speed.coerceIn(MIN_SPEED_CURVE_SPEED, MAX_SPEED_CURVE_SPEED),
                )
            }
            .sortedBy { it.position }
            .fold(mutableListOf<SpeedCurvePoint>()) { out, point ->
                if (out.isNotEmpty() && abs(out.last().position - point.position) < .0001f) {
                    out[out.lastIndex] = point
                } else {
                    out += point
                }
                out
            }

        val withBounds = when {
            sanitized.isEmpty() -> mutableListOf(
                SpeedCurvePoint(0f, 1f),
                SpeedCurvePoint(1f, 1f),
            )
            sanitized.size == 1 -> mutableListOf(
                SpeedCurvePoint(0f, sanitized.first().speed),
                SpeedCurvePoint(1f, sanitized.first().speed),
            )
            else -> sanitized.toMutableList().apply {
                if (first().position > 0f) add(0, SpeedCurvePoint(0f, first().speed))
                else this[0] = first().copy(position = 0f)
                if (last().position < 1f) add(SpeedCurvePoint(1f, last().speed))
                else this[lastIndex] = last().copy(position = 1f)
            }
        }
        return copy(points = withBounds)
    }

    fun speedAt(position: Float): Float {
        val normalized = normalized().points
        val p = position.coerceIn(0f, 1f)
        if (p <= normalized.first().position) return normalized.first().speed
        if (p >= normalized.last().position) return normalized.last().speed
        val rightIndex = normalized.indexOfFirst { it.position >= p }.coerceAtLeast(1)
        val left = normalized[rightIndex - 1]
        val right = normalized[rightIndex]
        val span = (right.position - left.position).coerceAtLeast(.000001f)
        val raw = ((p - left.position) / span).coerceIn(0f, 1f)
        val t = raw * raw * (3f - 2f * raw)
        return (left.speed + (right.speed - left.speed) * t)
            .coerceIn(MIN_SPEED_CURVE_SPEED, MAX_SPEED_CURVE_SPEED)
    }

    val hasSlowMotion: Boolean
        get() = normalized().points.any { it.speed < .999f }

    fun sampledSchedule(
        durationUs: Long,
        preferredStepUs: Long = 20_000L,
        maxSegments: Int = 6_000,
    ): SpeedCurveSchedule {
        val duration = durationUs.coerceAtLeast(1L)
        val boundedSegments = maxSegments.coerceAtLeast(2)
        val minStepForBound = ceil(duration.toDouble() / boundedSegments.toDouble()).toLong()
        val stepUs = maxOf(5_000L, preferredStepUs, minStepForBound)

        val starts = mutableListOf<Long>()
        val speeds = mutableListOf<Float>()
        var cursor = 0L
        while (cursor < duration) {
            val speed = speedAt(cursor.toFloat() / duration.toFloat())
            if (speeds.isEmpty() || abs(speeds.last() - speed) >= .001f) {
                starts += cursor
                speeds += speed
            }
            cursor = (cursor + stepUs).coerceAtMost(duration)
        }
        if (starts.isEmpty()) {
            starts += 0L
            speeds += 1f
        } else if (starts.first() != 0L) {
            starts.add(0, 0L)
            speeds.add(0, speedAt(0f))
        }
        return SpeedCurveSchedule(duration, starts, speeds)
    }

    companion object {
        fun constant(speed: Float, smoothSlowMotion: Boolean = false): SpeedCurveSpec =
            SpeedCurveSpec(
                points = listOf(
                    SpeedCurvePoint(0f, speed),
                    SpeedCurvePoint(1f, speed),
                ),
                preset = SpeedCurvePreset.CUSTOM,
                smoothSlowMotion = smoothSlowMotion,
            ).normalized()

        fun preset(preset: SpeedCurvePreset, smoothSlowMotion: Boolean = true): SpeedCurveSpec {
            val points = when (preset) {
                SpeedCurvePreset.MONTAGE -> listOf(1f, 3.6f, .55f, 3.2f, 1f)
                SpeedCurvePreset.HERO -> listOf(1f, .62f, .28f, .62f, 1.45f)
                SpeedCurvePreset.BULLET -> listOf(1f, .42f, .16f, .42f, 1f)
                SpeedCurvePreset.JUMP_CUT -> listOf(1f, 4f, 1f, 4f, 1f)
                SpeedCurvePreset.FLASH_IN -> listOf(4f, 2.4f, .72f, .30f, 1f)
                SpeedCurvePreset.FLASH_OUT -> listOf(1f, .30f, .72f, 2.4f, 4f)
                SpeedCurvePreset.CUSTOM -> listOf(1f, 1f, 1f, 1f, 1f)
            }.mapIndexed { index, speed ->
                SpeedCurvePoint(index / 4f, speed)
            }
            return SpeedCurveSpec(points, preset, smoothSlowMotion).normalized()
        }
    }
}

data class SpeedCurveSchedule(
    val durationUs: Long,
    val startTimesUs: List<Long>,
    val speeds: List<Float>,
) {
    init {
        require(durationUs > 0L)
        require(startTimesUs.isNotEmpty())
        require(startTimesUs.size == speeds.size)
        require(startTimesUs.first() == 0L)
    }

    fun speedAtSourceTime(relativeSourceUs: Long): Float {
        val time = relativeSourceUs.coerceIn(0L, durationUs)
        var lo = 0
        var hi = startTimesUs.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (startTimesUs[mid] <= time) lo = mid else hi = mid - 1
        }
        return speeds[lo].coerceIn(MIN_SPEED_CURVE_SPEED, MAX_SPEED_CURVE_SPEED)
    }

    fun nextChangeAfter(relativeSourceUs: Long): Long? =
        startTimesUs.firstOrNull { it > relativeSourceUs }

    fun outputTimeForSourceTime(relativeSourceUs: Long): Long {
        val target = relativeSourceUs.coerceIn(0L, durationUs)
        var output = 0.0
        for (index in startTimesUs.indices) {
            val start = startTimesUs[index]
            if (start >= target) break
            val end = minOf(
                target,
                startTimesUs.getOrNull(index + 1) ?: durationUs,
            )
            if (end > start) output += (end - start).toDouble() / speeds[index].coerceAtLeast(.001f)
            if (end == target) break
        }
        return output.toLong().coerceAtLeast(0L)
    }

    val outputDurationUs: Long
        get() = outputTimeForSourceTime(durationUs).coerceAtLeast(1L)
}
