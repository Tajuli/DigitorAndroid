package com.tajuli.digitorandroid.editor.render

import com.tajuli.digitorandroid.editor.model.TransitionPresetV24
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TransitionPresetMotionV24Test {
    private val base = ResolveOverlayState(1f, 0f, 0f, 1f, 1f, 0f)

    @Test
    fun cubeAndPageTurnHaveDistinctGeometry() {
        val cube = TransitionPresetMotionV24.incoming(base, TransitionPresetV24.CUBE_3D, .25f)
        val page = TransitionPresetMotionV24.incoming(base, TransitionPresetV24.PAGE_TURN, .25f)
        assertNotNull(cube)
        assertNotNull(page)
        assertNotEquals(cube, page)
    }

    @Test
    fun shakeAndStretchAreNotFallbackMotion() {
        val shake = TransitionPresetMotionV24.incoming(base, TransitionPresetV24.CAMERA_SHAKE, .35f)
        val stretch = TransitionPresetMotionV24.incoming(base, TransitionPresetV24.STRETCH, .35f)
        assertNotNull(shake)
        assertNotNull(stretch)
        assertNotEquals(base, shake)
        assertNotEquals(base, stretch)
    }
}
