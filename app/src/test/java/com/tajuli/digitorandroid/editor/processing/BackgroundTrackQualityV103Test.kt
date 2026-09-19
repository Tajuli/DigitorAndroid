package com.tajuli.digitorandroid.editor.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundTrackQualityV103Test {
    @Test
    fun longLivedConsensusTrack_outweighsFreshTrack() {
        val fresh = backgroundTrackWeightV103(
            ageFrames = 2,
            consensusFrames = 0,
            normalizedSad = 6f,
        )
        val stable = backgroundTrackWeightV103(
            ageFrames = 28,
            consensusFrames = 18,
            normalizedSad = 10f,
        )

        assertTrue(stable > fresh * 2f)
        assertTrue(stable > .75f)
    }

    @Test
    fun v104NewTrackMapsBackIntoGlobalReference() {
        // Reference -> current: +24 px X, -12 px Y on a 480x480 analysis frame.
        val txNdc = 24f / 239.5f
        val tyNdc = 12f / 239.5f
        val referenceToCurrent = floatArrayOf(
            1f, 0f, txNdc,
            0f, 1f, tyNdc,
            0f, 0f, 1f,
        )

        val mapped = mapCurrentPointToReferenceV104(
            currentX = 200f + 24f,
            currentY = 180f - 12f,
            width = 480,
            height = 480,
            referenceToCurrent = referenceToCurrent,
        )
        assertTrue(mapped != null)
        assertEquals(200f, mapped!!.first, .05f)
        assertEquals(180f, mapped.second, .05f)
    }

    @Test
    fun stableBackgroundRequiresHistoryAndConsensus() {
        assertFalse(stableBackgroundTrackV103(2, 0, .95f))
        assertFalse(stableBackgroundTrackV103(20, 1, .95f))
        assertTrue(stableBackgroundTrackV103(20, 10, .80f))
    }
}
