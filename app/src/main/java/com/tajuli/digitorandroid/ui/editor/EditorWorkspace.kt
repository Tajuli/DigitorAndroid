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
import androidx.compose.material.icons.rounded.Home
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
import com.tajuli.digitorandroid.editor.model.VisualOverlayClipV19
import com.tajuli.digitorandroid.editor.model.activeTextOverlaysAt
import com.tajuli.digitorandroid.editor.model.activeVisualOverlaysAtV19
import com.tajuli.digitorandroid.editor.model.hasPlayableMedia
import com.tajuli.digitorandroid.editor.model.hasPlayableVideo
import com.tajuli.digitorandroid.editor.model.resolvedVisualOverlaysV19
import com.tajuli.digitorandroid.editor.model.topmostVideoClipAt
import com.tajuli.digitorandroid.editor.preview.DavinciFramePreviewEngine
import com.tajuli.digitorandroid.editor.preview.GpuPreviewSurface
import com.tajuli.digitorandroid.editor.preview.MultitrackAudioPreviewEngine
import com.tajuli.digitorandroid.editor.processing.ExportFrameRateV72
import com.tajuli.digitorandroid.editor.processing.ExportProgress
import com.tajuli.digitorandroid.editor.processing.ExportQuality
import com.tajuli.digitorandroid.editor.processing.ExportResolutionV72
import com.tajuli.digitorandroid.editor.processing.ExportSettingsV72
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
    FILTERS("Filters", Icons.Rounded.Palette),
    COLOR("Color", Icons.Rounded.Palette),
    TEXT("Text", Icons.Rounded.TextFields),
    OVERLAY("Overlay", Icons.Rounded.AddPhotoAlternate),
    AUDIO("Audio", Icons.Rounded.Audiotrack),
    MEDIA("Media", Icons.Rounded.VideoLibrary),
    NODES("Nodes", Icons.Rounded.AccountTree),
}

private fun TimelineProject.activeVideoClipsV7(timelineUs: Long): List<TimelineClip> =
    tracks.filter { it.kind == TrackKind.VIDEO && !it.muted }
        .mapNotNull { track -> track.clips.firstOrNull { clip -> timelineUs in clip.timelineStartUs until clip.timelineEndUs } }

