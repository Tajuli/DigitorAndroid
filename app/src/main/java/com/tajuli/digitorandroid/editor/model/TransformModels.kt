package com.tajuli.digitorandroid.editor.model

import kotlin.math.max
import kotlin.math.min

enum class TransformProperty {
    POSITION_X,
    POSITION_Y,
    SCALE_X,
    SCALE_Y,
    ROTATION,
}

enum class KeyframeInterpolation {
    LINEAR,
    EASE_IN_OUT,
}

data class FloatKeyframe(
    val timeUs: Long,
    val value: Float,
    val interpolation: KeyframeInterpolation = KeyframeInterpolation.LINEAR,
)

data class AnimatedFloat(
    val baseValue: Float,
    val keyframes: List<FloatKeyframe> = emptyList(),
) {
    private fun ordered(): List<FloatKeyframe> = keyframes
        .map { it.copy(timeUs = it.timeUs.coerceAtLeast(0L)) }
        .sortedBy { it.timeUs }
        .fold(mutableListOf<FloatKeyframe>()) { out, keyframe ->
            if (out.lastOrNull()?.timeUs == keyframe.timeUs) out[out.lastIndex] = keyframe else out += keyframe
            out
        }

    fun valueAt(timeUs: Long): Float {
        val keys = ordered()
        if (keys.isEmpty()) return baseValue
        val t = timeUs.coerceAtLeast(0L)
        if (t <= keys.first().timeUs) return keys.first().value
        if (t >= keys.last().timeUs) return keys.last().value

        val rightIndex = keys.indexOfFirst { it.timeUs >= t }.coerceAtLeast(1)
        val left = keys[rightIndex - 1]
        val right = keys[rightIndex]
        if (right.timeUs == left.timeUs) return right.value

        val raw = ((t - left.timeUs).toDouble() / (right.timeUs - left.timeUs).toDouble())
            .toFloat()
            .coerceIn(0f, 1f)
        val amount = when (left.interpolation) {
            KeyframeInterpolation.LINEAR -> raw
            KeyframeInterpolation.EASE_IN_OUT -> raw * raw * (3f - 2f * raw)
        }
        return left.value + (right.value - left.value) * amount
    }

    fun hasKeyframeAt(timeUs: Long): Boolean = ordered().any { it.timeUs == timeUs.coerceAtLeast(0L) }

    fun withBaseValue(value: Float): AnimatedFloat = copy(baseValue = value)

    fun upsertKeyframe(
        timeUs: Long,
        value: Float,
        interpolation: KeyframeInterpolation = KeyframeInterpolation.LINEAR,
    ): AnimatedFloat {
        val t = timeUs.coerceAtLeast(0L)
        val next = ordered().toMutableList()
        val index = next.indexOfFirst { it.timeUs == t }
        val keyframe = FloatKeyframe(t, value, interpolation)
        if (index >= 0) next[index] = keyframe else next += keyframe
        return copy(keyframes = next.sortedBy { it.timeUs })
    }

    fun removeKeyframeAt(timeUs: Long): AnimatedFloat {
        val t = timeUs.coerceAtLeast(0L)
        val keys = ordered()
        val removed = keys.firstOrNull { it.timeUs == t } ?: return this
        val remaining = keys.filterNot { it.timeUs == t }
        return if (remaining.isEmpty()) copy(baseValue = removed.value, keyframes = emptyList())
        else copy(keyframes = remaining)
    }

    fun setEditorValue(timeUs: Long, value: Float): AnimatedFloat =
        if (keyframes.isEmpty()) withBaseValue(value) else upsertKeyframe(timeUs, value)

    fun toggleKeyframe(timeUs: Long): AnimatedFloat {
        val t = timeUs.coerceAtLeast(0L)
        return if (hasKeyframeAt(t)) removeKeyframeAt(t) else upsertKeyframe(t, valueAt(t))
    }

    fun splitAt(splitUs: Long): Pair<AnimatedFloat, AnimatedFloat> {
        val keys = ordered()
        if (keys.isEmpty()) return this to this

        val split = splitUs.coerceAtLeast(0L)
        val splitValue = valueAt(split)
        val leftSegment = keys.zipWithNext().firstOrNull { (left, right) -> split in left.timeUs..right.timeUs }
        val activeInterpolation = leftSegment?.first?.interpolation ?: KeyframeInterpolation.LINEAR
        val leftKeys = (keys.filter { it.timeUs < split } + FloatKeyframe(split, splitValue, activeInterpolation))
            .distinctBy { it.timeUs }
            .sortedBy { it.timeUs }
        val rightKeys = (listOf(FloatKeyframe(0L, splitValue, activeInterpolation)) + keys
            .filter { it.timeUs > split }
            .map { it.copy(timeUs = it.timeUs - split) })
            .distinctBy { it.timeUs }
            .sortedBy { it.timeUs }

        return copy(keyframes = leftKeys) to copy(keyframes = rightKeys)
    }
}

data class EvaluatedClipTransform(
    val positionX: Float,
    val positionY: Float,
    val scaleX: Float,
    val scaleY: Float,
    val rotationDegrees: Float,
)

