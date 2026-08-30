package com.tajuli.digitorandroid.ui.editor

import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Redo
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.tajuli.digitorandroid.editor.model.PreviewTransformClock
import com.tajuli.digitorandroid.editor.model.TextOverlayClip
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.US_PER_SECOND
import com.tajuli.digitorandroid.editor.model.activeTextOverlaysAt
import com.tajuli.digitorandroid.editor.model.hasPlayableMedia
import com.tajuli.digitorandroid.editor.model.hasPlayableVideo
import com.tajuli.digitorandroid.editor.model.topmostVideoClipAt
import com.tajuli.digitorandroid.editor.preview.DavinciFramePreviewEngine
import com.tajuli.digitorandroid.editor.preview.GpuPreviewSurface
import com.tajuli.digitorandroid.editor.preview.MultitrackAudioPreviewEngine
import com.tajuli.digitorandroid.editor.processing.ExportProgress
import com.tajuli.digitorandroid.editor.processing.ExportQuality
import com.tajuli.digitorandroid.editor.processing.ProcessingRouter
import com.tajuli.digitorandroid.editor.render.Media3CompositionBuilder
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

private val E7Shell = Color(0xFF08080A)
private val E7Muted = Color(0xFF909098)
private val E7Accent = Color(0xFF30E0C3)
private val E7PreviewPasteboard = Color(0xFF222226)

/** First five entries intentionally match the primary mobile workflow order. */
private enum class WorkspaceV7(val label: String, val icon: ImageVector) {
    EDIT("Edit", Icons.Rounded.ContentCut),
    CORRECTION("Correction", Icons.Rounded.Tune),
    EFFECTS("Effects", Icons.Rounded.AutoAwesome),
    COLOR("Color", Icons.Rounded.Palette),
    TEXT("Text", Icons.Rounded.TextFields),
    AUDIO("Audio", Icons.Rounded.Audiotrack),
    MEDIA("Media", Icons.Rounded.VideoLibrary),
    NODES("Nodes", Icons.Rounded.AccountTree),
}

private fun TimelineProject.activeVideoClipsV7(timelineUs: Long): List<TimelineClip> =
    tracks.filter { it.kind == TrackKind.VIDEO && !it.muted }
        .mapNotNull { track -> track.clips.firstOrNull { clip -> timelineUs in clip.timelineStartUs until clip.timelineEndUs } }

