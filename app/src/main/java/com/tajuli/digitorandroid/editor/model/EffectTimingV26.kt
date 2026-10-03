package com.tajuli.digitorandroid.editor.model

const val EFFECT_MIN_DURATION_US_V26 = 100_000L

/**
 * V26 timeline semantics for creator effects.
 *
 * Effect bounds are stored in absolute source time so trims/splits keep the effect anchored to the
 * original media. Null bounds preserve V25/legacy projects by resolving to the full visible clip.
 */
fun NodeEffect.resolvedSourceStartUsV26(clip: TimelineClip): Long =
    (sourceStartUsV26 ?: clip.sourceInUs).coerceIn(clip.sourceInUs, clip.sourceOutUs)

fun NodeEffect.resolvedSourceEndUsV26(clip: TimelineClip): Long {
    val start = resolvedSourceStartUsV26(clip)
    return (sourceEndUsV26 ?: clip.sourceOutUs).coerceIn(start, clip.sourceOutUs)
}

fun NodeEffect.normalizedForClipV26(clip: TimelineClip): NodeEffect {
    val minDuration = minOf(EFFECT_MIN_DURATION_US_V26, clip.durationUs).coerceAtLeast(1L)
    val rawStart = resolvedSourceStartUsV26(clip)
    val rawEnd = resolvedSourceEndUsV26(clip)
    val start = rawStart.coerceIn(clip.sourceInUs, (clip.sourceOutUs - minDuration).coerceAtLeast(clip.sourceInUs))
    val end = rawEnd.coerceIn(start + minDuration, clip.sourceOutUs)
    return copy(sourceStartUsV26 = start, sourceEndUsV26 = end)
}

fun NodeEffect.activeAtSourceTimeV26(clip: TimelineClip, sourceTimeUs: Long): Boolean {
    if (!enabled || amount <= 0f) return false
    val normalized = normalizedForClipV26(clip)
    val start = normalized.sourceStartUsV26 ?: clip.sourceInUs
    val end = normalized.sourceEndUsV26 ?: clip.sourceOutUs
    return sourceTimeUs in start until end || (sourceTimeUs == clip.sourceOutUs && sourceTimeUs == end)
}

/** Resolve only effect instances whose V26 timeline bars cover the current source frame. */
fun resolveTimedCreatorEffectsV26(
    effects: List<NodeEffect>,
    clip: TimelineClip,
    sourceTimeUs: Long,
): CreatorEffectVectorV25 = resolveCreatorEffectsV25(
    effects.filter { it.activeAtSourceTimeV26(clip, sourceTimeUs) },
)


/** Remap absolute source-time effect bars onto a newly baked 0-based retimed source. */
fun NodeEffect.retimedForBakedClipV26(
    sourceInUs: Long,
    sourceOutUs: Long,
    schedule: SpeedCurveSchedule,
): NodeEffect {
    if (sourceStartUsV26 == null && sourceEndUsV26 == null) return this
    val safeOut = sourceOutUs.coerceAtLeast(sourceInUs + 1L)
    fun map(value: Long?): Long? = value?.let {
        schedule.outputTimeForSourceTime((it.coerceIn(sourceInUs, safeOut) - sourceInUs).coerceAtLeast(0L))
    }
    val mappedStart = map(sourceStartUsV26)
    val mappedEnd = map(sourceEndUsV26)
    return copy(
        sourceStartUsV26 = mappedStart,
        sourceEndUsV26 = mappedEnd?.let { end ->
            maxOf(end, (mappedStart ?: 0L) + 1L).coerceAtMost(schedule.outputDurationUs)
        },
    )
}

fun ColorNode.retimedEffectTimingV26(
    sourceInUs: Long,
    sourceOutUs: Long,
    schedule: SpeedCurveSchedule,
): ColorNode = copy(
    effects = effects.map { it.retimedForBakedClipV26(sourceInUs, sourceOutUs, schedule) },
)

fun ClipNodeGraph.retimedEffectTimingV26(
    sourceInUs: Long,
    sourceOutUs: Long,
    schedule: SpeedCurveSchedule,
): ClipNodeGraph = copy(
    nodes = nodes.map { it.retimedEffectTimingV26(sourceInUs, sourceOutUs, schedule) },
    revision = revision + 1L,
)
