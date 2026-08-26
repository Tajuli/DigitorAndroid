package com.tajuli.digitorandroid.ui.editor

import android.net.Uri
import android.os.Handler
import android.os.Looper
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

private val E7Shell = Color(0xFF08080A)
private val E7Muted = Color(0xFF909098)
private val E7Accent = Color(0xFF30E0C3)

private enum class WorkspaceV7(val label: String, val icon: ImageVector) {
    EDIT("Edit", Icons.Rounded.ContentCut),
    AUDIO("Audio", Icons.Rounded.Audiotrack),
    MEDIA("Media", Icons.Rounded.VideoLibrary),
    CORRECTION("Correction", Icons.Rounded.Tune),
    COLOR("Color", Icons.Rounded.Palette),
    EFFECTS("Effects", Icons.Rounded.AutoAwesome),
    NODES("Nodes", Icons.Rounded.AccountTree),
}

private fun TimelineProject.activeVideoClipsV7(timelineUs: Long): List<TimelineClip> =
    tracks
        .filter { track -> track.kind == TrackKind.VIDEO && !track.muted }
        .mapNotNull { track ->
            track.clips.firstOrNull { clip -> timelineUs in clip.timelineStartUs until clip.timelineEndUs }
        }

/**
 * Identifies edits that change decoder/compositor topology rather than only shader parameters.
 * A new key forces a fresh CompositionPlayer + PlayerView surface so Media3 cannot keep a stale
 * decoder/video graph after split/delete, track insertion, clip moves between V tracks or trims.
 */
private fun TimelineProject.previewTopologyKeyV7(): String = buildString {
    append(width).append('x').append(height).append('|')
    tracks.forEach { track ->
        append(track.id).append(':')
            .append(track.kind).append(':')
            .append(track.muted).append('[')
        track.sortedClips().forEach { clip ->
            append(clip.id).append('@')
                .append(clip.uri).append('@')
                .append(clip.timelineStartUs).append('@')
                .append(clip.sourceInUs).append('@')
                .append(clip.sourceOutUs).append(';')
        }
        append(']')
    }
}

/**
 * Resolve-inspired editor viewer.
 *
 * Preview and export now share the exact same Media3 Composition built by [Media3CompositionBuilder].
 * CompositionPlayer owns realtime decode/playback while MultipleInputVideoGraph runs the same
 * OpenGL multilayer effects/compositor graph that Transformer receives for final export.
 */
