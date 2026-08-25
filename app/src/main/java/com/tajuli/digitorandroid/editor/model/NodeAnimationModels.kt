package com.tajuli.digitorandroid.editor.model

import kotlin.math.max
import kotlin.math.min

enum class NodeAnimationDomain {
    CORRECTION,
    COLOR,
    EFFECTS,
}

data class NodeSnapshotKeyframe(
    val sourceTimeUs: Long,
    val node: ColorNode,
    val interpolation: KeyframeInterpolation = KeyframeInterpolation.LINEAR,
)

data class NodeAnimationTrack(
    val keyframes: List<NodeSnapshotKeyframe> = emptyList(),
) {
    private fun ordered(): List<NodeSnapshotKeyframe> = keyframes
        .map { it.copy(sourceTimeUs = it.sourceTimeUs.coerceAtLeast(0L)) }
        .sortedBy { it.sourceTimeUs }
        .fold(mutableListOf()) { out, keyframe ->
            if (out.lastOrNull()?.sourceTimeUs == keyframe.sourceTimeUs) out[out.lastIndex] = keyframe
            else out += keyframe
            out
        }

    fun hasKeyframeAt(sourceTimeUs: Long): Boolean =
        ordered().any { it.sourceTimeUs == sourceTimeUs.coerceAtLeast(0L) }

    fun upsert(
        sourceTimeUs: Long,
        node: ColorNode,
        interpolation: KeyframeInterpolation = KeyframeInterpolation.LINEAR,
    ): NodeAnimationTrack {
        val t = sourceTimeUs.coerceAtLeast(0L)
        val next = ordered().toMutableList()
        val index = next.indexOfFirst { it.sourceTimeUs == t }
        val keyframe = NodeSnapshotKeyframe(t, node, interpolation)
        if (index >= 0) next[index] = keyframe else next += keyframe
        return copy(keyframes = next.sortedBy { it.sourceTimeUs })
    }

    fun removeAt(sourceTimeUs: Long): NodeAnimationTrack =
        copy(keyframes = ordered().filterNot { it.sourceTimeUs == sourceTimeUs.coerceAtLeast(0L) })

    fun evaluate(base: ColorNode, domain: NodeAnimationDomain, sourceTimeUs: Long): ColorNode {
        val keys = ordered()
        if (keys.isEmpty()) return base
        val t = sourceTimeUs.coerceAtLeast(0L)
        if (t <= keys.first().sourceTimeUs) return applyDomain(base, keys.first().node, domain)
        if (t >= keys.last().sourceTimeUs) return applyDomain(base, keys.last().node, domain)

        val rightIndex = keys.indexOfFirst { it.sourceTimeUs >= t }.coerceAtLeast(1)
        val left = keys[rightIndex - 1]
        val right = keys[rightIndex]
        if (left.sourceTimeUs == right.sourceTimeUs) return applyDomain(base, right.node, domain)
        val raw = ((t - left.sourceTimeUs).toDouble() / (right.sourceTimeUs - left.sourceTimeUs).toDouble())
            .toFloat()
            .coerceIn(0f, 1f)
        val amount = when (left.interpolation) {
            KeyframeInterpolation.LINEAR -> raw
            KeyframeInterpolation.EASE_IN_OUT -> raw * raw * (3f - 2f * raw)
        }
        return interpolateDomain(base, left.node, right.node, domain, amount)
    }
}

