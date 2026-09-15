package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.processing.AutoCaptionGeneratorInstallerV85
import com.tajuli.digitorandroid.editor.processing.AutoCaptionLanguageV79
import com.tajuli.digitorandroid.editor.processing.AutoCaptionProgressV77
import com.tajuli.digitorandroid.editor.processing.AutoCaptionQualityV77
import com.tajuli.digitorandroid.editor.processing.ZipformerAutoCaptionEngineV83
import com.tajuli.digitorandroid.editor.processing.autoCaptionCountV77
import com.tajuli.digitorandroid.editor.processing.autoCaptionOverlaysV84
import com.tajuli.digitorandroid.editor.processing.autoCaptionTrackIdV84
import com.tajuli.digitorandroid.editor.processing.clearAutoCaptionsV77
import com.tajuli.digitorandroid.editor.processing.deleteAutoCaptionTextV84
import com.tajuli.digitorandroid.editor.processing.deleteAutoCaptionTrackV84
import com.tajuli.digitorandroid.editor.processing.updateAutoCaptionTextV84
import com.tajuli.digitorandroid.editor.processing.withAutoCaptionsV77
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CC85Accent = Color(0xFF30E0C3)
private val CC85Panel = Color(0xFF121217)
private val CC85Muted = Color(0xFF9898A1)
private const val CC85_PREFS = "auto_caption_v85"
private const val CC85_LANGUAGE = "language"

private enum class AutoCcOperationV85 {
    IDLE,
    DOWNLOAD,
    GENERATE,
}

