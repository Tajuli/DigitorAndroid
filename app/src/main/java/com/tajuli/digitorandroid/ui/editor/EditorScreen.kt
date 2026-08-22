package com.tajuli.digitorandroid.ui.editor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.US_PER_SECOND
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.roundToLong

private const val TIMELINE_PX_PER_SECOND = 46f
private val Shell = Color(0xFF08080A)
private val PreviewBlack = Color(0xFF030304)
private val Panel = Color(0xFF0B0B0F)
private val Raised = Color(0xFF17171C)
private val Divider = Color(0xFF24242A)
private val Muted = Color(0xFF8A8A92)
private val Playhead = Color(0xFFFF4D4D)
private val VideoClip = Color(0xFF385B78)
private val AudioClip = Color(0xFF315F57)

private data class WorkspaceItem(
    val label: String,
    val icon: ImageVector,
    val features: List<String>,
)

private val workspaces = listOf(
    WorkspaceItem("Edit", Icons.Rounded.ContentCut, listOf("Trim", "Split", "Delete", "Speed")),
    WorkspaceItem("Audio", Icons.Rounded.Audiotrack, listOf("Volume", "Fade", "EQ", "Pan")),
    WorkspaceItem("Media", Icons.Rounded.VideoLibrary, listOf("Import", "Replace", "Relink")),
    WorkspaceItem("Transform", Icons.Rounded.OpenWith, listOf("Position", "Scale", "Rotate", "Crop")),
    WorkspaceItem("Correction", Icons.Rounded.Tune, listOf("Exposure", "Contrast", "Saturation", "Temp", "Tint")),
    WorkspaceItem("Color", Icons.Rounded.Palette, listOf("Wheels", "Curves", "Qualifier", "Nodes")),
    WorkspaceItem("Looks", Icons.Rounded.Star, listOf("LUT", "Film", "Creative")),
    WorkspaceItem("Effects", Icons.Rounded.AutoAwesome, listOf("Blur", "Sharpen", "Glow")),
    WorkspaceItem("Masks", Icons.Rounded.CropFree, listOf("Circle", "Linear", "Track")),
    WorkspaceItem("Nodes", Icons.Rounded.AccountTree, listOf("Node Graph", "Serial", "Parallel")),
    WorkspaceItem("Playback", Icons.Rounded.PlayArrow, listOf("Loop", "Speed", "Quality")),
    WorkspaceItem("Scopes", Icons.Rounded.Equalizer, listOf("Waveform", "Parade", "Vectorscope")),
    WorkspaceItem("Performance", Icons.Rounded.Speed, listOf("Proxy", "Cache", "Quality")),
    WorkspaceItem("Engine", Icons.Rounded.Memory, listOf("Backend", "GPU", "CPU")),
)