@UnstableApi
@Composable
fun DigitorEditorScreenV7(vm: EditorViewModelV4 = viewModel()) {
    val state by vm.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val router = remember { ProcessingRouter(appContext) }
    val compositionBuilder = remember { Media3CompositionBuilder() }
    val previewTopologyKey = state.project.previewTopologyKeyV7()
    val previewPlayer = remember(previewTopologyKey) {
        CompositionPlayer.Builder(appContext)
            .setVideoGraphFactory(MultipleInputVideoGraph.Factory())
            .build()
    }

    // Topology edits can wedge the old MediaCodec/GL graph if it is synchronously reused or
    // released while Compose is replacing the surface. Pause immediately, then retire it after the
    // replacement PlayerView has had time to attach to the fresh player.
    DisposableEffect(previewPlayer) {
        onDispose {
            val retiredPlayer = previewPlayer
            runCatching { retiredPlayer.pause() }
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { retiredPlayer.release() }
            }, 750L)
        }
    }

    val selectedClip = state.project.clip(state.selectedClipId)
    var workspace by remember { mutableStateOf(WorkspaceV7.EDIT) }
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
    val activeVideoClips = state.project.activeVideoClipsV7(cursorUs)
    val hasMedia = state.project.hasPlayableMedia()
    val hasVideo = state.project.hasPlayableVideo()

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            isPlaying = false
            runCatching { previewPlayer.pause() }
            vm.importUris(uris)
        }
    }
    fun launchImport() = mediaPicker.launch(vm.selectedImportMimeTypes())

    // Rebuild on immutable timeline/edit state. A topology change supplies a brand-new player;
    // parameter-only edits keep the current player. build() remains the exact export composition.
    LaunchedEffect(state.project, previewPlayer) {
        val snapshot = state.project
        previewReady = false
        if (!snapshot.hasPlayableMedia()) {
            runCatching {
                previewPlayer.pause()
                previewPlayer.stop()
            }
            isPlaying = false
            previewStatus = null
            return@LaunchedEffect
        }

        val resumePlayback = isPlaying || previewPlayer.isPlaying
        delay(80)
        try {
            val composition = withContext(Dispatchers.Default) {
                compositionBuilder.build(snapshot)
            }
            val maxStartUs = (snapshot.durationUs - 1L).coerceAtLeast(0L)
            val startMs = cursorUs.coerceIn(0L, maxStartUs) / 1000L
            previewPlayer.pause()
            previewPlayer.stop()
            previewPlayer.setComposition(composition, startMs)
            previewPlayer.prepare()
            previewReady = true
            previewStatus = "GPU preview · shared export pipeline"
            if (resumePlayback) previewPlayer.play()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            previewReady = false
            isPlaying = false
            previewStatus = "GPU preview: ${error.message ?: "unavailable"}"
        }
    }

    LaunchedEffect(state.project.durationUs) {
        val durationUs = state.project.durationUs.coerceAtLeast(0L)
        if (durationUs < previousProjectDurationUs && cursorUs >= durationUs) {
            cursorUs = if (durationUs > 0L) durationUs - 1L else 0L
            if (previewReady) runCatching { previewPlayer.seekTo(cursorUs / 1000L) }
        }
        previousProjectDurationUs = durationUs
    }

    // CompositionPlayer is the single authoritative preview clock for both video and mixed audio.
    // Cursor updates never trigger frame extraction or composition rebuilds, so normal playback can
    // run continuously on MediaCodec + the OpenGL video graph.
    LaunchedEffect(previewPlayer, previewReady, hasMedia) {
        while (previewReady && hasMedia) {
            val playing = previewPlayer.isPlaying
            isPlaying = playing
            if (playing) {
                cursorUs = (previewPlayer.currentPosition.coerceAtLeast(0L) * 1000L)
                    .coerceIn(0L, state.project.durationUs.coerceAtLeast(0L))
            }
            delay(33)
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
        if (previewReady) runCatching { previewPlayer.seekTo(target / 1000L) }
        val activeVideo = state.project.topmostVideoClipAt(target)
        if (activeVideo != null && selectedClip == null) vm.selectClip(activeVideo.id)
    }

    fun togglePlayback() {
        if (!hasMedia || !previewReady) return
        if (previewPlayer.isPlaying) {
            previewPlayer.pause()
            isPlaying = false
        } else {
            if (cursorUs >= state.project.durationUs && state.project.durationUs > 0L) seekTimeline(0L)
            previewPlayer.play()
            isPlaying = true
        }
    }

    fun startExport(destination: Uri) {
        if (state.project.durationUs <= 0L) {
            exportStatus = "Timeline is empty"
            return
        }
        scope.launch {
            exportFraction = 0f
            exportStatus = "Preparing export"
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
                    Text("File type", fontSize = 10.sp, color = E7Muted)
                    AssistChip(onClick = {}, label = { Text("MP4 · H.264 / AAC") })
                    Text("Viewer and export share the same Media3 GPU composition.", fontSize = 9.sp, color = E7Muted)
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

    Surface(Modifier.fillMaxSize(), color = E7Shell) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            TopBarV7(
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
                        color = E7Muted,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            FramePreviewV7(
                player = previewPlayer,
                generation = previewTopologyKey,
                ready = previewReady,
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
            TransportV7(
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
                    WorkspaceV7.EDIT -> EditWorkspaceV5(
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
                    WorkspaceV7.COLOR -> KeyframedColorWorkspaceV5(selectedClip, state.project.frameRate, vm, Modifier.fillMaxSize())
                    WorkspaceV7.NODES -> NodeGraphV4(selectedClip, vm, Modifier.fillMaxSize())
                    WorkspaceV7.CORRECTION -> KeyframedCorrectionWorkspaceV5(selectedClip, state.project.frameRate, vm, Modifier.fillMaxSize())
                    WorkspaceV7.EFFECTS -> KeyframedEffectsWorkspaceV5(selectedClip, state.project.frameRate, vm, Modifier.fillMaxSize())
                    WorkspaceV7.AUDIO, WorkspaceV7.MEDIA -> TimelineEditorV4(
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
            WorkspaceBarV7(
                selected = workspace,
                onSelected = { next ->
                    workspace = next
                    val editsClip = next == WorkspaceV7.EDIT || next == WorkspaceV7.COLOR ||
                        next == WorkspaceV7.CORRECTION || next == WorkspaceV7.NODES || next == WorkspaceV7.EFFECTS
                    val selectedIsActiveVideo = selectedClip?.let { clip ->
                        state.project.trackContaining(clip.id)?.kind == TrackKind.VIDEO &&
                            cursorUs in clip.timelineStartUs until clip.timelineEndUs
                    } == true
                    if (editsClip && !selectedIsActiveVideo && previewClip != null) vm.selectClip(previewClip.id)
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
                Box(Modifier.size(6.dp).clip(CircleShape).background(E7Accent))
                Spacer(Modifier.width(5.dp))
                Text(status, fontSize = 9.sp, color = Color.White.copy(alpha = .55f), maxLines = 1)
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
private fun FramePreviewV7(
    player: Player,
    generation: String,
    ready: Boolean,
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
                    update = { view -> view.player = player },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (!ready) {
                Text(
                    "Preparing GPU preview…",
                    color = Color.White.copy(alpha = .55f),
                    fontSize = 11.sp,
                    modifier = Modifier.background(Color.Black.copy(alpha = .62f), RoundedCornerShape(5.dp))
                        .padding(horizontal = 9.dp, vertical = 6.dp),
                )
            } else if (activeVideoClip == null) {
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
            "GPU Preview · $activeLayerCount ${if (activeLayerCount == 1) "layer" else "layers"}",
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
                    Icon(Icons.Rounded.Colorize, null, modifier = Modifier.size(15.dp), tint = E7Accent)
                    Spacer(Modifier.width(5.dp))
                    Text("Tap the color to qualify selected layer", fontSize = 9.sp, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun TransportV7(
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
        Text(timeV7(cursorUs), color = E7Muted, fontSize = 9.sp, modifier = Modifier.width(66.dp))
        IconButton(onClick = onBack, enabled = enabled, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Rounded.Replay10, null, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onPlayPause, enabled = enabled, modifier = Modifier.size(38.dp)) {
            Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, modifier = Modifier.size(23.dp))
        }
        IconButton(onClick = onForward, enabled = enabled, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Rounded.Forward10, null, modifier = Modifier.size(18.dp))
        }
        Text(timeV7(durationUs), color = E7Muted, fontSize = 9.sp, modifier = Modifier.width(66.dp))
    }
}

@Composable
private fun WorkspaceBarV7(selected: WorkspaceV7, onSelected: (WorkspaceV7) -> Unit, modifier: Modifier) {
    Row(
        modifier.background(Color(0xFF0A0A0D)).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        WorkspaceV7.entries.forEach { item ->
            val active = item == selected
            Column(
                Modifier.width(68.dp).fillMaxHeight().clickable { onSelected(item) }
                    .background(if (active) E7Accent.copy(alpha = .10f) else Color.Transparent),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    item.icon,
                    item.label,
                    modifier = Modifier.size(18.dp),
                    tint = if (active) E7Accent else Color.White.copy(alpha = .55f),
                )
                Spacer(Modifier.height(3.dp))
                Text(item.label, fontSize = 8.sp, color = if (active) E7Accent else Color.White.copy(alpha = .55f))
            }
        }
    }
}

private fun timeV7(us: Long): String {
    val totalSeconds = us.coerceAtLeast(0L) / US_PER_SECOND
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%02d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
}