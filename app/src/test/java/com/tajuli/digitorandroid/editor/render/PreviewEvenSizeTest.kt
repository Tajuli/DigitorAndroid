package com.tajuli.digitorandroid.editor.render

import com.tajuli.digitorandroid.editor.model.TimelineProject
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewEvenSizeTest {
    @Test
    fun oddSmallDimensions_areRoundedDownToEven() {
        assertEquals(2 to 2, resolvePreviewOutputSize(TimelineProject(width = 3, height = 3), 720))
    }
}