@Composable
fun AutoCaptionLauncherV85(
    vm: EditorViewModelV4,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
    var open by remember { mutableStateOf(false) }
    val count = state.project.autoCaptionCountV77()

    FilledTonalButton(
        onClick = { open = true },
        modifier = modifier.height(34.dp),
        shape = RoundedCornerShape(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
    ) {
        Icon(Icons.Rounded.Subtitles, contentDescription = null)
        Spacer(Modifier.width(5.dp))
        Text(if (count > 0) "Auto CC · $count" else "Auto CC", fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    }

    if (open) AutoCaptionDialogV85(vm = vm, onDismiss = { open = false })
}

@Composable
private fun AutoCaptionDialogV85(
    vm: EditorViewModelV4,
    onDismiss: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val engine = remember(context) { ZipformerAutoCaptionEngineV83(context) }
    val installer = remember(context) { AutoCaptionGeneratorInstallerV85(context) }
    val preferences = remember(context) {
        context.getSharedPreferences(CC85_PREFS, android.content.Context.MODE_PRIVATE)
    }

    var installed by remember { mutableStateOf(installer.isInstalled()) }
    var operation by remember { mutableStateOf(AutoCcOperationV85.IDLE) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf(if (installed) "Ready" else "Download required") }
    var lastBackend by remember { mutableStateOf<String?>(null) }

    val audioTracks = state.project.tracks.filter { it.kind == TrackKind.AUDIO && !it.muted && it.clips.isNotEmpty() }
    var selectedTrackId by remember {
        mutableStateOf(audioTracks.firstOrNull { it.name == "A1" }?.id ?: audioTracks.firstOrNull()?.id)
    }
    var language by remember {
        val stored = preferences.getString(CC85_LANGUAGE, AutoCaptionLanguageV79.BANGLA.name)
        mutableStateOf(runCatching { AutoCaptionLanguageV79.valueOf(stored.orEmpty()) }.getOrDefault(AutoCaptionLanguageV79.BANGLA))
    }
    var quality by remember { mutableStateOf(AutoCaptionQualityV77.ACCURATE) }
    val generatedCaptions = state.project.autoCaptionOverlaysV84()
    var selectedCaptionId by remember { mutableStateOf<String?>(generatedCaptions.firstOrNull()?.id) }
    var editText by remember { mutableStateOf(generatedCaptions.firstOrNull()?.text.orEmpty()) }

    val busy = operation != AutoCcOperationV85.IDLE

    LaunchedEffect(audioTracks, selectedTrackId) {
        if (selectedTrackId == null || audioTracks.none { it.id == selectedTrackId }) {
            selectedTrackId = audioTracks.firstOrNull { it.name == "A1" }?.id ?: audioTracks.firstOrNull()?.id
        }
    }
    LaunchedEffect(generatedCaptions, selectedCaptionId) {
        val selected = generatedCaptions.firstOrNull { it.id == selectedCaptionId }
            ?: generatedCaptions.firstOrNull()
        if (selected?.id != selectedCaptionId) selectedCaptionId = selected?.id
        if (selected != null && editText != selected.text && selected.id == selectedCaptionId) {
            editText = selected.text
        }
        if (selected == null) editText = ""
    }

    fun rememberLanguage(next: AutoCaptionLanguageV79) {
        language = next
        preferences.edit().putString(CC85_LANGUAGE, next.name).apply()
    }

    fun cancelActive() {
        status = when (operation) {
            AutoCcOperationV85.DOWNLOAD -> "Cancelling download…"
            AutoCcOperationV85.GENERATE -> "Cancelling Auto CC…"
            AutoCcOperationV85.IDLE -> status
        }
        activeJob?.cancel()
    }

    fun startDownload() {
        if (busy || installed || !ZipformerAutoCaptionEngineV83.supportedOnThisDevice()) return
        operation = AutoCcOperationV85.DOWNLOAD
        progress = 0f
        status = "Downloading Auto Caption Generator · 0%"
        lastBackend = null
        activeJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    installer.download { update: AutoCaptionProgressV77 ->
                        progress = update.fraction.coerceIn(0f, 1f)
                        status = update.message
                    }
                }
                installed = installer.isInstalled()
                progress = if (installed) 1f else progress
                status = if (installed) "Auto Caption Generator ready" else "Download incomplete"
            } catch (cancelled: CancellationException) {
                status = "Download cancelled"
                throw cancelled
            } catch (error: Throwable) {
                status = error.message ?: "Download failed"
                vm.setEditorStatusV19(status)
            } finally {
                operation = AutoCcOperationV85.IDLE
                activeJob = null
            }
        }
    }

    fun startGenerate() {
        val trackId = selectedTrackId ?: return
        if (busy || !installed) return
        operation = AutoCcOperationV85.GENERATE
        progress = 0f
        status = "Starting Auto CC…"
        lastBackend = null
        val projectSnapshot = state.project
        val languageSnapshot = language
        val qualitySnapshot = quality
        activeJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    engine.generate(
                        project = projectSnapshot,
                        audioTrackId = trackId,
                        quality = qualitySnapshot,
                        language = languageSnapshot,
                        onProgress = { update: AutoCaptionProgressV77 ->
                            progress = update.fraction.coerceIn(0f, 1f)
                            status = update.message
                        },
                    )
                }
                val nextProject = vm.state.value.project.withAutoCaptionsV77(result.captions)
                vm.commitProjectV19(
                    label = "auto-caption-v85",
                    project = nextProject,
                    status = "Auto CC · ${result.captions.size} captions · ${result.backend}",
                )
                progress = 1f
                status = "${result.captions.size} captions ready · ${result.model}"
                lastBackend = result.backend
                selectedCaptionId = null
            } catch (cancelled: CancellationException) {
                status = "Auto CC cancelled"
                vm.setEditorStatusV19(status)
                throw cancelled
            } catch (error: Throwable) {
                status = error.message ?: "Auto CC failed"
                vm.setEditorStatusV19(status)
            } finally {
                operation = AutoCcOperationV85.IDLE
                activeJob = null
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Rounded.Subtitles, contentDescription = null, tint = CC85Accent) },
        title = { Text("Auto Captions", fontWeight = FontWeight.SemiBold) },
        text = {
            if (!installed) {
                Column(
                    Modifier.fillMaxWidth().background(CC85Panel, RoundedCornerShape(10.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Download Auto Caption Generator", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "One-time download · বাংলা + English · saved on this device",
                        fontSize = 9.sp,
                        color = CC85Muted,
                    )
                    Text(
                        "Speech stays on your phone. After this download, Auto CC opens directly without downloading a model from Generate.",
                        fontSize = 8.sp,
                        color = CC85Muted,
                    )
                    if (!ZipformerAutoCaptionEngineV83.supportedOnThisDevice()) {
                        Text("Auto CC is not available for this device ABI.", fontSize = 9.sp, color = Color(0xFFFFB4AB))
                    }
                    if (operation == AutoCcOperationV85.DOWNLOAD || progress > 0f) {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                        )
                        Text(status, fontSize = 8.sp, color = if (busy) CC85Accent else CC85Muted)
                    }
                }
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(CC85Panel, RoundedCornerShape(10.dp))
                        .padding(10.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Text("On-device Auto CC · বাংলা + English", fontSize = 9.sp, color = Color.White.copy(alpha = .78f))
                    Text("Long captions wrap inside a fixed safe width and stay centered at the same position.", fontSize = 8.sp, color = CC85Muted)

                    Text("Speech source", fontSize = 8.sp, color = CC85Muted)
                    if (audioTracks.isEmpty()) {
                        Text("Import a video with speech or add an audio clip first.", fontSize = 9.sp)
                    } else {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            audioTracks.forEach { track ->
                                AssistChip(
                                    onClick = { if (!busy) selectedTrackId = track.id },
                                    label = { Text(if (track.id == selectedTrackId) "✓ ${track.name}" else track.name, fontSize = 8.sp) },
                                )
                            }
                        }
                    }

                    Text("Speech language", fontSize = 8.sp, color = CC85Muted)
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        AutoCaptionLanguageV79.entries.forEach { item ->
                            val label = if (item == AutoCaptionLanguageV79.AUTO) "Auto · বাংলা + English" else item.label
                            if (item == language) {
                                Button(onClick = { rememberLanguage(item) }, enabled = !busy, modifier = Modifier.height(34.dp)) {
                                    Text(label, fontSize = 8.sp)
                                }
                            } else {
                                OutlinedButton(onClick = { rememberLanguage(item) }, enabled = !busy, modifier = Modifier.height(34.dp)) {
                                    Text(label, fontSize = 8.sp)
                                }
                            }
                        }
                    }
                    Text(language.detail, fontSize = 8.sp, color = CC85Muted)

                    Text("Mode", fontSize = 8.sp, color = CC85Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        AutoCaptionQualityV77.entries.forEach { item ->
                            if (item == quality) {
                                Button(onClick = { quality = item }, enabled = !busy, modifier = Modifier.height(34.dp)) {
                                    Text(item.label, fontSize = 8.sp)
                                }
                            } else {
                                OutlinedButton(onClick = { quality = item }, enabled = !busy, modifier = Modifier.height(34.dp)) {
                                    Text(item.label, fontSize = 8.sp)
                                }
                            }
                        }
                    }

                    if (operation == AutoCcOperationV85.GENERATE || progress > 0f) {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            status,
                            modifier = Modifier.weight(1f),
                            fontSize = 8.sp,
                            color = if (busy) CC85Accent else CC85Muted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        lastBackend?.let { backend -> Text(backend, fontSize = 8.sp, color = CC85Accent) }
                    }

                    if (generatedCaptions.isNotEmpty() && !busy) {
                        Text("Edit generated captions", fontSize = 8.sp, color = CC85Muted)
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            generatedCaptions.forEachIndexed { index, caption ->
                                AssistChip(
                                    onClick = {
                                        selectedCaptionId = caption.id
                                        editText = caption.text
                                        TimelineTextSelectionBusV10.select(caption.id)
                                        vm.selectTextOverlay(caption.id)
                                    },
                                    label = {
                                        Text(
                                            if (caption.id == selectedCaptionId) "✓ ${index + 1}" else "${index + 1}",
                                            fontSize = 8.sp,
                                        )
                                    },
                                )
                            }
                        }

                        val selectedCaption = generatedCaptions.firstOrNull { it.id == selectedCaptionId }
                        if (selectedCaption != null) {
                            OutlinedTextField(
                                value = editText,
                                onValueChange = { editText = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Caption text", fontSize = 8.sp) },
                                minLines = 2,
                                maxLines = 4,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    enabled = editText.isNotBlank() && editText.trim() != selectedCaption.text,
                                    onClick = {
                                        vm.commitProjectV19(
                                            label = "edit-auto-caption-v85",
                                            project = state.project.updateAutoCaptionTextV84(selectedCaption.id, editText),
                                            status = "Caption updated",
                                        )
                                        status = "Caption updated"
                                    },
                                ) { Text("Apply edit", fontSize = 8.sp) }
                                TextButton(
                                    onClick = {
                                        vm.commitProjectV19(
                                            label = "delete-auto-caption-v85",
                                            project = state.project.deleteAutoCaptionTextV84(selectedCaption.id),
                                            status = "Caption deleted",
                                        )
                                        TimelineTextSelectionBusV10.clear(selectedCaption.id)
                                        selectedCaptionId = null
                                        editText = ""
                                        status = "Caption deleted"
                                    },
                                ) { Text("Delete caption", fontSize = 8.sp, color = Color(0xFFFF7474)) }
                            }
                        }
                    }

                    val existing = state.project.autoCaptionCountV77()
                    if (existing > 0 && !busy) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            TextButton(
                                onClick = {
                                    vm.commitProjectV19(
                                        label = "clear-auto-caption-v85",
                                        project = state.project.clearAutoCaptionsV77(),
                                        status = "Auto captions cleared",
                                    )
                                    TimelineTextSelectionBusV10.clear()
                                    selectedCaptionId = null
                                    editText = ""
                                    status = "Auto captions cleared"
                                    progress = 0f
                                },
                            ) { Text("Clear $existing captions", fontSize = 8.sp) }

                            if (state.project.autoCaptionTrackIdV84() != null) {
                                TextButton(
                                    onClick = {
                                        vm.commitProjectV19(
                                            label = "delete-cc-track-v85",
                                            project = state.project.deleteAutoCaptionTrackV84(),
                                            status = "CC track deleted",
                                        )
                                        TimelineTextSelectionBusV10.clear()
                                        selectedCaptionId = null
                                        editText = ""
                                        status = "CC track deleted"
                                    },
                                ) { Text("Delete CC track", fontSize = 8.sp, color = Color(0xFFFF7474)) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                operation == AutoCcOperationV85.DOWNLOAD -> {
                    Button(onClick = ::cancelActive) { Text("Cancel Download") }
                }
                operation == AutoCcOperationV85.GENERATE -> {
                    Button(onClick = ::cancelActive) { Text("Cancel Auto CC") }
                }
                !installed -> {
                    Button(
                        onClick = ::startDownload,
                        enabled = ZipformerAutoCaptionEngineV83.supportedOnThisDevice(),
                    ) { Text("Download") }
                }
                else -> {
                    Button(
                        onClick = ::startGenerate,
                        enabled = selectedTrackId != null && ZipformerAutoCaptionEngineV83.supportedOnThisDevice(),
                    ) { Text("Generate Auto CC") }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") }
        },
    )
}
