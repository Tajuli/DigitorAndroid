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
import androidx.compose.runtime.key
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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MultipleInputVideoGraph
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.ui.PlayerView
import com.tajuli.digitorandroid.editor.model.PreviewTransformClock
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.US_PER_SECOND
import com.tajuli.digitorandroid.editor.model.hasPlayableMedia
import com.tajuli.digitorandroid.editor.model.hasPlayableVideo
import com.tajuli.digitorandroid.editor.model.topmostVideoClipAt
import com.tajuli.digitorandroid.editor.processing.ExportProgress
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

private val E6Shell = Color(0xFF08080A)
private val E6Muted = Color(0xFF909098)
private val E6Accent = Color(0xFF30E0C3)

private enum class WorkspaceV6(val label: String, val icon: ImageVector) {
    EDIT("Edit", Icons.Rounded.ContentCut),
    AUDIO("Audio", Icons.Rounded.Audiotrack),
    MEDIA("Media", Icons.Rounded.VideoLibrary),
    CORRECTION("Correction", Icons.Rounded.Tune),
    COLOR("Color", Icons.Rounded.Palette),
    EFFECTS("Effects", Icons.Rounded.AutoAwesome),
    NODES("Nodes", Icons.Rounded.AccountTree),
}

private fun TimelineProject.activeVideoClipsAt(timelineUs: Long): List<TimelineClip> =
    tracks
        .filter { it.kind == TrackKind.VIDEO && !it.muted }
        .mapNotNull { track -> track.clips.firstOrNull { timelineUs in it.timelineStartUs until it.timelineEndUs } }

/**
 * V6 uses CompositionPlayer for timeline preview. A single video sequence stays on Media3's normal
 * single-input video graph; MultipleInputVideoGraph is only installed when the project actually has
 * more than one visible video sequence. This avoids routing ordinary one-layer scrubbing through the
 * still-sensitive multi-input graph while preserving true overlapping-layer preview when needed.
 */
