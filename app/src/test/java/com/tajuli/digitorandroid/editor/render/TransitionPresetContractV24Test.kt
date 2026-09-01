package com.tajuli.digitorandroid.editor.render

import com.tajuli.digitorandroid.editor.model.TransitionStyleV22
import org.junit.Assert.assertEquals
import org.junit.Test

class TransitionPresetContractV24Test {
    @Test
    fun missingPresetKeepsStableV22Style() {
        val key = TransitionRenderKeyV24(TransitionStyleV22.CROSS_DISSOLVE, null)
        assertEquals(TransitionStyleV22.CROSS_DISSOLVE, key.style)
        assertEquals(null, key.presetId)
    }
}