@UnstableApi
@Composable
fun EditorScreen(vm: EditorViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val compact = configuration.screenHeightDp < 650
    val timelineHeight = if (compact) 192.dp else 224.dp
    val featureHeight = if (compact) 58.dp else 64.dp
    val workspaceHeight = if (compact) 66.dp else 72.dp

    val selectedClip = state.project.tracks
        .flatMap { it.clips }
        .firstOrNull { it.id == state.selectedClipId }

    val player = remember { ExoPlayer.Builder(context).build() }
    var isPlaying by remember { mutableStateOf(false) }
    var playerPositionMs by remember { mutableStateOf(0L) }
    var selectedWorkspace by remember { mutableStateOf(workspaces.first()) }
    var selectedFeature by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> -> vm.importUris(uris) }

    LaunchedEffect(selectedClip?.id) {
        player.stop()
        player.clearMediaItems()
        playerPositionMs = 0L
        if (selectedClip != null) {
            val mediaItem = MediaItem.Builder()
                .setUri(selectedClip.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(selectedClip.sourceInUs / 1000L)
                        .setEndPositionMs(selectedClip.sourceOutUs / 1000L)
                        .build(),
                )
                .build()
            player.setMediaItem(mediaItem)
            player.prepare()
        }
    }

    LaunchedEffect(player) {
        while (true) {
            isPlaying = player.isPlaying
            playerPositionMs = player.currentPosition.coerceAtLeast(0L)
            delay(100)
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    val playheadUs = selectedClip?.let {
        it.timelineStartUs + playerPositionMs * 1000L
    } ?: 0L

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Shell,
    ) {
        Column(Modifier.fillMaxSize()) {
            DigitorTopBar(
                title = selectedClip?.label ?: "New project",
                status = state.status,
                exporting = state.exportFraction != null && state.exportFraction!! < 1f,
                exportFraction = state.exportFraction,
                onImport = { picker.launch(arrayOf("video/*", "audio/*")) },
                onExport = vm::export,
            )

            if (state.exportFraction != null && state.exportFraction!! < 1f) {
                LinearProgressIndicator(
                    progress = { state.exportFraction!!.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                )
            }

            Column(Modifier.weight(1f)) {
                PreviewPanel(
                    clip = selectedClip,
                    player = player,
                    onImport = { picker.launch(arrayOf("video/*", "audio/*")) },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )

                TransportBar(
                    isPlaying = isPlaying,
                    positionMs = playerPositionMs,
                    durationMs = selectedClip?.durationUs?.div(1000L) ?: 0L,
                    enabled = selectedClip != null,
                    onPrevious = { player.seekTo(0) },
                    onBack = { player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L)) },
                    onPlayPause = {
                        if (player.isPlaying) player.pause() else player.play()
                    },
                    onForward = {
                        val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                        player.seekTo((player.currentPosition + 10_000L).coerceAtMost(duration))
                    },
                )

                Box(Modifier.fillMaxWidth().height(timelineHeight)) {
                    if (selectedFeature == null) {
                        ProfessionalTimeline(
                            project = state.project,
                            selectedTrackId = state.selectedTrackId,
                            selectedClipId = state.selectedClipId,
                            playheadUs = playheadUs,
                            onSelectTrack = vm::selectTrack,
                            onSelectClip = vm::selectClip,
                            onMoveClip = vm::moveClip,
                            onAddVideoTrack = { vm.addTrack(TrackKind.VIDEO) },
                            onAddAudioTrack = { vm.addTrack(TrackKind.AUDIO) },
                            onImport = { picker.launch(arrayOf("video/*", "audio/*")) },
                            onEdit = {
                                selectedWorkspace = workspaces.first()
                                selectedFeature = null
                            },
                        )
                    } else {
                        FeatureInspector(
                            workspace = selectedWorkspace.label,
                            feature = selectedFeature!!,
                            onClose = { selectedFeature = null },
                        )
                    }
                }

                FeatureRibbon(
                    features = selectedWorkspace.features,
                    selected = selectedFeature,
                    onSelected = { selectedFeature = it },
                    modifier = Modifier.fillMaxWidth().height(featureHeight),
                )

                WorkspaceToolbar(
                    selected = selectedWorkspace,
                    onSelected = {
                        selectedWorkspace = it
                        selectedFeature = null
                    },
                    modifier = Modifier.fillMaxWidth().height(workspaceHeight),
                )
            }
        }
    }
}

@Composable
private fun DigitorTopBar(
    title: String,
    status: String,
    exporting: Boolean,
    exportFraction: Float?,
    onImport: () -> Unit,
    onExport: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onImport, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Rounded.Add, contentDescription = "Import media")
        }

        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = if (status == "Ready") "DigitorEngine" else status,
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.54f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Button(
            onClick = onExport,
            enabled = !exporting,
            modifier = Modifier.height(34.dp),
            shape = RoundedCornerShape(7.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
        ) {
            Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
            Text(
                if (exporting && exportFraction != null) "${(exportFraction * 100).toInt()}%" else "Export",
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun PreviewPanel(
    clip: TimelineClip?,
    player: ExoPlayer,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(PreviewBlack), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            if (clip == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Rounded.VideoFile,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.34f),
                        modifier = Modifier.size(42.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("No media loaded", color = Color.White.copy(alpha = 0.54f), fontSize = 12.sp)
                    TextButton(onClick = onImport) {
                        Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Import media")
                    }
                }
            } else {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            this.player = player
                            setShutterBackgroundColor(android.graphics.Color.BLACK)
                        }
                    },
                    update = { it.player = player },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        PreviewBadge(
            text = "Preview",
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
        )
        PreviewBadge(
            text = "Engine",
            leading = Icons.Rounded.Bolt,
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
        )
    }
}