data class NodeAnimations(
    val tracks: Map<String, NodeAnimationTrack> = emptyMap(),
) {
    private fun key(nodeId: String, domain: NodeAnimationDomain): String = "$nodeId|${domain.name}"

    fun track(nodeId: String, domain: NodeAnimationDomain): NodeAnimationTrack =
        tracks[key(nodeId, domain)] ?: NodeAnimationTrack()

    fun hasAnimation(nodeId: String, domain: NodeAnimationDomain): Boolean =
        track(nodeId, domain).keyframes.isNotEmpty()

    fun hasKeyframeAt(nodeId: String, domain: NodeAnimationDomain, sourceTimeUs: Long): Boolean =
        track(nodeId, domain).hasKeyframeAt(sourceTimeUs)

    fun keyframeTimes(nodeId: String, domain: NodeAnimationDomain): List<Long> =
        track(nodeId, domain).keyframes.map { it.sourceTimeUs }.sorted()

    fun evaluateNode(base: ColorNode, sourceTimeUs: Long): ColorNode =
        NodeAnimationDomain.entries.fold(base) { current, domain ->
            track(base.id, domain).evaluate(current, domain, sourceTimeUs)
        }

    fun evaluateGraph(graph: ClipNodeGraph, sourceTimeUs: Long): ClipNodeGraph =
        graph.copy(nodes = graph.nodes.map { evaluateNode(it, sourceTimeUs) })

    fun toggle(node: ColorNode, domain: NodeAnimationDomain, sourceTimeUs: Long): NodeAnimations {
        val t = sourceTimeUs.coerceAtLeast(0L)
        val current = track(node.id, domain)
        val next = if (current.hasKeyframeAt(t)) {
            current.removeAt(t)
        } else {
            current.upsert(t, evaluateNode(node, t))
        }
        return withTrack(node.id, domain, next)
    }

    fun upsertIfAnimated(
        node: ColorNode,
        domain: NodeAnimationDomain,
        sourceTimeUs: Long,
    ): NodeAnimations {
        val current = track(node.id, domain)
        if (current.keyframes.isEmpty()) return this
        return withTrack(node.id, domain, current.upsert(sourceTimeUs, node))
    }

    fun remove(nodeId: String, domain: NodeAnimationDomain, sourceTimeUs: Long): NodeAnimations =
        withTrack(nodeId, domain, track(nodeId, domain).removeAt(sourceTimeUs))

    fun hasColorAnimation: Boolean
        get() = tracks.any { (key, track) ->
            track.keyframes.isNotEmpty() &&
                (key.endsWith("|${NodeAnimationDomain.CORRECTION.name}") ||
                    key.endsWith("|${NodeAnimationDomain.COLOR.name}"))
        }

    fun qualifierIsAnimated(nodeId: String): Boolean {
        val keys = track(nodeId, NodeAnimationDomain.COLOR).keyframes
        if (keys.size < 2) return false
        return keys.map { it.node.advancedColor.qualifier }.distinct().size > 1
    }

    private fun withTrack(nodeId: String, domain: NodeAnimationDomain, track: NodeAnimationTrack): NodeAnimations {
        val mutable = tracks.toMutableMap()
        val key = key(nodeId, domain)
        if (track.keyframes.isEmpty()) mutable.remove(key) else mutable[key] = track
        return copy(tracks = mutable)
    }
}

private fun applyDomain(base: ColorNode, source: ColorNode, domain: NodeAnimationDomain): ColorNode = when (domain) {
    NodeAnimationDomain.CORRECTION -> base.copy(corrections = source.corrections)
    NodeAnimationDomain.COLOR -> base.copy(advancedColor = source.advancedColor)
    NodeAnimationDomain.EFFECTS -> base.copy(effects = source.effects)
}

