package com.tajuli.digitorandroid.editor.processing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonRoiRuntimeStatusV60Test {
    @Test
    fun roiProofUsesTheActuallySelectedFixedMattingResolution() {
        val legacy =
            "ROI DEBUG: ACTIVE · box=0,0 500x500 of 960x540 · frame=51.9% · " +
                "density-vs-384≈1.93x · density-vs-512≈1.08x · preferred<=52% · " +
                "crop-before-384=YES · outside-ROI alpha=0"

        try {
            for (size in intArrayOf(256, 320, 384, 512)) {
                PpMattingResolutionRuntimeV69.select(size)
                PersonRoiRuntimeStatusV60.update(legacy)
                val label = PersonRoiRuntimeStatusV60.label.value.orEmpty()

                assertTrue(label.contains("density-vs-$size≈1.93x"))
                assertTrue(label.contains("crop-before-$size=YES"))
                assertFalse(label.contains("density-vs-512≈1.08x"))
            }
        } finally {
            PpMattingResolutionRuntimeV69.select(384)
            PersonRoiRuntimeStatusV60.update(null)
        }
    }
}