@UnstableApi
@Composable
fun DigitorEditorScreenV6(vm: EditorViewModelV4 = viewModel()) {
    val state by vm.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val router = remember { ProcessingRouter(context.applicationContext) }
    val compositionBuilder = remember { Media3CompositionBuilder() }

    val visibleVideoSequenceCount = state.project.tracks.count { track ->
        track.kind == TrackKind.VIDEO && !track.muted && track.clips.isNotEmpty()
    }
    val useMultipleInputVideoGraph = visibleVideoSequenceCount > 1

    var playerGeneration by remember { mutableStateOf(0) }
    val player = remember(playerGeneration, useMultipleInputVideoGraph) {
        val builder = CompositionPlayer.Builder(context.applicationContext)
        if (useMultipleInputVideoGraph) {
            builder.setVideoGraphFactory(MultipleInputVideoGraph.Factory())
        }
        builder.build()
    }

    val selectedClip = state.project.clip(state.selectedClipId)
    var workspace by remember { mutableStateOf(WorkspaceV6.EDIT) }
    var isPlaying by remember { mutableStateOf(false) }
    var cursorUs by remember { mutableStateOf(0L) }
    var previousProjectDurationUs by remember { mutableStateOf(state.project.durationUs) }
    var previewReady by remember { mutableStateOf(false) }
    var previewStatus by remember { mutableStateOf<String?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var exportName by remember { mutableStateOf("Digitor_${System.currentTimeMillis()}") }
    var exportFraction by remember { mutableStateOf<Float?>(null) }
    var exportStatus by remember { mutableStateOf<String?>(null) }

    val previewClip = state.project.topmostVideoClipAt(cursorUs)
    val activeVideoClips = state.project.activeVideoClipsAt(cursorUs)
    val hasMedia = state.project.hasPlayableMedia()
    val hasVideo = state.project.hasPlayableVideo()

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            isPlaying = false
            runCatching { player.pause() }
            vm.importUris(uris)
            playerGeneration += 1
        }
    }
    fun launchImport() = mediaPicker.launch(vm.selectedImportMimeTypes())

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        previewReady = true
                        previewStatus = null
                    }
                    Player.STATE_IDLE -> previewReady = false
                    Player.STATE_ENDED -> {
                        isPlaying = false
                        previewReady = true
                    }
                }
            }

            override fun onRenderedFirstFrame() {
                previewReady = true
                previewStatus = null
            }

            override fun onPlayerError(error: PlaybackException) {
                previewReady = false
                previewStatus = "Preview: ${error.message ?: error.errorCodeName}"
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(state.project.durationUs) {
        val durationUs = state.project.durationUs.coerceAtLeast(0L)
        if (durationUs < previousProjectDurationUs && cursorUs >= durationUs) {
            cursorUs = if (durationUs > 0L) durationUs - 1L else 0L
        }
        previousProjectDurationUs = durationUs
    }

    LaunchedEffect(player, state.project, hasMedia) {
        previewReady = false
        if (!hasMedia) {
            runCatching { player.stop() }
            previewStatus = null
            return@LaunchedEffect
        }
        val resume = isPlaying
        val durationUs = state.project.durationUs.coerceAtLeast(0L)
        val startMs = cursorUs.coerceIn(0L, durationUs) / 1000L
        delay(70)
        try {
            val composition = withContext(Dispatchers.Default) {
                compositionBuilder.buildPreview(state.project)
            }
            player.pause()
            player.stop()
            player.setComposition(composition, startMs)
            player.prepare()
            player.seekTo(startMs)
            if (resume) player.play()
            previewStatus = "Preview: preparing…"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            runCatching { player.stop() }
            previewReady = false
            previewStatus = "Preview: ${error.message ?: "composition unavailable"}"
        }
    }

    LaunchedEffect(player, isPlaying, previewReady) {
        while (isPlaying && previewReady) {
            val durationUs = state.project.durationUs.coerceAtLeast(0L)
            cursorUs = (player.currentPosition.coerceAtLeast(0L) * 1000L).coerceIn(0L, durationUs)
            if (durationUs > 0L && cursorUs >= durationUs) {
                player.pause()
                isPlaying = false
                break
            }
            delay(32)
        }
    }

    LaunchedEffect(cursorUs, selectedClip?.id, previewClip?.id) {
        val clockClip = selectedClip?.takeIf { clip ->
            state.project.trackContaining(clip.id)?.kind == TrackKind.VIDEO &&
                cursorUs in clip.timelineStartUs until clip.timelineEndUs
        } ?: previewClip
        if (clockClip == null) PreviewTransformClock.clear()
        else PreviewTransformClock.update(clockClip, cursorUs)
    }

    fun seekTimeline(requestUs: Long) {
        val target = requestUs.coerceIn(0L, state.project.durationUs.coerceAtLeast(0L))
        cursorUs = target
        // Seeking is valid while CompositionPlayer is preparing/buffering. Do not gate this on
        // STATE_READY: some devices render the first frame before the ready callback reaches Compose.
        if (hasMedia) {
            runCatching { player.seekTo(target / 1000L) }
                .onFailure { previewStatus = "Preview seek: ${it.message ?: "unavailable"}" }
        }
        val activeVideo = state.project.topmostVideoClipAt(target)
        if (activeVideo != null && activeVideo.id != selectedClip?.id) vm.selectClip(activeVideo.id)
    }

    fun togglePlayback() {
        if (!hasMedia) return
        if (isPlaying) {
            isPlaying = false
            player.pause()
        } else {
            if (cursorUs >= state.project.durationUs && state.project.durationUs > 0L) seekTimeline(0L)
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            isPlaying = true
            player.play()
        }
    }

    fun startExport(destination: Uri) {
        if (state.project.durationUs <= 0L) {
            exportStatus = "Timeline is empty"
            return
        }
        scope.launch {
            exportFraction = 0f
            exportStatus = "Preparing multilayer GPU export"
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
                exportStatus = "Saved · ${result.backend} · multilayer"
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
                    Text("File type", fontSize = 10.sp, color = E6Muted)
                    AssistChip(onClick = {}, label = { Text("MP4 · H.264 / AAC · multilayer") })
                    Text("All visible V tracks are composited in project z-order.", fontSize = 9.sp, color = E6Muted)
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

    Surface(Modifier.fillMaxSize(), color = E6Shell) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            TopBarV6(
                title = selectedClip?.label ?: previewClip?.label ?: "New project",
                status = exportStatus ?: previewStatus ?: state.status,
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
                        color = E6Muted,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            PreviewV6(
                player = player,
                generation = playerGeneration,
                hasVideo = hasVideo,
                activeVideoClip = previewClip,
                activeLayerCount = activeVideoClips.size,
                onImport = ::launchImport,
                qualifierPickerActive = state.qualifierPickerActive,
                onPickColor = { x, y, width, height ->
                    val target = selectedClip?.takeIf { clip ->
                        state.project.trackContaining(clip.id)?.kind == TrackKind.VIDEO &&
                            cursorUs in clip.timelineStartUs until clip.timelineEndUs
                    } ?: previewClip
                    target?.let { clip ->
                        if (clip.id != selectedClip?.id) vm.selectClip(clip.id)
                        vm.pickQualifierFromPreview(cursorUs, x, y, width, height)
                    }
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            TransportV6(
                enabled = hasMedia,
                isPlaying = isPlaying,
                cursorUs = cursorUs,
                durationUs = state.project.durationUs,
                onBack = { seekTimeline(cursorUs - 10 * US_PER_SECOND) },
                onPlayPause = ::togglePlayback,
                onForward = { seekTimeline(cursorUs + 10 * US_PER_SECOND) },
            )

            Box(Modifier.fillMaxWidth().height(290.dp)) {
                when (workspace) {
                    WorkspaceV6.EDIT -> EditWorkspaceV5(
                        project = state.project,
                        selectedTrackId = state.selectedTrackId,
                        selectedClipIds = state.selectedClipIds,
                        selectedClip = selectedClip,
                        cursorUs = cursorUs,
                        vm = vm,
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
                    WorkspaceV6.COLOR -> KeyframedColorWorkspaceV5(selectedClip, state.project.frameRate, vm, Modifier.fillMaxSize())
                    WorkspaceV6.NODES -> NodeGraphV4(selectedClip, vm, Modifier.fillMaxSize())
                    WorkspaceV6.CORRECTION -> KeyframedCorrectionWorkspaceV5(selectedClip, state.project.frameRate, vm, Modifier.fillMaxSize())
                    WorkspaceV6.EFFECTS -> KeyframedEffectsWorkspaceV5(selectedClip, state.project.frameRate, vm, Modifier.fillMaxSize())
                    WorkspaceV6.AUDIO, WorkspaceV6.MEDIA -> TimelineEditorV4(
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
            WorkspaceBarV6(
                selected = workspace,
                onSelected = {
                    workspace = it
                    val editsClip = it == WorkspaceV6.EDIT || it == WorkspaceV6.COLOR ||
                        it == WorkspaceV6.CORRECTION || it == WorkspaceV6.NODES || it == WorkspaceV6.EFFECTS
                    if (editsClip && previewClip != null && previewClip.id != selectedClip?.id) vm.selectClip(previewClip.id)
                    if (it != WorkspaceV6.COLOR && state.qualifierPickerActive) vm.setQualifierPickerActive(false)
                },
                modifier = Modifier.fillMaxWidth().height(66.dp),
            )
        }
    }
}

@Composable
private fun TopBarV6(
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
        IconButton(onClick = onImport, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.Add, "Import") }
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(E6Accent))
                Spacer(Modifier.width(5.dp))
                Text(
                    if (status == "Ready") "True multilayer preview · mixed audio" else status,
                    fontSize = 9.sp,
                    color = Color.White.copy(alpha = .55f),
                    maxLines = 1,
                )
            }
        }
        Button(onClick = onExport, enabled = !exporting, modifier = Modifier.height(34.dp), shape = RoundedCornerShape(7.dp)) {
            Icon(Icons.Rounded.Share, null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
            Text(if (exporting) "${((exportFraction ?: 0f) * 100).roundToInt()}%" else "Export", fontSize = 11.sp)
        }
    }
}

@Composable
private fun PreviewV6(
    player: Player,
    generation: Int,
    hasVideo: Boolean,
    activeVideoClip: TimelineClip?,
    activeLayerCount: Int,
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
        if (hasVideo) {
            key(generation) {
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
            if (activeVideoClip == null) {
                Text(
                    "No video at cursor · audio can continue",
                    color = Color.White.copy(alpha = .55f),
                    fontSize = 11.sp,
                    modifier = Modifier.background(Color.Black.copy(alpha = .62f), RoundedCornerShape(5.dp))
                        .padding(horizontal = 9.dp, vertical = 6.dp),
                )
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.AddPhotoAlternate, null, tint = Color.White.copy(alpha = .35f), modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(8.dp))
                Text("No video media", color = Color.White.copy(alpha = .55f), fontSize = 12.sp)
                TextButton(onClick = onImport) { Text("Import media") }
            }
        }

        Text(
            "Multilayer Preview · $activeLayerCount ${if (activeLayerCount == 1) "layer" else "layers"}",
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
                .background(Color.Black.copy(alpha = .6f), RoundedCornerShape(5.dp))
                .padding(horizontal = 7.dp, vertical = 4.dp),
            fontSize = 9.sp,
            color = Color.White.copy(alpha = .72f),
        )

        if (qualifierPickerActive && activeVideoClip != null) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = .05f)).pointerInput(previewSize) {
                    detectTapGestures { pos ->
                        onPickColor(pos.x, pos.y, previewSize.width.toFloat(), previewSize.height.toFloat())
                    }
                },
                contentAlignment = Alignment.TopCenter,
            ) {
                Row(
                    Modifier.padding(top = 9.dp).background(Color.Black.copy(alpha = .76f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 9.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Colorize, null, modifier = Modifier.size(15.dp), tint = E6Accent)
                    Spacer(Modifier.width(5.dp))
                    Text("Tap the color to qualify selected layer", fontSize = 9.sp, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun TransportV6(
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
        Text(timeV6(cursorUs), color = E6Muted, fontSize = 9.sp, modifier = Modifier.width(66.dp))
        IconButton(onClick = onBack, enabled = enabled, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Rounded.Replay10, null, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onPlayPause, enabled = enabled, modifier = Modifier.size(38.dp)) {
            Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, modifier = Modifier.size(23.dp))
        }
        IconButton(onClick = onForward, enabled = enabled, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Rounded.Forward10, null, modifier = Modifier.size(18.dp))
        }
        Text(timeV6(durationUs), color = E6Muted, fontSize = 9.sp, modifier = Modifier.width(66.dp))
    }
}

@Composable
private fun WorkspaceBarV6(selected: WorkspaceV6, onSelected: (WorkspaceV6) -> Unit, modifier: Modifier) {
    Row(
        modifier.background(Color(0xFF0A0A0D)).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        WorkspaceV6.entries.forEach { item ->
            val active = item == selected
            Column(
                Modifier.width(68.dp).fillMaxHeight().clickable { onSelected(item) }
                    .background(if (active) E6Accent.copy(alpha = .10f) else Color.Transparent),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    item.icon,
                    item.label,
                    modifier = Modifier.size(18.dp),
                    tint = if (active) E6Accent else Color.White.copy(alpha = .55f),
                )
                Spacer(Modifier.height(3.dp))
                Text(item.label, fontSize = 8.sp, color = if (active) E6Accent else Color.White.copy(alpha = .55f))
            }
        }
    }
}

private fun timeV6(us: Long): String {
    val totalSeconds = us.coerceAtLeast(0L) / US_PER_SECOND
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%02d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
}
