package com.tajuli.digitorandroid.ui.editor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.US_PER_SECOND
import com.tajuli.digitorandroid.editor.model.activeTextOverlaysAt
import com.tajuli.digitorandroid.editor.model.activeVisualOverlaysAtV19
import com.tajuli.digitorandroid.editor.model.hasPlayableMedia
import com.tajuli.digitorandroid.editor.model.hasPlayableVideo
import com.tajuli.digitorandroid.editor.model.resolvedVisualOverlaysV19
import com.tajuli.digitorandroid.editor.model.topmostVideoClipAt
import com.tajuli.digitorandroid.editor.preview.DavinciFramePreviewEngine
import com.tajuli.digitorandroid.editor.preview.MultitrackAudioPreviewEngine
import com.tajuli.digitorandroid.editor.processing.ExportFrameRateV72
import com.tajuli.digitorandroid.editor.processing.ExportQuality
import com.tajuli.digitorandroid.editor.processing.ExportResolutionV72
import com.tajuli.digitorandroid.editor.processing.ExportSettingsV72
import com.tajuli.digitorandroid.editor.processing.ProcessingRouter
import com.tajuli.digitorandroid.editor.render.Media3CompositionBuilder
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val EditorShell = Color(0xFF08080A)
private val EditorMuted = Color(0xFF909098)

private fun TimelineProject.activeVideoClips(timelineUs: Long): List<TimelineClip> =
    tracks.filter { it.kind == TrackKind.VIDEO && !it.muted }
        .mapNotNull { track ->
            track.clips.firstOrNull { clip ->
                timelineUs in clip.timelineStartUs until clip.timelineEndUs
            }
        }

