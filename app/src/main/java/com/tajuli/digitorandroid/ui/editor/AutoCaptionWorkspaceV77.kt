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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CC77Accent = Color(0xFF30E0C3)
private val CC77Panel = Color(0xFF121217)
private val CC77Muted = Color(0xFF9898A1)
private val CC84Danger = Color(0xFFFF7474)
private const val CC84_PREFS = "auto_cc_v84"
private const val CC84_LANGUAGE_KEY = "speech_language"

@Composable
fun AutoCaptionLauncherV77(
    vm: EditorViewModel,
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

    if (open) AutoCaptionDialogV77(vm = vm, onDismiss = { open = false })
}

@Composable
private fun AutoCaptionDialogV77(
    vm: EditorViewModel,
    onDismiss: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val engine = remember(context) { ZipformerAutoCaptionEngineV83(context) }
    val preferences = remember(context) {
        context.getSharedPreferences(CC84_PREFS, android.content.Context.MODE_PRIVATE)
    }
    val audioTracks = state.project.tracks.filter { it.kind == TrackKind.AUDIO && !it.muted && it.clips.isNotEmpty() }
    val generatedCaptions = state.project.autoCaptionOverlaysV84()
    val ccTrackId = state.project.autoCaptionTrackIdV84()

    var selectedTrackId by remember {
        mutableStateOf(audioTracks.firstOrNull { it.name == "A1" }?.id ?: audioTracks.firstOrNull()?.id)
    }
    var language by remember {
        val saved = preferences.getString(CC84_LANGUAGE_KEY, null)
        mutableStateOf(
            AutoCaptionLanguageV79.entries.firstOrNull { it.name == saved }
                ?: AutoCaptionLanguageV79.BANGLA,
        )
    }
    var quality by remember { mutableStateOf(AutoCaptionQualityV77.ACCURATE) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("Ready") }
    var lastBackend by remember { mutableStateOf<String?>(null) }
    var editingCaptionId by remember { mutableStateOf<String?>(null) }
    var editingText by remember { mutableStateOf("") }

    LaunchedEffect(audioTracks, selectedTrackId) {
        if (selectedTrackId == null || audioTracks.none { it.id == selectedTrackId }) {
            selectedTrackId = audioTracks.firstOrNull { it.name == "A1" }?.id ?: audioTracks.firstOrNull()?.id
        }
    }

    LaunchedEffect(generatedCaptions.map { it.id }) {
        val selected = editingCaptionId?.let { id -> generatedCaptions.firstOrNull { it.id == id } }
        if (selected == null) {
            val first = generatedCaptions.firstOrNull()
            editingCaptionId = first?.id
            editingText = first?.text.orEmpty()
        }
    }

    fun selectLanguage(item: AutoCaptionLanguageV79) {
        if (running) return
        language = item
        preferences.edit().putString(CC84_LANGUAGE_KEY, item.name).apply()
        status = "Language · ${item.label}"
    }

    fun startGenerate() {
        val trackId = selectedTrackId ?: return
        if (running) return
        running = true
        progress = 0f
        status = "Starting Auto CC · ${language.label}…"
        lastBackend = null
        scope.launch {
            runCatching {
                val sourceProject = vm.state.value.project
                withContext(Dispatchers.IO) {
                    engine.generate(
                        project = sourceProject,
                        audioTrackId = trackId,
                        quality = quality,
                        language = language,
                        onProgress = { update: AutoCaptionProgressV77 ->
                            scope.launch {
                                progress = update.fraction.coerceIn(0f, 1f)
                                status = update.message
                            }
                        },
                    )
                }
            }.onSuccess { result ->
                val nextProject = vm.state.value.project.withAutoCaptionsV77(result.captions)
                vm.commitProjectV19(
                    label = "auto-caption-v84",
                    project = nextProject,
                    status = "Auto CC · ${language.label} · ${result.captions.size} captions · ${result.backend}",
                )
                progress = 1f
                status = "${result.captions.size} captions ready · ${language.label}"
                lastBackend = result.backend
                editingCaptionId = null
                editingText = ""
            }.onFailure { error ->
                status = error.message ?: "Auto CC failed"
                vm.setEditorStatusV19(status)
            }
            running = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        icon = { Icon(Icons.Rounded.Subtitles, contentDescription = null, tint = CC77Accent) },
        title = { Text("Auto Captions", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(CC77Panel, RoundedCornerShape(10.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text(
                    "Zipformer-only on-device captions. Choose speech language before Generate; the choice is remembered.",
                    fontSize = 9.sp,
                    color = Color.White.copy(alpha = .78f),
                )
                Text(
                    "বাংলা + English · token timestamps · generated captions can be edited below or in Text",
                    fontSize = 8.sp,
                    color = CC77Muted,
                )

                if (!ZipformerAutoCaptionEngineV83.supportedOnThisDevice()) {
                    Text(
                        "Auto CC is not available for this device ABI. The rest of Digitor is unaffected.",
                        fontSize = 9.sp,
                        color = Color(0xFFFFB4AB),
                    )
                }

                Text("Speech source", fontSize = 8.sp, color = CC77Muted)
                if (audioTracks.isEmpty()) {
                    Text("Import a video with speech or add an audio clip first.", fontSize = 9.sp)
                } else {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        audioTracks.forEach { track ->
                            AssistChip(
                                onClick = { if (!running) selectedTrackId = track.id },
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

                Text("Speech language · select before Generate", fontSize = 8.sp, color = CC77Muted)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    AutoCaptionLanguageV79.entries.forEach { item ->
                        val label = when (item) {
                            AutoCaptionLanguageV79.BANGLA -> "বাংলা"
                            AutoCaptionLanguageV79.ENGLISH -> "English"
                            AutoCaptionLanguageV79.AUTO -> "Auto · বাংলা + English"
                        }
                        if (item == language) {
                            Button(onClick = { selectLanguage(item) }, enabled = !running, modifier = Modifier.height(34.dp)) {
                                Text("✓ $label", fontSize = 8.sp)
                            }
                        } else {
                            OutlinedButton(onClick = { selectLanguage(item) }, enabled = !running, modifier = Modifier.height(34.dp)) {
                                Text(label, fontSize = 8.sp)
                            }
                        }
                    }
                }
                Text(language.detail, fontSize = 8.sp, color = CC77Muted)

                Text("Mode", fontSize = 8.sp, color = CC77Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    AutoCaptionQualityV77.entries.forEach { item ->
                        if (item == quality) {
                            Button(onClick = { quality = item }, enabled = !running, modifier = Modifier.height(34.dp)) {
                                Text(item.label, fontSize = 8.sp)
                            }
                        } else {
                            OutlinedButton(onClick = { quality = item }, enabled = !running, modifier = Modifier.height(34.dp)) {
                                Text(item.label, fontSize = 8.sp)
                            }
                        }
                    }
                }
                Text(
                    if (quality == AutoCaptionQualityV77.ACCURATE) {
                        "Zipformer tuned · speech normalization · silence-aware chunks · 24-path beam search"
                    } else {
                        "Greedy Zipformer decode · fastest and lowest CPU use"
                    },
                    fontSize = 8.sp,
                    color = CC77Muted,
                )

                if (running || progress > 0f) {
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
                        color = if (running) CC77Accent else CC77Muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    lastBackend?.let { backend -> Text(backend, fontSize = 8.sp, color = CC77Accent) }
                }

                if (generatedCaptions.isNotEmpty() && !running) {
                    Text("Edit generated caption", fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        generatedCaptions.forEachIndexed { index, caption ->
                            AssistChip(
                                onClick = {
                                    editingCaptionId = caption.id
                                    editingText = caption.text
                                    TimelineTextSelectionBusV10.select(caption.id)
                                    vm.selectTextOverlay(caption.id)
                                },
                                label = {
                                    val selected = caption.id == editingCaptionId
                                    Text(
                                        "${if (selected) "✓ " else ""}#${index + 1} ${caption.text.take(18)}",
                                        fontSize = 7.sp,
                                        maxLines = 1,
                                    )
                                },
                            )
                        }
                    }

                    val editId = editingCaptionId
                    if (editId != null) {
                        OutlinedTextField(
                            value = editingText,
                            onValueChange = { editingText = it },
                            label = { Text("Caption text", fontSize = 8.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 4,
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(
                                onClick = {
                                    val cleaned = editingText.trim()
                                    if (cleaned.isBlank()) {
                                        status = "Caption text cannot be empty"
                                    } else {
                                        val next = vm.state.value.project.updateAutoCaptionTextV84(editId, cleaned)
                                        vm.commitProjectV19(
                                            label = "edit-auto-caption-v84",
                                            project = next,
                                            status = "Auto CC caption updated",
                                            coalesce = true,
                                        )
                                        TimelineTextSelectionBusV10.select(editId)
                                        vm.selectTextOverlay(editId)
                                        editingText = cleaned
                                        status = "Caption updated"
                                    }
                                },
                                enabled = editingText.isNotBlank(),
                                modifier = Modifier.height(34.dp),
                            ) { Text("Apply edit", fontSize = 8.sp) }
                            Spacer(Modifier.width(6.dp))
                            TextButton(
                                onClick = {
                                    val next = vm.state.value.project.deleteAutoCaptionTextV84(editId)
                                    TimelineTextSelectionBusV10.clear(editId)
                                    vm.commitProjectV19(
                                        label = "delete-auto-caption-v84",
                                        project = next,
                                        status = "Auto CC caption deleted",
                                    )
                                    editingCaptionId = null
                                    editingText = ""
                                    status = "Caption deleted"
                                },
                            ) { Text("Delete caption", fontSize = 8.sp, color = CC84Danger) }
                        }
                        Text(
                            "This is the same editable text item used by the Text workspace; style/position can still be changed there.",
                            fontSize = 7.sp,
                            color = CC77Muted,
                        )
                    }
                }

                val existing = state.project.autoCaptionCountV77()
                if (existing > 0 && !running) {
                    TextButton(
                        onClick = {
                            val next = vm.state.value.project.clearAutoCaptionsV77()
                            TimelineTextSelectionBusV10.clear()
                            vm.commitProjectV19(
                                label = "clear-auto-caption-v84",
                                project = next,
                                status = "Auto captions cleared",
                            )
                            vm.focusVisualOverlayV19(next.autoCaptionTrackIdV84())
                            editingCaptionId = null
                            editingText = ""
                            status = "Auto captions cleared"
                            progress = 0f
                        },
                    ) { Text("Clear $existing generated captions", fontSize = 8.sp) }
                }

                if (ccTrackId != null && !running) {
                    TextButton(
                        onClick = {
                            val next = vm.state.value.project.deleteAutoCaptionTrackV84()
                            TimelineTextSelectionBusV10.clear()
                            vm.commitProjectV19(
                                label = "delete-auto-caption-track-v84",
                                project = next,
                                status = "CC track deleted",
                            )
                            val fallbackTrackId = next.tracks.firstOrNull { it.kind == TrackKind.VIDEO }?.id
                                ?: next.tracks.firstOrNull()?.id
                            vm.focusVisualOverlayV19(fallbackTrackId)
                            editingCaptionId = null
                            editingText = ""
                            status = "CC track and captions deleted"
                            progress = 0f
                        },
                    ) { Text("Delete CC track", fontSize = 8.sp, color = CC84Danger) }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = ::startGenerate,
                enabled = !running && selectedTrackId != null && ZipformerAutoCaptionEngineV83.supportedOnThisDevice(),
            ) { Text(if (running) "Generating…" else "Generate Auto CC") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !running) { Text("Close") } },
    )
}
