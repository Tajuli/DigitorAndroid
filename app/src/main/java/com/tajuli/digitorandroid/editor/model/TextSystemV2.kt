package com.tajuli.digitorandroid.editor.model

import kotlin.math.min

/** System-font families that are available on every supported Android device. */
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

/**
 * Creator-facing text styling. Values are intentionally platform-neutral so the Compose preview
 * and Media3 export renderer can consume exactly the same project metadata.
 */
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

data class TextAnimationFrameV2(
    val alpha: Float = 1f,
    /** Normalized project offset. +X right, +Y down. */
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

/** Resolve V1 text metadata into V2 defaults without requiring a project migration. */
fun TextOverlayClip.resolvedTextStyleV2(): TextStyleV2 =
    (styleV2 ?: TextStyleV2(
        colorArgb = argb,
        backgroundEnabled = background,
    )).normalized()

fun TextOverlayClip.resolvedEntryAnimationV2(): TextAnimationSpecV2 =
    (entryAnimationV2 ?: TextAnimationSpecV2()).normalizedFor(durationUs)

fun TextOverlayClip.resolvedExitAnimationV2(): TextAnimationSpecV2 =
    (exitAnimationV2 ?: TextAnimationSpecV2()).normalizedFor(durationUs)

/**
 * Shared animation evaluator used by realtime Compose preview and Media3 export overlays.
 * Slide distance is expressed in normalized canvas coordinates so it scales with project size.
 */
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
