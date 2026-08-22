package com.tajuli.digitorandroid.ui.editor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.LinkOff
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.RgbAdjustment
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.tajuli.digitorandroid.editor.model.ColorGrade
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.US_PER_SECOND
import com.tajuli.digitorandroid.editor.processing.ExportProgress
import com.tajuli.digitorandroid.editor.processing.ProcessingRouter
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val V3Shell = Color(0xFF08080A)
private val V3Panel = Color(0xFF0B0B0F)
private val V3Raised = Color(0xFF17171C)
private val V3Divider = Color(0xFF24242A)
private val V3Muted = Color(0xFF8A8A92)
private val V3Playhead = Color(0xFFFF4D4D)
private val V3Video = Color(0xFF385B78)
private val V3Audio = Color(0xFF315F57)
private val V3Accent = Color(0xFF30E0C3)
private const val V3_TRACK_HEIGHT_DP = 38f

private enum class V3Workspace(val label: String, val icon: ImageVector) {
    EDIT("Edit", Icons.Rounded.ContentCut),
    AUDIO("Audio", Icons.Rounded.Audiotrack),
    MEDIA("Media", Icons.Rounded.VideoLibrary),
    CORRECTION("Correction", Icons.Rounded.Tune),
    EFFECTS("Effects", Icons.Rounded.AutoAwesome),
    NODES("Nodes", Icons.Rounded.AccountTree),
}

