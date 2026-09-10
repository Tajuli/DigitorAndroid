package com.tajuli.digitorandroid.editor.processing

import com.tajuli.digitorandroid.editor.model.normalizedPpMattingSizeV69
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-local handoff from the clip analysis signature to the lazily-created Vulkan backend.
 *
 * CutoutAnalysisRuntimeV66 permits one active analysis pipeline at a time, so the chosen operating
 * point is published before the inference worker can construct its segmenter. The backend snapshots
 * the value once at construction and never switches graph/engine mid-run. That is deliberate: prior
 * physical-device testing showed live Vulkan teardown/recreate transitions can destabilize fragile
 * UNISOC drivers.
 *
 * V70 keeps four fixed graph params but all four use one verified byte-identical ncnn weight blob.
 * This preserves 256/320/384/512 behavior while avoiding three duplicate ~18 MB assets in the APK.
 */
internal object PpMattingResolutionRuntimeV69 {
    private val selectedSize = AtomicInteger(384)
    private const val SHARED_BIN_ASSET = "ppmattingv2_stdc1_human_vulkan_shared.ncnn.bin"

    val supportedSizes: IntArray
        get() = intArrayOf(256, 320, 384, 512)

    fun select(requested: Int): Int {
        val safe = normalizedPpMattingSizeV69(requested)
        selectedSize.set(safe)
        return safe
    }

    fun currentSize(): Int = normalizedPpMattingSizeV69(selectedSize.get())

    fun paramAsset(size: Int = currentSize()): String =
        "ppmattingv2_stdc1_human_vulkan_${normalizedPpMattingSizeV69(size)}.ncnn.param"

    fun binAsset(@Suppress("UNUSED_PARAMETER") size: Int = currentSize()): String =
        SHARED_BIN_ASSET
}