@UnstableApi
@Composable
fun DigitorEditorScreenV7(vm: EditorViewModelV4 = viewModel()) {
    val state by vm.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val router = remember { ProcessingRouter(appContext) }
    val compositionBuilder = remember { Media3CompositionBuilder() }
    val previewEngine = remember { DavinciFramePreviewEngine(appContext, maxPreviewLongEdge = 720) }
    val previewFrame by previewEngine.frame.collectAsState()
    val audioPreview = remember { MultitrackAudioPreviewEngine(appContext, compositionBuilder) }
    val audioPreviewState by audioPreview.state.collectAsState()
    val audioPreviewReady = audioPreviewState.ready

    DisposableEffect(previewEngine, audioPreview) {
        onDispose { previewEngine.close(); audioPreview.close() }
    }

    val selectedClip = state.project.clip(state.selectedClipId)
    var workspace by remember { mutableStateOf(WorkspaceV7.EDIT) }
    var isPlaying by remember { mutableStateOf(false) }
    var cursorUs by remember { mutableStateOf(0L) }
    var previousProjectDurationUs by remember { mutableStateOf(state.project.durationUs) }
    var playAnchorCursorUs by remember { mutableStateOf(0L) }
    var playAnchorRealtimeMs by remember { mutableStateOf(0L) }
    var previewStatus by remember { mutableStateOf<String?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var exportName by remember { mutableStateOf("Digitor_${System.currentTimeMillis()}") }
    var exportQuality by remember { mutableStateOf(ExportQuality.HIGH) }
    var exportFraction by remember { mutableStateOf<Float?>(null) }
    var exportStatus by remember { mutableStateOf<String?>(null) }

    val previewClip = state.project.topmostVideoClipAt(cursorUs)
    val activeVideoClips = state.project.activeVideoClipsV7(cursorUs)
    val activeText = state.project.activeTextOverlaysAt(cursorUs)
    val hasMedia = state.project.hasPlayableMedia()
    val hasVideo = state.project.hasPlayableVideo()
    val hasAudio = state.project.tracks.any { it.kind == TrackKind.AUDIO && !it.muted && it.clips.isNotEmpty() }
    val audioPreviewKey = state.project.tracks.filter { it.kind == TrackKind.AUDIO }.hashCode()

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            isPlaying = false
            runCatching { audioPreview.pause() }
            vm.importUris(uris)
        }
    }
    fun launchImport() = mediaPicker.launch(vm.selectedImportMimeTypes())

    LaunchedEffect(state.project, cursorUs, hasVideo) {
        if (hasVideo) previewEngine.submit(state.project, cursorUs)
    }

    LaunchedEffect(previewFrame?.timelineUs, previewFrame?.renderTimeMs) {
        val frame = previewFrame
        previewStatus = when {
            !hasVideo -> null
            frame == null -> "Preview: GPU preparing…"
            frame.bitmap != null -> "Preview: CPU fallback · ${frame.renderTimeMs}ms"
            else -> "GPU ${timeV7(frame.timelineUs)} · ${frame.activeLayerCount}L"
        }
    }

    LaunchedEffect(audioPreviewState.error) {
        audioPreviewState.error?.let { previewStatus = "Audio preview: $it" }
    }

    LaunchedEffect(state.project.durationUs) {
        val durationUs = state.project.durationUs.coerceAtLeast(0L)
        if (durationUs < previousProjectDurationUs && cursorUs >= durationUs) {
            cursorUs = if (durationUs > 0L) durationUs - 1L else 0L
        }
        previousProjectDurationUs = durationUs
    }

    LaunchedEffect(audioPreviewKey, hasAudio) {
        if (!hasAudio) { audioPreview.clear(); return@LaunchedEffect }
        val snapshot = state.project
        val resume = isPlaying
        delay(100)
        try {
            val maxStartUs = (snapshot.durationUs - 1L).coerceAtLeast(0L)
            audioPreview.rebuild(snapshot, cursorUs.coerceIn(0L, maxStartUs) / 1000L, resume)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            previewStatus = "Audio preview: ${error.message ?: "unavailable"}"
        }
    }

    LaunchedEffect(isPlaying, audioPreviewReady, hasAudio) {
        while (isPlaying) {
            val durationUs = state.project.durationUs.coerceAtLeast(0L)
            val nextUs = if (hasAudio && audioPreviewReady) {
                audioPreview.syncFollowers()
                audioPreview.currentPositionMs().coerceAtLeast(0L) * 1000L
            } else {
                playAnchorCursorUs + (SystemClock.elapsedRealtime() - playAnchorRealtimeMs) * 1000L
            }.coerceIn(0L, durationUs)
            cursorUs = nextUs
            if (durationUs > 0L && nextUs >= durationUs) {
                runCatching { audioPreview.pause() }; isPlaying = false; break
            }
            delay(33)
        }
    }

    LaunchedEffect(cursorUs, selectedClip?.id, previewClip?.id) {
        val clockClip = selectedClip?.takeIf { clip ->
            state.project.trackContaining(clip.id)?.kind == TrackKind.VIDEO && cursorUs in clip.timelineStartUs until clip.timelineEndUs
        } ?: previewClip
        if (clockClip == null) PreviewTransformClock.clear() else PreviewTransformClock.update(clockClip, cursorUs)
    }

    fun seekTimeline(requestUs: Long) {
        val target = requestUs.coerceIn(0L, state.project.durationUs.coerceAtLeast(0L))
        cursorUs = target
        if (hasAudio && audioPreviewReady) runCatching { audioPreview.seekTo(target / 1000L) }
        if (isPlaying && !(hasAudio && audioPreviewReady)) {
            playAnchorCursorUs = target; playAnchorRealtimeMs = SystemClock.elapsedRealtime()
        }
        val activeVideo = state.project.topmostVideoClipAt(target)
        if (activeVideo != null && selectedClip == null && workspace != WorkspaceV7.TEXT) vm.selectClip(activeVideo.id)
    }

    fun stopForEdit() {
        isPlaying = false
        runCatching { audioPreview.pause() }
    }

    fun togglePlayback() {
        if (!hasMedia) return
        if (isPlaying) {
            stopForEdit()
        } else {
            if (cursorUs >= state.project.durationUs && state.project.durationUs > 0L) seekTimeline(0L)
            playAnchorCursorUs = cursorUs; playAnchorRealtimeMs = SystemClock.elapsedRealtime()
            if (hasAudio && audioPreviewReady) runCatching { audioPreview.play() }
            isPlaying = true
        }
    }

    fun startExport(destination: Uri) {
        if (state.project.durationUs <= 0L) { exportStatus = "Timeline is empty"; return }
        val exportProject = state.project
        val exportCursorUs = cursorUs
        val exportQualitySnapshot = exportQuality
        val exportHasAudio = exportProject.tracks.any { it.kind == TrackKind.AUDIO && !it.muted && it.clips.isNotEmpty() }
        scope.launch {
            stopForEdit()
            // Pausing is not enough on low-memory devices: CompositionPlayer keeps its decoder and
            // audio graph alive. Release those preview resources before Transformer opens export AV.
            runCatching { audioPreview.suspendForExternalWork() }
            exportFraction = 0f
            exportStatus = "Preparing ${exportQualitySnapshot.label} export"
            val temp = File(context.cacheDir, "digitor_export_${System.currentTimeMillis()}.mp4")
            try {
                val result = router.export(exportProject, temp, exportQualitySnapshot) { progress ->
                    if (progress is ExportProgress.Stage) {
                        exportStatus = progress.name
                        progress.fraction?.let { exportFraction = it.coerceIn(0f, 1f) }
                    }
                }
                exportStatus = "Saving file…"
                exportFraction = max(exportFraction ?: 0f, .99f)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(destination, "w")?.use { output ->
                        temp.inputStream().use { it.copyTo(output, 1024 * 1024) }
                    } ?: error("Could not open selected save location")
                }
                exportFraction = 1f
                exportStatus = "Saved · ${result.backend} · ${exportQualitySnapshot.label}"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                exportFraction = null
                exportStatus = error.message ?: "Export failed"
            } finally {
                temp.delete()
                if (exportHasAudio) {
                    try {
                        val maxStartUs = (exportProject.durationUs - 1L).coerceAtLeast(0L)
                        audioPreview.rebuild(
                            exportProject,
                            exportCursorUs.coerceIn(0L, maxStartUs) / 1000L,
                            resumePlayback = false,
                        )
                    } catch (_: CancellationException) {
                        // Screen is leaving; no preview rebuild is needed.
                    } catch (error: Throwable) {
                        previewStatus = "Audio preview: ${error.message ?: "unavailable"}"
                    }
                }
            }
        }
    }

    val saveDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri: Uri? -> if (uri != null) startExport(uri) }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Export video") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = exportName, onValueChange = { exportName = it }, label = { Text("File name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Quality", fontSize = 10.sp, color = E7Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ExportQuality.entries.forEach { quality ->
                            AssistChip(
                                onClick = { exportQuality = quality },
                                label = { Text(if (exportQuality == quality) "✓ ${quality.label}" else quality.label, fontSize = 9.sp) },
                            )
                        }
                    }
                    val targetMbps = exportQuality.videoBitrate(state.project.width, state.project.height, state.project.frameRate) / 1_000_000f
                    Text("${exportQuality.label} · %.1f Mbps H.264 target".format(targetMbps), fontSize = 9.sp, color = E7Muted)
                    Text("File type", fontSize = 10.sp, color = E7Muted)
                    AssistChip(onClick = {}, label = { Text("MP4 · H.264 / AAC") })
                    Text("Quality changes encoder bitrate; canvas resolution and frame rate stay unchanged.", fontSize = 9.sp, color = E7Muted)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val base = exportName.trim().ifEmpty { "Digitor_export" }.removeSuffix(".mp4")
                    showExportDialog = false; saveDocument.launch("$base.mp4")
                }) { Icon(Icons.Rounded.Save, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("Choose location") }
            },
            dismissButton = { TextButton(onClick = { showExportDialog = false }) { Text("Cancel") } },
        )
    }

    val currentExportFraction = exportFraction
    val exportingNow = currentExportFraction != null && currentExportFraction < 1f

    Surface(Modifier.fillMaxSize(), color = E7Shell) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            TopBarV7(
                title = selectedClip?.label ?: state.project.textOverlays.firstOrNull { it.id == state.selectedTextId }?.text ?: previewClip?.label ?: "New project",
                status = exportStatus ?: state.busyOperation ?: previewStatus ?: state.status,
                exportFraction = currentExportFraction,
                canUndo = state.canUndo,
                canRedo = state.canRedo,
                onUndo = { stopForEdit(); vm.undo() },
                onRedo = { stopForEdit(); vm.redo() },
                onSaveProject = vm::saveProject,
                onLoadProject = { stopForEdit(); vm.loadProject() },
                onImport = ::launchImport,
                onExport = { showExportDialog = true },
            )
            ProjectActionsBarV7(
                canUndo = state.canUndo,
                canRedo = state.canRedo,
                exporting = exportingNow,
                onUndo = { stopForEdit(); vm.undo() },
                onRedo = { stopForEdit(); vm.redo() },
                onSaveProject = vm::saveProject,
                onLoadProject = { stopForEdit(); vm.loadProject() },
            )
            if (exportingNow) {
                val stableProgress = currentExportFraction ?: 0f
                Column {
                    LinearProgressIndicator(progress = { stableProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp))
                    Text("${(stableProgress * 100).roundToInt()}%  ${exportStatus.orEmpty()}", fontSize = 9.sp, color = E7Muted, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }

            FramePreviewV7(
                previewEngine = previewEngine,
                frame = previewFrame,
                hasVideo = hasVideo,
                activeVideoClip = previewClip,
                activeLayerCount = activeVideoClips.size,
                textOverlays = activeText,
                timelineUs = cursorUs,
                onImport = ::launchImport,
                qualifierPickerActive = state.qualifierPickerActive,
                onPickColor = { red, green, blue ->
                    val target = selectedClip?.takeIf { clip -> state.project.trackContaining(clip.id)?.kind == TrackKind.VIDEO && cursorUs in clip.timelineStartUs until clip.timelineEndUs } ?: previewClip
                    target?.let { clip ->
                        if (clip.id != selectedClip?.id) vm.selectClip(clip.id)
                        applyQualifierPickedColor(vm, red, green, blue)
                    }
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            TransportV7(enabled = hasMedia, isPlaying = isPlaying, cursorUs = cursorUs, durationUs = state.project.durationUs, onBack = { seekTimeline(cursorUs - 10 * US_PER_SECOND) }, onPlayPause = ::togglePlayback, onForward = { seekTimeline(cursorUs + 10 * US_PER_SECOND) })

            Box(Modifier.fillMaxWidth().height(290.dp)) {
                when (workspace) {
                    WorkspaceV7.EDIT -> EditWorkspaceV5(
                        project = state.project, selectedTrackId = state.selectedTrackId, selectedClipIds = state.selectedClipIds, selectedClip = selectedClip,
                        cursorUs = cursorUs, vm = vm, onSeek = ::seekTimeline, onSelectTrack = vm::selectTrack, onSelectClip = vm::selectClip,
                        onMoveClip = vm::moveClip, onMoveClipToTrack = vm::moveClipToTrack, onAddVideoTrack = { vm.addTrack(TrackKind.VIDEO) },
                        onAddAudioTrack = { vm.addTrack(TrackKind.AUDIO) }, onSplit = { vm.splitSelectedAt(cursorUs) }, onDelete = vm::deleteSelected,
                        onUnlink = vm::unlinkSelected, onImport = ::launchImport, modifier = Modifier.fillMaxSize(),
                    )
                    WorkspaceV7.CORRECTION -> KeyframedCorrectionWorkspaceV5(selectedClip, state.project.frameRate, vm, Modifier.fillMaxSize())
                    WorkspaceV7.EFFECTS -> KeyframedEffectsWorkspaceV5(selectedClip, state.project.frameRate, vm, Modifier.fillMaxSize())
                    WorkspaceV7.COLOR -> KeyframedColorWorkspaceV5(selectedClip, state.project.frameRate, vm, Modifier.fillMaxSize())
                    WorkspaceV7.TEXT -> TextWorkspaceV9(
                        project = state.project,
                        selectedTextId = state.selectedTextId,
                        cursorUs = cursorUs,
                        frameRate = state.project.frameRate,
                        vm = vm,
                        onSeek = ::seekTimeline,
                        modifier = Modifier.fillMaxSize(),
                    )
                    WorkspaceV7.AUDIO -> CreatorAudioWorkspaceV8(state.project, state.selectedClipId, state.selectedClipIds, vm, Modifier.fillMaxSize())
                    WorkspaceV7.MEDIA -> CreatorMediaWorkspaceV8(state.project, selectedClip, state.selectedTextId, cursorUs, state.busyOperation, vm, Modifier.fillMaxSize())
                    WorkspaceV7.NODES -> NodeGraphV4(selectedClip, vm, Modifier.fillMaxSize())
                }
            }
            WorkspaceBarV7(
                selected = workspace,
                onSelected = { next ->
                    workspace = next
                    if (next == WorkspaceV7.TEXT) {
                        val textTarget = state.project.activeTextOverlaysAt(cursorUs).lastOrNull()
                            ?: state.project.textOverlays.lastOrNull()
                        if (textTarget != null) vm.selectTextOverlay(textTarget.id)
                    } else {
                        val clipWorkspace = next == WorkspaceV7.EDIT || next == WorkspaceV7.CORRECTION ||
                            next == WorkspaceV7.EFFECTS || next == WorkspaceV7.COLOR || next == WorkspaceV7.NODES ||
                            next == WorkspaceV7.MEDIA
                        val selectedIsActiveVideo = selectedClip?.let { clip ->
                            state.project.trackContaining(clip.id)?.kind == TrackKind.VIDEO && cursorUs in clip.timelineStartUs until clip.timelineEndUs
                        } == true
                        if (clipWorkspace && !selectedIsActiveVideo && previewClip != null) vm.selectClip(previewClip.id)
                    }
                    if (next != WorkspaceV7.COLOR && state.qualifierPickerActive) vm.setQualifierPickerActive(false)
                },
                modifier = Modifier.fillMaxWidth().height(66.dp),
            )
        }
    }
}

@Composable
private fun TopBarV7(
    title: String,
    status: String,
    exportFraction: Float?,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSaveProject: () -> Unit,
    onLoadProject: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
) {
    val exporting = exportFraction != null && exportFraction < 1f
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onImport, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.Add, "Import", modifier = Modifier.size(18.dp)) }
        IconButton(onClick = onUndo, enabled = canUndo && !exporting, modifier = Modifier.size(30.dp)) { Icon(Icons.Rounded.Undo, "Undo", modifier = Modifier.size(16.dp)) }
        IconButton(onClick = onRedo, enabled = canRedo && !exporting, modifier = Modifier.size(30.dp)) { Icon(Icons.Rounded.Redo, "Redo", modifier = Modifier.size(16.dp)) }
        IconButton(onClick = onSaveProject, enabled = !exporting, modifier = Modifier.size(30.dp)) { Icon(Icons.Rounded.Save, "Save project", modifier = Modifier.size(16.dp)) }
        IconButton(onClick = onLoadProject, enabled = !exporting, modifier = Modifier.size(30.dp)) { Icon(Icons.Rounded.FolderOpen, "Load project", modifier = Modifier.size(16.dp)) }
        Column(Modifier.weight(1f).padding(start = 3.dp)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(E7Accent)); Spacer(Modifier.width(4.dp))
                Text(status, fontSize = 8.sp, color = Color.White.copy(alpha = .55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Button(onClick = onExport, enabled = !exporting, modifier = Modifier.height(32.dp), shape = RoundedCornerShape(7.dp)) {
            Icon(Icons.Rounded.Share, null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp))
            Text(if (exporting) "${((exportFraction ?: 0f) * 100).roundToInt()}%" else "Export", fontSize = 10.sp)
        }
    }
}