@UnstableApi
@Composable
fun DigitorEditorScreenV7(
    vm: EditorViewModelV4 = viewModel(),
    onHome: () -> Unit = {},
) {
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

    LaunchedEffect(Unit) {
        vm.migrateLegacyImageOverlaysV21()
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
    var exportResolution by remember { mutableStateOf(ExportResolutionV72.ORIGINAL) }
    var exportFrameRate by remember { mutableStateOf(ExportFrameRateV72.ORIGINAL) }
    var exportFraction by remember { mutableStateOf<Float?>(null) }
    var exportStatus by remember { mutableStateOf<String?>(null) }

    val previewClip = state.project.topmostVideoClipAt(cursorUs)
    val activeVideoClips = state.project.activeVideoClipsV7(cursorUs)
    val activeText = state.project.activeTextOverlaysAt(cursorUs)
    val activeVisual = state.project.activeVisualOverlaysAtV19(cursorUs)
    val hasVisual = state.project.resolvedVisualOverlaysV19().isNotEmpty()
    val hasMedia = state.project.hasPlayableMedia() || hasVisual
    val hasVideo = state.project.hasPlayableVideo()
    val hasAudio = state.project.tracks.any { it.kind == TrackKind.AUDIO && !it.muted && it.clips.isNotEmpty() }
    val audioPreviewKey = state.project.tracks.filter { it.kind == TrackKind.AUDIO }.hashCode()

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            isPlaying = false
            runCatching { audioPreview.pause() }
            vm.importUrisAppendAwareV12(uris)
        }
    }
    fun launchImport() = mediaPicker.launch(vm.selectedImportMimeTypesV21())

    LaunchedEffect(state.project, cursorUs, hasVideo, isPlaying) {
        if (hasVideo) previewEngine.submit(state.project, cursorUs, isPlaying)
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
        val textSelectedNow = vm.state.value.selectedTextId != null || TimelineTextSelectionBusV10.selectedTextId.value != null
        val visualSelectedNow = VisualOverlaySelectionBusV19.selectedId.value != null
        if (
            activeVideo != null && selectedClip == null && !textSelectedNow && !visualSelectedNow &&
            workspace != WorkspaceV7.TEXT && workspace != WorkspaceV7.OVERLAY
        ) vm.selectClip(activeVideo.id)
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
        val exportProject = state.project
        val exportCursorUs = cursorUs
        val settings = ExportSettingsV72(
            quality = exportQuality,
            resolution = exportResolution,
            frameRate = exportFrameRate,
        )
        scope.launch {
            stopForEdit()
            runEditorExport(
                context = context,
                router = router,
                audioPreview = audioPreview,
                project = exportProject,
                cursorUs = exportCursorUs,
                destination = destination,
                settings = settings,
                onProgress = { fraction, status ->
                    exportFraction = fraction
                    exportStatus = status
                },
                onPreviewStatus = { previewStatus = it },
            )
        }
    }

    val saveDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri: Uri? ->
        if (uri != null) startExport(uri)
    }

    if (showExportDialog) {
        EditorExportDialog(
            project = state.project,
            name = exportName,
            quality = exportQuality,
            resolution = exportResolution,
            frameRate = exportFrameRate,
            onNameChange = { exportName = it },
            onQualityChange = { exportQuality = it },
            onResolutionChange = { exportResolution = it },
            onFrameRateChange = { exportFrameRate = it },
            onConfirm = { base ->
                showExportDialog = false
                saveDocument.launch("$base.mp4")
            },
            onDismiss = { showExportDialog = false },
        )
    }

    val currentExportFraction = exportFraction
    val exportingNow = currentExportFraction != null && currentExportFraction < 1f
    val selectedVisual = state.project.resolvedVisualOverlaysV19().firstOrNull { it.id == VisualOverlaySelectionBusV19.selectedId.value }

    Surface(Modifier.fillMaxSize(), color = E7Shell) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            TopBarV7(
                title = selectedClip?.label
                    ?: selectedVisual?.label
                    ?: state.project.textOverlays.firstOrNull { it.id == state.selectedTextId }?.text
                    ?: previewClip?.label
                    ?: "New project",
                status = exportStatus ?: state.busyOperation ?: previewStatus ?: state.status,
                exportFraction = currentExportFraction,
                canUndo = state.canUndo,
                canRedo = state.canRedo,
                onUndo = { stopForEdit(); vm.undo() },
                onRedo = { stopForEdit(); vm.redo() },
                onSaveProject = vm::saveProject,
                onLoadProject = { stopForEdit(); vm.loadProject() },
                onHome = { stopForEdit(); onHome() },
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
                project = state.project,
                previewEngine = previewEngine,
                frame = previewFrame,
                hasVideo = hasVideo,
                activeVideoClip = previewClip,
                activeLayerCount = activeVideoClips.size,
                visualOverlays = activeVisual,
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
                    WorkspaceV7.FILTERS -> CreatorFiltersWorkspaceV27(selectedClip, vm, Modifier.fillMaxSize())
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
                    WorkspaceV7.OVERLAY -> VisualOverlayWorkspaceV19(
                        project = state.project,
                        cursorUs = cursorUs,
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
                    when (next) {
                        WorkspaceV7.TEXT -> {
                            VisualOverlaySelectionBusV19.clear()
                            val textTarget = state.project.activeTextOverlaysAt(cursorUs).lastOrNull()
                                ?: state.project.textOverlays.lastOrNull()
                            if (textTarget != null) vm.selectTextOverlay(textTarget.id)
                        }
                        WorkspaceV7.OVERLAY -> {
                            TimelineTextSelectionBusV10.clear()
                            val overlayTarget = state.project.activeVisualOverlaysAtV19(cursorUs)
                                .filter { it.kind != com.tajuli.digitorandroid.editor.model.VisualOverlayKindV19.IMAGE }
                                .lastOrNull()
                                ?: state.project.resolvedVisualOverlaysV19()
                                    .filter { it.kind != com.tajuli.digitorandroid.editor.model.VisualOverlayKindV19.IMAGE }
                                    .lastOrNull()
                            if (overlayTarget != null) vm.selectVisualOverlayV19(overlayTarget.id)
                        }
                        else -> {
                            val clipWorkspace = next == WorkspaceV7.EDIT || next == WorkspaceV7.CORRECTION ||
                                next == WorkspaceV7.EFFECTS || next == WorkspaceV7.FILTERS || next == WorkspaceV7.COLOR || next == WorkspaceV7.NODES ||
                                next == WorkspaceV7.MEDIA
                            val selectedIsActiveVideo = selectedClip?.let { clip ->
                                state.project.trackContaining(clip.id)?.kind == TrackKind.VIDEO && cursorUs in clip.timelineStartUs until clip.timelineEndUs
                            } == true
                            if (clipWorkspace && !selectedIsActiveVideo && previewClip != null) vm.selectClip(previewClip.id)
                        }
                    }
                    if (next != WorkspaceV7.COLOR && state.qualifierPickerActive) vm.setQualifierPickerActive(false)
                },
                modifier = Modifier.fillMaxWidth().height(66.dp),
            )
        }
    }
}

@Composable
private fun WorkspaceBarV7(selected: WorkspaceV7, onSelected: (WorkspaceV7) -> Unit, modifier: Modifier) {
    Row(modifier.background(Color(0xFF0A0A0D)).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        WorkspaceV7.entries.filterNot { it == WorkspaceV7.MEDIA }.forEach { item ->
            val active = item == selected
            Column(Modifier.width(68.dp).fillMaxHeight().clickable { onSelected(item) }.background(if (active) E7Accent.copy(alpha = .10f) else Color.Transparent), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(item.icon, item.label, modifier = Modifier.size(18.dp), tint = if (active) E7Accent else Color.White.copy(alpha = .55f)); Spacer(Modifier.height(3.dp))
                Text(item.label, fontSize = 8.sp, color = if (active) E7Accent else Color.White.copy(alpha = .55f))
            }
        }
    }
}