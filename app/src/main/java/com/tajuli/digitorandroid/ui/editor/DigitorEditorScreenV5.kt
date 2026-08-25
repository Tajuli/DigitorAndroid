package com.tajuli.digitorandroid.ui.editor

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.ui.PlayerView
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.US_PER_SECOND
import com.tajuli.digitorandroid.editor.model.hasPlayableMedia
import com.tajuli.digitorandroid.editor.model.hasPlayableVideo
import com.tajuli.digitorandroid.editor.model.topmostVideoClipAt
import com.tajuli.digitorandroid.editor.processing.ExportProgress
import com.tajuli.digitorandroid.editor.processing.ProcessingRouter
import com.tajuli.digitorandroid.editor.render.Media3CompositionBuilder
import com.tajuli.digitorandroid.editor.render.SharedVideoPipeline
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private val E5Shell = Color(0xFF08080A)
private val E5Muted = Color(0xFF909098)
private val E5Accent = Color(0xFF30E0C3)
private const val VIDEO_PREVIEW_TAG = "DigitorVideoPreview"

private fun TimelineClip.previewSourcePositionMs(timelineUs: Long): Long {
    val maxLocalUs = (durationUs - 1L).coerceAtLeast(0L)
    val localUs = (timelineUs - timelineStartUs).coerceIn(0L, maxLocalUs)
    return (sourceInUs + localUs).coerceAtLeast(0L) / 1000L
}

private fun TimelineClip.previewTimelinePositionUs(sourcePositionMs: Long): Long {
    val sourceUs = sourcePositionMs.coerceAtLeast(0L) * 1000L
    val localUs = (sourceUs - sourceInUs).coerceIn(0L, durationUs.coerceAtLeast(0L))
    return timelineStartUs + localUs
}

private enum class WorkspaceV5(val label: String, val icon: ImageVector) {
    EDIT("Edit", Icons.Rounded.ContentCut),
    AUDIO("Audio", Icons.Rounded.Audiotrack),
    MEDIA("Media", Icons.Rounded.VideoLibrary),
    CORRECTION("Correction", Icons.Rounded.Tune),
    COLOR("Color", Icons.Rounded.Palette),
    EFFECTS("Effects", Icons.Rounded.AutoAwesome),
    NODES("Nodes", Icons.Rounded.AccountTree),
}

/**
 * Stable editor preview architecture:
 * - exactly one topmost video clip is decoded by ExoPlayer at any time;
 * - all active A tracks are mixed by a separate audio-only CompositionPlayer;
 * - export still uses the shared Media3 Transformer composition.
 *
 * Video preview deliberately decodes the full source and seeks to source timestamps instead of
 * using MediaItem clipping. A successful document-picker import rotates to a fresh video-player
 * generation. This avoids carrying an OEM MediaCodec/Surface state that became wedged while the
 * picker owned the foreground window. Audio composition rebuilds are independent and
 * cancellation-safe.
 */