@Composable
private fun PreviewBadge(
    text: String,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.58f), RoundedCornerShape(5.dp))
            .padding(horizontal = 7.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Icon(leading, contentDescription = null, modifier = Modifier.size(12.dp), tint = Color.White.copy(alpha = .7f))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, fontSize = 9.sp, color = Color.White.copy(alpha = .68f))
    }
}

@Composable
private fun TransportBar(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onPrevious: () -> Unit,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onForward: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(42.dp).background(Color(0xFF0D0D11)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        TransportButton(Icons.Rounded.SkipPrevious, enabled, onPrevious)
        TransportButton(Icons.Rounded.Replay10, enabled, onBack)
        IconButton(
            onClick = onPlayPause,
            enabled = enabled,
            modifier = Modifier.size(38.dp),
        ) {
            Icon(
                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = "Play pause",
                modifier = Modifier.size(25.dp),
                tint = if (enabled) Color.White else Color.White.copy(alpha = .25f),
            )
        }
        TransportButton(Icons.Rounded.Forward10, enabled, onForward)
        Spacer(Modifier.width(8.dp))
        Text(
            "${clock(positionMs)} / ${clock(durationMs)}",
            fontSize = 9.sp,
            color = Color.White.copy(alpha = .48f),
        )
    }
    HorizontalDivider(color = Divider, thickness = 1.dp)
}

@Composable
private fun TransportButton(icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(34.dp)) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (enabled) Color.White.copy(alpha = .75f) else Color.White.copy(alpha = .22f),
        )
    }
}

@Composable
private fun ProfessionalTimeline(
    project: TimelineProject,
    selectedTrackId: String?,
    selectedClipId: String?,
    playheadUs: Long,
    onSelectTrack: (String) -> Unit,
    onSelectClip: (String) -> Unit,
    onMoveClip: (String, String, Long) -> Unit,
    onAddVideoTrack: () -> Unit,
    onAddAudioTrack: () -> Unit,
    onImport: () -> Unit,
    onEdit: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Panel)
            .border(1.dp, Divider),
    ) {
        TimelineToolbar(
            playheadUs = playheadUs,
            onAddVideoTrack = onAddVideoTrack,
            onAddAudioTrack = onAddAudioTrack,
            onImport = onImport,
            onEdit = onEdit,
        )

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val headerWidth = 54.dp
            val rulerHeight = 24.dp
            val trackHeight = 34.dp
            val viewport = (maxWidth - headerWidth).coerceAtLeast(1.dp)
            val seconds = (project.durationUs / US_PER_SECOND.toFloat()).coerceAtLeast(10f)
            val contentWidth = maxOf(viewport, (seconds * TIMELINE_PX_PER_SECOND).dp)
            val scroll = rememberScrollState()

            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(headerWidth).fillMaxHeight()) {
                    Box(
                        Modifier.fillMaxWidth().height(rulerHeight)
                            .background(Color(0xFF111116))
                            .border(1.dp, Color(0xFF2B2B31)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("TC", fontSize = 8.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = .35f))
                    }
                    project.tracks.forEach { track ->
                        TrackHeader(
                            track = track,
                            selected = track.id == selectedTrackId,
                            height = trackHeight,
                            onClick = { onSelectTrack(track.id) },
                        )
                    }
                }

                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .horizontalScroll(scroll)
                        .clipToBounds(),
                ) {
                    Box(Modifier.width(contentWidth).fillMaxHeight()) {
                        Column(Modifier.fillMaxSize()) {
                            TimelineRuler(contentWidth = contentWidth, seconds = seconds, height = rulerHeight)
                            project.tracks.forEach { track ->
                                TrackLane(
                                    track = track,
                                    selectedClipId = selectedClipId,
                                    contentWidth = contentWidth,
                                    height = trackHeight,
                                    onSelectClip = onSelectClip,
                                    onMoveClip = onMoveClip,
                                )
                            }
                        }

                        val safePlayhead = playheadUs.coerceIn(0L, (seconds * US_PER_SECOND).toLong())
                        val playheadX = (safePlayhead / US_PER_SECOND.toFloat() * TIMELINE_PX_PER_SECOND).dp
                        Box(
                            Modifier.offset(x = playheadX)
                                .width(1.5.dp)
                                .fillMaxHeight()
                                .background(Playhead),
                        )
                        Box(
                            Modifier.offset(x = playheadX - 5.dp)
                                .size(width = 11.dp, height = 7.dp)
                                .background(Playhead, RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineToolbar(
    playheadUs: Long,
    onAddVideoTrack: () -> Unit,
    onAddAudioTrack: () -> Unit,
    onImport: () -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(30.dp).background(Color(0xFF0E0E12)).padding(start = 9.dp, end = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("▤", fontSize = 13.sp, color = Color.White.copy(alpha = .5f))
        Spacer(Modifier.width(5.dp))
        Text("Timeline", fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = .6f))
        Spacer(Modifier.width(8.dp))
        Text(
            timelineClock(playheadUs),
            modifier = Modifier.background(Raised, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
            fontSize = 8.sp,
            color = Color.White.copy(alpha = .54f),
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onAddVideoTrack, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 5.dp)) {
            Text("+V", fontSize = 9.sp)
        }
        TextButton(onClick = onAddAudioTrack, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 5.dp)) {
            Text("+A", fontSize = 9.sp)
        }
        IconButton(onClick = onImport, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = "Import", modifier = Modifier.size(16.dp))
        }
        TextButton(onClick = onEdit, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 5.dp)) {
            Icon(Icons.Rounded.ContentCut, contentDescription = null, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(3.dp))
            Text("Edit", fontSize = 8.sp)
        }
    }
}

