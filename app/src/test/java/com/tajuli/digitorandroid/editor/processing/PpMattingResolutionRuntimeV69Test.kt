package com.tajuli.digitorandroid.editor.processing

import org.junit.Assert.assertEquals
import org.junit.Test

class PpMattingResolutionRuntimeV69Test {
    @Test
    fun allFixedResolutionsUseOneSharedWeightsAsset() {
        val expected = "ppmattingv2_stdc1_human_vulkan_shared.ncnn.bin"
        PpMattingResolutionRuntimeV69.supportedSizes.forEach { size ->
            assertEquals(expected, PpMattingResolutionRuntimeV69.binAsset(size))
            assertEquals(
                "ppmattingv2_stdc1_human_vulkan_${size}.ncnn.param",
                PpMattingResolutionRuntimeV69.paramAsset(size),
            )
        }
    }
}
