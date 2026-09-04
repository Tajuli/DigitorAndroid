package com.tajuli.digitorandroid.editor.processing

import com.tajuli.digitorandroid.editor.model.CutoutAnalysisQualityV47

/** Shared V47 user-selected analysis policy. There is deliberately no long-clip density cap. */
internal data class PersonCutoutCadenceV47(
    val anchorsPerSecond: Int?,
    val everyDecodedFrame: Boolean,
    val label: String,
)

internal fun personCutoutCadenceV47(quality: CutoutAnalysisQualityV47): PersonCutoutCadenceV47 =
    when (quality) {
        CutoutAnalysisQualityV47.LOW -> PersonCutoutCadenceV47(
            anchorsPerSecond = 4,
            everyDecodedFrame = false,
            label = "Low · 4 fps",
        )
        CutoutAnalysisQualityV47.MEDIUM -> PersonCutoutCadenceV47(
            anchorsPerSecond = 12,
            everyDecodedFrame = false,
            label = "Medium · 12 fps",
        )
        CutoutAnalysisQualityV47.HIGH -> PersonCutoutCadenceV47(
            anchorsPerSecond = null,
            everyDecodedFrame = true,
            label = "High · every frame",
        )
    }

/** Exact fixed-cadence target timestamps for LOW/MEDIUM. HIGH is emitted directly by MediaCodec. */
internal fun personCutoutTargetTimesV47(
    startUs: Long,
    endUs: Long,
    quality: CutoutAnalysisQualityV47,
): List<Long> {
    val start = startUs.coerceAtLeast(0L)
    val end = endUs.coerceAtLeast(start + 1L)
    val cadence = personCutoutCadenceV47(quality)
    if (cadence.everyDecodedFrame) return emptyList()
    val fps = cadence.anchorsPerSecond ?: return emptyList()
    val durationUs = (end - start).coerceAtLeast(1L)
    val count = ((durationUs * fps.toLong() + 999_999L) / 1_000_000L)
        .coerceIn(1L, Int.MAX_VALUE.toLong())
        .toInt()
    return (0 until count).mapNotNull { index ->
        val timeUs = start + (index.toLong() * 1_000_000L) / fps.toLong()
        timeUs.takeIf { it < end }
    }
}

internal fun personCutoutEstimatedAnchorCountV47(
    durationUs: Long,
    quality: CutoutAnalysisQualityV47,
    sourceFpsHint: Float? = null,
): Int {
    val safeDuration = durationUs.coerceAtLeast(1L)
    val cadence = personCutoutCadenceV47(quality)
    val fps = if (cadence.everyDecodedFrame) {
        sourceFpsHint?.takeIf { it.isFinite() && it > 0f }?.coerceIn(1f, 240f) ?: 30f
    } else {
        cadence.anchorsPerSecond!!.toFloat()
    }
    val count = kotlin.math.ceil(safeDuration / 1_000_000.0 * fps.toDouble()).toLong()
    return count.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
}

/** Coverage tolerances are intentionally looser than one cadence interval for VFR/decode jitter. */
internal fun personCutoutMaxGapUsV47(quality: CutoutAnalysisQualityV47): Long =
    when (quality) {
        CutoutAnalysisQualityV47.LOW -> 650_000L
        CutoutAnalysisQualityV47.MEDIUM -> 250_000L
        CutoutAnalysisQualityV47.HIGH -> 200_000L
    }

/** Historical helper retained for source-compatible older callers; V47 default is Medium. */
internal fun personCutoutTargetAnchorCountV46(durationUs: Long): Int =
    personCutoutEstimatedAnchorCountV47(durationUs, CutoutAnalysisQualityV47.MEDIUM)
