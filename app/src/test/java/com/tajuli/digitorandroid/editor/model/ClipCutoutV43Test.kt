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
    fun normalizationKeepsV46AndChromaControlsInsideRenderContract() {
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
    fun untouchedLegacyPersonDefaultsMigrateToNaturalSoftEdge() {
        val normalized = ClipCutoutV43(
            mode = CutoutModeV43.PERSON,
            personThreshold = .42f,
            personFeather = .12f,
        ).normalized()

        assertEquals(.50f, normalized.personThreshold, .0001f)
        assertEquals(.075f, normalized.personFeather, .0001f)
    }

    @Test
    fun untouchedV44QualityTupleMigratesDirectlyToV46FabricDefaults() {
        val normalized = ClipCutoutV43(
            mode = CutoutModeV43.PERSON,
            personFeather = .06f,
            edgeShiftV44 = -.015f,
            edgeCleanV44 = .34f,
            dehaloV44 = .62f,
            hairDetailV44 = .72f,
            temporalStabilityV44 = .68f,
        ).normalized()

        assertEquals(.075f, normalized.personFeather, .0001f)
        assertEquals(-.004f, normalized.edgeShiftV44, .0001f)
        assertEquals(.24f, normalized.edgeCleanV44, .0001f)
        assertEquals(.30f, normalized.dehaloV44, .0001f)
        assertEquals(.62f, normalized.hairDetailV44, .0001f)
        assertEquals(.54f, normalized.temporalStabilityV44, .0001f)
    }

    @Test
    fun untouchedV45QualityTupleMigratesToV46FabricDefaults() {
        val normalized = ClipCutoutV43(
            mode = CutoutModeV43.PERSON,
            personFeather = .06f,
            edgeShiftV44 = -.008f,
            edgeCleanV44 = .30f,
            dehaloV44 = .46f,
            hairDetailV44 = .84f,
            temporalStabilityV44 = .62f,
        ).normalized()

        assertEquals(.075f, normalized.personFeather, .0001f)
        assertEquals(-.004f, normalized.edgeShiftV44, .0001f)
        assertEquals(.24f, normalized.edgeCleanV44, .0001f)
        assertEquals(.30f, normalized.dehaloV44, .0001f)
        assertEquals(.62f, normalized.hairDetailV44, .0001f)
        assertEquals(.54f, normalized.temporalStabilityV44, .0001f)
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
