package com.tajuli.digitorandroid.editor.processing

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
    fun stableBackgroundRequiresHistoryAndConsensus() {
        assertFalse(stableBackgroundTrackV103(2, 0, .95f))
        assertFalse(stableBackgroundTrackV103(20, 1, .95f))
        assertTrue(stableBackgroundTrackV103(20, 10, .80f))
    }
}
