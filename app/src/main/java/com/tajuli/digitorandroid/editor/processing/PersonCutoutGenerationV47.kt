package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.resolvedCutoutV43
import java.io.File
import java.security.MessageDigest

// V69 extends the durable signature with the selected fixed PP-MattingV2 operating point. A paused
// 320 run can therefore never resume into a 256/384/512 engine or mix incompatible matte semantics.
private const val V57_CACHE_DIR_NAME = "person_cutout_masks_v65_ppmattingv2_384_motion_safe_detector_roi"
private const val V47_READY_MARKER = ".v47_gpu_ready"
private const val V47_PENDING_MARKER = ".v47_gpu_pending"
private const val V47_GENERATION_VERSION = "stable-v69-ppmattingv2-multires-motion-safe-detector-hysteresis-headroom-r1"

// PersonCutoutMaskStoreV43 intentionally retains its historical directory name for compatibility.
// Keep this value in sync with PERSON_CUTOUT_CACHE_DIR_V50 in PersonCutoutSegmentationV43.kt.
private const val V66_DURABLE_MASK_CACHE_DIR = "person_cutout_masks_v50_ppmattingv2_hair_spatialflow_512"

internal data class PersonCutoutGenerationStartV66(
    val resumed: Boolean,
    val durableTimesUs: List<Long>,
) {
    val savedFrames: Int get() = durableTimesUs.size
}

/**
 * Start or resume one generation.
 *
 * Matching pending signature = keep durable frame PNGs and continue. Any analysis-time setting
 * change (quality / matting resolution / trim / Hair Detail / Temporal Stability) changes the
 * signature, so old mattes are deleted before a new generation starts. A completed generation has
 * no pending marker; tapping Refresh Matte therefore also starts cleanly from frame one.
 */
internal fun beginPersonCutoutGenerationV66(
    context: Context,
    clip: TimelineClip,
): PersonCutoutGenerationStartV66 {
    val appContext = context.applicationContext
    val settings = clip.resolvedCutoutV43()

    // Publish the validated size before GpuPersonCutoutSegmenterV47 is lazily constructed. The
    // selected backend snapshots this once and stays on one persistent engine for the whole run.
    PpMattingResolutionRuntimeV69.select(settings.mattingSizeV69)

    val dir = personCutoutSourceDirV47(appContext, clip.uri)
    val signature = personCutoutGenerationSignatureV66(clip)
    val pending = File(dir, V47_PENDING_MARKER)
    val matchingPending = pending.isFile &&
        runCatching { pending.readText() == signature }.getOrDefault(false)

    if (!matchingPending) {
        clearGenerationMarkersV66(dir)
        clearDurableMasksV66(appContext, clip.uri)
    } else {
        // A crash can leave a half-written temp file. Completed PNGs are atomic rename checkpoints;
        // temps are never valid resume frames.
        durableMaskDirV66(appContext, clip.uri).listFiles().orEmpty()
            .filter { it.name.endsWith(".tmp", ignoreCase = true) }
            .forEach { runCatching { it.delete() } }
        runCatching { File(dir, V47_READY_MARKER).delete() }
    }

    dir.mkdirs()
    pending.writeText(signature)

    val durableTimes = if (matchingPending) durableTimesV66(appContext, clip) else emptyList()
    return PersonCutoutGenerationStartV66(
        resumed = matchingPending && durableTimes.isNotEmpty(),
        durableTimesUs = durableTimes,
    )
}

/** Historical entry point retained for source compatibility. */
internal fun preparePersonCutoutGenerationV47(context: Context, clip: TimelineClip) {
    beginPersonCutoutGenerationV66(context, clip)
}

internal fun markPersonCutoutGenerationV47Ready(context: Context, clip: TimelineClip) {
    val dir = personCutoutSourceDirV47(context.applicationContext, clip.uri).apply { mkdirs() }
    File(dir, V47_READY_MARKER).writeText(personCutoutGenerationSignatureV66(clip))
    runCatching { File(dir, V47_PENDING_MARKER).delete() }
}

internal fun hasPersonCutoutGenerationV47Marker(context: Context, clip: TimelineClip): Boolean {
    val marker = File(personCutoutSourceDirV47(context.applicationContext, clip.uri), V47_READY_MARKER)
    if (!marker.isFile) return false
    return runCatching { marker.readText() == personCutoutGenerationSignatureV66(clip) }.getOrDefault(false)
}

internal fun hasPersonCutoutGenerationV47PendingMarker(context: Context, clip: TimelineClip): Boolean {
    val marker = File(personCutoutSourceDirV47(context.applicationContext, clip.uri), V47_PENDING_MARKER)
    if (!marker.isFile) return false
    return runCatching { marker.readText() == personCutoutGenerationSignatureV66(clip) }.getOrDefault(false)
}

internal fun hasResumablePersonCutoutGenerationV66(context: Context, clip: TimelineClip): Boolean =
    hasPersonCutoutGenerationV47PendingMarker(context, clip) &&
        durableTimesV66(context.applicationContext, clip).isNotEmpty()

internal fun personCutoutSavedFrameCountV66(context: Context, clip: TimelineClip): Int =
    if (hasPersonCutoutGenerationV47PendingMarker(context, clip) ||
        hasPersonCutoutGenerationV47Marker(context, clip)
    ) {
        durableTimesV66(context.applicationContext, clip).size
    } else {
        0
    }

internal fun personCutoutGenerationSignatureV66(clip: TimelineClip): String {
    val settings = clip.resolvedCutoutV43()
    return buildString {
        append(V47_GENERATION_VERSION)
        append('|'); append(settings.analysisQualityV47.name)
        append('|'); append(settings.mattingSizeV69)
        append('|'); append(clip.sourceInUs)
        append('|'); append(clip.sourceOutUs)
        append('|'); append(settings.hairDetailV44.toBits())
        append('|'); append(settings.temporalStabilityV44.toBits())
    }
}

private fun durableTimesV66(context: Context, clip: TimelineClip): List<Long> {
    val start = clip.sourceInUs.coerceAtLeast(0L)
    val end = clip.sourceOutUs.coerceAtLeast(start + 1L)
    return durableMaskDirV66(context, clip.uri).listFiles().orEmpty()
        .asSequence()
        .filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
        .mapNotNull { it.nameWithoutExtension.toLongOrNull() }
        .filter { it >= start && it < end }
        .distinct()
        .sorted()
        .toList()
}

private fun clearGenerationMarkersV66(dir: File) {
    if (dir.exists()) {
        dir.listFiles().orEmpty().forEach { file -> runCatching { file.delete() } }
    }
    dir.mkdirs()
}

private fun clearDurableMasksV66(context: Context, sourceUri: String) {
    val dir = durableMaskDirV66(context, sourceUri)
    if (!dir.exists()) return
    dir.listFiles().orEmpty().forEach { file -> runCatching { file.delete() } }
}

private fun durableMaskDirV66(context: Context, sourceUri: String): File =
    File(File(context.filesDir, V66_DURABLE_MASK_CACHE_DIR), personCutoutCacheKeyV47(sourceUri))

private fun personCutoutSourceDirV47(context: Context, sourceUri: String): File =
    File(File(context.filesDir, V57_CACHE_DIR_NAME), personCutoutCacheKeyV47(sourceUri))

private fun personCutoutCacheKeyV47(sourceUri: String): String = MessageDigest.getInstance("SHA-256")
    .digest(sourceUri.toByteArray())
    .joinToString("") { byte -> "%02x".format(byte) }
    .take(32)
