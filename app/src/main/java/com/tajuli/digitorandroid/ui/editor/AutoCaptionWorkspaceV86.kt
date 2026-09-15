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
import com.tajuli.digitorandroid.editor.processing.AutoCaptionLanguagePackManagerV86
import com.tajuli.digitorandroid.editor.processing.AutoCaptionLanguageV86
import com.tajuli.digitorandroid.editor.processing.AutoCaptionProgressV77
import com.tajuli.digitorandroid.editor.processing.AutoCaptionQualityV77
import com.tajuli.digitorandroid.editor.processing.GlobalZipformerAutoCaptionEngineV86
import com.tajuli.digitorandroid.editor.processing.autoCaptionCountV77
import com.tajuli.digitorandroid.editor.processing.autoCaptionOverlaysV84
import com.tajuli.digitorandroid.editor.processing.autoCaptionTrackIdV84
import com.tajuli.digitorandroid.editor.processing.autoLanguageAvailableV86
import com.tajuli.digitorandroid.editor.processing.clearAutoCaptionsV77
import com.tajuli.digitorandroid.editor.processing.deleteAutoCaptionTextV84
import com.tajuli.digitorandroid.editor.processing.deleteAutoCaptionTrackV84
import com.tajuli.digitorandroid.editor.processing.internationalLanguageChoicesV86
import com.tajuli.digitorandroid.editor.processing.updateAutoCaptionTextV84
import com.tajuli.digitorandroid.editor.processing.withAutoCaptionsV77
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CC86Accent = Color(0xFF30E0C3)
private val CC86Panel = Color(0xFF121217)
private val CC86Muted = Color(0xFF9898A1)
private const val CC86_PREFS = "auto_caption_v86"
private const val CC86_LANGUAGE = "language"
private val CC86_DEFAULT_LANGUAGE = AutoCaptionLanguageV86.ENGLISH

private enum class AutoCcOperationV86 {
    IDLE,
    DOWNLOAD,
    GENERATE,
}

@Composable
fun AutoCaptionLauncherV86(
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
        Text(
            if (count > 0) "Auto CC · $count" else "Auto CC",
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }

    if (open) AutoCaptionDialogV86(vm = vm, onDismiss = { open = false })
}

