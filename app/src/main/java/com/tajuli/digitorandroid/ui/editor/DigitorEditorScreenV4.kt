package com.tajuli.digitorandroid.ui.editor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.US_PER_SECOND
import com.tajuli.digitorandroid.editor.processing.ExportProgress
import com.tajuli.digitorandroid.editor.processing.ProcessingRouter
import com.tajuli.digitorandroid.editor.render.SharedColorPipeline
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

private val E4Shell = Color(0xFF08080A)
private val E4Muted = Color(0xFF909098)
private val E4Accent = Color(0xFF30E0C3)

private enum class WorkspaceV4(val label: String, val icon: ImageVector) {
    EDIT("Edit", Icons.Rounded.ContentCut),
    AUDIO("Audio", Icons.Rounded.Audiotrack),
    MEDIA("Media", Icons.Rounded.VideoLibrary),
    CORRECTION("Correction", Icons.Rounded.Tune),
    COLOR("Color", Icons.Rounded.Palette),
    EFFECTS("Effects", Icons.Rounded.AutoAwesome),
    NODES("Nodes", Icons.Rounded.AccountTree),
}

@UnstableApi
@Composable
fun DigitorEditorScreenV4(vm: EditorViewModelV4 = viewModel()) {
    val state by vm.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val router = remember { ProcessingRouter(context.applicationContext) }
    val selectedClip = state.project.clip(state.selectedClipId)
    val player = remember { ExoPlayer.Builder(context).build() }

    var workspace by remember { mutableStateOf(WorkspaceV4.EDIT) }
    var isPlaying by remember { mutableStateOf(false) }
    var cursorUs by remember { mutableStateOf(0L) }
    var pendingSeekUs by remember { mutableStateOf<Long?>(null) }
    var previewAnchorClipId by remember { mutableStateOf<String?>(null) }
    var previewAnchorTimelineStartUs by remember { mutableStateOf<Long?>(null) }
    var loadedPreviewClipId by remember { mutableStateOf<String?>(null) }
    var loadedPreviewUri by remember { mutableStateOf<String?>(null) }
    var loadedPreviewSourceInUs by remember { mutableStateOf<Long?>(null) }
    var loadedPreviewSourceOutUs by remember { mutableStateOf<Long?>(null) }
    var loadedPreviewGradeHash by remember { mutableStateOf<Int?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var exportName by remember { mutableStateOf("Digitor_${System.currentTimeMillis()}") }
    var exportFraction by remember { mutableStateOf<Float?>(null) }
    var exportStatus by remember { mutableStateOf<String?>(null) }

    // Preview follows the timeline cursor, not clip selection. Selection is only the editing target.
    val previewClip = state.project.tracks
        .filter { it.kind == TrackKind.VIDEO && !it.muted }
        .asReversed()
        .asSequence()
        .flatMap { it.clips.asSequence() }
        .firstOrNull { cursorUs in it.timelineStartUs until it.timelineEndUs }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        vm.importUris(uris)
    }
    fun launchImport() = mediaPicker.launch(vm.selectedImportMimeTypes())

    fun startExport(destination: Uri) {
        if (state.project.durationUs <= 0L) {
            exportStatus = "Timeline is empty"
            return
        }
        scope.launch {
            exportFraction = 0f
            exportStatus = "Preparing GPU export"
            val temp = File(context.cacheDir, "digitor_export_${System.currentTimeMillis()}.mp4")
            runCatching {
                val result = router.export(state.project, temp) { progress ->
                    if (progress is ExportProgress.Stage) {
                        exportStatus = progress.name
                        progress.fraction?.let { exportFraction = it.coerceIn(0f, 1f) }
                    }
                }
                exportStatus = "Saving file…"
                exportFraction = max(exportFraction ?: 0f, .99f)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(destination, "w")?.use { output ->
                        temp.inputStream().use { input -> input.copyTo(output, 1024 * 1024) }
                    } ?: error("Could not open selected save location")
                }
                result
            }.onSuccess { result ->
                exportFraction = 1f
                exportStatus = "Saved · ${result.backend}"
                temp.delete()
            }.onFailure { error ->
                exportFraction = null
                exportStatus = error.message ?: "Export failed"
                temp.delete()
            }
        }
    }

    val saveDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri: Uri? ->
        if (uri != null) startExport(uri)
    }

    // Dynamic setVideoEffects() calls are unreliable on some Media3/device combinations. Instead,
    // load effects before prepare(). Source/cursor clip changes are immediate; grading changes are
    // debounced so rapid wheel/slider edits collapse into one safe preview rebuild.
    LaunchedEffect(
        previewClip?.id,
        previewClip?.uri,
        previewClip?.sourceInUs,
        previewClip?.sourceOutUs,
        previewClip?.nodeGraph,
    ) {
        val clip = previewClip
        if (clip == null) {
            player.stop()
            player.clearMediaItems()
            player.setVideoEffects(emptyList())
            loadedPreviewClipId = null
            loadedPreviewUri = null
            loadedPreviewSourceInUs = null
            loadedPreviewSourceOutUs = null
            loadedPreviewGradeHash = null
            pendingSeekUs = null
            return@LaunchedEffect
        }

        val sourceChanged = loadedPreviewClipId != clip.id ||
            loadedPreviewUri != clip.uri ||
            loadedPreviewSourceInUs != clip.sourceInUs ||
            loadedPreviewSourceOutUs != clip.sourceOutUs
        val gradeHash = clip.nodeGraph.hashCode()
        val gradeChanged = loadedPreviewGradeHash != gradeHash

        if (!sourceChanged && gradeChanged) {
            delay(320)
        }

        val requestedTimelineUs = pendingSeekUs ?: cursorUs
        val maxLocalUs = (clip.durationUs - 1L).coerceAtLeast(0L)
        val localUs = if (sourceChanged) {
            (requestedTimelineUs - clip.timelineStartUs).coerceIn(0L, maxLocalUs)
        } else {
            (player.currentPosition.coerceAtLeast(0L) * 1000L).coerceIn(0L, maxLocalUs)
        }
        val resumePlayback = player.isPlaying
        val effects = withContext(Dispatchers.Default) {
            SharedColorPipeline.previewEffectsFor(clip)
        }

        player.stop()
        player.clearMediaItems()
        player.setVideoEffects(effects)
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(clip.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.sourceInUs / 1000L)
                        .setEndPositionMs(clip.sourceOutUs / 1000L)
                        .build(),
                )
                .build(),
        )
        player.prepare()
        player.seekTo(localUs / 1000L)
        if (resumePlayback) player.play()

        loadedPreviewClipId = clip.id
        loadedPreviewUri = clip.uri
        loadedPreviewSourceInUs = clip.sourceInUs
        loadedPreviewSourceOutUs = clip.sourceOutUs
        loadedPreviewGradeHash = gradeHash
        pendingSeekUs = null
    }

    // A timeline move changes only timelineStartUs. If the cursor was on the moving selected clip,
    // preserve the same local source frame and move the cursor by the clip's actual movement.
    LaunchedEffect(selectedClip?.id, selectedClip?.timelineStartUs) {
        val clip = selectedClip
        if (clip == null) {
            previewAnchorClipId = null
            previewAnchorTimelineStartUs = null
            return@LaunchedEffect
        }
        val previousId = previewAnchorClipId
        val previousStart = previewAnchorTimelineStartUs
        if (previousId == clip.id && previousStart != null && previousStart != clip.timelineStartUs) {
            val previousEnd = previousStart + clip.durationUs
            if (cursorUs in previousStart until previousEnd) {
                val maxLocalUs = (clip.durationUs - 1L).coerceAtLeast(0L)
                val localUs = (cursorUs - previousStart).coerceIn(0L, maxLocalUs)
                cursorUs = (clip.timelineStartUs + localUs)
                    .coerceIn(0L, state.project.durationUs.coerceAtLeast(0L))
                pendingSeekUs = cursorUs
                if (loadedPreviewClipId == clip.id) {
                    player.seekTo(localUs / 1000L)
                    pendingSeekUs = null
                }
            }
        }
        previewAnchorClipId = clip.id
        previewAnchorTimelineStartUs = clip.timelineStartUs
    }

    LaunchedEffect(player, previewClip?.id) {
        while (true) {
            isPlaying = player.isPlaying
            if (previewClip != null && player.isPlaying) {
                cursorUs = (previewClip.timelineStartUs + player.currentPosition.coerceAtLeast(0L) * 1000L)
                    .coerceIn(0L, state.project.durationUs.coerceAtLeast(0L))
            }
            delay(70)
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    fun seekTimeline(requestUs: Long) {
        val target = requestUs.coerceIn(0L, state.project.durationUs.coerceAtLeast(0L))
        cursorUs = target
        val activeVideo = state.project.tracks
            .filter { it.kind == TrackKind.VIDEO && !it.muted }
            .asReversed()
            .asSequence()
            .flatMap { it.clips.asSequence() }
            .firstOrNull { target in it.timelineStartUs until it.timelineEndUs }
        if (activeVideo != null) {
            pendingSeekUs = target
            // Keep the editing target aligned with the frame the user explicitly seeks to.
            if (activeVideo.id != selectedClip?.id) {
                vm.selectClip(activeVideo.id)
            }
            if (activeVideo.id == loadedPreviewClipId) {
                player.seekTo(((target - activeVideo.timelineStartUs).coerceIn(0L, activeVideo.durationUs)) / 1000L)
                pendingSeekUs = null
            }
        } else {
            pendingSeekUs = null
        }
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Export video") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = exportName,
                        onValueChange = { exportName = it },
                        label = { Text("File name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("File type", fontSize = 10.sp, color = E4Muted)
                    AssistChip(onClick = {}, label = { Text("MP4 · H.264 / AAC") })
                    Text("Choose location on the next screen.", fontSize = 9.sp, color = E4Muted)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val base = exportName.trim().ifEmpty { "Digitor_export" }.removeSuffix(".mp4")
                    showExportDialog = false
                    saveDocument.launch("$base.mp4")
                }) {
                    Icon(Icons.Rounded.Save, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Choose location")
                }
            },
            dismissButton = { TextButton(onClick = { showExportDialog = false }) { Text("Cancel") } },
        )
    }

    Surface(Modifier.fillMaxSize(), color = E4Shell) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            TopBarV4(
                title = selectedClip?.label ?: previewClip?.label ?: "New project",
                status = exportStatus ?: state.status,
                exportFraction = exportFraction,
                onImport = ::launchImport,
                onExport = { showExportDialog = true },
            )
            if (exportFraction != null && exportFraction!! < 1f) {
                Column {
                    LinearProgressIndicator(
                        progress = { exportFraction!!.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                    )
                    Text(
                        "${((exportFraction ?: 0f) * 100).roundToInt()}%  ${exportStatus.orEmpty()}",
                        fontSize = 9.sp,
                        color = E4Muted,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            PreviewV4(
                clip = previewClip,
                player = player,
                onImport = ::launchImport,
                qualifierPickerActive = state.qualifierPickerActive,
                onPickColor = { x, y, width, height ->
                    vm.pickQualifierFromPreview(cursorUs, x, y, width, height)
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            TransportV4(
                enabled = previewClip != null,
                isPlaying = isPlaying,
                cursorUs = cursorUs,
                durationUs = state.project.durationUs,
                onBack = { seekTimeline(cursorUs - 10 * US_PER_SECOND) },
                onPlayPause = { if (player.isPlaying) player.pause() else player.play() },
                onForward = { seekTimeline(cursorUs + 10 * US_PER_SECOND) },
            )

            Box(Modifier.fillMaxWidth().height(290.dp)) {
                when (workspace) {
                    WorkspaceV4.COLOR -> ColorWorkspaceV4(selectedClip, vm, Modifier.fillMaxSize())
                    WorkspaceV4.NODES -> NodeGraphV4(selectedClip, vm, Modifier.fillMaxSize())
                    WorkspaceV4.CORRECTION -> CorrectionWorkspaceV4(selectedClip, vm, Modifier.fillMaxSize())
                    WorkspaceV4.EFFECTS -> EffectsWorkspaceV4(selectedClip, vm, Modifier.fillMaxSize())
                    else -> TimelineEditorV4(
                        project = state.project,
                        selectedTrackId = state.selectedTrackId,
                        selectedClipIds = state.selectedClipIds,
                        cursorUs = cursorUs,
                        onSeek = ::seekTimeline,
                        onSelectTrack = vm::selectTrack,
                        onSelectClip = vm::selectClip,
                        onMoveClip = vm::moveClip,
                        onMoveClipToTrack = vm::moveClipToTrack,
                        onAddVideoTrack = { vm.addTrack(TrackKind.VIDEO) },
                        onAddAudioTrack = { vm.addTrack(TrackKind.AUDIO) },
                        onSplit = { vm.splitSelectedAt(cursorUs) },
                        onDelete = vm::deleteSelected,
                        onUnlink = vm::unlinkSelected,
                        onImport = ::launchImport,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            WorkspaceBarV4(
                selected = workspace,
                onSelected = {
                    workspace = it
                    val editsClip = it == WorkspaceV4.COLOR ||
                        it == WorkspaceV4.CORRECTION ||
                        it == WorkspaceV4.NODES ||
                        it == WorkspaceV4.EFFECTS
                    if (editsClip && previewClip != null && previewClip.id != selectedClip?.id) {
                        vm.selectClip(previewClip.id)
                    }
                    if (it != WorkspaceV4.COLOR && state.qualifierPickerActive) {
                        vm.setQualifierPickerActive(false)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(66.dp),
            )
        }
    }
}

@Composable
private fun TopBarV4(
    title: String,
    status: String,
    exportFraction: Float?,
    onImport: () -> Unit,
    onExport: () -> Unit,
) {
    val exporting = exportFraction != null && exportFraction < 1f
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onImport, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Rounded.Add, "Import")
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(E4Accent))
                Spacer(Modifier.width(5.dp))
                Text(
                    if (status == "Ready") "GPU-first · Preview = Export LUT" else status,
                    fontSize = 9.sp,
                    color = Color.White.copy(alpha = .55f),
                    maxLines = 1,
                )
            }
        }
        Button(
            onClick = onExport,
            enabled = !exporting,
            modifier = Modifier.height(34.dp),
            shape = RoundedCornerShape(7.dp),
        ) {
            Icon(Icons.Rounded.Share, null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
            Text(if (exporting) "${((exportFraction ?: 0f) * 100).roundToInt()}%" else "Export", fontSize = 11.sp)
        }
    }
}

@Composable
private fun PreviewV4(
    clip: TimelineClip?,
    player: ExoPlayer,
    onImport: () -> Unit,
    qualifierPickerActive: Boolean,
    onPickColor: (Float, Float, Float, Float) -> Unit,
    modifier: Modifier,
) {
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier.background(Color(0xFF030304)).onSizeChanged { previewSize = it },
        contentAlignment = Alignment.Center,
    ) {
        if (clip == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Rounded.AddPhotoAlternate,
                    null,
                    tint = Color.White.copy(alpha = .35f),
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text("No media at cursor", color = Color.White.copy(alpha = .55f), fontSize = 12.sp)
                TextButton(onClick = onImport) { Text("Import media") }
            }
        } else {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        setKeepContentOnPlayerReset(true)
                        this.player = player
                    }
                },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            "Cursor Preview",
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
                .background(Color.Black.copy(alpha = .6f), RoundedCornerShape(5.dp))
                .padding(horizontal = 7.dp, vertical = 4.dp),
            fontSize = 9.sp,
            color = Color.White.copy(alpha = .72f),
        )

        if (qualifierPickerActive && clip != null) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = .05f))
                    .pointerInput(previewSize) {
                        detectTapGestures { pos ->
                            onPickColor(
                                pos.x,
                                pos.y,
                                previewSize.width.toFloat(),
                                previewSize.height.toFloat(),
                            )
                        }
                    },
                contentAlignment = Alignment.TopCenter,
            ) {
                Row(
                    Modifier.padding(top = 9.dp)
                        .background(Color.Black.copy(alpha = .76f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 9.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Colorize, null, modifier = Modifier.size(15.dp), tint = E4Accent)
                    Spacer(Modifier.width(5.dp))
                    Text("Tap the color to qualify", fontSize = 9.sp, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun TransportV4(
    enabled: Boolean,
    isPlaying: Boolean,
    cursorUs: Long,
    durationUs: Long,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onForward: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(42.dp).background(Color(0xFF0D0D11)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(timeV4(cursorUs), color = E4Muted, fontSize = 9.sp, modifier = Modifier.width(66.dp))
        IconButton(onClick = onBack, enabled = enabled, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Rounded.Replay10, null, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onPlayPause, enabled = enabled, modifier = Modifier.size(38.dp)) {
            Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, modifier = Modifier.size(23.dp))
        }
        IconButton(onClick = onForward, enabled = enabled, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Rounded.Forward10, null, modifier = Modifier.size(18.dp))
        }
        Text(timeV4(durationUs), color = E4Muted, fontSize = 9.sp, modifier = Modifier.width(66.dp))
    }
}

@Composable
private fun WorkspaceBarV4(
    selected: WorkspaceV4,
    onSelected: (WorkspaceV4) -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier.background(Color(0xFF0A0A0D)).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        WorkspaceV4.entries.forEach { item ->
            val active = item == selected
            Column(
                Modifier.width(68.dp).fillMaxHeight().clickable { onSelected(item) }
                    .background(if (active) E4Accent.copy(alpha = .10f) else Color.Transparent),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    item.icon,
                    item.label,
                    modifier = Modifier.size(18.dp),
                    tint = if (active) E4Accent else Color.White.copy(alpha = .55f),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    item.label,
                    fontSize = 8.sp,
                    color = if (active) E4Accent else Color.White.copy(alpha = .55f),
                )
            }
        }
    }
}

private fun timeV4(us: Long): String {
    val totalSeconds = us.coerceAtLeast(0L) / US_PER_SECOND
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%02d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}
