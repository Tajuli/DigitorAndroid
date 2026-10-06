package com.tajuli.digitorandroid.editor.model

/** Curve coordinates stay anchored to source media across trims and splits. Null means legacy 1x. */
data class ClipRetime(
    val curve: SpeedCurveSpec,
    val sourceStartUs: Long,
    val sourceEndUs: Long,
)

/** Bounded immutable maps shared by render/audio workers and timeline edits. */
private object RetimeMaps {
    private val maps = object : LinkedHashMap<ClipRetime, SpeedCurveTimeMap>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ClipRetime, SpeedCurveTimeMap>?) = size > 64
    }
    @Synchronized fun get(spec: ClipRetime): SpeedCurveTimeMap = maps.getOrPut(spec) {
        SpeedCurveTimeMap(spec.curve.sampledSchedule((spec.sourceEndUs - spec.sourceStartUs).coerceAtLeast(1L)))
    }
}

val TimelineClip.sourceDurationUs: Long get() = (sourceOutUs - sourceInUs).coerceAtLeast(1L)

/** Unclamped variants are for trim handles; frame readers use the bounded variants below. */
fun TimelineClip.outputTimeForSourceUnbounded(sourceUs: Long): Long {
    val spec = retime ?: return sourceUs - sourceInUs
    val map = RetimeMaps.get(spec)
    return (map.outputUnbounded(sourceUs - spec.sourceStartUs) -
        map.outputUnbounded(sourceInUs - spec.sourceStartUs)).toLong()
}

fun TimelineClip.sourceTimeForOutputUnbounded(localUs: Long): Long {
    val spec = retime ?: return sourceInUs + localUs
    val map = RetimeMaps.get(spec)
    return spec.sourceStartUs + map.sourceUnbounded(map.outputUnbounded(sourceInUs - spec.sourceStartUs) + localUs).toLong()
}

fun TimelineClip.outputTimeForSource(sourceUs: Long): Long =
    outputTimeForSourceUnbounded(sourceUs.coerceIn(sourceInUs, sourceOutUs)).coerceAtLeast(0L)

fun TimelineClip.sourceTimeForOutput(localUs: Long): Long =
    sourceTimeForOutputUnbounded(localUs).coerceIn(sourceInUs, sourceOutUs)

fun TimelineClip.sourceTimeAtTimeline(timelineUs: Long): Long = sourceTimeForOutput(timelineUs - timelineStartUs)
fun TimelineClip.timelineTimeAtSource(sourceUs: Long): Long = timelineStartUs + outputTimeForSource(sourceUs)

fun TimelineClip.speedAtSource(sourceUs: Long): Float {
    val spec = retime ?: return 1f
    return RetimeMaps.get(spec).speedAtSourceTime(sourceUs - spec.sourceStartUs)
}
