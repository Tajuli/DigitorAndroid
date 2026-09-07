package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.resolvedCutoutV43
import java.io.File
import java.security.MessageDigest

// Keep the stable-weight, human-preserving detector-authoritative ROI mattes physically separate
// from v62/v63 generations. v62 used a destructive envelope; v63 removed that gate but still used
// the CI-adapted 384 checkpoint that produced repeatable holes through valid human body regions on
// the physical Symphony Z60. v64 restores the proven official human weights at fixed 384.
private const val V57_CACHE_DIR_NAME = "person_cutout_masks_v64_ppmattingv2_384_stable_weights_detector_roi"
private const val V47_READY_MARKER = ".v47_gpu_ready"
private const val V47_PENDING_MARKER = ".v47_gpu_pending"
private const val V47_GENERATION_VERSION = "stable-v64-ppmattingv2-384-official-human-weights-detector-size-matte-center-raw-alpha-r1"

internal fun preparePersonCutoutGenerationV47(context: Context, clip: TimelineClip) {
    val dir = personCutoutSourceDirV47(context, clip.uri)
    if (dir.exists()) {
        dir.listFiles().orEmpty().forEach { file -> runCatching { file.delete() } }
    }
    dir.mkdirs()
    File(dir, V47_PENDING_MARKER).writeText(personCutoutGenerationSignatureV47(clip))
}

internal fun markPersonCutoutGenerationV47Ready(context: Context, clip: TimelineClip) {
    val dir = personCutoutSourceDirV47(context, clip.uri).apply { mkdirs() }
    File(dir, V47_READY_MARKER).writeText(personCutoutGenerationSignatureV47(clip))
    runCatching { File(dir, V47_PENDING_MARKER).delete() }
}

internal fun hasPersonCutoutGenerationV47Marker(context: Context, clip: TimelineClip): Boolean {
    val marker = File(personCutoutSourceDirV47(context, clip.uri), V47_READY_MARKER)
    if (!marker.isFile) return false
    return runCatching { marker.readText() == personCutoutGenerationSignatureV47(clip) }.getOrDefault(false)
}

internal fun hasPersonCutoutGenerationV47PendingMarker(context: Context, clip: TimelineClip): Boolean {
    val marker = File(personCutoutSourceDirV47(context, clip.uri), V47_PENDING_MARKER)
    if (!marker.isFile) return false
    return runCatching { marker.readText() == personCutoutGenerationSignatureV47(clip) }.getOrDefault(false)
}

private fun personCutoutGenerationSignatureV47(clip: TimelineClip): String {
    val settings = clip.resolvedCutoutV43()
    return buildString {
        append(V47_GENERATION_VERSION)
        append('|'); append(settings.analysisQualityV47.name)
        append('|'); append(clip.sourceInUs)
        append('|'); append(clip.sourceOutUs)
        append('|'); append(settings.hairDetailV44.toBits())
        append('|'); append(settings.temporalStabilityV44.toBits())
    }
}

private fun personCutoutSourceDirV47(context: Context, sourceUri: String): File =
    File(File(context.filesDir, V57_CACHE_DIR_NAME), personCutoutCacheKeyV47(sourceUri))

private fun personCutoutCacheKeyV47(sourceUri: String): String = MessageDigest.getInstance("SHA-256")
    .digest(sourceUri.toByteArray())
    .joinToString("") { byte -> "%02x".format(byte) }
    .take(32)