@Composable
private fun TrackHeader(
    track: TimelineTrack,
    selected: Boolean,
    height: Dp,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(height)
            .background(if (selected) Color(0xFF1B2423) else Color(0xFF121217))
            .border(0.5.dp, Divider)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(3.dp).fillMaxHeight()
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            track.name,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) Color.White.copy(alpha = .82f) else Color.White.copy(alpha = .46f),
        )
        Spacer(Modifier.weight(1f))
        Icon(
            if (track.kind == TrackKind.VIDEO) Icons.Rounded.VideoLibrary else Icons.Rounded.Audiotrack,
            contentDescription = null,
            modifier = Modifier.size(11.dp),
            tint = Color.White.copy(alpha = if (selected) .42f else .22f),
        )
        Spacer(Modifier.width(5.dp))
    }
}

@Composable
private fun TimelineRuler(contentWidth: Dp, seconds: Float, height: Dp) {
    val tickCount = ceil(seconds.toDouble()).toInt().coerceAtMost(180)
    Row(Modifier.width(contentWidth).height(height).background(Color(0xFF111116))) {
        repeat(tickCount + 1) { second ->
            Box(
                Modifier.width(TIMELINE_PX_PER_SECOND.dp).fillMaxHeight(),
                contentAlignment = Alignment.TopStart,
            ) {
                Box(Modifier.width(1.dp).height(7.dp).background(Color.White.copy(alpha = .18f)))
                Text("${second}s", modifier = Modifier.padding(start = 3.dp, top = 8.dp), fontSize = 7.sp, color = Color.White.copy(alpha = .32f))
            }
        }
    }
}

@Composable
private fun TrackLane(
    track: TimelineTrack,
    selectedClipId: String?,
    contentWidth: Dp,
    height: Dp,
    onSelectClip: (String) -> Unit,
    onMoveClip: (String, String, Long) -> Unit,
) {
    Box(
        Modifier.width(contentWidth).height(height)
            .background(if (track.kind == TrackKind.VIDEO) Color(0xFF0F1115) else Color(0xFF0E1312))
            .border(0.5.dp, Divider),
    ) {
        track.clips.forEach { clip ->
            TimelineClipBox(
                track = track,
                clip = clip,
                selected = clip.id == selectedClipId,
                onSelectClip = onSelectClip,
                onMoveClip = onMoveClip,
            )
        }
    }
}