@Composable
private fun AutoCaptionDialogV86(
    vm: EditorViewModelV4,
    onDismiss: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val engine = remember(context) { GlobalZipformerAutoCaptionEngineV86(context) }
    val packManager = remember(context) { AutoCaptionLanguagePackManagerV86(context) }
    val preferences = remember(context) {
        context.getSharedPreferences(CC86_PREFS, android.content.Context.MODE_PRIVATE)
    }

    var installedLanguages by remember { mutableStateOf(packManager.installedLanguages().toSet()) }
    val storedLanguage = remember {
        preferences.getString(CC86_LANGUAGE, null)?.let { stored ->
            runCatching { AutoCaptionLanguageV86.valueOf(stored) }.getOrNull()
        }
    }
    val initialLanguage = remember(installedLanguages, storedLanguage) {
        when {
            storedLanguage != null && (
                storedLanguage in installedLanguages ||
                    storedLanguage == AutoCaptionLanguageV86.AUTO_BN_EN && autoLanguageAvailableV86(installedLanguages)
                ) -> storedLanguage
            CC86_DEFAULT_LANGUAGE in installedLanguages -> CC86_DEFAULT_LANGUAGE
            installedLanguages.isNotEmpty() -> installedLanguages.first()
            else -> CC86_DEFAULT_LANGUAGE
        }
    }

    var language by remember { mutableStateOf(initialLanguage) }
    var quality by remember { mutableStateOf(AutoCaptionQualityV77.ACCURATE) }
    var operation by remember { mutableStateOf(AutoCcOperationV86.IDLE) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var downloadTarget by remember { mutableStateOf<AutoCaptionLanguageV86?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember {
        mutableStateOf(
            if (CC86_DEFAULT_LANGUAGE !in installedLanguages) {
                "Download English to enable Auto CC"
            } else {
                "Ready"
            },
        )
    }
    var lastBackend by remember { mutableStateOf<String?>(null) }
    var manageLanguages by remember { mutableStateOf(false) }

    val audioTracks = state.project.tracks.filter {
        it.kind == TrackKind.AUDIO && !it.muted && it.clips.isNotEmpty()
    }
    var selectedTrackId by remember {
        mutableStateOf(audioTracks.firstOrNull { it.name == "A1" }?.id ?: audioTracks.firstOrNull()?.id)
    }

    val generatedCaptions = state.project.autoCaptionOverlaysV84()
    var selectedCaptionId by remember { mutableStateOf<String?>(generatedCaptions.firstOrNull()?.id) }
    var editText by remember { mutableStateOf(generatedCaptions.firstOrNull()?.text.orEmpty()) }
    val busy = operation != AutoCcOperationV86.IDLE
    val defaultPackInstalled = CC86_DEFAULT_LANGUAGE in installedLanguages
    val firstInstall = !defaultPackInstalled

    val languagePackChoices = buildList {
        add(CC86_DEFAULT_LANGUAGE)
        addAll(internationalLanguageChoicesV86().filter { it != CC86_DEFAULT_LANGUAGE })
    }
    val generationLanguages = buildList {
        if (CC86_DEFAULT_LANGUAGE in installedLanguages) add(CC86_DEFAULT_LANGUAGE)
        addAll(
            internationalLanguageChoicesV86().filter {
                it != CC86_DEFAULT_LANGUAGE && it in installedLanguages
            },
        )
        if (autoLanguageAvailableV86(installedLanguages)) add(AutoCaptionLanguageV86.AUTO_BN_EN)
    }

    LaunchedEffect(audioTracks, selectedTrackId) {
        if (selectedTrackId == null || audioTracks.none { it.id == selectedTrackId }) {
            selectedTrackId = audioTracks.firstOrNull { it.name == "A1" }?.id ?: audioTracks.firstOrNull()?.id
        }
    }

    LaunchedEffect(installedLanguages) {
        val valid = language in installedLanguages ||
            (language == AutoCaptionLanguageV86.AUTO_BN_EN && autoLanguageAvailableV86(installedLanguages))
        if (!valid && installedLanguages.isNotEmpty()) {
            language = if (CC86_DEFAULT_LANGUAGE in installedLanguages) {
                CC86_DEFAULT_LANGUAGE
            } else {
                installedLanguages.first()
            }
            preferences.edit().putString(CC86_LANGUAGE, language.name).apply()
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

    fun rememberLanguage(next: AutoCaptionLanguageV86) {
        language = next
        preferences.edit().putString(CC86_LANGUAGE, next.name).apply()
    }

    fun cancelActive() {
        status = when (operation) {
            AutoCcOperationV86.DOWNLOAD -> "Cancelling download…"
            AutoCcOperationV86.GENERATE -> "Cancelling Auto CC…"
            AutoCcOperationV86.IDLE -> status
        }
        activeJob?.cancel()
    }

    fun startDownload(target: AutoCaptionLanguageV86) {
        if (busy || !target.downloadable || packManager.isInstalled(target)) return
        if (!GlobalZipformerAutoCaptionEngineV86.supportedOnThisDevice()) return
        val installsDefaultPack = target == CC86_DEFAULT_LANGUAGE && !defaultPackInstalled
        operation = AutoCcOperationV86.DOWNLOAD
        downloadTarget = target
        progress = 0f
        status = "Downloading ${target.label} · 0%"
        lastBackend = null
        activeJob = scope.launch {
            try {
                packManager.download(target) { update: AutoCaptionProgressV77 ->
                    progress = update.fraction.coerceIn(0f, 1f)
                    status = update.message
                }
                installedLanguages = packManager.installedLanguages().toSet()
                if (installsDefaultPack) rememberLanguage(CC86_DEFAULT_LANGUAGE)
                progress = 1f
                status = "${target.label} language pack ready"
                manageLanguages = false
            } catch (cancelled: CancellationException) {
                status = "${target.label} download cancelled"
                throw cancelled
            } catch (error: Throwable) {
                status = error.message ?: "Download failed"
                vm.setEditorStatusV19(status)
            } finally {
                operation = AutoCcOperationV86.IDLE
                downloadTarget = null
                activeJob = null
            }
        }
    }

    fun deleteLanguage(target: AutoCaptionLanguageV86) {
        if (busy || !target.downloadable || target == CC86_DEFAULT_LANGUAGE) return
        packManager.delete(target)
        installedLanguages = packManager.installedLanguages().toSet()
        status = "${target.label} language pack removed"
        progress = 0f
    }

    fun startGenerate() {
        val trackId = selectedTrackId ?: return
        if (busy || !packManager.isInstalled(language)) return
        operation = AutoCcOperationV86.GENERATE
        progress = 0f
        status = "Starting ${language.label} Auto CC…"
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
                    label = "auto-caption-v86",
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
                operation = AutoCcOperationV86.IDLE
                activeJob = null
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Rounded.Subtitles, contentDescription = null, tint = CC86Accent) },
        title = { Text("Auto Captions", fontWeight = FontWeight.SemiBold) },
        text = {
            if (firstInstall) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(CC86Panel, RoundedCornerShape(10.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Download Auto Caption Generator", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "English is the default Auto CC language.",
                        fontSize = 9.sp,
                        color = Color.White.copy(alpha = .82f),
                    )
                    Text(
                        "${CC86_DEFAULT_LANGUAGE.detail} · about ${packManager.approximateDownloadMb(CC86_DEFAULT_LANGUAGE)} MB · saved on this device",
                        fontSize = 8.sp,
                        color = CC86Muted,
                    )
                    Text(
                        "বাংলা, 中文, 한국어 and Français can be added later from Manage Languages.",
                        fontSize = 8.sp,
                        color = CC86Muted,
                    )
                    Text(
                        "Speech stays on your phone. Language packs download only when you choose to add them.",
                        fontSize = 8.sp,
                        color = CC86Muted,
                    )
                    if (!GlobalZipformerAutoCaptionEngineV86.supportedOnThisDevice()) {
                        Text("Auto CC is not available for this device ABI.", fontSize = 9.sp, color = Color(0xFFFFB4AB))
                    }
                    if (operation == AutoCcOperationV86.DOWNLOAD || progress > 0f) {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                        )
                        Text(status, fontSize = 8.sp, color = if (busy) CC86Accent else CC86Muted)
                    }
                }
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(CC86Panel, RoundedCornerShape(10.dp))
                        .padding(10.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Text(
                        "On-device Auto CC · English default · ${installedLanguages.size} language pack${if (installedLanguages.size == 1) "" else "s"} installed",
                        fontSize = 9.sp,
                        color = Color.White.copy(alpha = .78f),
                    )
                    Text(
                        "Long captions wrap inside a fixed safe width and stay centered at the same position.",
                        fontSize = 8.sp,
                        color = CC86Muted,
                    )

                    Text("Speech source", fontSize = 8.sp, color = CC86Muted)
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
                                    label = {
                                        Text(
                                            if (track.id == selectedTrackId) "✓ ${track.name}" else track.name,
                                            fontSize = 8.sp,
                                        )
                                    },
                                )
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Speech language", modifier = Modifier.weight(1f), fontSize = 8.sp, color = CC86Muted)
                        TextButton(onClick = { if (!busy) manageLanguages = !manageLanguages }) {
                            Text(if (manageLanguages) "Done" else "Manage Languages", fontSize = 8.sp)
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        generationLanguages.forEach { item ->
                            if (item == language) {
                                Button(
                                    onClick = { rememberLanguage(item) },
                                    enabled = !busy,
                                    modifier = Modifier.height(34.dp),
                                ) { Text(item.label, fontSize = 8.sp) }
                            } else {
                                OutlinedButton(
                                    onClick = { rememberLanguage(item) },
                                    enabled = !busy,
                                    modifier = Modifier.height(34.dp),
                                ) { Text(item.label, fontSize = 8.sp) }
                            }
                        }
                    }
                    Text(language.detail, fontSize = 8.sp, color = CC86Muted)

                    if (manageLanguages) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(Color.White.copy(alpha = .035f), RoundedCornerShape(8.dp))
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Text("Language packs", fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                            languagePackChoices.forEach { item ->
                                val installed = item in installedLanguages
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            if (item == CC86_DEFAULT_LANGUAGE) "${item.label} · Default" else item.label,
                                            fontSize = 9.sp,
                                        )
                                        Text(
                                            when {
                                                item == CC86_DEFAULT_LANGUAGE && installed -> "Installed · Base Auto CC language"
                                                installed -> "Installed · ${item.detail}"
                                                else -> "About ${packManager.approximateDownloadMb(item)} MB · ${item.detail}"
                                            },
                                            fontSize = 7.sp,
                                            color = CC86Muted,
                                        )
                                    }
                                    when {
                                        item == CC86_DEFAULT_LANGUAGE && installed -> {
                                            Text("Installed", fontSize = 8.sp, color = CC86Accent)
                                        }
                                        installed -> {
                                            TextButton(
                                                onClick = { deleteLanguage(item) },
                                                enabled = !busy,
                                            ) { Text("Delete", fontSize = 8.sp, color = Color(0xFFFF7474)) }
                                        }
                                        else -> {
                                            OutlinedButton(
                                                onClick = { startDownload(item) },
                                                enabled = !busy,
                                                modifier = Modifier.height(32.dp),
                                            ) { Text("Download", fontSize = 8.sp) }
                                        }
                                    }
                                }
                            }
                            if (autoLanguageAvailableV86(installedLanguages)) {
                                Text(
                                    "Auto · বাংলা + English becomes available automatically when both packs are installed.",
                                    fontSize = 7.sp,
                                    color = CC86Muted,
                                )
                            }
                        }
                    }

                    Text("Mode", fontSize = 8.sp, color = CC86Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        AutoCaptionQualityV77.entries.forEach { item ->
                            if (item == quality) {
                                Button(
                                    onClick = { quality = item },
                                    enabled = !busy,
                                    modifier = Modifier.height(34.dp),
                                ) { Text(item.label, fontSize = 8.sp) }
                            } else {
                                OutlinedButton(
                                    onClick = { quality = item },
                                    enabled = !busy,
                                    modifier = Modifier.height(34.dp),
                                ) { Text(item.label, fontSize = 8.sp) }
                            }
                        }
                    }

                    if (operation != AutoCcOperationV86.IDLE || progress > 0f) {
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
                            color = if (busy) CC86Accent else CC86Muted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        lastBackend?.let { backend -> Text(backend, fontSize = 7.sp, color = CC86Accent) }
                    }

                    if (generatedCaptions.isNotEmpty() && !busy) {
                        Text("Edit generated captions", fontSize = 8.sp, color = CC86Muted)
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
                                            label = "edit-auto-caption-v86",
                                            project = state.project.updateAutoCaptionTextV84(selectedCaption.id, editText),
                                            status = "Caption updated",
                                        )
                                        status = "Caption updated"
                                    },
                                ) { Text("Apply edit", fontSize = 8.sp) }
                                TextButton(
                                    onClick = {
                                        vm.commitProjectV19(
                                            label = "delete-auto-caption-v86",
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
                                        label = "clear-auto-caption-v86",
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
                                            label = "delete-cc-track-v86",
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
                operation == AutoCcOperationV86.DOWNLOAD -> {
                    Button(onClick = ::cancelActive) {
                        Text(downloadTarget?.let { "Cancel ${it.label} Download" } ?: "Cancel Download")
                    }
                }
                operation == AutoCcOperationV86.GENERATE -> {
                    Button(onClick = ::cancelActive) { Text("Cancel Auto CC") }
                }
                firstInstall -> {
                    Button(
                        onClick = { startDownload(CC86_DEFAULT_LANGUAGE) },
                        enabled = GlobalZipformerAutoCaptionEngineV86.supportedOnThisDevice(),
                    ) { Text("Download English") }
                }
                else -> {
                    Button(
                        onClick = ::startGenerate,
                        enabled = selectedTrackId != null &&
                            packManager.isInstalled(language) &&
                            GlobalZipformerAutoCaptionEngineV86.supportedOnThisDevice(),
                    ) { Text("Generate Auto CC") }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") }
        },
    )
}
