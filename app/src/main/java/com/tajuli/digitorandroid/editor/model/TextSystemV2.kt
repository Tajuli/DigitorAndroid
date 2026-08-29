package com.tajuli.digitorandroid.editor.model

import kotlin.math.min

enum class TextFontV2 {
    SANS,
    SERIF,
    MONO,
    CURSIVE,
}

enum class TextAlignmentV2 {
    LEFT,
    CENTER,
    RIGHT,
}

enum class TextAnimationV2 {
    NONE,
    FADE,
    SLIDE_UP,
    SLIDE_DOWN,
    SLIDE_LEFT,
    SLIDE_RIGHT,
}

data class TextStyleV2(
    val font: TextFontV2 = TextFontV2.SANS,
    val colorArgb: Long = 0xFFFFFFFFL,
    val strokeWidth: Float = 0f,
    val strokeArgb: Long = 0xFF000000L,
    val shadowEnabled: Boolean = false,
    val shadowArgb: Long = 0xB0000000L,
    val shadowRadius: Float = 5f,
    val shadowDx: Float = 2f,
    val shadowDy: Float = 2f,
    val backgroundEnabled: Boolean = false,
    val backgroundArgb: Long = 0xB0000000L,
    val alignment: TextAlignmentV2 = TextAlignmentV2.CENTER,
) {
    fun normalized(): TextStyleV2 = copy(
        strokeWidth = strokeWidth.coerceIn(0f, 12f),
        shadowRadius = shadowRadius.coerceIn(0f, 24f),
        shadowDx = shadowDx.coerceIn(-24f, 24f),
        shadowDy = shadowDy.coerceIn(-24f, 24f),
    )
}

data class TextAnimationSpecV2(
    val kind: TextAnimationV2 = TextAnimationV2.NONE,
    val durationUs: Long = 350_000L,
) {
    fun normalizedFor(overlayDurationUs: Long): TextAnimationSpecV2 {
        val upper = min(2_000_000L, (overlayDurationUs.coerceAtLeast(1L) / 2L).coerceAtLeast(1L))
        val lower = min(100_000L, upper)
        return copy(durationUs = durationUs.coerceIn(lower, upper))
    }
}

/** A manual playhead keyframe, stored in local text-clip time. */
data class TextTransformKeyframeV2(
    val localUs: Long,
    val positionX: Float,
    val positionY: Float,
    val sizeScale: Float,
    val alpha: Float,
) {
    fun normalizedFor(durationUs: Long): TextTransformKeyframeV2 = copy(
        localUs = localUs.coerceIn(0L, durationUs.coerceAtLeast(1L)),
        positionX = positionX.coerceIn(-1f, 1f),
        positionY = positionY.coerceIn(-1f, 1f),
        sizeScale = sizeScale.coerceIn(.35f, 4f),
        alpha = alpha.coerceIn(0f, 1f),
    )
}

data class TextManualAnimationV2(
    val keyframes: List<TextTransformKeyframeV2> = emptyList(),
) {
    fun normalizedFor(durationUs: Long): TextManualAnimationV2 {
        val normalized = keyframes
            .map { it.normalizedFor(durationUs) }
            .sortedBy { it.localUs }
            .groupBy { it.localUs }
            .map { (_, sameTime) -> sameTime.last() }
        return copy(keyframes = normalized)
    }

    fun keyframeNear(localUs: Long, toleranceUs: Long): TextTransformKeyframeV2? =
        keyframes.minByOrNull { kotlin.math.abs(it.localUs - localUs) }
            ?.takeIf { kotlin.math.abs(it.localUs - localUs) <= toleranceUs.coerceAtLeast(0L) }

    fun withKeyframe(
        keyframe: TextTransformKeyframeV2,
        durationUs: Long,
        toleranceUs: Long,
    ): TextManualAnimationV2 {
        val normalizedKey = keyframe.normalizedFor(durationUs)
        val kept = keyframes.filter { kotlin.math.abs(it.localUs - normalizedKey.localUs) > toleranceUs.coerceAtLeast(0L) }
        return copy(keyframes = (kept + normalizedKey).sortedBy { it.localUs }).normalizedFor(durationUs)
    }

    fun withoutKeyframeNear(localUs: Long, toleranceUs: Long): TextManualAnimationV2 = copy(
        keyframes = keyframes.filter { kotlin.math.abs(it.localUs - localUs) > toleranceUs.coerceAtLeast(0L) },
    )
}

data class TextManualFrameV2(
    val positionX: Float,
    val positionY: Float,
    val sizeScale: Float,
    val alpha: Float,
)