@Composable
private fun TimelineClipBox(
    track: TimelineTrack,
    clip: TimelineClip,
    selected: Boolean,
    onSelectClip: (String) -> Unit,
    onMoveClip: (String, String, Long) -> Unit,
) {
    val startDp = (clip.timelineStartUs / US_PER_SECOND.toFloat() * TIMELINE_PX_PER_SECOND).dp
    val widthDp = (clip.durationUs / US_PER_SECOND.toFloat() * TIMELINE_PX_PER_SECOND).coerceAtLeast(28f).dp
    val shape = RoundedCornerShape(4.dp)

    Box(
        modifier = Modifier.offset(x = startDp, y = 3.dp)
            .width(widthDp).height(28.dp)
            .background(if (track.kind == TrackKind.VIDEO) VideoClip else AudioClip, shape)
            .then(
                if (selected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, shape)
                else Modifier,
            )
            .pointerInput(clip.id) {
                var carriedPx = 0f
                detectDragGestures(
                    onDragStart = { onSelectClip(clip.id) },
                    onDragEnd = { carriedPx = 0f },
                ) { change, dragAmount ->
                    change.consume()
                    carriedPx += dragAmount.x
                    if (kotlin.math.abs(carriedPx) >= 3f) {
                        val secondsDelta = carriedPx / TIMELINE_PX_PER_SECOND
                        val snappedUs = (secondsDelta * US_PER_SECOND / 50_000f).roundToLong() * 50_000L
                        if (snappedUs != 0L) {
                            onMoveClip(track.id, clip.id, snappedUs)
                            carriedPx = 0f
                        }
                    }
                }
            }
            .clickable { onSelectClip(clip.id) }
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            clip.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 8.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = .82f),
        )
    }
}

@Composable
private fun FeatureRibbon(
    features: List<String>,
    selected: String?,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.background(Color(0xFF0C0C10)).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        features.forEach { feature ->
            val active = feature == selected
            FilledTonalButton(
                onClick = { onSelected(feature) },
                modifier = Modifier.height(34.dp),
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (active) MaterialTheme.colorScheme.primary.copy(alpha = .18f) else Raised,
                    contentColor = if (active) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .7f),
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 11.dp),
            ) {
                Text(feature, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun WorkspaceToolbar(
    selected: WorkspaceItem,
    onSelected: (WorkspaceItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.background(Color(0xFF09090C)).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        workspaces.forEach { item ->
            val active = item == selected
            Column(
                modifier = Modifier.width(68.dp).fillMaxHeight()
                    .clickable { onSelected(item) }
                    .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = .08f) else Color.Transparent),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    item.icon,
                    contentDescription = item.label,
                    modifier = Modifier.size(20.dp),
                    tint = if (active) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .5f),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    item.label,
                    fontSize = 8.sp,
                    maxLines = 1,
                    color = if (active) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .5f),
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier.width(24.dp).height(2.dp)
                        .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}

@Composable
private fun FeatureInspector(
    workspace: String,
    feature: String,
    onClose: () -> Unit,
) {
    var amount by remember(feature) { mutableStateOf(0f) }
    var mix by remember(feature) { mutableStateOf(1f) }

    Column(Modifier.fillMaxSize().background(Panel)) {
        Row(
            Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(workspace, fontSize = 10.sp, color = Muted)
            Text("  /  ", fontSize = 10.sp, color = Muted)
            Text(feature, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Rounded.Close, contentDescription = "Close", modifier = Modifier.size(17.dp))
            }
        }
        HorizontalDivider(color = Divider)
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp)) {
            InspectorSlider("Amount", amount, { amount = it }, -1f..1f)
            InspectorSlider("Mix", mix, { mix = it }, 0f..1f)
            Text(
                "Digitor-style inspector surface. Native processing controls will bind here feature-by-feature.",
                fontSize = 9.sp,
                color = Color.White.copy(alpha = .38f),
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
private fun InspectorSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(64.dp), fontSize = 9.sp, color = Color.White.copy(alpha = .58f))
        Slider(value = value, onValueChange = onValueChange, valueRange = range, modifier = Modifier.weight(1f))
        Text("%.2f".format(value), modifier = Modifier.width(42.dp), fontSize = 8.sp, color = Color.White.copy(alpha = .45f))
    }
}

private fun clock(ms: Long): String {
    val safe = ms.coerceAtLeast(0L)
    val totalSeconds = safe / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d".format(minutes, seconds)
}

private fun timelineClock(us: Long): String {
    val totalMs = us.coerceAtLeast(0L) / 1000L
    val minutes = totalMs / 60_000L
    val seconds = (totalMs / 1000L) % 60L
    val millis = totalMs % 1000L
    return "%02d:%02d.%03d".format(minutes, seconds, millis)
}
