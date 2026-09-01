package com.tajuli.digitorandroid.editor.model

private const val MAX_TRANSITION_DURATION_US_V22 = 3_000_000L

/** One resolved cut transition between two contiguous clips on the same V track. */
data class TransitionPairV22(
    val trackId: String,
    val outgoing: TimelineClip,
    val incoming: TimelineClip,
    val style: TransitionStyleV22,
    val durationUs: Long,
) {
    val startUs: Long get() = incoming.timelineStartUs
    val endUs: Long get() = startUs + durationUs
}

fun TimelineTrack.transitionPairsV22(): List<TransitionPairV22> {
    if (kind != TrackKind.VIDEO || muted) return emptyList()
    val clips = sortedClips()
    if (clips.size < 2) return emptyList()
    return clips.zipWithNext().mapNotNull { (outgoing, incoming) ->
        if (outgoing.timelineEndUs != incoming.timelineStartUs) return@mapNotNull null
        val transition = incoming.transition.normalizedFor(incoming.durationUs)
        if (!transition.hasCutTransitionV22) return@mapNotNull null
        val duration = minOf(
            transition.resolvedDurationUsV22,
            outgoing.durationUs,
            incoming.durationUs,
            MAX_TRANSITION_DURATION_US_V22,
        ).coerceAtLeast(0L)
        if (duration <= 0L) return@mapNotNull null
        TransitionPairV22(
            trackId = id,
            outgoing = outgoing,
            incoming = incoming,
            style = transition.resolvedStyleV22,
            durationUs = duration,
        )
    }
}

fun TimelineTrack.transitionPairForIncomingV22(clipId: String): TransitionPairV22? =
    transitionPairsV22().firstOrNull { it.incoming.id == clipId }

fun TimelineProject.transitionPairsV22(): List<TransitionPairV22> =
    tracks.filter { it.kind == TrackKind.VIDEO && !it.muted }.flatMap { it.transitionPairsV22() }

fun TimelineProject.hasCutTransitionsV22(): Boolean = transitionPairsV22().isNotEmpty()
