package com.tajuli.digitorandroid.editor.assets

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads JSON presets over TLS from a public GitHub repository.
 *
 * Prefers the dedicated DigitorAssets repository. During a repository rename, the app
 * retains a temporary fallback to its previous name, then the original bundled catalog.
 * Catalogs are non-executable JSON; no remote native code is loaded.
 */
object GithubAssetSyncV1 {
    private const val BASE_URL =
        "https://raw.githubusercontent.com/Tajuli/DigitorAssets/main"
    private const val PRE_RENAME_BASE_URL =
        "https://raw.githubusercontent.com/Tajuli/Digitor-Assets-/main"
    private const val FALLBACK_BASE_URL =
        "https://raw.githubusercontent.com/Tajuli/DigitorAndroid/main/remote-assets"
    private const val MAX_BYTES = 256 * 1024
    private const val SUCCESS_INTERVAL_MS = 6L * 60 * 60 * 1000
    private const val RETRY_INTERVAL_MS = 60L * 60 * 1000
    private val categories = listOf("transitions", "effects", "filters")
    private val lock = Mutex()

    suspend fun syncOnLaunch(context: Context) = withContext(Dispatchers.IO) {
        lock.withLock {
            val app = context.applicationContext
            val folder = File(app.filesDir, "github-asset-catalog-v1")
            if (!folder.isDirectory && !folder.mkdirs()) return@withLock

            // Restore the last downloaded presets even while offline.
            for (kind in categories) {
                val cached = File(folder, kind + ".json")
                if (cached.isFile && cached.length() in 1..MAX_BYTES.toLong()) {
                    runCatching { RemoteAssetCatalogV1.install(kind, cached.readText(Charsets.UTF_8)) }
                }
            }

            val prefs = app.getSharedPreferences("github-asset-sync-v1", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val lastAttempt = prefs.getLong("lastAttemptMs", 0L)
            val previousSuccess = prefs.getBoolean("allSucceeded", false)
            val interval = if (previousSuccess) SUCCESS_INTERVAL_MS else RETRY_INTERVAL_MS
            if (now >= lastAttempt && now - lastAttempt < interval) return@withLock

            var allSucceeded = true
            for (kind in categories) {
                // One small, bounded, data-only file for each category.
                val json = sequenceOf(BASE_URL, PRE_RENAME_BASE_URL, FALLBACK_BASE_URL)
                    .mapNotNull { base ->
                        runCatching { download("$base/$kind/presets.json") }.getOrNull()
                            ?.takeIf { RemoteAssetCatalogV1.isValid(kind, it) }
                    }
                    .firstOrNull()
                if (json == null) {
                    allSucceeded = false
                    continue
                }
                val target = File(folder, kind + ".json")
                val temp = File(folder, kind + ".json.tmp")
                val installed = runCatching {
                    // Never destroy the cached file on failed download/parse/write.
                    temp.writeText(json, Charsets.UTF_8)
                    check(temp.renameTo(target)) { "Cannot install preset catalog" }
                    check(RemoteAssetCatalogV1.install(kind, json))
                }.isSuccess
                temp.delete()
                if (!installed) allSucceeded = false
            }
            prefs.edit().putLong("lastAttemptMs", now)
                .putBoolean("allSucceeded", allSucceeded).apply()
        }
    }

    private fun download(address: String): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "DigitorAndroid/remote-assets-v1")
        }
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "GitHub returned HTTP " + connection.responseCode
            }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val block = ByteArray(8 * 1024)
                var size = 0
                while (true) {
                    val count = input.read(block)
                    if (count < 0) break
                    size += count
                    require(size <= MAX_BYTES) { "Preset catalog exceeds 256 KiB" }
                    output.write(block, 0, count)
                }
            }
            return output.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }
}
