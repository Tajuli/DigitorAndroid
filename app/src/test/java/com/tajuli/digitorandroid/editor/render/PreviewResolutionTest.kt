package com.tajuli.digitorandroid.editor.render

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewResolutionTest {
    @Test
    fun previewInputsAndCanvasUseTheSameScale() {
        assertEquals(1280 to 720, resolvePreviewInputSize(3840, 2160, 3840, 2160, 1280, 720))
        assertEquals(640 to 360, resolvePreviewInputSize(1920, 1080, 3840, 2160, 1280, 720))
    }

    @Test
    fun previewScaleDoesNotUpscaleInputs() {
        assertEquals(640 to 360, resolvePreviewInputSize(640, 360, 1280, 720, 1280, 720))
    }
}
