package com.tajuli.digitorandroid.editor.processing

import org.junit.Assert.assertEquals
import org.junit.Test

class PersonCutoutAnalysisPolicyV46Test {
    @Test
    fun sixtyThreeSecondClipKeepsFourAnchorsPerSecond() {
        assertEquals(254, personCutoutTargetAnchorCountV46(63_000_000L))
    }

    @Test
    fun longClipCapsAtThreeHundredTwentyAnchors() {
        assertEquals(320, personCutoutTargetAnchorCountV46(90_000_000L))
    }

    @Test
    fun shortClipKeepsMinimumCoverage() {
        assertEquals(12, personCutoutTargetAnchorCountV46(1_000_000L))
    }
}