@UnstableApi
@Composable
fun DigitorEditorScreenV3(vm: EditorViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val router = remember { ProcessingRouter(context.applicationContext) }
    val selectedClip = state.project.clip(state.selectedClipId)
    val player = remember { ExoPlayer.Builder(context).build() }

    var workspace by remember { mutableStateOf(V3Workspace.EDIT) }
    var isPlaying by remember { mutableStateOf(false) }
    var playerPositionMs by remember { mutableStateOf(0L) }
    var timelineCursorUs by remember { mutableStateOf(0L) }
    var pendingSeekUs by remember { mutableStateOf<Long?>(null) }

    var showExportDialog by remember { mutableStateOf(false) }
    var exportName by remember { mutableStateOf("Digitor_${System.currentTimeMillis()}") }
    var exportFraction by remember { mutableStateOf<Float?>(null) }
    var exportStatus by remember { mutableStateOf<String?>(null) }

    val mediaPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> -> vm.importUris(uris) }

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
                exportFraction = max(exportFraction ?: 0f, 0.99f)
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

    val saveDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4"),
    ) { uri: Uri? ->
        if (uri != null) startExport(uri)
    }

    LaunchedEffect(selectedClip?.id, selectedClip?.colorGrade) {
        player.stop()
        player.clearMediaItems()
        playerPositionMs = 0L
        if (selectedClip != null) {
            player.setVideoEffects(v3PreviewEffects(selectedClip.colorGrade))
            player.setMediaItem(
                MediaItem.Builder()
                    .setUri(selectedClip.uri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(selectedClip.sourceInUs / 1000L)
                            .setEndPositionMs(selectedClip.sourceOutUs / 1000L)
                            .build(),
                    )
                    .build(),
            )
            pendingSeekUs?.let { timelineUs ->
                val localUs = (timelineUs - selectedClip.timelineStartUs).coerceIn(0L, selectedClip.durationUs)
                player.seekTo(localUs / 1000L)
                pendingSeekUs = null
            }
            player.prepare()
        } else {
            player.setVideoEffects(emptyList())
        }
    }

    LaunchedEffect(player, selectedClip?.id) {
        while (true) {
            isPlaying = player.isPlaying
            playerPositionMs = player.currentPosition.coerceAtLeast(0L)
            if (selectedClip != null && player.isPlaying) {
                timelineCursorUs = (selectedClip.timelineStartUs + playerPositionMs * 1000L)
                    .coerceIn(0L, state.project.durationUs.coerceAtLeast(0L))
            }
            delay(80)
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    fun seekTimeline(requestUs: Long) {
        val duration = state.project.durationUs.coerceAtLeast(0L)
        val targetUs = requestUs.coerceIn(0L, duration)
        timelineCursorUs = targetUs
        val activeVideo = state.project.tracks
            .filter { it.kind == TrackKind.VIDEO && !it.muted }
            .asReversed()
            .asSequence()
            .flatMap { it.clips.asSequence() }
            .firstOrNull { targetUs in it.timelineStartUs until it.timelineEndUs }
        if (activeVideo != null) {
            pendingSeekUs = targetUs
            if (activeVideo.id != selectedClip?.id) {
                vm.selectClip(activeVideo.id)
            } else {
                val localUs = (targetUs - activeVideo.timelineStartUs).coerceIn(0L, activeVideo.durationUs)
                player.seekTo(localUs / 1000L)
                pendingSeekUs = null
            }
        }
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Export video") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = exportName,
                        onValueChange = { exportName = it },
                        singleLine = true,
                        label = { Text("File name") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("File type", fontSize = 11.sp, color = V3Muted)
                    AssistChip(
                        onClick = {},
                        label = { Text("MP4 · H.264 / AAC") },
                        leadingIcon = { Icon(Icons.Rounded.VideoLibrary, null, modifier = Modifier.size(16.dp)) },
                    )
                    Text(
                        "MP4 is the export container currently supported. Next screen lets you choose the folder/location.",
                        fontSize = 9.sp,
                        color = V3Muted,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val base = exportName.trim().ifEmpty { "Digitor_export" }.removeSuffix(".mp4")
                        showExportDialog = false
                        saveDocument.launch("$base.mp4")
                    },
                ) {
                    Icon(Icons.Rounded.Save, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Choose location")
                }
            },
            dismissButton = { TextButton(onClick = { showExportDialog = false }) { Text("Cancel") } },
        )
    }

    Surface(Modifier.fillMaxSize(), color = V3Shell) {
        Column(
            Modifier.fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            V3TopBar(
                title = selectedClip?.label ?: "New project",
                status = exportStatus ?: state.status,
                exportFraction = exportFraction,
                onImport = { mediaPicker.launch(arrayOf("video/*", "audio/*")) },
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
                        color = V3Muted,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            V3Preview(
                clip = selectedClip,
                player = player,
                onImport = { mediaPicker.launch(arrayOf("video/*", "audio/*")) },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            V3Transport(
                enabled = selectedClip != null,
                isPlaying = isPlaying,
                positionUs = timelineCursorUs,
                durationUs = state.project.durationUs,
                onBack = { seekTimeline(timelineCursorUs - 10 * US_PER_SECOND) },
                onPlayPause = { if (player.isPlaying) player.pause() else player.play() },
                onForward = { seekTimeline(timelineCursorUs + 10 * US_PER_SECOND) },
            )

            Box(Modifier.fillMaxWidth().height(260.dp)) {
                when (workspace) {
                    V3Workspace.NODES -> V3NodeGraph(selectedClip, vm, Modifier.fillMaxSize())
                    V3Workspace.CORRECTION -> V3CorrectionPanel(selectedClip, vm, Modifier.fillMaxSize())
                    V3Workspace.EFFECTS -> V3EffectsPanel(selectedClip, vm, Modifier.fillMaxSize())
                    else -> V3Timeline(
                        project = state.project,
                        selectedTrackId = state.selectedTrackId,
                        selectedClipIds = state.selectedClipIds,
                        playheadUs = timelineCursorUs,
                        onSeek = ::seekTimeline,
                        onSelectTrack = vm::selectTrack,
                        onSelectClip = vm::selectClip,
                        onMoveClip = vm::moveClip,
                        onMoveClipToTrack = vm::moveClipToTrack,
                        onAddVideoTrack = { vm.addTrack(TrackKind.VIDEO) },
                        onAddAudioTrack = { vm.addTrack(TrackKind.AUDIO) },
                        onSplit = { vm.splitSelectedAt(timelineCursorUs) },
                        onDelete = vm::deleteSelected,
                        onUnlink = vm::unlinkSelected,
                        onImport = { mediaPicker.launch(arrayOf("video/*", "audio/*")) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            V3WorkspaceBar(
                selected = workspace,
                onSelected = { workspace = it },
                modifier = Modifier.fillMaxWidth().height(68.dp),
            )
        }
    }
}

@Composable
private fun V3TopBar(
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
            Icon(Icons.Rounded.Add, contentDescription = "Import")
        }
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(V3Accent))
                Spacer(Modifier.width(5.dp))
                Text(
                    if (status == "Ready") "GPU-first DigitorEngine" else status,
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = .54f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Button(onClick = onExport, enabled = !exporting, modifier = Modifier.height(34.dp), shape = RoundedCornerShape(7.dp)) {
            Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
            Text(if (exporting) "${((exportFraction ?: 0f) * 100).roundToInt()}%" else "Export", fontSize = 11.sp)
        }
    }
}

@Composable
private fun V3Preview(clip: TimelineClip?, player: ExoPlayer, onImport: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.background(Color(0xFF030304)), contentAlignment = Alignment.Center) {
        if (clip == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.AddPhotoAlternate, null, tint = Color.White.copy(alpha = .35f), modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(8.dp))
                Text("No media loaded", color = Color.White.copy(alpha = .55f), fontSize = 12.sp)
                TextButton(onClick = onImport) { Text("Import media") }
            }
        } else {
            AndroidView(
                factory = { ctx -> PlayerView(ctx).apply { useController = false; this.player = player } },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            "GPU Preview",
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
                .background(Color.Black.copy(alpha = .6f), RoundedCornerShape(5.dp))
                .padding(horizontal = 7.dp, vertical = 4.dp),
            fontSize = 9.sp,
            color = Color.White.copy(alpha = .7f),
        )
    }
}

@Composable
private fun V3Transport(
    enabled: Boolean,
    isPlaying: Boolean,
    positionUs: Long,
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
        Text(v3TimeText(positionUs / 1000L), color = V3Muted, fontSize = 9.sp, modifier = Modifier.width(58.dp))
        IconButton(onClick = onBack, enabled = enabled, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.Replay10, null, modifier = Modifier.size(18.dp)) }
        IconButton(onClick = onPlayPause, enabled = enabled, modifier = Modifier.size(38.dp)) {
            Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, modifier = Modifier.size(23.dp))
        }
        IconButton(onClick = onForward, enabled = enabled, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.Forward10, null, modifier = Modifier.size(18.dp)) }
        Text(v3TimeText(durationUs / 1000L), color = V3Muted, fontSize = 9.sp, modifier = Modifier.width(58.dp))
    }
}

