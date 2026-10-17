package com.tajuli.digitorandroid.editor.assets

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class RemoteDownloadStatusV2(
    /** SHA of catalogs advertised by GitHub, not downloaded assets. */
    val available: Map<String, String> = emptyMap(),
    val installed: Map<String, String> = emptyMap(),
    val downloading: Set<String> = emptySet(),
    val errors: Map<String, String> = emptyMap(),
    val checking: Boolean = false,
    val indexError: String? = null,
)

/**
 * Opt-in remote preset delivery. Startup only reads previously approved, cached catalogs
 * and requests GitHub tree METADATA. Never fetch or install a new preset without the user
 * tapping Download/Update and confirming the UI dialog.
 */
object GithubAssetSyncV1 {
    private const val BASE = "https://raw.githubusercontent.com/Tajuli/DigitorAssets/main"
    private const val TREE = "https://api.github.com/repos/Tajuli/DigitorAssets/git/trees/main?recursive=1"
    private const val MAX_CATALOG_BYTES = 256 * 1024
    private val mutex = Mutex()
    private val updates = MutableStateFlow(RemoteDownloadStatusV2())
    val status = updates.asStateFlow()
    private var cacheRestored = false

    suspend fun syncOnLaunch(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            restoreApprovedCache(context.applicationContext)
            refreshIndexLocked()
        }
    }

    /** Manual Refresh also retrieves metadata only. */
    suspend fun refreshIndex(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            restoreApprovedCache(context.applicationContext)
            refreshIndexLocked()
        }
    }

    private fun refreshIndexLocked() {
        updates.value = updates.value.copy(checking = true, indexError = null)
        val result = runCatching {
            RemoteAssetFoldersV2.discover(fetch(TREE, 512 * 1024))
        }
        updates.value = if (result.isSuccess) {
            updates.value.copy(available = result.getOrThrow(), checking = false)
        } else {
            updates.value.copy(checking = false,
                indexError = result.exceptionOrNull()?.message ?: "Could not check GitHub")
        }
    }

    suspend fun downloadWithConsent(context: Context, kind: String, folder: String): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val key = RemoteAssetFoldersV2.key(kind, folder)
                restoreApprovedCache(context.applicationContext)
                val expected = updates.value.available[key] ?: return@withLock false
                if (updates.value.installed[key] == expected) return@withLock true
                updates.value = updates.value.copy(
                    downloading = updates.value.downloading + key,
                    errors = updates.value.errors - key,
                )
                val success = runCatching {
                    val safeFolder = URLEncoder.encode(folder, "UTF-8").replace("+", "%20")
                    val json = fetch("$BASE/$kind/$safeFolder/presets.json", MAX_CATALOG_BYTES)
                    // Prevent installing another revision between discovery and download.
                    require(gitBlobSha(json) == expected) { "Catalog changed; refresh and retry" }
                    check(RemoteAssetCatalogV1.isValidFolder(kind, folder, json)) {
                        "Invalid preset recipe or incorrect category"
                    }
                    val cache = cacheDirectory(context.applicationContext)
                    check(cache.isDirectory || cache.mkdirs())
                    val target = File(cache, localName(kind, folder))
                    val tmp = File(cache, localName(kind, folder) + ".tmp")
                    tmp.writeText(json, Charsets.UTF_8)
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                    check(RemoteAssetCatalogV1.installFolder(kind, folder, json))
                    context.applicationContext.getSharedPreferences("manual-asset-packs-v2",
                        Context.MODE_PRIVATE).edit().putString(key, expected).apply()
                    updates.value = updates.value.copy(installed = updates.value.installed + (key to expected))
                }
                updates.value = updates.value.copy(
                    downloading = updates.value.downloading - key,
                    errors = if (success.isSuccess) updates.value.errors
                        else updates.value.errors + (key to (success.exceptionOrNull()?.message
                            ?: "Download failed")),
                )
                success.isSuccess
            }
        }

    private fun restoreApprovedCache(context: Context) {
        if (cacheRestored) return
        val cache = cacheDirectory(context)
        val prefs = context.getSharedPreferences("manual-asset-packs-v2", Context.MODE_PRIVATE)
        val installed = mutableMapOf<String, String>()
        for (kind in listOf("effects", "filters", "transitions")) {
            for (folder in RemoteAssetFoldersV2.folders(kind)) {
                val key = RemoteAssetFoldersV2.key(kind, folder)
                val sha = prefs.getString(key, null)?.takeIf { it.matches(Regex("[0-9a-f]{40}")) }
                    ?: continue
                val file = File(cache, localName(kind, folder))
                if (!file.isFile || file.length() !in 1..MAX_CATALOG_BYTES.toLong()) continue
                val json = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: continue
                if (gitBlobSha(json) == sha &&
                    RemoteAssetCatalogV1.installFolder(kind, folder, json)
                ) installed[key] = sha
            }
        }
        updates.value = updates.value.copy(installed = installed)
        cacheRestored = true
    }

    private fun cacheDirectory(context: Context): File =
        File(context.filesDir, "approved-github-catalogs-v2")

    private fun localName(kind: String, folder: String): String =
        (kind + "_" + folder.replace(" ", "_").lowercase()) + ".json"

    private fun gitBlobSha(content: String): String {
        val body = content.toByteArray(Charsets.UTF_8)
        val header = "blob ${body.size}\u0000".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-1").digest(header + body)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun fetch(address: String, maxBytes: Int): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "DigitorAndroid/manual-assets-v2")
        }
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "GitHub HTTP ${connection.responseCode}"
            }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val block = ByteArray(8192)
                var count = 0
                while (true) {
                    val n = input.read(block)
                    if (n < 0) break
                    count += n
                    require(count <= maxBytes) { "Remote catalog exceeds size limit" }
                    output.write(block, 0, n)
                }
            }
            return output.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }
}
