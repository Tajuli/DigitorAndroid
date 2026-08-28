package com.tajuli.digitorandroid.editor

import com.tajuli.digitorandroid.editor.model.ClipTransition
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionBoundaryTest {
    @Test
    fun identityTransitionIsDetected() {
        assertTrue(ClipTransition().isIdentity)
    }
}
