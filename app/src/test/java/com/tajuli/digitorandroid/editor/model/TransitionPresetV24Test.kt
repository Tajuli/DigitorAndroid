package com.tajuli.digitorandroid.editor.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TransitionPresetV24Test {
    @Test
    fun presetIdsRemainStableAndUnique() {
        val ids = listOf(
            TransitionPresetV24.PULL_IN, TransitionPresetV24.PULL_OUT, TransitionPresetV24.ZOOM_IN,
            TransitionPresetV24.ZOOM_OUT, TransitionPresetV24.ZOOM_LENS, TransitionPresetV24.CAMERA_ZOOM,
            TransitionPresetV24.SWIPE_LEFT, TransitionPresetV24.SWIPE_RIGHT, TransitionPresetV24.SWIPE_UP,
            TransitionPresetV24.SWIPE_DOWN, TransitionPresetV24.SLIDE_LEFT, TransitionPresetV24.SLIDE_RIGHT,
            TransitionPresetV24.SLIDE_UP, TransitionPresetV24.SLIDE_DOWN, TransitionPresetV24.SPIN,
            TransitionPresetV24.ROTATE, TransitionPresetV24.BLUR, TransitionPresetV24.MOTION_BLUR,
            TransitionPresetV24.VERTICAL_BLUR, TransitionPresetV24.HORIZONTAL_BLUR, TransitionPresetV24.BLUR_ZOOM,
            TransitionPresetV24.FADE, TransitionPresetV24.FADE_IN, TransitionPresetV24.FADE_OUT,
            TransitionPresetV24.BLACK_FADE, TransitionPresetV24.WHITE_FADE, TransitionPresetV24.MIX,
            TransitionPresetV24.DISSOLVE, TransitionPresetV24.CROSS_FADE, TransitionPresetV24.FLASH,
            TransitionPresetV24.WHITE_FLASH, TransitionPresetV24.CAMERA_FLASH, TransitionPresetV24.FLICKER,
            TransitionPresetV24.LIGHT_LEAK, TransitionPresetV24.LENS_FLARE, TransitionPresetV24.FILM_BURN,
            TransitionPresetV24.GLITCH, TransitionPresetV24.RGB_GLITCH, TransitionPresetV24.DIGITAL_GLITCH,
            TransitionPresetV24.DISTORTION, TransitionPresetV24.SHAKE, TransitionPresetV24.CAMERA_SHAKE,
            TransitionPresetV24.STRETCH, TransitionPresetV24.WARP, TransitionPresetV24.CUBE_3D,
            TransitionPresetV24.PAGE_TURN, TransitionPresetV24.MASK_TRANSITION, TransitionPresetV24.SPLIT,
            TransitionPresetV24.VELOCITY, TransitionPresetV24.BEAT_SYNC,
        )
        assertEquals(50, ids.size)
        assertEquals(50, ids.toSet().size)
    }
}
