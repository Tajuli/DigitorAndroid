package com.tajuli.digitorandroid.editor.processing

import org.junit.Assert.assertEquals
import org.junit.Test

class RetimeFrameCadenceTest {
    @Test fun exactFrameTimestampDoesNotSeekOneFrameBackward() {
        for (fps in listOf(24, 25, 30, 50, 60)) for (index in 1L..1000L) {
            val timestamp = index * 1_000_000L / fps
            assertEquals("$fps fps, frame $index", index, retimeFrameIndexAtOrBefore(timestamp, fps))
            assertEquals(index - 1, retimeFrameIndexAtOrBefore(timestamp - 1, fps))
        }
    }
}
