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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
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
import com.tajuli.digitorandroid.editor.processing.ZipformerAutoCaptionEngineV79
import com.tajuli.digitorandroid.editor.processing.autoCaptionCountV77
import com.tajuli.digitorandroid.editor.processing.clearAutoCaptionsV77
import com.tajuli.digitorandroid.editor.processing.withAutoCaptionsV77
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CC77Accent = Color(0xFF30E0C3)
private val CC77Panel = Color(0xFF121217)
private val CC77Muted = Color(0xFF9898A1)

/**
 * Small launcher above the existing workspace rail. Generated clips remain normal TextOverlayClip
 * items, so creators can immediately switch to Text and edit words, style, position or keyframes.
 */
@Composable
fun AutoCaptionLauncherV77(
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

    if (open) {
        AutoCaptionDialogV77(vm = vm, onDismiss = { open = false })
    }
}

@Composable
private fun AutoCaptionDialogV77(
    vm: EditorViewModelV4,
    onDismiss: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val engine = remember(context) { ZipformerAutoCaptionEngineV79(context) }
    val audioTracks = state.project.tracks.filter { it.kind == TrackKind.AUDIO && !it.muted && it.clips.isNotEmpty() }
    var selectedTrackId by remember { mutableStateOf(audioTracks.firstOrNull { it.name == "A1" }?.id ?: audioTracks.firstOrNull()?.id) }
    var language by remember { mutableStateOf(AutoCaptionLanguageV79.BANGLA) }
    var quality by remember { mutableStateOf(AutoCaptionQualityV77.ACCURATE) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("Ready") }
    var lastBackend by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(audioTracks, selectedTrackId) {
        if (selectedTrackId == null || audioTracks.none { it.id == selectedTrackId }) {
            selectedTrackId = audioTracks.firstOrNull { it.name == "A1" }?.id ?: audioTracks.firstOrNull()?.id
        }
    }

    fun startGenerate() {
        val trackId = selectedTrackId ?: return
        if (running) return
        running = true
        progress = 0f
        status = "Starting Auto CC…"
        lastBackend = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    engine.generate(
                        project = state.project,
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
                val nextProject = state.project.withAutoCaptionsV77(result.captions)
                vm.commitProjectV19(
                    label = "auto-caption-v79",
                    project = nextProject,
                    status = "Auto CC · ${result.captions.size} captions · ${result.backend}",
                )
                progress = 1f
                status = "${result.captions.size} captions ready · ${result.model}"
                lastBackend = result.backend
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
                Modifier.fillMaxWidth().background(CC77Panel, RoundedCornerShape(10.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text(
                    "On-device Zipformer captions. Audio stays on your phone; first use downloads only the selected speech model, then it runs from local cache.",
                    fontSize = 9.sp,
                    color = Color.White.copy(alpha = .78f),
                )
                Text(
                    "বাংলা-first · English available · token timestamps · captions remain editable in Text",
                    fontSize = 8.sp,
                    color = CC77Muted,
                )

                if (!ZipformerAutoCaptionEngineV79.supportedOnThisDevice()) {
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

                Text("Language", fontSize = 8.sp, color = CC77Muted)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    AutoCaptionLanguageV79.entries.forEach { item ->
                        if (item == language) {
                            Button(onClick = { language = item }, enabled = !running, modifier = Modifier.height(34.dp)) {
                                Text(item.label, fontSize = 8.sp)
                            }
                        } else {
                            OutlinedButton(onClick = { language = item }, enabled = !running, modifier = Modifier.height(34.dp)) {
                                Text(item.label, fontSize = 8.sp)
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
                Text(quality.detail, fontSize = 8.sp, color = CC77Muted)

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

                val existing = state.project.autoCaptionCountV77()
                if (existing > 0 && !running) {
                    TextButton(
                        onClick = {
                            vm.commitProjectV19(
                                label = "clear-auto-caption-v79",
                                project = state.project.clearAutoCaptionsV77(),
                                status = "Auto captions cleared",
                            )
                            status = "Auto captions cleared"
                            progress = 0f
                        },
                    ) {
                        Text("Clear $existing generated captions", fontSize = 8.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = ::startGenerate,
                enabled = !running && selectedTrackId != null && ZipformerAutoCaptionEngineV79.supportedOnThisDevice(),
            ) {
                Text(if (running) "Generating…" else "Generate Auto CC")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !running) { Text("Close") }
        },
    )
}