data class ClipTransform(
    val positionX: AnimatedFloat = AnimatedFloat(0f),
    val positionY: AnimatedFloat = AnimatedFloat(0f),
    val scaleX: AnimatedFloat = AnimatedFloat(1f),
    val scaleY: AnimatedFloat = AnimatedFloat(1f),
    val rotationDegrees: AnimatedFloat = AnimatedFloat(0f),
) {
    fun evaluate(timeUs: Long): EvaluatedClipTransform = EvaluatedClipTransform(
        positionX = positionX.valueAt(timeUs).coerceIn(-2f, 2f),
        positionY = positionY.valueAt(timeUs).coerceIn(-2f, 2f),
        scaleX = scaleX.valueAt(timeUs).coerceIn(.05f, 8f),
        scaleY = scaleY.valueAt(timeUs).coerceIn(.05f, 8f),
        rotationDegrees = rotationDegrees.valueAt(timeUs),
    )

    fun channel(property: TransformProperty): AnimatedFloat = when (property) {
        TransformProperty.POSITION_X -> positionX
        TransformProperty.POSITION_Y -> positionY
        TransformProperty.SCALE_X -> scaleX
        TransformProperty.SCALE_Y -> scaleY
        TransformProperty.ROTATION -> rotationDegrees
    }

    fun valueAt(property: TransformProperty, timeUs: Long): Float = channel(property).valueAt(timeUs)

    fun hasKeyframeAt(property: TransformProperty, timeUs: Long): Boolean =
        channel(property).hasKeyframeAt(timeUs)

    fun withChannel(property: TransformProperty, value: AnimatedFloat): ClipTransform = when (property) {
        TransformProperty.POSITION_X -> copy(positionX = value)
        TransformProperty.POSITION_Y -> copy(positionY = value)
        TransformProperty.SCALE_X -> copy(scaleX = value)
        TransformProperty.SCALE_Y -> copy(scaleY = value)
        TransformProperty.ROTATION -> copy(rotationDegrees = value)
    }

    fun setEditorValue(property: TransformProperty, timeUs: Long, value: Float): ClipTransform {
        val safe = when (property) {
            TransformProperty.POSITION_X, TransformProperty.POSITION_Y -> value.coerceIn(-2f, 2f)
            TransformProperty.SCALE_X, TransformProperty.SCALE_Y -> value.coerceIn(.05f, 8f)
            TransformProperty.ROTATION -> value.coerceIn(-1080f, 1080f)
        }
        return withChannel(property, channel(property).setEditorValue(timeUs, safe))
    }

    fun toggleKeyframe(property: TransformProperty, timeUs: Long): ClipTransform =
        withChannel(property, channel(property).toggleKeyframe(timeUs))

    fun toggleAllKeyframes(timeUs: Long): ClipTransform {
        val t = timeUs.coerceAtLeast(0L)
        val properties = TransformProperty.entries
        val remove = properties.all { hasKeyframeAt(it, t) }
        return properties.fold(this) { current, property ->
            val channel = current.channel(property)
            current.withChannel(
                property,
                if (remove) channel.removeKeyframeAt(t) else if (channel.hasKeyframeAt(t)) channel
                else channel.upsertKeyframe(t, channel.valueAt(t)),
            )
        }
    }

    fun resetAt(timeUs: Long): ClipTransform =
        setEditorValue(TransformProperty.POSITION_X, timeUs, 0f)
            .setEditorValue(TransformProperty.POSITION_Y, timeUs, 0f)
            .setEditorValue(TransformProperty.SCALE_X, timeUs, 1f)
            .setEditorValue(TransformProperty.SCALE_Y, timeUs, 1f)
            .setEditorValue(TransformProperty.ROTATION, timeUs, 0f)

    fun splitAt(splitUs: Long): Pair<ClipTransform, ClipTransform> {
        val x = positionX.splitAt(splitUs)
        val y = positionY.splitAt(splitUs)
        val sx = scaleX.splitAt(splitUs)
        val sy = scaleY.splitAt(splitUs)
        val r = rotationDegrees.splitAt(splitUs)
        return copy(
            positionX = x.first,
            positionY = y.first,
            scaleX = sx.first,
            scaleY = sy.first,
            rotationDegrees = r.first,
        ) to copy(
            positionX = x.second,
            positionY = y.second,
            scaleX = sx.second,
            scaleY = sy.second,
            rotationDegrees = r.second,
        )
    }

    val hasAnimation: Boolean
        get() = positionX.keyframes.isNotEmpty() || positionY.keyframes.isNotEmpty() ||
            scaleX.keyframes.isNotEmpty() || scaleY.keyframes.isNotEmpty() || rotationDegrees.keyframes.isNotEmpty()

    val isStaticIdentity: Boolean
        get() = !hasAnimation && positionX.baseValue == 0f && positionY.baseValue == 0f &&
            scaleX.baseValue == 1f && scaleY.baseValue == 1f && rotationDegrees.baseValue == 0f

    companion object {
        fun normalizedPositionPixels(normalized: Float, dimensionPx: Int): Float =
            normalized * max(1, dimensionPx) * .5f

        fun normalizedPositionFromPixels(pixels: Float, dimensionPx: Int): Float =
            pixels / max(1, dimensionPx) * 2f

        fun clipLocalTimeUs(clipStartUs: Long, clipDurationUs: Long, timelineUs: Long): Long =
            min(max(0L, timelineUs - clipStartUs), max(0L, clipDurationUs))
    }
}