data class TextAnimationFrameV2(
    val alpha: Float = 1f,
    /** Normalized project offset. +X right, +Y down. */
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

fun TextOverlayClip.resolvedTextStyleV2(): TextStyleV2 =
    (styleV2 ?: TextStyleV2(
        colorArgb = argb,
        backgroundEnabled = background,
    )).normalized()

fun TextOverlayClip.resolvedEntryAnimationV2(): TextAnimationSpecV2 =
    (entryAnimationV2 ?: TextAnimationSpecV2()).normalizedFor(durationUs)

fun TextOverlayClip.resolvedExitAnimationV2(): TextAnimationSpecV2 =
    (exitAnimationV2 ?: TextAnimationSpecV2()).normalizedFor(durationUs)

fun TextOverlayClip.resolvedManualAnimationV2(): TextManualAnimationV2 =
    (manualAnimationV2 ?: TextManualAnimationV2()).normalizedFor(durationUs)

/**
 * DaVinci-style manual transform evaluator. One keyframe holds its value; two or more keyframes
 * interpolate linearly between playhead positions. With no keyframes, legacy base properties win.
 */
fun TextOverlayClip.textManualFrameV2(timeUs: Long): TextManualFrameV2 {
    val fallback = TextManualFrameV2(
        positionX = positionX.coerceIn(-1f, 1f),
        positionY = positionY.coerceIn(-1f, 1f),
        sizeScale = sizeScale.coerceIn(.35f, 4f),
        alpha = 1f,
    )
    if (!activeAt(timeUs)) return fallback.copy(alpha = 0f)

    val frames = resolvedManualAnimationV2().keyframes
    if (frames.isEmpty()) return fallback
    val localUs = (timeUs - timelineStartUs).coerceIn(0L, durationUs)
    if (frames.size == 1 || localUs <= frames.first().localUs) return frames.first().asManualFrame()
    if (localUs >= frames.last().localUs) return frames.last().asManualFrame()

    val rightIndex = frames.indexOfFirst { it.localUs >= localUs }.coerceAtLeast(1)
    val left = frames[rightIndex - 1]
    val right = frames[rightIndex]
    val span = (right.localUs - left.localUs).coerceAtLeast(1L)
    val t = ((localUs - left.localUs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
    return TextManualFrameV2(
        positionX = lerp(left.positionX, right.positionX, t),
        positionY = lerp(left.positionY, right.positionY, t),
        sizeScale = lerp(left.sizeScale, right.sizeScale, t),
        alpha = lerp(left.alpha, right.alpha, t),
    )
}

fun TextOverlayClip.textAnimationFrameV2(timeUs: Long): TextAnimationFrameV2 {
    if (!activeAt(timeUs)) return TextAnimationFrameV2(alpha = 0f)

    val entry = resolvedEntryAnimationV2()
    val exit = resolvedExitAnimationV2()
    val sinceStartUs = (timeUs - timelineStartUs).coerceAtLeast(0L)
    val untilEndUs = (timelineEndUs - timeUs).coerceAtLeast(0L)

    val entryFrame = if (entry.kind != TextAnimationV2.NONE && sinceStartUs < entry.durationUs) {
        animationFrame(entry.kind, smooth(sinceStartUs.toFloat() / entry.durationUs.toFloat()), entering = true)
    } else {
        TextAnimationFrameV2()
    }
    val exitFrame = if (exit.kind != TextAnimationV2.NONE && untilEndUs < exit.durationUs) {
        animationFrame(exit.kind, smooth(untilEndUs.toFloat() / exit.durationUs.toFloat()), entering = false)
    } else {
        TextAnimationFrameV2()
    }

    return TextAnimationFrameV2(
        alpha = min(entryFrame.alpha, exitFrame.alpha).coerceIn(0f, 1f),
        offsetX = entryFrame.offsetX + exitFrame.offsetX,
        offsetY = entryFrame.offsetY + exitFrame.offsetY,
    )
}

private fun TextTransformKeyframeV2.asManualFrame() = TextManualFrameV2(
    positionX = positionX,
    positionY = positionY,
    sizeScale = sizeScale,
    alpha = alpha,
)

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

private fun smooth(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun animationFrame(kind: TextAnimationV2, progress: Float, entering: Boolean): TextAnimationFrameV2 {
    val t = progress.coerceIn(0f, 1f)
    val travel = 0.18f * (1f - t)
    return when (kind) {
        TextAnimationV2.NONE -> TextAnimationFrameV2()
        TextAnimationV2.FADE -> TextAnimationFrameV2(alpha = t)
        TextAnimationV2.SLIDE_UP -> TextAnimationFrameV2(
            alpha = t,
            offsetY = if (entering) travel else -travel,
        )
        TextAnimationV2.SLIDE_DOWN -> TextAnimationFrameV2(
            alpha = t,
            offsetY = if (entering) -travel else travel,
        )
        TextAnimationV2.SLIDE_LEFT -> TextAnimationFrameV2(
            alpha = t,
            offsetX = if (entering) travel else -travel,
        )
        TextAnimationV2.SLIDE_RIGHT -> TextAnimationFrameV2(
            alpha = t,
            offsetX = if (entering) -travel else travel,
        )
    }
}