@Composable
private fun V3Timeline(
    project: TimelineProject,
    selectedTrackId: String?,
    selectedClipIds: Set<String>,
    playheadUs: Long,
    onSeek: (Long) -> Unit,
    onSelectTrack: (String) -> Unit,
    onSelectClip: (String) -> Unit,
    onMoveClip: (String, String, Long) -> Unit,
    onMoveClipToTrack: (String, String) -> Unit,
    onAddVideoTrack: () -> Unit,
    onAddAudioTrack: () -> Unit,
    onSplit: () -> Unit,
    onDelete: () -> Unit,
    onUnlink: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val horizontalScroll = rememberScrollState()
    val density = LocalDensity.current
    var zoomFraction by remember { mutableStateOf(0.18f) }

    BoxWithConstraints(modifier.background(V3Panel)) {
        val viewportDp = (maxWidth - 54.dp).coerceAtLeast(120.dp)
        val viewportPx = with(density) { viewportDp.toPx() }
        val durationSec = max(project.durationUs / US_PER_SECOND.toFloat(), 1f)
        val overviewPps = (viewportPx * 0.10f / durationSec).coerceAtLeast(0.03f)
        val fitPps = (viewportPx / durationSec).coerceAtLeast(overviewPps)
        val frameWidthPx = with(density) { 18.dp.toPx() }
        val oneFramePps = max(fitPps, frameWidthPx * project.frameRate.coerceAtLeast(1))
        val fitFraction = if (oneFramePps <= overviewPps * 1.001f) 0f else {
            (ln((fitPps / overviewPps).toDouble()) / ln((oneFramePps / overviewPps).toDouble())).toFloat().coerceIn(0f, 1f)
        }
        val ratio = (oneFramePps / overviewPps).coerceAtLeast(1f)
        val pixelsPerSecond = if (ratio <= 1.0001f) overviewPps else overviewPps * ratio.toDouble().pow(zoomFraction.toDouble()).toFloat()
        val contentWidthPx = max(viewportPx, durationSec * pixelsPerSecond)
        val contentWidth = with(density) { contentWidthPx.toDp() }
        val frameUs = (US_PER_SECOND.toDouble() / project.frameRate.coerceAtLeast(1)).roundToLong().coerceAtLeast(1L)

        fun xToTimeUs(xPx: Float): Long {
            val raw = (xPx / pixelsPerSecond * US_PER_SECOND).roundToLong()
            return ((raw.toDouble() / frameUs).roundToLong() * frameUs)
                .coerceIn(0L, project.durationUs.coerceAtLeast(0L))
        }

        Column(Modifier.fillMaxSize()) {
            V3TimelineToolbar(
                selectedCount = selectedClipIds.size,
                zoomFraction = zoomFraction,
                fitFraction = fitFraction,
                onZoom = { zoomFraction = it.coerceIn(0f, 1f) },
                onOverview = { zoomFraction = 0f },
                onFit = { zoomFraction = fitFraction },
                onOneFrame = { zoomFraction = 1f },
                onAddVideoTrack = onAddVideoTrack,
                onAddAudioTrack = onAddAudioTrack,
                onSplit = onSplit,
                onDelete = onDelete,
                onUnlink = onUnlink,
                onImport = onImport,
            )

            Row(Modifier.weight(1f)) {
                Column(Modifier.width(54.dp)) {
                    Box(Modifier.fillMaxWidth().height(24.dp).background(Color(0xFF111116)), contentAlignment = Alignment.Center) {
                        Text("TC", color = Color.White.copy(alpha = .35f), fontSize = 8.sp)
                    }
                    project.tracks.forEach { track -> V3TrackHeader(track, track.id == selectedTrackId, onSelectTrack) }
                }

                Column(
                    Modifier.horizontalScroll(horizontalScroll)
                        .requiredWidth(contentWidth)
                        .pointerInput(overviewPps, oneFramePps) {
                            detectTransformGestures { _, _, zoom, _ ->
                                if (ratio > 1.0001f && zoom > 0f) {
                                    val delta = (ln(zoom.toDouble()) / ln(ratio.toDouble())).toFloat()
                                    zoomFraction = (zoomFraction + delta).coerceIn(0f, 1f)
                                }
                            }
                        },
                ) {
                    Box(
                        Modifier.requiredWidth(contentWidth).height(24.dp)
                            .pointerInput(pixelsPerSecond) {
                                detectTapGestures { offset -> onSeek(xToTimeUs(offset.x)) }
                            }
                            .pointerInput(pixelsPerSecond) {
                                detectDragGestures(
                                    onDragStart = { offset -> onSeek(xToTimeUs(offset.x)) },
                                ) { change, _ ->
                                    change.consume()
                                    onSeek(xToTimeUs(change.position.x))
                                }
                            },
                    ) {
                        V3Ruler(contentWidth, durationSec, pixelsPerSecond, project.frameRate, horizontalScroll.value.toFloat(), viewportPx)
                    }

                    Box(Modifier.requiredWidth(contentWidth).weight(1f)) {
                        Column(Modifier.fillMaxSize()) {
                            project.tracks.forEach { track ->
                                V3TimelineLane(
                                    project = project,
                                    track = track,
                                    selectedClipIds = selectedClipIds,
                                    pixelsPerSecond = pixelsPerSecond,
                                    onSelectClip = onSelectClip,
                                    onMoveClip = onMoveClip,
                                    onMoveClipToTrack = onMoveClipToTrack,
                                    width = contentWidth,
                                )
                            }
                        }
                        val playheadXPx = playheadUs / US_PER_SECOND.toFloat() * pixelsPerSecond
                        val playheadX = with(density) { playheadXPx.toDp() }
                        Box(
                            Modifier.offset(x = playheadX - 11.dp)
                                .width(22.dp).fillMaxHeight()
                                .pointerInput(pixelsPerSecond) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        val nextPx = playheadUs / US_PER_SECOND.toFloat() * pixelsPerSecond + dragAmount.x
                                        onSeek(xToTimeUs(nextPx))
                                    }
                                },
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            Box(Modifier.width(1.5.dp).fillMaxHeight().background(V3Playhead))
                            Box(Modifier.size(10.dp).clip(CircleShape).background(V3Playhead))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun V3TimelineToolbar(
    selectedCount: Int,
    zoomFraction: Float,
    fitFraction: Float,
    onZoom: (Float) -> Unit,
    onOverview: () -> Unit,
    onFit: () -> Unit,
    onOneFrame: () -> Unit,
    onAddVideoTrack: () -> Unit,
    onAddAudioTrack: () -> Unit,
    onSplit: () -> Unit,
    onDelete: () -> Unit,
    onUnlink: () -> Unit,
    onImport: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF0E0E12))) {
        Row(
            Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            V3TinyAction("+V", onAddVideoTrack)
            V3TinyAction("+A", onAddAudioTrack)
            V3TinyAction("Split", onSplit, selectedCount > 0, Icons.Rounded.ContentCut)
            V3TinyAction("Delete", onDelete, selectedCount > 0, Icons.Rounded.Delete)
            V3TinyAction("Unlink", onUnlink, selectedCount > 1, Icons.Rounded.LinkOff)
            Spacer(Modifier.weight(1f))
            V3TinyAction("Import", onImport, icon = Icons.Rounded.AddPhotoAlternate)
        }
        Row(
            Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onOverview, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)) {
                Text("Overview", fontSize = 7.sp)
            }
            IconButton(onClick = onFit, modifier = Modifier.size(27.dp)) {
                Icon(Icons.Rounded.FitScreen, contentDescription = "Fit", modifier = Modifier.size(14.dp))
            }
            Slider(value = zoomFraction, onValueChange = onZoom, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
            TextButton(onClick = onOneFrame, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)) {
                Text("1F", fontSize = 8.sp)
            }
            Text("Fit ${(fitFraction * 100).roundToInt()}%", fontSize = 6.sp, color = V3Muted)
        }
    }
}