@UnstableApi
@Composable
fun DigitorEditorScreenV5(vm: EditorViewModelV4 = viewModel()) {
    val state by vm.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val router = remember { ProcessingRouter(context.applicationContext) }
    val compositionBuilder = remember { Media3CompositionBuilder() }
    var videoPlayerGeneration by remember { mutableStateOf(0) }
    val videoPlayer = remember(videoPlayerGeneration) {
        ExoPlayer.Builder(context.applicationContext)
            .setDetachSurfaceTimeoutMs(350L)
            .setReleaseTimeoutMs(500L)
            .build()
            .apply {
                volume = 0f // A tracks own preview audio; never double-play embedded video audio.
            }
    }
    val audioPlayer = remember {
        CompositionPlayer.Builder(context.applicationContext).build()
    }
    val selectedClip = state.project.clip(state.selectedClipId)

    var workspace by remember { mutableStateOf(WorkspaceV5.EDIT) }
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
    var loadedPreviewGeneration by remember { mutableStateOf(-1) }
    var audioPreviewReady by remember { mutableStateOf(false) }
    var previousProjectDurationUs by remember { mutableStateOf(state.project.durationUs) }
    var showExportDialog by remember { mutableStateOf(false) }
    var exportName by remember { mutableStateOf("Digitor_${System.currentTimeMillis()}") }
    var exportFraction by remember { mutableStateOf<Float?>(null) }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var previewStatus by remember { mutableStateOf<String?>(null) }

    val previewClip = state.project.topmostVideoClipAt(cursorUs)
    val hasMedia = state.project.hasPlayableMedia()
    val hasVideo = state.project.hasPlayableVideo()
    val hasAudio = state.project.tracks.any {
        it.kind == TrackKind.AUDIO && !it.muted && it.clips.isNotEmpty()
    }
    val audioPreviewKey = state.project.tracks
        .filter { it.kind == TrackKind.AUDIO }
        .hashCode()

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            isPlaying = false
            runCatching { audioPlayer.pause() }
            pendingSeekUs = cursorUs
            videoPlayerGeneration += 1
            Log.d(
                VIDEO_PREVIEW_TAG,
                "Fresh video player requested after media import; generation=$videoPlayerGeneration",
            )
            vm.importUris(uris)
        }
    }
    fun launchImport() = mediaPicker.launch(vm.selectedImportMimeTypes())

    DisposableEffect(videoPlayer, videoPlayerGeneration) {
        val generation = videoPlayerGeneration
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.d(
                    VIDEO_PREVIEW_TAG,
                    "generation=$generation state=$playbackState mediaItems=${videoPlayer.mediaItemCount} " +
                        "position=${videoPlayer.currentPosition}ms",
                )
            }

            override fun onRenderedFirstFrame() {
                Log.d(
                    VIDEO_PREVIEW_TAG,
                    "generation=$generation rendered first frame at ${videoPlayer.currentPosition}ms",
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(
                    VIDEO_PREVIEW_TAG,
                    "generation=$generation player error: ${error.errorCodeName}: ${error.message}",
                    error,
                )
            }
        }
        videoPlayer.addListener(listener)

        onDispose {
            videoPlayer.removeListener(listener)
            val retiredPlayer = videoPlayer
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { retiredPlayer.release() }
                    .onFailure { error ->
                        Log.w(VIDEO_PREVIEW_TAG, "Retired generation=$generation release failed", error)
                    }
            }, 900L)
        }
    }

    DisposableEffect(audioPlayer) {
        onDispose { audioPlayer.release() }
    }

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

    LaunchedEffect(state.project.durationUs) {
        val durationUs = state.project.durationUs.coerceAtLeast(0L)
        if (durationUs < previousProjectDurationUs && cursorUs >= durationUs) {
            cursorUs = if (durationUs > 0L) durationUs - 1L else 0L
            pendingSeekUs = cursorUs.takeIf { durationUs > 0L }
        }
        previousProjectDurationUs = durationUs
    }

    LaunchedEffect(
        videoPlayerGeneration,
        previewClip?.id,
        previewClip?.uri,
        previewClip?.sourceInUs,
        previewClip?.sourceOutUs,
        previewClip?.nodeGraph,
        previewClip?.transform,
    ) {
        val clip = previewClip
        if (clip == null) {
            videoPlayer.stop()
            videoPlayer.clearMediaItems()
            videoPlayer.setVideoEffects(emptyList())
            loadedPreviewClipId = null
            loadedPreviewUri = null
            loadedPreviewSourceInUs = null
            loadedPreviewSourceOutUs = null
            loadedPreviewGradeHash = null
            loadedPreviewGeneration = videoPlayerGeneration
            pendingSeekUs = null
            return@LaunchedEffect
        }

        val sourceChanged = loadedPreviewGeneration != videoPlayerGeneration ||
            loadedPreviewClipId != clip.id ||
            loadedPreviewUri != clip.uri ||
            loadedPreviewSourceInUs != clip.sourceInUs ||
            loadedPreviewSourceOutUs != clip.sourceOutUs
        val gradeHash = 31 * clip.nodeGraph.hashCode() + clip.transform.hashCode()
        val gradeChanged = loadedPreviewGradeHash != gradeHash
        if (!sourceChanged && gradeChanged) delay(120)

        val requestedTimelineUs = pendingSeekUs ?: cursorUs
        val sourcePositionMs = if (sourceChanged) {
            clip.previewSourcePositionMs(requestedTimelineUs)
        } else {
            val minMs = clip.sourceInUs.coerceAtLeast(0L) / 1000L
            val maxMs = (clip.sourceOutUs - 1L).coerceAtLeast(clip.sourceInUs) / 1000L
            videoPlayer.currentPosition.coerceIn(minMs, maxMs)
        }
        val resumePlayback = isPlaying
        val effects = withContext(Dispatchers.Default) {
            SharedVideoPipeline.previewEffectsFor(clip)
        }

        Log.d(
            VIDEO_PREVIEW_TAG,
            "Loading generation=$videoPlayerGeneration clip=${clip.id} source=${sourcePositionMs}ms",
        )
        videoPlayer.stop()
        videoPlayer.clearMediaItems()
        videoPlayer.setVideoEffects(effects)
        videoPlayer.setMediaItem(MediaItem.fromUri(clip.uri))
        videoPlayer.prepare()
        videoPlayer.seekTo(sourcePositionMs)
        if (resumePlayback) videoPlayer.play()

        loadedPreviewClipId = clip.id
        loadedPreviewUri = clip.uri
        loadedPreviewSourceInUs = clip.sourceInUs
        loadedPreviewSourceOutUs = clip.sourceOutUs
        loadedPreviewGradeHash = gradeHash
        loadedPreviewGeneration = videoPlayerGeneration
        pendingSeekUs = null
    }

    LaunchedEffect(audioPreviewKey, hasAudio) {
        audioPreviewReady = false
        runCatching {
            audioPlayer.pause()
            audioPlayer.stop()
        }
        if (!hasAudio) {
            previewStatus = null
            return@LaunchedEffect
        }

        val projectSnapshot = state.project
        val resumePlayback = isPlaying
        delay(140)
        val maxStartUs = (projectSnapshot.durationUs - 1L).coerceAtLeast(0L)
        val startPositionMs = cursorUs.coerceIn(0L, maxStartUs) / 1000L
        try {
            val composition = withContext(Dispatchers.Default) {
                compositionBuilder.buildAudioPreview(projectSnapshot)
            }
            audioPlayer.stop()
            audioPlayer.setComposition(composition, startPositionMs)
            audioPlayer.prepare()
            if (resumePlayback) audioPlayer.play()
            audioPreviewReady = true
            previewStatus = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            runCatching { audioPlayer.stop() }
            audioPreviewReady = false
            previewStatus = "Audio preview: ${error.message ?: "unavailable"}"
        }
    }

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
                if (hasAudio && audioPreviewReady) audioPlayer.seekTo(cursorUs / 1000L)
                pendingSeekUs = cursorUs
                if (loadedPreviewClipId == clip.id) {
                    videoPlayer.seekTo(clip.previewSourcePositionMs(cursorUs))
                    pendingSeekUs = null
                }
            }
        }
        previewAnchorClipId = clip.id
        previewAnchorTimelineStartUs = clip.timelineStartUs
    }

    LaunchedEffect(videoPlayer, audioPlayer, isPlaying, hasAudio, audioPreviewReady, previewClip?.id) {
        while (isPlaying) {
            val durationUs = state.project.durationUs.coerceAtLeast(0L)
            val nextUs = when {
                hasAudio && audioPreviewReady -> audioPlayer.currentPosition.coerceAtLeast(0L) * 1000L
                previewClip != null -> previewClip.previewTimelinePositionUs(videoPlayer.currentPosition)
                else -> cursorUs + 70_000L
            }.coerceIn(0L, durationUs)

            cursorUs = nextUs

            if (hasAudio && audioPreviewReady && previewClip != null && loadedPreviewClipId == previewClip.id) {
                val desiredMs = previewClip.previewSourcePositionMs(nextUs)
                if (abs(videoPlayer.currentPosition - desiredMs) > 180L) {
                    videoPlayer.seekTo(desiredMs)
                }
            }

            if (durationUs > 0L && nextUs >= durationUs) {
                videoPlayer.pause()
                audioPlayer.pause()
                isPlaying = false
                break
            }
            delay(70)
        }
    }

    fun seekTimeline(requestUs: Long) {
        val target = requestUs.coerceIn(0L, state.project.durationUs.coerceAtLeast(0L))
        cursorUs = target
        if (hasAudio && audioPreviewReady) audioPlayer.seekTo(target / 1000L)

        val activeVideo = state.project.topmostVideoClipAt(target)
        if (activeVideo != null) {
            pendingSeekUs = target
            if (activeVideo.id != selectedClip?.id) vm.selectClip(activeVideo.id)
            if (activeVideo.id == loadedPreviewClipId) {
                videoPlayer.seekTo(activeVideo.previewSourcePositionMs(target))
                pendingSeekUs = null
            }
        } else {
            pendingSeekUs = null
        }
    }

    fun togglePlayback() {
        if (!hasMedia) return
        if (isPlaying) {
            isPlaying = false
            videoPlayer.pause()
            audioPlayer.pause()
        } else {
            if (cursorUs >= state.project.durationUs && state.project.durationUs > 0L) {
                seekTimeline(0L)
            }
            isPlaying = true
            if (previewClip != null) videoPlayer.play()
            if (hasAudio && audioPreviewReady) audioPlayer.play()
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
                    Text("File type", fontSize = 10.sp, color = E5Muted)
                    AssistChip(onClick = {}, label = { Text("MP4 · H.264 / AAC") })
                    Text("Choose location on the next screen.", fontSize = 9.sp, color = E5Muted)
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

    Surface(Modifier.fillMaxSize(), color = E5Shell) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            TopBarV5(
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
                        color = E5Muted,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            PreviewV5(
                activeVideoClip = previewClip,
                hasVideo = hasVideo,
                player = videoPlayer,
                generation = videoPlayerGeneration,
                onImport = ::launchImport,
                qualifierPickerActive = state.qualifierPickerActive,
                onPickColor = { x, y, width, height ->
                    previewClip?.let { clip ->
                        if (clip.id != selectedClip?.id) vm.selectClip(clip.id)
                        vm.pickQualifierFromPreview(cursorUs, x, y, width, height)
                    }
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            TransportV5(
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
                    WorkspaceV5.EDIT -> EditWorkspaceV5(
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
                    WorkspaceV5.COLOR -> ColorWorkspaceV4(selectedClip, vm, Modifier.fillMaxSize())
                    WorkspaceV5.NODES -> NodeGraphV4(selectedClip, vm, Modifier.fillMaxSize())
                    WorkspaceV5.CORRECTION -> CorrectionWorkspaceV4(selectedClip, vm, Modifier.fillMaxSize())
                    WorkspaceV5.EFFECTS -> EffectsWorkspaceV4(selectedClip, vm, Modifier.fillMaxSize())
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
            WorkspaceBarV5(
                selected = workspace,
                onSelected = {
                    workspace = it
                    val editsClip = it == WorkspaceV5.EDIT ||
                        it == WorkspaceV5.COLOR ||
                        it == WorkspaceV5.CORRECTION ||
                        it == WorkspaceV5.NODES ||
                        it == WorkspaceV5.EFFECTS
                    if (editsClip && previewClip != null && previewClip.id != selectedClip?.id) {
                        vm.selectClip(previewClip.id)
                    }
                    if (it != WorkspaceV5.COLOR && state.qualifierPickerActive) {
                        vm.setQualifierPickerActive(false)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(66.dp),
            )
        }
    }
}

@Composable
private fun TopBarV5(
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
            Text(
                title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(E5Accent))
                Spacer(Modifier.width(5.dp))
                Text(
                    if (status == "Ready") "Stable preview · mixed audio" else status,
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
private fun PreviewV5(
    activeVideoClip: TimelineClip?,
    hasVideo: Boolean,
    player: Player,
    generation: Int,
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
                Icon(
                    Icons.Rounded.AddPhotoAlternate,
                    null,
                    tint = Color.White.copy(alpha = .35f),
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text("No video media", color = Color.White.copy(alpha = .55f), fontSize = 12.sp)
                TextButton(onClick = onImport) { Text("Import media") }
            }
        }
        Text(
            "Top-track Preview",
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
                .background(Color.Black.copy(alpha = .6f), RoundedCornerShape(5.dp))
                .padding(horizontal = 7.dp, vertical = 4.dp),
            fontSize = 9.sp,
            color = Color.White.copy(alpha = .72f),
        )

        if (qualifierPickerActive && activeVideoClip != null) {
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
                    Icon(Icons.Rounded.Colorize, null, modifier = Modifier.size(15.dp), tint = E5Accent)
                    Spacer(Modifier.width(5.dp))
                    Text("Tap the color to qualify", fontSize = 9.sp, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun TransportV5(
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
        Text(timeV5(cursorUs), color = E5Muted, fontSize = 9.sp, modifier = Modifier.width(66.dp))
        IconButton(onClick = onBack, enabled = enabled, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Rounded.Replay10, null, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onPlayPause, enabled = enabled, modifier = Modifier.size(38.dp)) {
            Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, modifier = Modifier.size(23.dp))
        }
        IconButton(onClick = onForward, enabled = enabled, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Rounded.Forward10, null, modifier = Modifier.size(18.dp))
        }
        Text(timeV5(durationUs), color = E5Muted, fontSize = 9.sp, modifier = Modifier.width(66.dp))
    }
}

@Composable
private fun WorkspaceBarV5(
    selected: WorkspaceV5,
    onSelected: (WorkspaceV5) -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier.background(Color(0xFF0A0A0D)).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        WorkspaceV5.entries.forEach { item ->
            val active = item == selected
            Column(
                Modifier.width(68.dp).fillMaxHeight().clickable { onSelected(item) }
                    .background(if (active) E5Accent.copy(alpha = .10f) else Color.Transparent),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    item.icon,
                    item.label,
                    modifier = Modifier.size(18.dp),
                    tint = if (active) E5Accent else Color.White.copy(alpha = .55f),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    item.label,
                    fontSize = 8.sp,
                    color = if (active) E5Accent else Color.White.copy(alpha = .55f),
                )
            }
        }
    }
}

private fun timeV5(us: Long): String {
    val totalSeconds = us.coerceAtLeast(0L) / US_PER_SECOND
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%02d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}
