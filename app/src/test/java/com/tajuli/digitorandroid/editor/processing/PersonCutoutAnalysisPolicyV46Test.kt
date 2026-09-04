package com.tajuli.digitorandroid.editor.processing

import com.tajuli.digitorandroid.editor.model.CutoutAnalysisQualityV47
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonCutoutAnalysisPolicyV46Test {
    @Test
    fun lowUsesFourAnchorsPerSecondWithoutLongClipCap() {
        assertEquals(252, personCutoutEstimatedAnchorCountV47(63_000_000L, CutoutAnalysisQualityV47.LOW))
        assertEquals(360, personCutoutEstimatedAnchorCountV47(90_000_000L, CutoutAnalysisQualityV47.LOW))
    }

    @Test
    fun mediumUsesTwelveAnchorsPerSecond() {
        assertEquals(756, personCutoutEstimatedAnchorCountV47(63_000_000L, CutoutAnalysisQualityV47.MEDIUM))
        assertEquals(12, personCutoutTargetTimesV47(0L, 1_000_000L, CutoutAnalysisQualityV47.MEDIUM).size)
    }

    @Test
    fun highIsEveryDecodedFrame() {
        val cadence = personCutoutCadenceV47(CutoutAnalysisQualityV47.HIGH)
        assertTrue(cadence.everyDecodedFrame)
        assertEquals(0, personCutoutTargetTimesV47(0L, 1_000_000L, CutoutAnalysisQualityV47.HIGH).size)
        assertEquals(61, personCutoutEstimatedAnchorCountV47(1_000_000L, CutoutAnalysisQualityV47.HIGH, 60f))
    }

    @Test
    fun lowOneSecondHasFourTargets() {
        assertEquals(5, personCutoutTargetTimesV47(0L, 1_000_000L, CutoutAnalysisQualityV47.LOW).size)
    }

    @Test
    fun v48HairSemanticRefreshIsSparseRelativeToDenseMatting() {
        assertEquals(250_000L, hairSemanticRefreshIntervalUsV48(CutoutAnalysisQualityV47.LOW))
        assertEquals(250_000L, hairSemanticRefreshIntervalUsV48(CutoutAnalysisQualityV47.MEDIUM))
        assertEquals(125_000L, hairSemanticRefreshIntervalUsV48(CutoutAnalysisQualityV47.HIGH))
    }
}
