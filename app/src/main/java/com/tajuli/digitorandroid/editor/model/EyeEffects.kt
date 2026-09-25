package com.tajuli.digitorandroid.editor.model

import kotlin.math.abs

/** Stable names are serialized through the existing timed NodeEffect contract. */
object EyeEffectCatalog {
    val names = listOf("Fire Eyes", "Laser Eyes", "Lightning Eyes", "Plasma Eyes",
        "Ice Eyes", "Galaxy Eyes", "Neon Eyes", "Solar Eyes", "Cyber Eyes",
        "Heart Eyes", "Star Eyes", "Rainbow Eyes")
    fun index(name: String): Int = names.indexOfFirst { it.equals(name, true) }
    fun contains(name: String): Boolean = index(name) >= 0
}

fun TimelineClip.hasEyeEffects(): Boolean = nodeGraph.nodes.any { node ->
    (node.kind == NodeKind.SERIAL || node.kind == NodeKind.PARALLEL) &&
        node.visibleEffects().any { EyeEffectCatalog.contains(it.name) &&
            ((it.enabled && it.amount > 0f) || nodeAnimations.hasAnimation(node.id, NodeAnimationDomain.EFFECTS)) }
}

fun resolveEyeEffects(effects: List<NodeEffect>, clip: TimelineClip, timeUs: Long): FloatArray {
    val amounts = FloatArray(EyeEffectCatalog.names.size)
    effects.filter { it.activeAtSourceTimeV26(clip, timeUs) }.forEach {
        val index = EyeEffectCatalog.index(it.name)
        if (index >= 0) amounts[index] = (amounts[index] + it.amount).coerceIn(0f, 1f)
    }
    return amounts
}

/** Center and horizontal half-width in normalized source coordinates; roll is in radians. */
data class TrackedEye(val x: Float, val y: Float, val radius: Float, val roll: Float, val open: Float) {
    fun interpolate(other: TrackedEye, t: Float): TrackedEye {
        val delta = kotlin.math.atan2(kotlin.math.sin(other.roll - roll), kotlin.math.cos(other.roll - roll))
        return TrackedEye(x + (other.x-x)*t, y + (other.y-y)*t,
            radius + (other.radius-radius)*t, roll + delta*t, open + (other.open-open)*t)
    }
}
data class EyePose(val left: TrackedEye, val right: TrackedEye, val identity: Int?)
data class EyeSample(val timeUs: Long, val pose: EyePose?)
data class EyeTrack(val uri: String, val startUs: Long, val endUs: Long, val samples: List<EyeSample>, val version: Int = 1) {
    fun covers(clip: TimelineClip): Boolean = version == 1 && uri == clip.uri && startUs <= clip.sourceInUs && endUs >= clip.sourceOutUs
    fun at(timeUs: Long): EyePose? {
        if (timeUs < startUs || timeUs > endUs || samples.isEmpty()) return null
        val index = samples.binarySearchBy(timeUs) { it.timeUs }
        if (index >= 0) return samples[index].pose
        val right = -index - 1
        val a = samples.getOrNull(right - 1) ?: return null
        val b = samples.getOrNull(right) ?: return if (timeUs - a.timeUs <= 50_000L) a.pose else null
        val pa = a.pose ?: return null
        val pb = b.pose ?: return null
        // Do not invent motion across a missing face, scene cut or different tracked person.
        if (b.timeUs - a.timeUs > 100_000L || pa.identity != pb.identity ||
            abs(pa.left.x - pb.left.x) + abs(pa.left.y - pb.left.y) > .18f) return null
        val t = (timeUs - a.timeUs).toFloat() / (b.timeUs - a.timeUs).coerceAtLeast(1)
        return EyePose(pa.left.interpolate(pb.left, t), pa.right.interpolate(pb.right, t), pa.identity)
    }
}
