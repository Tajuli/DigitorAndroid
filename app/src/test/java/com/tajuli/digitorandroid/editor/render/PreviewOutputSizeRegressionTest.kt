package com.tajuli.digitorandroid.editor.render

import com.tajuli.digitorandroid.editor.model.TimelineProject
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewOutputSizeRegressionTest {
    @Test
    fun portraitPreview_capsHeight() {
        val project = TimelineProject(width = 1080, height = 1920)
        assertEquals(404 to 720, resolvePreviewOutputSize(project, 720))
    }
}