@Composable
private fun V3TinyAction(label: String, onClick: () -> Unit, enabled: Boolean = true, icon: ImageVector? = null) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 5.dp, vertical = 0.dp)) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(11.dp))
            Spacer(Modifier.width(2.dp))
        }
        Text(label, fontSize = 7.sp)
    }
}

@Composable
private fun V3TrackHeader(track: TimelineTrack, selected: Boolean, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(V3_TRACK_HEIGHT_DP.dp)
            .background(if (selected) V3Accent.copy(alpha = .11f) else Color(0xFF121217))
            .border(.5.dp, V3Divider)
            .clickable { onSelect(track.id) }
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(if (track.kind == TrackKind.VIDEO) Color(0xFF607D9B) else Color(0xFF3E7569)))
        Spacer(Modifier.width(5.dp))
        Text(track.name, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = .72f))
    }
}

@Composable
private fun V3Ruler(width: Dp, durationSec: Float, pixelsPerSecond: Float, frameRate: Int, scrollPx: Float, viewportPx: Float) {
    Canvas(Modifier.requiredWidth(width).fillMaxSize().background(Color(0xFF111116))) {
        val startSec = (scrollPx / pixelsPerSecond).coerceAtLeast(0f)
        val endSec = ((scrollPx + viewportPx) / pixelsPerSecond).coerceAtMost(durationSec)
        val framePx = pixelsPerSecond / frameRate.coerceAtLeast(1)
        if (framePx >= 6f) {
            val firstFrame = (startSec * frameRate).toInt().coerceAtLeast(0)
            val lastFrame = ceil(endSec * frameRate).toInt()
            for (frame in firstFrame..lastFrame) {
                val x = frame / frameRate.toFloat() * pixelsPerSecond
                drawLine(
                    color = if (frame % frameRate == 0) Color.White.copy(alpha = .32f) else Color.White.copy(alpha = .12f),
                    start = androidx.compose.ui.geometry.Offset(x, if (frame % frameRate == 0) 0f else size.height * .55f),
                    end = androidx.compose.ui.geometry.Offset(x, size.height),
                    strokeWidth = 1f,
                )
            }
        } else {
            val stepSec = when {
                pixelsPerSecond >= 100f -> 1f
                pixelsPerSecond >= 30f -> 2f
                pixelsPerSecond >= 12f -> 5f
                pixelsPerSecond >= 5f -> 10f
                pixelsPerSecond >= 1f -> 30f
                else -> 60f
            }
            var second = kotlin.math.floor(startSec / stepSec) * stepSec
            while (second <= endSec + stepSec) {
                val x = second * pixelsPerSecond
                drawLine(Color.White.copy(alpha = .24f), androidx.compose.ui.geometry.Offset(x, size.height * .42f), androidx.compose.ui.geometry.Offset(x, size.height), 1f)
                second += stepSec
            }
        }
    }
}