@UnstableApi
@Composable
internal fun EditorWorkspaceScreen(
    vm: EditorViewModel = viewModel(),
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
        onDispose {
            previewEngine.close()
            audioPreview.close()
        }
    }

    LaunchedEffect(Unit) {
        vm.migrateLegacyImageOverlaysV21()
    }

    val selectedClip = state.project.clip(state.selectedClipId)
    var workspace by remember { mutableStateOf(EditorWorkspaceTab.EDIT) }
    val playback = rememberEditorPlaybackController(state.project.durationUs)
    val isPlaying = playback.isPlaying
    val cursorUs = playback.cursorUs
    val previewStatus = playback.previewStatus
    var showExportDialog by remember { mutableStateOf(false) }
    var exportName by remember { mutableStateOf("Digitor_${System.currentTimeMillis()}") }
    var exportQuality by remember { mutableStateOf(ExportQuality.HIGH) }
    var exportResolution by remember { mutableStateOf(ExportResolutionV72.ORIGINAL) }
    var exportFrameRate by remember { mutableStateOf(ExportFrameRateV72.ORIGINAL) }
    var exportFraction by remember { mutableStateOf<Float?>(null) }
    var exportStatus by remember { mutableStateOf<String?>(null) }

    val previewClip = state.project.topmostVideoClipAt(cursorUs)
    val activeVideoClips = state.project.activeVideoClips(cursorUs)
    val activeText = state.project.activeTextOverlaysAt(cursorUs)
    val activeVisual = state.project.activeVisualOverlaysAtV19(cursorUs)
    val hasVisual = state.project.resolvedVisualOverlaysV19().isNotEmpty()
    val hasMedia = state.project.hasPlayableMedia() || hasVisual
    val hasVideo = state.project.hasPlayableVideo()
    val hasAudio = state.project.tracks.any {
        it.kind == TrackKind.AUDIO && !it.muted && it.clips.isNotEmpty()
    }
    val audioPreviewKey = state.project.tracks.filter { it.kind == TrackKind.AUDIO }.hashCode()

    val mediaPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            playback.isPlaying = false
            runCatching { audioPreview.pause() }
            vm.importUrisAppendAwareV12(uris)
        }
    }
    fun launchImport() = mediaPicker.launch(vm.selectedImportMimeTypesV21())

    EditorPlaybackEffects(
        project = state.project,
        selectedClip = selectedClip,
        previewClip = previewClip,
        hasVideo = hasVideo,
        hasAudio = hasAudio,
        audioPreviewKey = audioPreviewKey,
        previewEngine = previewEngine,
        previewFrame = previewFrame,
        audioPreview = audioPreview,
        audioPreviewReady = audioPreviewReady,
        audioPreviewError = audioPreviewState.error,
        controller = playback,
    )

    fun seekTimeline(requestUs: Long) {
        val target = playback.seek(
            requestUs = requestUs,
            project = state.project,
            hasAudio = hasAudio,
            audioPreviewReady = audioPreviewReady,
            audioPreview = audioPreview,
        )
        val activeVideo = state.project.topmostVideoClipAt(target)
        val textSelectedNow = vm.state.value.selectedTextId != null ||
            TimelineTextSelectionBusV10.selectedTextId.value != null
        val visualSelectedNow = VisualOverlaySelectionBusV19.selectedId.value != null
        if (
            activeVideo != null &&
            selectedClip == null &&
            !textSelectedNow &&
            !visualSelectedNow &&
            workspace != EditorWorkspaceTab.TEXT &&
            workspace != EditorWorkspaceTab.OVERLAY
        ) {
            vm.selectClip(activeVideo.id)
        }
    }

    fun stopForEdit() {
        playback.stopForEdit(audioPreview)
    }

    fun togglePlayback() {
        playback.togglePlayback(
            hasMedia = hasMedia,
            durationUs = state.project.durationUs,
            hasAudio = hasAudio,
            audioPreviewReady = audioPreviewReady,
            audioPreview = audioPreview,
            onSeek = ::seekTimeline,
        )
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
                onPreviewStatus = { playback.previewStatus = it },
            )
        }
    }

    val saveDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4"),
    ) { uri: Uri? ->
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
    val selectedVisual = state.project.resolvedVisualOverlaysV19()
        .firstOrNull { it.id == VisualOverlaySelectionBusV19.selectedId.value }

    Surface(Modifier.fillMaxSize(), color = EditorShell) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            EditorTopBar(
                title = selectedClip?.label
                    ?: selectedVisual?.label
                    ?: state.project.textOverlays.firstOrNull { it.id == state.selectedTextId }?.text
                    ?: previewClip?.label
                    ?: "New project",
                status = exportStatus ?: state.busyOperation ?: previewStatus ?: state.status,
                exportFraction = currentExportFraction,
                canUndo = state.canUndo,
                canRedo = state.canRedo,
                onUndo = {
                    stopForEdit()
                    vm.undo()
                },
                onRedo = {
                    stopForEdit()
                    vm.redo()
                },
                onSaveProject = vm::saveProject,
                onLoadProject = {
                    stopForEdit()
                    vm.loadProject()
                },
                onHome = {
                    stopForEdit()
                    onHome()
                },
                onImport = ::launchImport,
                onExport = { showExportDialog = true },
            )

            EditorProjectActionsBar(
                canUndo = state.canUndo,
                canRedo = state.canRedo,
                exporting = exportingNow,
                onUndo = {
                    stopForEdit()
                    vm.undo()
                },
                onRedo = {
                    stopForEdit()
                    vm.redo()
                },
                onSaveProject = vm::saveProject,
                onLoadProject = {
                    stopForEdit()
                    vm.loadProject()
                },
            )

            if (exportingNow) {
                val stableProgress = currentExportFraction ?: 0f
                Column {
                    LinearProgressIndicator(
                        progress = { stableProgress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                    )
                    Text(
                        "${(stableProgress * 100).roundToInt()}%  ${exportStatus.orEmpty()}",
                        fontSize = 9.sp,
                        color = EditorMuted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            EditorFramePreview(
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
                    val target = selectedClip?.takeIf { clip ->
                        state.project.trackContaining(clip.id)?.kind == TrackKind.VIDEO &&
                            cursorUs in clip.timelineStartUs until clip.timelineEndUs
                    } ?: previewClip
                    target?.let { clip ->
                        if (clip.id != selectedClip?.id) vm.selectClip(clip.id)
                        applyQualifierPickedColor(vm, red, green, blue)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            EditorTransportControls(
                enabled = hasMedia,
                isPlaying = isPlaying,
                cursorUs = cursorUs,
                durationUs = state.project.durationUs,
                onBack = { seekTimeline(cursorUs - 10 * US_PER_SECOND) },
                onPlayPause = ::togglePlayback,
                onForward = { seekTimeline(cursorUs + 10 * US_PER_SECOND) },
            )

            EditorWorkspaceContent(
                selected = workspace,
                state = state,
                selectedClip = selectedClip,
                cursorUs = cursorUs,
                vm = vm,
                onSeek = ::seekTimeline,
                onImport = ::launchImport,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(290.dp),
            )

            EditorWorkspaceNavigationBar(
                selected = workspace,
                onSelected = { next ->
                    workspace = next
                    applyEditorWorkspaceSelection(
                        next = next,
                        state = state,
                        selectedClip = selectedClip,
                        previewClip = previewClip,
                        cursorUs = cursorUs,
                        vm = vm,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(66.dp),
            )
        }
    }
}
