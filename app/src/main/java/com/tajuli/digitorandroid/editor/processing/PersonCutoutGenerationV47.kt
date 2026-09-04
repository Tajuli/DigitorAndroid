package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import java.io.File
import java.security.MessageDigest

private const val V46_CACHE_DIR_NAME = "person_cutout_masks_v46_modnet_hair_spatialflow_512_320"
private const val V47_READY_MARKER = ".v47_gpu_ready"

internal fun preparePersonCutoutGenerationV47(context: Context, sourceUri: String) {
    val dir = personCutoutSourceDirV47(context, sourceUri)
    if (dir.exists()) {
        dir.listFiles().orEmpty().forEach { file -> runCatching { file.delete() } }
    }
    dir.mkdirs()
}

internal fun markPersonCutoutGenerationV47Ready(context: Context, sourceUri: String) {
    val dir = personCutoutSourceDirV47(context, sourceUri).apply { mkdirs() }
    File(dir, V47_READY_MARKER).writeText("gpu-first-v47")
}

internal fun hasPersonCutoutGenerationV47Marker(context: Context, sourceUri: String): Boolean =
    File(personCutoutSourceDirV47(context, sourceUri), V47_READY_MARKER).isFile

private fun personCutoutSourceDirV47(context: Context, sourceUri: String): File =
    File(File(context.filesDir, V46_CACHE_DIR_NAME), personCutoutCacheKeyV47(sourceUri))

private fun personCutoutCacheKeyV47(sourceUri: String): String = MessageDigest.getInstance("SHA-256")
    .digest(sourceUri.toByteArray())
    .joinToString("") { byte -> "%02x".format(byte) }
    .take(32)
