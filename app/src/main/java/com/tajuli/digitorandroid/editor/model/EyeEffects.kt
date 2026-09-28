package com.tajuli.digitorandroid.editor.model

import kotlin.math.abs

/** Stable names are serialized through the existing timed NodeEffect contract. */
object EyeEffectCatalog {
    val names = listOf("Fire Eyes", "Laser Eyes", "Lightning Eyes", "Plasma Eyes",
        "Ice Eyes", "Galaxy Eyes", "Neon Eyes", "Solar Eyes", "Cyber Eyes",
        "Heart Eyes", "Star Eyes", "Rainbow Eyes",
        "Electric Eyes", "Flame Eyes", "Flame Eyes 2", "Flaming Horns",
        "Outline Scan", "Eye Reflection", "Face Glitch", "Futuristic Lab 2",
        "Cheer", "Embarrassed Face", "Fake Laugh", "Big Head", "Gorilla Face", "Big Mouth", "Bend")
    val funnyNames get() = names.drop(20)
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
        return TrackedEye(
            x + (other.x - x) * t,
            y + (other.y - y) * t,
            radius + (other.radius - radius) * t,
            roll + delta * t,
            // Openness is sampled per decoded frame. Interpolating it linearly makes an eye
            // effect linger during a blink; use the nearest real frame's openness instead.
            if (t < .5f) open else other.open,
        )
    }
}
data class EyePose(val left: TrackedEye, val right: TrackedEye, val identity: Int?,
    val face: BeautyRectV28? = null, val mouth: BeautyRectV28? = null)
data class EyeSample(val timeUs: Long, val pose: EyePose?)
data class EyeTrack(val uri: String, val startUs: Long, val endUs: Long, val samples: List<EyeSample>, val version: Int = 9) {
    fun covers(clip: TimelineClip): Boolean = version == 9 && uri == clip.uri && startUs <= clip.sourceInUs && endUs >= clip.sourceOutUs

    private fun validAtOrBefore(index: Int): EyeSample? {
        var i = index.coerceAtMost(samples.lastIndex)
        while (i >= 0) {
            samples[i].pose?.let { return samples[i] }
            if (index - i >= 4) break
            i--
        }
        return null
    }

    private fun validAtOrAfter(index: Int): EyeSample? {
        var i = index.coerceAtLeast(0)
        while (i <= samples.lastIndex) {
            samples[i].pose?.let { return samples[i] }
            if (i - index >= 4) break
            i++
        }
        return null
    }

    fun at(timeUs: Long): EyePose? {
        if (timeUs < startUs || timeUs > endUs || samples.isEmpty()) return null
        val exact = samples.binarySearchBy(timeUs) { it.timeUs }
        val insertion = if (exact >= 0) exact else -exact - 1

        val left = validAtOrBefore(if (exact >= 0) exact else insertion - 1)
        val right = validAtOrAfter(if (exact >= 0) exact else insertion)

        if (left == null) {
            val explicitMissing = (0 until insertion.coerceAtMost(samples.size))
                .any { samples[it].timeUs <= timeUs && samples[it].pose == null }
            return if (explicitMissing) null
            else right?.takeIf { it.timeUs - timeUs <= 50_000L }?.pose
        }
        if (right == null) {
            val explicitMissing = (insertion.coerceAtLeast(0) until samples.size)
                .any { samples[it].timeUs >= timeUs && samples[it].pose == null }
            return if (explicitMissing) null
            else left.takeIf { timeUs - it.timeUs <= 50_000L }?.pose
        }
        if (left.timeUs == right.timeUs) return left.pose

        val pa = left.pose ?: return null
        val pb = right.pose ?: return null
        // Bridge only brief model dropouts. Longer losses/scene cuts stay missing instead of
        // dragging a stale face across the frame.
        if (right.timeUs - left.timeUs > 120_000L || pa.identity != pb.identity ||
            abs(pa.left.x - pb.left.x) + abs(pa.left.y - pb.left.y) > .18f) return null
        val t = (timeUs - left.timeUs).toFloat() /
            (right.timeUs - left.timeUs).coerceAtLeast(1L).toFloat()
        return EyePose(
            pa.left.interpolate(pb.left, t.coerceIn(0f, 1f)),
            pa.right.interpolate(pb.right, t.coerceIn(0f, 1f)),
            pa.identity,
            pa.face?.let { a -> pb.face?.let { a.lerp(it, t.coerceIn(0f, 1f)) } },
            pa.mouth?.let { a -> pb.mouth?.let { a.lerp(it, t.coerceIn(0f, 1f)) } },
        )
    }
}