private fun interpolateDomain(
    base: ColorNode,
    left: ColorNode,
    right: ColorNode,
    domain: NodeAnimationDomain,
    amount: Float,
): ColorNode = when (domain) {
    NodeAnimationDomain.CORRECTION -> base.copy(
        corrections = lerpCorrections(left.corrections, right.corrections, amount),
    )
    NodeAnimationDomain.COLOR -> base.copy(
        advancedColor = lerpAdvancedColor(left.advancedColor, right.advancedColor, amount),
    )
    NodeAnimationDomain.EFFECTS -> base.copy(
        effects = lerpEffects(left.effects, right.effects, amount),
    )
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

private fun lerpCorrections(a: NodeCorrections, b: NodeCorrections, t: Float): NodeCorrections = NodeCorrections(
    exposure = lerp(a.exposure, b.exposure, t),
    contrast = lerp(a.contrast, b.contrast, t),
    saturation = lerp(a.saturation, b.saturation, t),
    temperature = lerp(a.temperature, b.temperature, t),
    tint = lerp(a.tint, b.tint, t),
    highlights = lerp(a.highlights, b.highlights, t),
    shadows = lerp(a.shadows, b.shadows, t),
    hue = lerp(a.hue, b.hue, t),
    colorBoost = lerp(a.colorBoost, b.colorBoost, t),
)

private fun lerpWheel(a: ColorWheelValue, b: ColorWheelValue, t: Float): ColorWheelValue = ColorWheelValue(
    red = lerp(a.red, b.red, t),
    green = lerp(a.green, b.green, t),
    blue = lerp(a.blue, b.blue, t),
    luma = lerp(a.luma, b.luma, t),
    puckX = lerp(a.puckX, b.puckX, t),
    puckY = lerp(a.puckY, b.puckY, t),
)

private fun lerpPrimary(a: PrimaryWheels, b: PrimaryWheels, t: Float): PrimaryWheels = PrimaryWheels(
    lift = lerpWheel(a.lift, b.lift, t),
    gamma = lerpWheel(a.gamma, b.gamma, t),
    gain = lerpWheel(a.gain, b.gain, t),
    offset = lerpWheel(a.offset, b.offset, t),
)

private fun lerpLog(a: LogWheels, b: LogWheels, t: Float): LogWheels = LogWheels(
    shadows = lerpWheel(a.shadows, b.shadows, t),
    midtones = lerpWheel(a.midtones, b.midtones, t),
    highlights = lerpWheel(a.highlights, b.highlights, t),
    global = lerpWheel(a.global, b.global, t),
    shadowRange = lerp(a.shadowRange, b.shadowRange, t),
    highlightRange = lerp(a.highlightRange, b.highlightRange, t),
)

private fun lerpCurve(a: Curve5, b: Curve5, t: Float): Curve5 {
    if (a.points.size != b.points.size) return if (t < .5f) a else b
    return Curve5(
        points = a.points.zip(b.points).map { (left, right) ->
            CurvePoint(lerp(left.x, right.x, t), lerp(left.y, right.y, t))
        },
    )
}

private fun lerpCurves(a: RgbCurves, b: RgbCurves, t: Float): RgbCurves = RgbCurves(
    master = lerpCurve(a.master, b.master, t),
    red = lerpCurve(a.red, b.red, t),
    green = lerpCurve(a.green, b.green, t),
    blue = lerpCurve(a.blue, b.blue, t),
)

private fun lerpNullable(a: Float?, b: Float?, t: Float): Float? = when {
    a != null && b != null -> lerp(a, b, t)
    t < .5f -> a
    else -> b
}

private fun lerpQualifier(a: HslQualifier, b: HslQualifier, t: Float): HslQualifier = HslQualifier(
    enabled = if (t < .5f) a.enabled else b.enabled,
    hueCenterDegrees = lerpAngle(a.hueCenterDegrees, b.hueCenterDegrees, t, 360f),
    hueWidthDegrees = lerp(a.hueWidthDegrees, b.hueWidthDegrees, t),
    saturationMin = lerp(a.saturationMin, b.saturationMin, t),
    saturationMax = lerp(a.saturationMax, b.saturationMax, t),
    luminanceMin = lerp(a.luminanceMin, b.luminanceMin, t),
    luminanceMax = lerp(a.luminanceMax, b.luminanceMax, t),
    softness = lerp(a.softness, b.softness, t),
    hueShiftDegrees = lerp(a.hueShiftDegrees, b.hueShiftDegrees, t),
    saturationShift = lerp(a.saturationShift, b.saturationShift, t),
    luminanceShift = lerp(a.luminanceShift, b.luminanceShift, t),
    pickedRed = lerpNullable(a.pickedRed, b.pickedRed, t),
    pickedGreen = lerpNullable(a.pickedGreen, b.pickedGreen, t),
    pickedBlue = lerpNullable(a.pickedBlue, b.pickedBlue, t),
)

private fun lerpAdvancedColor(a: AdvancedColorGrade, b: AdvancedColorGrade, t: Float): AdvancedColorGrade =
    AdvancedColorGrade(
        primary = lerpPrimary(a.primary, b.primary, t),
        log = lerpLog(a.log, b.log, t),
        curves = lerpCurves(a.curves, b.curves, t),
        qualifier = lerpQualifier(a.qualifier, b.qualifier, t),
    )

private fun lerpEffects(a: List<NodeEffect>, b: List<NodeEffect>, t: Float): List<NodeEffect> {
    val ids = (a.map { it.id } + b.map { it.id }).distinct()
    val byA = a.associateBy { it.id }
    val byB = b.associateBy { it.id }
    return ids.mapNotNull { id ->
        val left = byA[id]
        val right = byB[id]
        val template = right ?: left ?: return@mapNotNull null
        template.copy(
            amount = lerp(left?.amount ?: 0f, right?.amount ?: 0f, t),
            enabled = (left?.enabled == true) || (right?.enabled == true),
        )
    }
}

private fun lerpAngle(a: Float, b: Float, t: Float, period: Float): Float {
    var delta = (b - a) % period
    if (delta > period / 2f) delta -= period
    if (delta < -period / 2f) delta += period
    val value = a + delta * t.coerceIn(0f, 1f)
    return ((value % period) + period) % period
}
