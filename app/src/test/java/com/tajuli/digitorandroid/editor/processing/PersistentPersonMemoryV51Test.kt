package com.tajuli.digitorandroid.editor.processing

import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentPersonMemoryV51Test {
    @Test
    fun staticUnsupportedForegroundBirthIsRejected() {
        val result = decidePersonMemorySampleV51(
            PersonMemorySampleV51(
                currentAlpha = .90f,
                previousAlpha = .04f,
                previousLock = .02f,
                previousConfidence = .03f,
                previousAlphaSupport = .05f,
                previousLockSupport = .04f,
                flowConfidence = .85f,
                motionBlocks = .08f,
                temporalStrength = .54f,
            ),
        )
        assertTrue("alpha=${result.alpha}", result.alpha < .30f)
        assertTrue("lock=${result.nextLock}", result.nextLock < .10f)
        assertTrue("confidence=${result.nextConfidence}", result.nextConfidence < .25f)
    }

    @Test
    fun establishedForegroundSurvivesOneFrameAlphaDrop() {
        val result = decidePersonMemorySampleV51(
            PersonMemorySampleV51(
                currentAlpha = .08f,
                previousAlpha = .82f,
                previousLock = .91f,
                previousConfidence = .86f,
                previousAlphaSupport = .88f,
                previousLockSupport = .92f,
                flowConfidence = .82f,
                motionBlocks = .25f,
                temporalStrength = .54f,
            ),
        )
        assertTrue("alpha=${result.alpha}", result.alpha > .45f)
        assertTrue("lock=${result.nextLock}", result.nextLock > .80f)
        assertTrue("confidence=${result.nextConfidence}", result.nextConfidence > .75f)
    }

    @Test
    fun motionSupportedNewForegroundEntersMoreEasilyThanStaticBirth() {
        val staticResult = decidePersonMemorySampleV51(
            PersonMemorySampleV51(
                currentAlpha = .82f,
                previousAlpha = .04f,
                previousLock = .02f,
                previousConfidence = .03f,
                previousAlphaSupport = .05f,
                previousLockSupport = .04f,
                flowConfidence = .85f,
                motionBlocks = .08f,
                temporalStrength = .54f,
            ),
        )
        val movingResult = decidePersonMemorySampleV51(
            PersonMemorySampleV51(
                currentAlpha = .82f,
                previousAlpha = .04f,
                previousLock = .02f,
                previousConfidence = .03f,
                previousAlphaSupport = .05f,
                previousLockSupport = .04f,
                flowConfidence = .85f,
                motionBlocks = 1.80f,
                temporalStrength = .54f,
            ),
        )
        assertTrue("static=${staticResult.alpha}, moving=${movingResult.alpha}", movingResult.alpha > staticResult.alpha + .45f)
        assertTrue("moving lock=${movingResult.nextLock}", movingResult.nextLock > staticResult.nextLock)
    }

    @Test
    fun unreliableFlowDoesNotAggressivelyRewriteCurrentMatte() {
        val result = decidePersonMemorySampleV51(
            PersonMemorySampleV51(
                currentAlpha = .64f,
                previousAlpha = .10f,
                previousLock = .05f,
                previousConfidence = .05f,
                previousAlphaSupport = .08f,
                previousLockSupport = .05f,
                flowConfidence = .03f,
                motionBlocks = .10f,
                temporalStrength = .80f,
            ),
        )
        assertTrue("alpha=${result.alpha}", result.alpha > .58f)
    }
}