@Composable
private fun V3TimelineLane(
    project: TimelineProject,
    track: TimelineTrack,
    selectedClipIds: Set<String>,
    pixelsPerSecond: Float,
    onSelectClip: (String) -> Unit,
    onMoveClip: (String, String, Long) -> Unit,
    onMoveClipToTrack: (String, String) -> Unit,
    width: Dp,
) {
    Box(
        Modifier.requiredWidth(width).height(V3_TRACK_HEIGHT_DP.dp)
            .background(if (track.kind == TrackKind.VIDEO) Color(0xFF10141A) else Color(0xFF101713))
            .border(.5.dp, V3Divider),
    ) {
        track.clips.forEach { clip ->
            V3Clip(project, track, clip, clip.id in selectedClipIds, pixelsPerSecond, onSelectClip, onMoveClip, onMoveClipToTrack)
        }
    }
}

@Composable
private fun V3Clip(
    project: TimelineProject,
    track: TimelineTrack,
    clip: TimelineClip,
    selected: Boolean,
    pixelsPerSecond: Float,
    onSelectClip: (String) -> Unit,
    onMoveClip: (String, String, Long) -> Unit,
    onMoveClipToTrack: (String, String) -> Unit,
) {
    val density = LocalDensity.current
    val ppsDp = with(density) { pixelsPerSecond.toDp().value }
    val start = (clip.timelineStartUs / US_PER_SECOND.toFloat() * ppsDp).dp
    val clipWidth = (clip.durationUs / US_PER_SECOND.toFloat() * ppsDp).coerceAtLeast(1f).dp
    val frameUs = (US_PER_SECOND.toDouble() / project.frameRate.coerceAtLeast(1)).roundToLong().coerceAtLeast(1L)
    val framePx = pixelsPerSecond / project.frameRate.coerceAtLeast(1)
    val compatibleTracks = project.tracks.filter { it.kind == track.kind }
    val sourceIndex = compatibleTracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
    val trackHeightPx = with(density) { V3_TRACK_HEIGHT_DP.dp.toPx() }
    var carriedX by remember(clip.id) { mutableStateOf(0f) }
    var totalY by remember(clip.id) { mutableStateOf(0f) }

    Box(
        Modifier.offset(x = start, y = 3.dp)
            .width(clipWidth).height(31.dp)
            .clip(RoundedCornerShape(if (clipWidth < 3.dp) 0.dp else 4.dp))
            .background(if (track.kind == TrackKind.VIDEO) V3Video else V3Audio)
            .border(if (selected) 2.dp else .5.dp, if (selected) V3Accent else Color.White.copy(alpha = .13f))
            .clickable { onSelectClip(clip.id) }
            .pointerInput(clip.id, track.id, pixelsPerSecond) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { carriedX = 0f; totalY = 0f; onSelectClip(clip.id) },
                    onDragEnd = {
                        val shift = (totalY / trackHeightPx).roundToInt()
                        if (shift != 0 && compatibleTracks.isNotEmpty()) {
                            val target = compatibleTracks[(sourceIndex + shift).coerceIn(0, compatibleTracks.lastIndex)]
                            if (target.id != track.id) onMoveClipToTrack(clip.id, target.id)
                        }
                        carriedX = 0f
                        totalY = 0f
                    },
                    onDragCancel = { carriedX = 0f; totalY = 0f },
                ) { change, dragAmount ->
                    change.consume()
                    carriedX += dragAmount.x
                    totalY += dragAmount.y
                    if (abs(carriedX) >= max(1f, framePx * .5f)) {
                        val rawUs = carriedX / pixelsPerSecond * US_PER_SECOND
                        val frames = (rawUs / frameUs).roundToLong()
                        val snappedUs = frames * frameUs
                        if (snappedUs != 0L) {
                            onMoveClip(track.id, clip.id, snappedUs)
                            carriedX -= snappedUs / US_PER_SECOND.toFloat() * pixelsPerSecond
                        }
                    }
                }
            }
            .padding(horizontal = if (clipWidth >= 20.dp) 4.dp else 0.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (clipWidth >= 20.dp) {
            Column {
                Text(clip.label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 8.sp, color = Color.White.copy(alpha = .88f))
                if (clip.linkGroupId != null) Text("linked", fontSize = 6.sp, color = Color.White.copy(alpha = .48f))
            }
        }
    }
}