@Composable
private fun ProjectActionsBarV7(
    canUndo: Boolean,
    canRedo: Boolean,
    exporting: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSaveProject: () -> Unit,
    onLoadProject: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(34.dp).background(Color(0xFF0D0D11))
            .horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        TextButton(
            onClick = onUndo,
            enabled = canUndo && !exporting,
            modifier = Modifier.height(30.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) {
            Icon(Icons.Rounded.Undo, null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("Undo", fontSize = 9.sp)
        }
        TextButton(
            onClick = onRedo,
            enabled = canRedo && !exporting,
            modifier = Modifier.height(30.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) {
            Icon(Icons.Rounded.Redo, null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("Redo", fontSize = 9.sp)
        }
        TextButton(
            onClick = onSaveProject,
            enabled = !exporting,
            modifier = Modifier.height(30.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) {
            Icon(Icons.Rounded.Save, null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("Save Project", fontSize = 9.sp)
        }
        TextButton(
            onClick = onLoadProject,
            enabled = !exporting,
            modifier = Modifier.height(30.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) {
            Icon(Icons.Rounded.FolderOpen, null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("Load Project", fontSize = 9.sp)
        }
    }
}

@Composable
private fun FramePreviewV7(
    previewEngine: DavinciFramePreviewEngine,
    frame: DavinciFramePreviewEngine.Frame?,
    hasVideo: Boolean,
    activeVideoClip: TimelineClip?,
    activeLayerCount: Int,
    textOverlays: List<TextOverlayClip>,
    timelineUs: Long,
    onImport: () -> Unit,
    qualifierPickerActive: Boolean,
    onPickColor: (Float, Float, Float) -> Unit,
    modifier: Modifier,
) {
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier.background(E7PreviewPasteboard).onSizeChanged { previewSize = it }, contentAlignment = Alignment.Center) {
        if (hasVideo) {
            GpuPreviewSurface(
                engine = previewEngine,
                qualifierPickerActive = qualifierPickerActive && activeVideoClip != null,
                onQualifierColorSample = onPickColor,
                modifier = Modifier.fillMaxSize(),
            )
            val fallbackBitmap = frame?.bitmap
            if (fallbackBitmap != null && !fallbackBitmap.isRecycled) {
                Image(bitmap = fallbackBitmap.asImageBitmap(), contentDescription = "CPU fallback preview", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else if (frame == null) {
                Text("Preparing GPU preview…", color = Color.White.copy(alpha = .55f), fontSize = 11.sp)
            }
            if (activeVideoClip == null) {
                Text("No video at cursor · audio can continue", color = Color.White.copy(alpha = .55f), fontSize = 11.sp, modifier = Modifier.background(Color.Black.copy(alpha = .62f), RoundedCornerShape(5.dp)).padding(horizontal = 9.dp, vertical = 6.dp))
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.AddPhotoAlternate, null, tint = Color.White.copy(alpha = .35f), modifier = Modifier.size(40.dp)); Spacer(Modifier.height(8.dp))
                Text("No video media", color = Color.White.copy(alpha = .55f), fontSize = 12.sp); TextButton(onClick = onImport) { Text("Import media") }
            }
        }

        textOverlays.forEach { overlay ->
            TextOverlayPreviewV2(
                overlay = overlay,
                timelineUs = timelineUs,
                previewSize = previewSize,
            )
        }

        Text("GPU Preview · $activeLayerCount ${if (activeLayerCount == 1) "layer" else "layers"}", modifier = Modifier.align(Alignment.TopStart).padding(10.dp).background(Color.Black.copy(alpha = .6f), RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 4.dp), fontSize = 9.sp, color = Color.White.copy(alpha = .72f))

        if (qualifierPickerActive && activeVideoClip != null) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .05f)), contentAlignment = Alignment.TopCenter) {
                Row(Modifier.padding(top = 9.dp).background(Color.Black.copy(alpha = .76f), RoundedCornerShape(6.dp)).padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Colorize, null, modifier = Modifier.size(15.dp), tint = E7Accent); Spacer(Modifier.width(5.dp)); Text("Tap the color to qualify selected layer", fontSize = 9.sp, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun TransportV7(enabled: Boolean, isPlaying: Boolean, cursorUs: Long, durationUs: Long, onBack: () -> Unit, onPlayPause: () -> Unit, onForward: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(42.dp).background(Color(0xFF0D0D11)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Text(timeV7(cursorUs), color = E7Muted, fontSize = 9.sp, modifier = Modifier.width(66.dp))
        IconButton(onClick = onBack, enabled = enabled, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.Replay10, null, modifier = Modifier.size(18.dp), tint = Color.White) }
        IconButton(onClick = onPlayPause, enabled = enabled, modifier = Modifier.size(38.dp)) { Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, modifier = Modifier.size(23.dp), tint = Color.White) }
        IconButton(onClick = onForward, enabled = enabled, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.Forward10, null, modifier = Modifier.size(18.dp), tint = Color.White) }
        Text(timeV7(durationUs), color = E7Muted, fontSize = 9.sp, modifier = Modifier.width(66.dp))
    }
}

@Composable
private fun WorkspaceBarV7(selected: WorkspaceV7, onSelected: (WorkspaceV7) -> Unit, modifier: Modifier) {
    Row(modifier.background(Color(0xFF0A0A0D)).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        WorkspaceV7.entries.forEach { item ->
            val active = item == selected
            Column(Modifier.width(68.dp).fillMaxHeight().clickable { onSelected(item) }.background(if (active) E7Accent.copy(alpha = .10f) else Color.Transparent), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(item.icon, item.label, modifier = Modifier.size(18.dp), tint = if (active) E7Accent else Color.White.copy(alpha = .55f)); Spacer(Modifier.height(3.dp))
                Text(item.label, fontSize = 8.sp, color = if (active) E7Accent else Color.White.copy(alpha = .55f))
            }
        }
    }
}

private fun timeV7(us: Long): String {
    val totalSeconds = us.coerceAtLeast(0L) / US_PER_SECOND
    val hours = totalSeconds / 3600; val minutes = (totalSeconds % 3600) / 60; val seconds = totalSeconds % 60
    return if (hours > 0) "%02d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
}
