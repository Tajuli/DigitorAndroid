package com.tajuli.digitorandroid.editor

import com.tajuli.digitorandroid.editor.model.TextAlignmentV2
import com.tajuli.digitorandroid.editor.model.TextAnimationSpecV2
import com.tajuli.digitorandroid.editor.model.TextAnimationV2
import com.tajuli.digitorandroid.editor.model.TextFontV2
import com.tajuli.digitorandroid.editor.model.TextManualAnimationV2
import com.tajuli.digitorandroid.editor.model.TextOverlayClip
import com.tajuli.digitorandroid.editor.model.TextStyleV2
import com.tajuli.digitorandroid.editor.model.TextTransformKeyframeV2
import com.tajuli.digitorandroid.editor.model.resolvedTextStyleV2
import com.tajuli.digitorandroid.editor.model.textAnimationFrameV2
import com.tajuli.digitorandroid.editor.model.textManualFrameV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSystemV2Test {
    @Test
    fun legacyTextFallsBackToV1ColorAndBackground() {
        val legacy = TextOverlayClip(
            text = "Legacy",
            timelineStartUs = 0L,
            timelineEndUs = 2_000_000L,
            argb = 0xFFFF0000L,
            background = true,
        )

        val style = legacy.resolvedTextStyleV2()
        assertEquals(0xFFFF0000L, style.colorArgb)
        assertTrue(style.backgroundEnabled)
        assertEquals(TextFontV2.SANS, style.font)
        assertEquals(TextAlignmentV2.CENTER, style.alignment)
    }

    @Test
    fun v2StyleNormalizesOutlineAndShadowRanges() {
        val clip = TextOverlayClip(
            text = "Styled",
            timelineStartUs = 0L,
            timelineEndUs = 2_000_000L,
            styleV2 = TextStyleV2(
                strokeWidth = 99f,
                shadowEnabled = true,
                shadowRadius = 99f,
                shadowDx = -99f,
                shadowDy = 99f,
            ),
        )

        val style = clip.resolvedTextStyleV2()
        assertEquals(12f, style.strokeWidth, 0f)
        assertEquals(24f, style.shadowRadius, 0f)
        assertEquals(-24f, style.shadowDx, 0f)
        assertEquals(24f, style.shadowDy, 0f)
    }

    @Test
    fun entryAndExitAnimationShareTimelineEvaluator() {
        val clip = TextOverlayClip(
            text = "Animated",
            timelineStartUs = 1_000_000L,
            timelineEndUs = 4_000_000L,
            entryAnimationV2 = TextAnimationSpecV2(TextAnimationV2.SLIDE_UP, 500_000L),
            exitAnimationV2 = TextAnimationSpecV2(TextAnimationV2.FADE, 500_000L),
        )

        val start = clip.textAnimationFrameV2(1_000_000L)
        assertEquals(0f, start.alpha, 0f)
        assertTrue(start.offsetY > 0f)

        val middle = clip.textAnimationFrameV2(2_000_000L)
        assertEquals(1f, middle.alpha, 0f)
        assertEquals(0f, middle.offsetX, 0f)
        assertEquals(0f, middle.offsetY, 0f)

        val nearEnd = clip.textAnimationFrameV2(3_900_000L)
        assertTrue(nearEnd.alpha in 0f..1f)
        assertTrue(nearEnd.alpha < 1f)
    }

    @Test
    fun animationIsInvisibleOutsideOverlayBounds() {
        val clip = TextOverlayClip(text = "A", timelineStartUs = 1_000L, timelineEndUs = 2_000L)
        assertEquals(0f, clip.textAnimationFrameV2(999L).alpha, 0f)
        assertEquals(0f, clip.textAnimationFrameV2(2_000L).alpha, 0f)
    }

    @Test
    fun manualKeyframesInterpolatePositionSizeAndOpacity() {
        val clip = TextOverlayClip(
            text = "Keyframed",
            timelineStartUs = 1_000_000L,
            timelineEndUs = 3_000_000L,
            manualAnimationV2 = TextManualAnimationV2(
                listOf(
                    TextTransformKeyframeV2(0L, -1f, -.5f, .5f, 0f),
                    TextTransformKeyframeV2(2_000_000L, 1f, .5f, 1.5f, 1f),
                ),
            ),
        )

        val middle = clip.textManualFrameV2(2_000_000L)
        assertEquals(0f, middle.positionX, .001f)
        assertEquals(0f, middle.positionY, .001f)
        assertEquals(1f, middle.sizeScale, .001f)
        assertEquals(.5f, middle.alpha, .001f)
    }

    @Test
    fun oneManualKeyframeHoldsItsValueAcrossClip() {
        val clip = TextOverlayClip(
            text = "Hold",
            timelineStartUs = 0L,
            timelineEndUs = 2_000_000L,
            manualAnimationV2 = TextManualAnimationV2(
                listOf(TextTransformKeyframeV2(1_000_000L, .4f, -.2f, 1.3f, .8f)),
            ),
        )

        val early = clip.textManualFrameV2(100_000L)
        val late = clip.textManualFrameV2(1_900_000L)
        assertEquals(.4f, early.positionX, 0f)
        assertEquals(.4f, late.positionX, 0f)
        assertEquals(1.3f, late.sizeScale, 0f)
        assertEquals(.8f, late.alpha, 0f)
    }
}
