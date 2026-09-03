package com.tajuli.digitorandroid.editor.model

import org.junit.Assert.assertEquals
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
    fun normalizationKeepsV44AndChromaControlsInsideRenderContract() {
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
            edgeShiftV44 = -9f,
            edgeCleanV44 = 4f,
            dehaloV44 = -3f,
            hairDetailV44 = 7f,
            temporalStabilityV44 = 8f,
        ).normalized()

        assertEquals(.05f, normalized.personThreshold, .0001f)
        assertEquals(.45f, normalized.personFeather, .0001f)
        assertEquals(0f, normalized.keyRed, .0001f)
        assertEquals(1f, normalized.keyGreen, .0001f)
        assertEquals(.4f, normalized.keyBlue, .0001f)
        assertEquals(.40f, normalized.chromaSimilarity, .0001f)
        assertEquals(.005f, normalized.chromaSoftness, .0001f)
        assertEquals(1f, normalized.spillSuppression, .0001f)
        assertEquals(-.18f, normalized.edgeShiftV44, .0001f)
        assertEquals(1f, normalized.edgeCleanV44, .0001f)
        assertEquals(0f, normalized.dehaloV44, .0001f)
        assertEquals(1f, normalized.hairDetailV44, .0001f)
        assertEquals(.92f, normalized.temporalStabilityV44, .0001f)
    }

    @Test
    fun untouchedLegacyPersonDefaultsMigrateToTighterEdgeTuning() {
        val normalized = ClipCutoutV43(
            mode = CutoutModeV43.PERSON,
            personThreshold = .42f,
            personFeather = .12f,
        ).normalized()

        assertEquals(.50f, normalized.personThreshold, .0001f)
        assertEquals(.06f, normalized.personFeather, .0001f)
    }

    @Test
    fun userTunedPersonEdgesAreNotOverridden() {
        val normalized = ClipCutoutV43(
            mode = CutoutModeV43.PERSON,
            personThreshold = .47f,
            personFeather = .09f,
            edgeShiftV44 = .04f,
            dehaloV44 = .81f,
        ).normalized()

        assertEquals(.47f, normalized.personThreshold, .0001f)
        assertEquals(.09f, normalized.personFeather, .0001f)
        assertEquals(.04f, normalized.edgeShiftV44, .0001f)
        assertEquals(.81f, normalized.dehaloV44, .0001f)
    }
}
