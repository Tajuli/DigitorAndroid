package com.tajuli.digitorandroid.editor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositeTemplateV2Test {
    private val video = TimelineClip(
        uri = "content://test/long-video.mp4",
        label = "Long clip",
        timelineStartUs = 0L,
        sourceInUs = 0L,
        sourceOutUs = 40_000_000L,
    )
    private val image = video.copy(visualMediaV21 = TimelineVisualMediaV21.IMAGE)
    private val videoFx = NodeEffect(name = CompositeTemplateCatalogV2.VIDEO_NAME)
    private val photoFx = NodeEffect(name = CompositeTemplateCatalogV2.IMAGE_NAME)

    private fun frame(clip: TimelineClip, effect: NodeEffect, seconds: Float) =
        CompositeTemplateCatalogV2.atSourceTime(
            listOf(effect), clip, (seconds * 1_000_000L).toLong()
        )

    @Test fun onlyOneSignatureTemplateExistsPerMediaType() {
        assertEquals(2, CompositeTemplateCatalogV2.presets.size)
        assertEquals(listOf(CompositeTemplateCatalogV2.VIDEO_NAME),
            CompositeTemplateCatalogV2.forCategory(CompositeTemplateCatalogV2.VIDEO).map { it.name })
        assertEquals(listOf(CompositeTemplateCatalogV2.IMAGE_NAME),
            CompositeTemplateCatalogV2.forCategory(CompositeTemplateCatalogV2.IMAGE).map { it.name })
        assertEquals(listOf(0, 2, 7, 12),
            CompositeTemplateCatalogV2.presets.first().stages.map { it.startSeconds })
        assertTrue(CompositeTemplateCatalogV2.find("Fashion Velocity") == null)
        assertTrue(CompositeTemplateCatalogV2.find("Beat Drop Cinema") == null)
    }

    @Test fun openingBlurIsHeavyAtZeroAndCompletelyGoneByTwoSeconds() {
        val opening = frame(video, videoFx, 0f)
        val mid = frame(video, videoFx, 1f)
        val clean = frame(video, videoFx, 2f)
        assertTrue(opening.active)
        assertTrue(opening.blurReveal > .99f)
        assertTrue(mid.blurReveal > 0f && mid.blurReveal < opening.blurReveal)
        assertEquals(0f, clean.blurReveal, 0f)
        assertTrue(clean.layers.isEmpty())
        assertTrue(opening.layers.map { it.effectName }.containsAll(listOf("Blur", "Soft Focus")))
        assertTrue(resolveTimedCreatorEffectsV26(listOf(videoFx), video, 500_000L).blur > 0f)
        assertTrue(resolveTimedCreatorEffectsV26(listOf(videoFx), video, 2_000_000L).isIdentity)
    }

    @Test fun fiveSecondWaveStaysInCleanWindowAndTravelsAcrossDiagonal() {
        val early = frame(video, videoFx, 2.1f)
        val first = frame(video, videoFx, 3f)
        val last = frame(video, videoFx, 5.5f)
        val end = frame(video, videoFx, 7f)
        assertEquals(0f, first.blurReveal, 0f)
        assertEquals(0f, first.shakeStrength, 0f)
        assertEquals(0f, early.waveStrength, 0f)
        assertTrue(first.waveStrength > 0f)
        assertTrue(first.waveProgress < last.waveProgress)
        assertTrue(last.waveProgress <= 1f)
        assertEquals(0f, end.waveStrength, 0f)
        assertTrue(first.layers.isEmpty())
    }

    @Test fun nextFiveSecondsHasExactlyTwoSeparatedShakeImpulses() {
        val hitOne = frame(video, videoFx, 8.45f)
        val between = frame(video, videoFx, 9.45f)
        val hitTwo = frame(video, videoFx, 10.45f)
        val end = frame(video, videoFx, 12f)
        assertTrue(hitOne.shakeStrength > .95f)
        assertTrue(hitTwo.shakeStrength > .95f)
        assertTrue(between.shakeStrength < .001f)
        assertEquals(0f, hitOne.waveStrength, 0f)
        assertEquals(0f, end.shakeStrength, 0f)
        assertEquals(0f, hitTwo.blurReveal, 0f)
        assertTrue(hitOne.layers.isEmpty())
    }

    @Test fun repeatingWindowAlternatesWaveAndDoubleShakeAfterTwelveSeconds() {
        assertTrue(frame(video, videoFx, 14f).waveStrength > 0f)
        assertTrue(frame(video, videoFx, 18.45f).shakeStrength > .95f)
        assertTrue(frame(video, videoFx, 24f).waveStrength > 0f)
        assertEquals(0f, frame(video, videoFx, 38f).blurReveal, 0f)
    }

    @Test fun slowZoomRunsDuringOpeningWaveShakeAndAfterManyCycles() {
        val start = frame(video, videoFx, 0f)
        val wave = frame(video, videoFx, 4f)
        val shake = frame(video, videoFx, 8f)
        val nextCycle = frame(video, videoFx, 16f)
        assertTrue(listOf(start, wave, shake, nextCycle).all { it.active && it.zoomScale > 1f })
        assertTrue(wave.zoomScale > start.zoomScale)
        assertTrue(shake.zoomScale > wave.zoomScale)
        assertEquals(start.zoomScale, nextCycle.zoomScale, .0001f)
    }

    @Test fun strengthAndTimelineBoundariesApplyToEntireSequence() {
        val full = frame(video, videoFx, 3.5f)
        val half = frame(video, videoFx.copy(amount = .5f), 3.5f)
        assertTrue(half.waveStrength < full.waveStrength)
        assertTrue(half.zoomScale < full.zoomScale)
        assertFalse(frame(video, videoFx.copy(amount = 0f), 3.5f).active)
        assertFalse(frame(video, videoFx.copy(enabled = false), 3.5f).active)

        val bounded = videoFx.copy(sourceStartUsV26 = 10_000_000L,
            sourceEndUsV26 = 30_000_000L)
        assertFalse(frame(video, bounded, 9f).active)
        assertTrue(frame(video, bounded, 10f).blurReveal > 0f)
        assertEquals(0f, frame(video, bounded, 12f).blurReveal, 0f)
        assertTrue(frame(video, bounded, 14f).waveStrength > 0f)
        assertFalse(frame(video, bounded, 31f).active)
    }

    @Test fun photoAndVideoTemplatesAreMutuallyMediaAwareButShareSameTimeline() {
        assertFalse(frame(video, photoFx, 4f).active)
        assertFalse(frame(image, videoFx, 4f).active)
        val v = frame(video, videoFx, 4f)
        val p = frame(image, photoFx, 4f)
        assertEquals(18, v.style)
        assertEquals(19, p.style)
        assertTrue(v.waveStrength > 0f && p.waveStrength > 0f)
        assertEquals(v.zoomScale, p.zoomScale, .00001f)
        assertEquals(v.blurReveal, p.blurReveal, 0f)
    }
}