@Composable
private fun V3NodeGraph(clip: TimelineClip?, vm: EditorViewModel, modifier: Modifier = Modifier) {
    if (clip == null) { V3Empty("Select a clip to open its node graph", modifier); return }
    val graph = clip.nodeGraph
    val density = LocalDensity.current
    val graphWidth = max(640f, (graph.nodes.maxOfOrNull { it.position.x } ?: 420f) + 140f).dp
    val graphHeight = max(220f, (graph.nodes.maxOfOrNull { it.position.y } ?: 160f) + 100f).dp
    val scroll = rememberScrollState()
    var menuNodeId by remember { mutableStateOf<String?>(null) }

    Column(modifier.background(V3Panel)) {
        Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Node Graph · ${clip.label}", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("Tap select · hold drag · hold/release add", fontSize = 7.sp, color = V3Muted)
        }
        HorizontalDivider(color = V3Divider)
        Box(Modifier.weight(1f).horizontalScroll(scroll)) {
            Box(Modifier.requiredWidth(graphWidth).height(graphHeight)) {
                Canvas(Modifier.fillMaxSize()) {
                    val byId = graph.nodes.associateBy { it.id }
                    graph.edges.forEach { edge ->
                        val from = byId[edge.fromId] ?: return@forEach
                        val to = byId[edge.toId] ?: return@forEach
                        drawLine(
                            Color.White.copy(alpha = .28f),
                            androidx.compose.ui.geometry.Offset(from.position.x.dp.toPx() + 88.dp.toPx(), from.position.y.dp.toPx() + 26.dp.toPx()),
                            androidx.compose.ui.geometry.Offset(to.position.x.dp.toPx(), to.position.y.dp.toPx() + 26.dp.toPx()),
                            2f,
                        )
                    }
                }
                graph.nodes.forEach { node ->
                    val selected = graph.selectedNodeId == node.id
                    var moved by remember(node.id) { mutableStateOf(false) }
                    Box(
                        Modifier.offset(x = node.position.x.dp, y = node.position.y.dp)
                            .width(88.dp).height(52.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(v3NodeColor(node.kind))
                            .border(if (selected) 2.dp else 1.dp, if (selected) V3Accent else Color.White.copy(alpha = .2f), RoundedCornerShape(6.dp))
                            .clickable { vm.selectNode(node.id) }
                            .pointerInput(node.id, node.position) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { moved = false; vm.selectNode(node.id) },
                                    onDragEnd = { if (!moved) menuNodeId = node.id; moved = false },
                                    onDragCancel = { moved = false },
                                ) { change, dragAmount ->
                                    change.consume()
                                    if (abs(dragAmount.x) + abs(dragAmount.y) > 1f) moved = true
                                    vm.moveNode(node.id, with(density) { dragAmount.x.toDp().value }, with(density) { dragAmount.y.toDp().value })
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(node.label, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Text(node.kind.name, fontSize = 6.sp, color = Color.White.copy(alpha = .52f))
                            if (node.effects.isNotEmpty()) Text("FX ${node.effects.size}", fontSize = 6.sp, color = V3Accent)
                        }
                        DropdownMenu(expanded = menuNodeId == node.id, onDismissRequest = { menuNodeId = null }) {
                            DropdownMenuItem(text = { Text("Add Serial") }, enabled = node.kind != NodeKind.OUTPUT, onClick = { menuNodeId = null; vm.addSerialNode(node.id) })
                            DropdownMenuItem(text = { Text("Add Parallel") }, enabled = node.kind == NodeKind.SERIAL || node.kind == NodeKind.PARALLEL, onClick = { menuNodeId = null; vm.addParallelNode(node.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun V3CorrectionPanel(clip: TimelineClip?, vm: EditorViewModel, modifier: Modifier = Modifier) {
    val node = clip?.nodeGraph?.selectedNode()
    if (clip == null || node == null) { V3Empty("Select a clip and a node first", modifier); return }
    if (node.kind != NodeKind.SERIAL && node.kind != NodeKind.PARALLEL) { V3Empty("Select a Serial or Parallel node for corrections", modifier); return }
    val c = node.corrections
    Column(modifier.background(V3Panel)) {
        V3PanelTitle("Correction · ${node.label}", "Selected node only")
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 5.dp)) {
            V3CorrectionSlider("Exposure", c.exposure, -5f..5f) { vm.setSelectedNodeCorrection("Exposure", it) }
            V3CorrectionSlider("Contrast", c.contrast, -100f..100f) { vm.setSelectedNodeCorrection("Contrast", it) }
            V3CorrectionSlider("Saturation", c.saturation, -100f..100f) { vm.setSelectedNodeCorrection("Saturation", it) }
            V3CorrectionSlider("Temperature", c.temperature, -100f..100f) { vm.setSelectedNodeCorrection("Temperature", it) }
            V3CorrectionSlider("Tint", c.tint, -100f..100f) { vm.setSelectedNodeCorrection("Tint", it) }
            V3CorrectionSlider("Highlights", c.highlights, -100f..100f) { vm.setSelectedNodeCorrection("Highlights", it) }
            V3CorrectionSlider("Shadows", c.shadows, -100f..100f) { vm.setSelectedNodeCorrection("Shadows", it) }
            V3CorrectionSlider("Hue", c.hue, -180f..180f) { vm.setSelectedNodeCorrection("Hue", it) }
            V3CorrectionSlider("Color Boost", c.colorBoost, 0f..100f) { vm.setSelectedNodeCorrection("Color Boost", it) }
        }
    }
}

@Composable
private fun V3CorrectionSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onValue: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(78.dp), fontSize = 8.sp, color = Color.White.copy(alpha = .72f))
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onValue, valueRange = range, modifier = Modifier.weight(1f))
        Text(String.format("%.1f", value), Modifier.width(42.dp), fontSize = 8.sp, color = V3Muted)
    }
}

@Composable
private fun V3EffectsPanel(clip: TimelineClip?, vm: EditorViewModel, modifier: Modifier = Modifier) {
    val node = clip?.nodeGraph?.selectedNode()
    if (clip == null || node == null) { V3Empty("Select a clip and a node first", modifier); return }
    if (node.kind != NodeKind.SERIAL && node.kind != NodeKind.PARALLEL) { V3Empty("Effects can be added to Serial or Parallel nodes", modifier); return }
    Column(modifier.background(V3Panel)) {
        V3PanelTitle("Effects · ${node.label}", "Selected node only")
        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("Blur", "Sharpen", "Glow", "Film Grain").forEach { name ->
                FilledTonalButton(onClick = { vm.addEffectToSelectedNode(name) }) { Text(name, fontSize = 9.sp) }
            }
        }
        HorizontalDivider(color = V3Divider)
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (node.effects.isEmpty()) Text("No effects on this node", fontSize = 9.sp, color = V3Muted)
            node.effects.forEach { effect ->
                Row(Modifier.fillMaxWidth().background(V3Raised, RoundedCornerShape(5.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(effect.name, fontSize = 9.sp, modifier = Modifier.weight(1f))
                    Text("${(effect.amount * 100).toInt()}%", fontSize = 8.sp, color = V3Accent)
                }
            }
        }
    }
}

@Composable
private fun V3PanelTitle(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text(subtitle, fontSize = 7.sp, color = V3Muted)
    }
    HorizontalDivider(color = V3Divider)
}

@Composable
private fun V3Empty(message: String, modifier: Modifier = Modifier) {
    Box(modifier.background(V3Panel), contentAlignment = Alignment.Center) { Text(message, color = V3Muted, fontSize = 10.sp) }
}

@Composable
private fun V3WorkspaceBar(selected: V3Workspace, onSelected: (V3Workspace) -> Unit, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    Row(
        modifier.background(Color(0xFF0A0A0D)).horizontalScroll(scroll).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        V3Workspace.entries.forEach { item ->
            val active = item == selected
            Column(
                Modifier.width(66.dp).fillMaxHeight().clickable { onSelected(item) }
                    .background(if (active) V3Accent.copy(alpha = .10f) else Color.Transparent),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(item.icon, item.label, modifier = Modifier.size(18.dp), tint = if (active) V3Accent else Color.White.copy(alpha = .55f))
                Spacer(Modifier.height(3.dp))
                Text(item.label, fontSize = 8.sp, color = if (active) V3Accent else Color.White.copy(alpha = .55f))
            }
        }
    }
}

private fun v3PreviewEffects(grade: ColorGrade): List<Effect> {
    val effects = mutableListOf<Effect>()
    effects += RgbAdjustment.Builder()
        .setRedScale(grade.redScale.coerceAtLeast(0f))
        .setGreenScale(grade.greenScale.coerceAtLeast(0f))
        .setBlueScale(grade.blueScale.coerceAtLeast(0f))
        .build()
    if (grade.hueDegrees != 0f || grade.saturationDelta != 0f || grade.lightnessDelta != 0f) {
        effects += HslAdjustment.Builder()
            .adjustHue(grade.hueDegrees)
            .adjustSaturation(grade.saturationDelta.coerceIn(-100f, 100f))
            .adjustLightness(grade.lightnessDelta.coerceIn(-100f, 100f))
            .build()
    }
    return effects
}

private fun v3NodeColor(kind: NodeKind): Color = when (kind) {
    NodeKind.IMPORT -> Color(0xFF25323B)
    NodeKind.SERIAL -> Color(0xFF333239)
    NodeKind.PARALLEL -> Color(0xFF3A2F48)
    NodeKind.MIX -> Color(0xFF3B3325)
    NodeKind.OUTPUT -> Color(0xFF26382E)
}

private fun v3TimeText(ms: Long): String {
    val total = ms.coerceAtLeast(0L) / 1000L
    return "%02d:%02d".format(total / 60L, total % 60L)
}
