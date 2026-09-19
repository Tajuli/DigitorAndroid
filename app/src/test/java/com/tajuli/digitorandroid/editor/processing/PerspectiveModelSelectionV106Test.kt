package com.tajuli.digitorandroid.editor.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerspectiveModelSelectionV106Test {
    @Test
    fun rejectsTinyHomographyGainThatOnlyPaysForExtraParameters() {
        val similarity = List(20) { 1.00f + (it % 3) * .02f }
        val projective = List(20) { .94f + (it % 3) * .02f }
        assertEquals(0f, perspectiveModelTrustV106(similarity, projective), 1e-6f)
    }

    @Test
    fun acceptsStrongSpatialProjectiveGain() {
        val similarity = List(24) { 2.2f + (it % 4) * .08f }
        val projective = List(24) { .75f + (it % 4) * .04f }
        assertTrue(perspectiveModelTrustV106(similarity, projective) > .75f)
    }

    @Test
    fun rejectsProjectiveModelWhenItIsNotBetter() {
        val similarity = List(16) { .8f }
        val projective = List(16) { .9f }
        assertEquals(0f, perspectiveModelTrustV106(similarity, projective), 1e-6f)
    }
}
