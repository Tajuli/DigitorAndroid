package com.tajuli.digitorandroid.editor.processing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live proof that crop-before-<selected> ROI inference is active on the physical device. */
object PersonRoiRuntimeStatusV60 {
    private val _label = MutableStateFlow<String?>(null)
    val label: StateFlow<String?> = _label.asStateFlow()

    // PersonRoiMatteV57 historically emitted a 384-based proof pair:
    //   density-vs-384≈1.93x · density-vs-512≈1.08x
    // With selectable fixed 256/320/384/512 graphs that wording is misleading. The first value is
    // actually the same-resolution ROI sampling gain (frameArea / roiArea), so publish it against
    // the graph that is really active for this analysis run. This also makes the crop proof use the
    // same selected size. Keep normalization here so every UI/debug consumer sees one truthful label
    // without depending on Compose-side string replacement.
    private val legacyDensityPair =
        Regex("""density-vs-384≈([0-9.,]+)x · density-vs-512≈[0-9.,]+x""")

    internal fun update(value: String?) {
        if (value == null) {
            _label.value = null
            return
        }

        val selectedSize = PpMattingResolutionRuntimeV69.currentSize()
        _label.value = value
            .replace(legacyDensityPair) { match ->
                "density-vs-$selectedSize≈${match.groupValues[1]}x"
            }
            .replace("crop-before-384=YES", "crop-before-$selectedSize=YES")
    }
}
