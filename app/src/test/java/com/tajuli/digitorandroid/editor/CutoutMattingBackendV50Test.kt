package com.tajuli.digitorandroid.editor

import com.tajuli.digitorandroid.editor.model.ClipCutoutV43
import com.tajuli.digitorandroid.editor.model.PersonMattingBackendV50
import com.tajuli.digitorandroid.editor.model.resolvedPersonMattingBackendV50
import org.junit.Assert.assertEquals
import org.junit.Test

class CutoutMattingBackendV50Test {
    @Test
    fun legacyMissingBackendResolvesToModNet() {
        val legacyCompatible = ClipCutoutV43(personMattingBackendV50 = null)
        assertEquals(PersonMattingBackendV50.MODNET, legacyCompatible.resolvedPersonMattingBackendV50())
    }

    @Test
    fun explicitPpMattingSelectionSurvivesNormalization() {
        val experimental = ClipCutoutV43(
            personMattingBackendV50 = PersonMattingBackendV50.PP_MATTING_V2,
        ).normalized()
        assertEquals(
            PersonMattingBackendV50.PP_MATTING_V2,
            experimental.resolvedPersonMattingBackendV50(),
        )
    }
}
