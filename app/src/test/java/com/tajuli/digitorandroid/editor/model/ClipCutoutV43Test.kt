package com.tajuli.digitorandroid.editor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ClipCutoutV43Test {
    @Test
    fun legacyClipResolvesToCutoutOff() {
        val clip = TimelineClip(
            uri = "content://sample/video",
            label = "sample",
            timelineStartUs = 0L,
            sourceOutUs = 1_000_000L,
        )

        assertEquals(CutoutModeV43.NONE, clip.resolvedCutoutV43().mode)
    }

    @Test
    fun normalizationKeepsCutoutControlsInsideShaderContract() {
        val normalized = ClipCutoutV43(
            mode = CutoutModeV43.CHROMA_KEY,
            personThreshold = -4f,
            personFeather = 9f,
            keyRed = -1f,
            keyGreen = 2f,
            keyBlue = .4f,
            chromaSimilarity = 8f,
            chromaSoftness = -2f,
            spillSuppression = 3f,
        ).normalized()

        assertEquals(.05f, normalized.personThreshold)
        assertEquals(.45f, normalized.personFeather)
        assertEquals(0f, normalized.keyRed)
        assertEquals(1f, normalized.keyGreen)
        assertEquals(.4f, normalized.keyBlue)
        assertEquals(.40f, normalized.chromaSimilarity)
        assertEquals(.005f, normalized.chromaSoftness)
        assertEquals(1f, normalized.spillSuppression)
        assertNotNull(normalized)
    }
}
