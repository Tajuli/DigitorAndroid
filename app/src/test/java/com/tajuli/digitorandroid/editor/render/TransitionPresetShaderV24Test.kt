package com.tajuli.digitorandroid.editor.render

import com.tajuli.digitorandroid.editor.model.TransitionPresetV24
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TransitionPresetShaderV24Test {
    @Test
    fun keyPresetShadersAreDistinct() {
        val ids = listOf(
            TransitionPresetV24.BLUR,
            TransitionPresetV24.MOTION_BLUR,
            TransitionPresetV24.VERTICAL_BLUR,
            TransitionPresetV24.HORIZONTAL_BLUR,
            TransitionPresetV24.RGB_GLITCH,
            TransitionPresetV24.DIGITAL_GLITCH,
            TransitionPresetV24.FILM_BURN,
            TransitionPresetV24.LENS_FLARE,
            TransitionPresetV24.PAGE_TURN,
            TransitionPresetV24.BEAT_SYNC,
        )
        val codes = ids.map(TransitionPresetShaderV24::code)
        assertEquals(ids.size, codes.toSet().size)
        assertNotEquals(0f, TransitionPresetShaderV24.code(TransitionPresetV24.RGB_GLITCH))
    }
}
