package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.assets.GithubAssetSyncV1
import com.tajuli.digitorandroid.editor.assets.RemoteAssetFoldersV2
import kotlinx.coroutines.launch

/** The user must tap Download AND confirm before any category JSON is transferred. */
@Composable
internal fun RemoteCategoryDownloadControlV2(
    kind: String,
    folder: String,
    modifier: Modifier = Modifier,
) {
    val key = RemoteAssetFoldersV2.key(kind, folder)
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val status by GithubAssetSyncV1.status.collectAsState()
    var ask by remember(key) { mutableStateOf(false) }
    val sha = status.available[key]
    val local = status.installed[key]
    val downloading = key in status.downloading
    val action = when {
        downloading -> "Downloading…"
        sha == null && status.checking -> "Checking…"
        sha == null -> "No online assets"
        local == sha -> "✓ Downloaded"
        local == null -> "Download"
        else -> "Update"
    }
    Row(
        modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "$folder · $action",
            fontSize = 9.sp,
            modifier = Modifier.weight(1f).padding(top = 8.dp),
        )
        if (sha != null && sha != local) {
            TextButton(
                onClick = { ask = true },
                enabled = !downloading && !status.checking,
            ) { Text(if (local == null) "Download" else "Update", fontSize = 10.sp) }
        }
        TextButton(
            onClick = { scope.launch { GithubAssetSyncV1.refreshIndex(context) } },
            enabled = !status.checking && !downloading,
        ) { Text("Refresh", fontSize = 9.sp) }
    }
    val error = status.errors[key] ?: status.indexError
    if (error != null) {
        Text("GitHub: $error", fontSize = 9.sp,
            modifier = Modifier.padding(horizontal = 10.dp))
    }
    if (ask) {
        AlertDialog(
            onDismissRequest = { ask = false },
            title = { Text("Download $folder?") },
            text = { Text(
                "Download the $kind/$folder preset pack from GitHub? It is saved offline " +
                    "and will appear in this category after validation. No files are downloaded " +
                    "until you confirm.",
            ) },
            confirmButton = {
                TextButton(onClick = {
                    ask = false
                    scope.launch { GithubAssetSyncV1.downloadWithConsent(context, kind, folder) }
                }) { Text("Download") }
            },
            dismissButton = {
                TextButton(onClick = { ask = false }) { Text("Cancel") }
            },
        )
    }
}
