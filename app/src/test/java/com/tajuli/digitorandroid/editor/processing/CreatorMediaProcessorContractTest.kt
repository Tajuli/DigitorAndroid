package com.tajuli.digitorandroid.editor.processing

import org.junit.Assert.assertTrue
import org.junit.Test

class CreatorMediaProcessorContractTest {
    @Test
    fun speedUiRangeStaysInsideProcessorContract() {
        listOf(.5f, .75f, 1.25f, 1.5f, 2f, 3f).forEach { speed ->
            assertTrue(speed in .25f..4f)
        }
    }
}
