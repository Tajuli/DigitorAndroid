package com.tajuli.digitorandroid.ui.editor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.tajuli.digitorandroid.editor.model.ColorNode
import com.tajuli.digitorandroid.editor.model.NodeCorrections
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.US_PER_SECOND
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToLong

private const val EDITOR_PX_PER_SECOND = 46f
private val EditorShell = Color(0xFF08080A)
private val EditorPanel = Color(0xFF0B0B0F)
private val EditorRaised = Color(0xFF17171C)
private val EditorDivider = Color(0xFF24242A)
private val EditorMuted = Color(0xFF8A8A92)
private val EditorPlayhead = Color(0xFFFF4D4D)
private val EditorVideo = Color(0xFF385B78)
private val EditorAudio = Color(0xFF315F57)
private val EditorAccent = Color(0xFF30E0C3)

private enum class EditorWorkspace(val label: String, val icon: ImageVector) {
    EDIT("Edit", Icons.Rounded.ContentCut),
    AUDIO("Audio", Icons.Rounded.Audiotrack),
    MEDIA("Media", Icons.Rounded.VideoLibrary),
    CORRECTION("Correction", Icons.Rounded.Tune),
    EFFECTS("Effects", Icons.Rounded.AutoAwesome),
    NODES("Nodes", Icons.Rounded.AccountTree),
}

@UnstableApi
@Composable
fun DigitorEditorScreen(vm: EditorViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val selectedClip = state.project.clip(state.selectedClipId)
    val player = remember { ExoPlayer.Builder(context).build() }
    var workspace by remember { mutableStateOf(EditorWorkspace.EDIT) }
    var isPlaying by remember { mutableStateOf(false) }
    var playerPositionMs by remember { mutableStateOf(0L) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> -> vm.importUris(uris) }

    LaunchedEffect(selectedClip?.id) {
        player.stop()
        player.clearMediaItems()
        playerPositionMs = 0L
        if (selectedClip != null) {
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

    DisposableEffect(player) { onDispose { player.release() } }

    val playheadUs = selectedClip?.let {
        it.timelineStartUs + playerPositionMs * 1000L
    } ?: 0L

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = EditorShell,
    ) {
        Column(
            Modifier.fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            EditorTopBar(
                title = selectedClip?.label ?: "New project",
                status = state.status,
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

            PreviewSurface(
                clip = selectedClip,
                player = player,
                onImport = { picker.launch(arrayOf("video/*", "audio/*")) },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            TransportStrip(
                enabled = selectedClip != null,
                isPlaying = isPlaying,
                positionMs = playerPositionMs,
                durationMs = selectedClip?.durationUs?.div(1000L) ?: 0L,
                onBack = { player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L)) },
                onPlayPause = { if (player.isPlaying) player.pause() else player.play() },
                onForward = {
                    val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                    player.seekTo((player.currentPosition + 10_000L).coerceAtMost(duration))
                },
            )

            Box(Modifier.fillMaxWidth().height(236.dp)) {
                when (workspace) {
                    EditorWorkspace.NODES -> ClipNodeGraphPanel(
                        clip = selectedClip,
                        vm = vm,
                        modifier = Modifier.fillMaxSize(),
                    )
                    EditorWorkspace.CORRECTION -> CorrectionPanel(
                        clip = selectedClip,
                        vm = vm,
                        modifier = Modifier.fillMaxSize(),
                    )
                    EditorWorkspace.EFFECTS -> EffectsPanel(
                        clip = selectedClip,
                        vm = vm,
                        modifier = Modifier.fillMaxSize(),
                    )
                    else -> ResolveTimeline(
                        project = state.project,
                        selectedTrackId = state.selectedTrackId,
                        selectedClipIds = state.selectedClipIds,
                        playheadUs = playheadUs,
                        onSelectTrack = vm::selectTrack,
                        onSelectClip = vm::selectClip,
                        onMoveClip = vm::moveClip,
                        onAddVideoTrack = { vm.addTrack(TrackKind.VIDEO) },
                        onAddAudioTrack = { vm.addTrack(TrackKind.AUDIO) },
                        onSplit = { vm.splitSelectedAt(playheadUs) },
                        onDelete = vm::deleteSelected,
                        onUnlink = vm::unlinkSelected,
                        onImport = { picker.launch(arrayOf("video/*", "audio/*")) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            WorkspaceBar(
                selected = workspace,
                onSelected = { workspace = it },
                modifier = Modifier.fillMaxWidth().height(68.dp),
            )
        }
    }
}

@Composable
private fun EditorTopBar(
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
                Box(Modifier.size(6.dp).clip(CircleShape).background(EditorAccent))
                Spacer(Modifier.width(5.dp))
                Text(if (status == "Ready") "DigitorEngine" else status, fontSize = 10.sp, color = Color.White.copy(alpha = .54f), maxLines = 1)
            }
        }
        Button(onClick = onExport, enabled = !exporting, modifier = Modifier.height(34.dp), shape = RoundedCornerShape(7.dp)) {
            Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
            Text(if (exporting) "${((exportFraction ?: 0f) * 100).toInt()}%" else "Export", fontSize = 11.sp)
        }
    }
}

@Composable
private fun PreviewSurface(
    clip: TimelineClip?,
    player: ExoPlayer,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(Color(0xFF030304)), contentAlignment = Alignment.Center) {
        if (clip == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null, tint = Color.White.copy(alpha = .35f), modifier = Modifier.size(40.dp))
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
            "Preview",
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp).background(Color.Black.copy(alpha = .6f), RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 4.dp),
            fontSize = 9.sp,
            color = Color.White.copy(alpha = .7f),
        )
    }
}

@Composable
private fun TransportStrip(
    enabled: Boolean,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onForward: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(42.dp).background(Color(0xFF0D0D11)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(timeText(positionMs), color = EditorMuted, fontSize = 9.sp, modifier = Modifier.width(58.dp))
        IconButton(onClick = onBack, enabled = enabled, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.Replay10, null, modifier = Modifier.size(18.dp)) }
        IconButton(onClick = onPlayPause, enabled = enabled, modifier = Modifier.size(38.dp)) {
            Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, modifier = Modifier.size(23.dp))
        }
        IconButton(onClick = onForward, enabled = enabled, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.Forward10, null, modifier = Modifier.size(18.dp)) }
        Text(timeText(durationMs), color = EditorMuted, fontSize = 9.sp, modifier = Modifier.width(58.dp))
    }
}

@Composable
private fun ResolveTimeline(
    project: TimelineProject,
    selectedTrackId: String?,
    selectedClipIds: Set<String>,
    playheadUs: Long,
    onSelectTrack: (String) -> Unit,
    onSelectClip: (String) -> Unit,
    onMoveClip: (String, String, Long) -> Unit,
    onAddVideoTrack: () -> Unit,
    onAddAudioTrack: () -> Unit,
    onSplit: () -> Unit,
    onDelete: () -> Unit,
    onUnlink: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    BoxWithConstraints(modifier.background(EditorPanel)) {
        val durationSec = max(12f, project.durationUs / US_PER_SECOND.toFloat())
        val viewport = (maxWidth - 54.dp).coerceAtLeast(120.dp)
        val contentWidth = maxOf(viewport, (durationSec * EDITOR_PX_PER_SECOND).dp)

        Column(Modifier.fillMaxSize()) {
            TimelineToolbar(
                selectedCount = selectedClipIds.size,
                onAddVideoTrack = onAddVideoTrack,
                onAddAudioTrack = onAddAudioTrack,
                onSplit = onSplit,
                onDelete = onDelete,
                onUnlink = onUnlink,
                onImport = onImport,
            )
            Row(Modifier.weight(1f)) {
                Column(Modifier.width(54.dp)) {
                    Box(Modifier.fillMaxWidth().height(22.dp).background(Color(0xFF111116)), contentAlignment = Alignment.Center) {
                        Text("TC", color = Color.White.copy(alpha = .35f), fontSize = 8.sp)
                    }
                    project.tracks.forEach { track ->
                        TrackHeader(track, track.id == selectedTrackId, onSelectTrack)
                    }
                }
                Column(Modifier.horizontalScroll(scroll).requiredWidth(contentWidth)) {
                    TimelineRuler(contentWidth, durationSec)
                    Box(Modifier.requiredWidth(contentWidth).weight(1f)) {
                        Column(Modifier.fillMaxSize()) {
                            project.tracks.forEach { track ->
                                TimelineLane(
                                    track = track,
                                    selectedClipIds = selectedClipIds,
                                    onSelectClip = onSelectClip,
                                    onMoveClip = onMoveClip,
                                    width = contentWidth,
                                )
                            }
                        }
                        val playheadX = (playheadUs / US_PER_SECOND.toFloat() * EDITOR_PX_PER_SECOND).dp
                        Box(
                            Modifier.offset(x = playheadX).width(1.dp).fillMaxHeight().background(EditorPlayhead),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineToolbar(
    selectedCount: Int,
    onAddVideoTrack: () -> Unit,
    onAddAudioTrack: () -> Unit,
    onSplit: () -> Unit,
    onDelete: () -> Unit,
    onUnlink: () -> Unit,
    onImport: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(36.dp).background(Color(0xFF0E0E12)).padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        TinyAction("+V", onAddVideoTrack)
        TinyAction("+A", onAddAudioTrack)
        TinyAction("Split", onSplit, enabled = selectedCount > 0, icon = Icons.Rounded.ContentCut)
        TinyAction("Delete", onDelete, enabled = selectedCount > 0, icon = Icons.Rounded.Delete)
        TinyAction("Unlink", onUnlink, enabled = selectedCount > 1, icon = Icons.Rounded.LinkOff)
        Spacer(Modifier.weight(1f))
        TinyAction("Import", onImport, icon = Icons.Rounded.AddPhotoAlternate)
    }
}

@Composable
private fun TinyAction(label: String, onClick: () -> Unit, enabled: Boolean = true, icon: ImageVector? = null) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(3.dp))
        }
        Text(label, fontSize = 8.sp)
    }
}

@Composable
private fun TrackHeader(track: TimelineTrack, selected: Boolean, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(38.dp)
            .background(if (selected) EditorAccent.copy(alpha = .11f) else Color(0xFF121217))
            .border(.5.dp, EditorDivider)
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
private fun TimelineRuler(width: Dp, durationSec: Float) {
    Box(Modifier.requiredWidth(width).height(22.dp).background(Color(0xFF111116))) {
        val seconds = durationSec.toInt().coerceAtLeast(1)
        for (second in 0..seconds) {
            val x = (second * EDITOR_PX_PER_SECOND).dp
            Box(Modifier.offset(x = x).width(1.dp).height(if (second % 5 == 0) 10.dp else 5.dp).background(Color.White.copy(alpha = .2f)))
            if (second % 5 == 0) {
                Text("${second}s", Modifier.offset(x = x + 3.dp, y = 5.dp), fontSize = 7.sp, color = Color.White.copy(alpha = .38f))
            }
        }
    }
}

@Composable
private fun TimelineLane(
    track: TimelineTrack,
    selectedClipIds: Set<String>,
    onSelectClip: (String) -> Unit,
    onMoveClip: (String, String, Long) -> Unit,
    width: Dp,
) {
    Box(
        Modifier.requiredWidth(width).height(38.dp)
            .background(if (track.kind == TrackKind.VIDEO) Color(0xFF10141A) else Color(0xFF101713))
            .border(.5.dp, EditorDivider),
    ) {
        track.clips.forEach { clip ->
            TimelineClipView(
                track = track,
                clip = clip,
                selected = clip.id in selectedClipIds,
                onSelectClip = onSelectClip,
                onMoveClip = onMoveClip,
            )
        }
    }
}

@Composable
private fun TimelineClipView(
    track: TimelineTrack,
    clip: TimelineClip,
    selected: Boolean,
    onSelectClip: (String) -> Unit,
    onMoveClip: (String, String, Long) -> Unit,
) {
    val density = LocalDensity.current
    val pxPerSecond = with(density) { EDITOR_PX_PER_SECOND.dp.toPx() }
    val start = (clip.timelineStartUs / US_PER_SECOND.toFloat() * EDITOR_PX_PER_SECOND).dp
    val width = (clip.durationUs / US_PER_SECOND.toFloat() * EDITOR_PX_PER_SECOND).coerceAtLeast(32f).dp
    var carriedPx by remember(clip.id) { mutableStateOf(0f) }

    Box(
        Modifier.offset(x = start, y = 3.dp)
            .width(width).height(31.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (track.kind == TrackKind.VIDEO) EditorVideo else EditorAudio)
            .border(if (selected) 2.dp else .5.dp, if (selected) EditorAccent else Color.White.copy(alpha = .13f), RoundedCornerShape(4.dp))
            .clickable { onSelectClip(clip.id) }
            .pointerInput(clip.id, clip.timelineStartUs) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        carriedPx = 0f
                        onSelectClip(clip.id)
                    },
                    onDragEnd = { carriedPx = 0f },
                    onDragCancel = { carriedPx = 0f },
                ) { change, dragAmount ->
                    change.consume()
                    carriedPx += dragAmount.x
                    if (kotlin.math.abs(carriedPx) >= pxPerSecond * .05f) {
                        val deltaUs = (carriedPx / pxPerSecond * US_PER_SECOND).roundToLong()
                        val snapped = (deltaUs / 50_000L) * 50_000L
                        if (snapped != 0L) {
                            onMoveClip(track.id, clip.id, snapped)
                            carriedPx = 0f
                        }
                    }
                }
            }
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column {
            Text(clip.label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 8.sp, color = Color.White.copy(alpha = .88f))
            if (clip.linkGroupId != null) Text("linked", fontSize = 6.sp, color = Color.White.copy(alpha = .48f))
        }
    }
}

@Composable
private fun ClipNodeGraphPanel(
    clip: TimelineClip?,
    vm: EditorViewModel,
    modifier: Modifier = Modifier,
) {
    if (clip == null) {
        EmptyPanel("Select a clip to open its node graph", modifier)
        return
    }
    val graph = clip.nodeGraph
    val density = LocalDensity.current
    val maxX = (graph.nodes.maxOfOrNull { it.position.x } ?: 420f) + 130f
    val maxY = (graph.nodes.maxOfOrNull { it.position.y } ?: 160f) + 90f
    val graphWidth = max(640f, maxX).dp
    val graphHeight = max(220f, maxY).dp
    val scroll = rememberScrollState()
    var menuNodeId by remember { mutableStateOf<String?>(null) }

    Column(modifier.background(EditorPanel)) {
        Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Node Graph · ${clip.label}", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("Tap select · Hold drag · Hold/release add", fontSize = 7.sp, color = EditorMuted)
        }
        HorizontalDivider(color = EditorDivider)
        Box(Modifier.weight(1f).horizontalScroll(scroll)) {
            Box(Modifier.requiredWidth(graphWidth).height(graphHeight)) {
                Canvas(Modifier.fillMaxSize()) {
                    val byId = graph.nodes.associateBy { it.id }
                    graph.edges.forEach { edge ->
                        val from = byId[edge.fromId] ?: return@forEach
                        val to = byId[edge.toId] ?: return@forEach
                        drawLine(
                            color = Color.White.copy(alpha = .28f),
                            start = androidx.compose.ui.geometry.Offset(
                                from.position.x.dp.toPx() + 88.dp.toPx(),
                                from.position.y.dp.toPx() + 26.dp.toPx(),
                            ),
                            end = androidx.compose.ui.geometry.Offset(
                                to.position.x.dp.toPx(),
                                to.position.y.dp.toPx() + 26.dp.toPx(),
                            ),
                            strokeWidth = 2f,
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
                            .background(nodeBackground(node.kind))
                            .border(if (selected) 2.dp else 1.dp, if (selected) EditorAccent else Color.White.copy(alpha = .2f), RoundedCornerShape(6.dp))
                            .clickable { vm.selectNode(node.id) }
                            .pointerInput(node.id, node.position) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        moved = false
                                        vm.selectNode(node.id)
                                    },
                                    onDragEnd = {
                                        if (!moved) menuNodeId = node.id
                                        moved = false
                                    },
                                    onDragCancel = { moved = false },
                                ) { change, dragAmount ->
                                    change.consume()
                                    if (kotlin.math.abs(dragAmount.x) + kotlin.math.abs(dragAmount.y) > 1f) moved = true
                                    val dx = with(density) { dragAmount.x.toDp().value }
                                    val dy = with(density) { dragAmount.y.toDp().value }
                                    vm.moveNode(node.id, dx, dy)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(node.label, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Text(nodeKindText(node.kind), fontSize = 6.sp, color = Color.White.copy(alpha = .52f))
                            if (node.effects.isNotEmpty()) Text("FX ${node.effects.size}", fontSize = 6.sp, color = EditorAccent)
                        }
                        DropdownMenu(
                            expanded = menuNodeId == node.id,
                            onDismissRequest = { menuNodeId = null },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Add Serial") },
                                enabled = node.kind != NodeKind.OUTPUT,
                                onClick = {
                                    menuNodeId = null
                                    vm.addSerialNode(node.id)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Add Parallel") },
                                enabled = node.kind == NodeKind.SERIAL || node.kind == NodeKind.PARALLEL,
                                onClick = {
                                    menuNodeId = null
                                    vm.addParallelNode(node.id)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CorrectionPanel(clip: TimelineClip?, vm: EditorViewModel, modifier: Modifier = Modifier) {
    val node = clip?.nodeGraph?.selectedNode()
    if (clip == null || node == null) {
        EmptyPanel("Select a clip and a node first", modifier)
        return
    }
    if (node.kind != NodeKind.SERIAL && node.kind != NodeKind.PARALLEL) {
        EmptyPanel("Select a Serial or Parallel node for corrections", modifier)
        return
    }
    val c = node.corrections
    Column(modifier.background(EditorPanel)) {
        PanelTitle("Correction · ${node.label}", "Applied only to selected node")
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 5.dp)) {
            CorrectionSlider("Exposure", c.exposure, -5f..5f) { vm.setSelectedNodeCorrection("Exposure", it) }
            CorrectionSlider("Contrast", c.contrast, -100f..100f) { vm.setSelectedNodeCorrection("Contrast", it) }
            CorrectionSlider("Saturation", c.saturation, -100f..100f) { vm.setSelectedNodeCorrection("Saturation", it) }
            CorrectionSlider("Temperature", c.temperature, -100f..100f) { vm.setSelectedNodeCorrection("Temperature", it) }
            CorrectionSlider("Tint", c.tint, -100f..100f) { vm.setSelectedNodeCorrection("Tint", it) }
            CorrectionSlider("Highlights", c.highlights, -100f..100f) { vm.setSelectedNodeCorrection("Highlights", it) }
            CorrectionSlider("Shadows", c.shadows, -100f..100f) { vm.setSelectedNodeCorrection("Shadows", it) }
            CorrectionSlider("Hue", c.hue, -180f..180f) { vm.setSelectedNodeCorrection("Hue", it) }
            CorrectionSlider("Color Boost", c.colorBoost, 0f..100f) { vm.setSelectedNodeCorrection("Color Boost", it) }
        }
    }
}

@Composable
private fun CorrectionSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onValue: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(78.dp), fontSize = 8.sp, color = Color.White.copy(alpha = .72f))
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onValue, valueRange = range, modifier = Modifier.weight(1f))
        Text(String.format("%.1f", value), Modifier.width(42.dp), fontSize = 8.sp, color = EditorMuted)
    }
}

@Composable
private fun EffectsPanel(clip: TimelineClip?, vm: EditorViewModel, modifier: Modifier = Modifier) {
    val node = clip?.nodeGraph?.selectedNode()
    if (clip == null || node == null) {
        EmptyPanel("Select a clip and a node first", modifier)
        return
    }
    if (node.kind != NodeKind.SERIAL && node.kind != NodeKind.PARALLEL) {
        EmptyPanel("Effects can be added to Serial or Parallel nodes", modifier)
        return
    }
    Column(modifier.background(EditorPanel)) {
        PanelTitle("Effects · ${node.label}", "Effects belong only to selected node")
        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("Blur", "Sharpen", "Glow", "Film Grain").forEach { name ->
                FilledTonalButton(onClick = { vm.addEffectToSelectedNode(name) }) { Text(name, fontSize = 9.sp) }
            }
        }
        HorizontalDivider(color = EditorDivider)
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (node.effects.isEmpty()) Text("No effects on this node", fontSize = 9.sp, color = EditorMuted)
            node.effects.forEach { effect ->
                Row(Modifier.fillMaxWidth().background(EditorRaised, RoundedCornerShape(5.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(effect.name, fontSize = 9.sp, modifier = Modifier.weight(1f))
                    Text("${(effect.amount * 100).toInt()}%", fontSize = 8.sp, color = EditorAccent)
                }
            }
        }
    }
}

@Composable
private fun PanelTitle(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text(subtitle, fontSize = 7.sp, color = EditorMuted)
    }
    HorizontalDivider(color = EditorDivider)
}

@Composable
private fun EmptyPanel(message: String, modifier: Modifier = Modifier) {
    Box(modifier.background(EditorPanel), contentAlignment = Alignment.Center) {
        Text(message, color = EditorMuted, fontSize = 10.sp)
    }
}

@Composable
private fun WorkspaceBar(selected: EditorWorkspace, onSelected: (EditorWorkspace) -> Unit, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    Row(
        modifier.background(Color(0xFF0A0A0D)).horizontalScroll(scroll).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        EditorWorkspace.entries.forEach { item ->
            val active = item == selected
            Column(
                Modifier.width(66.dp).fillMaxHeight().clickable { onSelected(item) }
                    .background(if (active) EditorAccent.copy(alpha = .10f) else Color.Transparent),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(item.icon, contentDescription = item.label, modifier = Modifier.size(18.dp), tint = if (active) EditorAccent else Color.White.copy(alpha = .55f))
                Spacer(Modifier.height(3.dp))
                Text(item.label, fontSize = 8.sp, color = if (active) EditorAccent else Color.White.copy(alpha = .55f))
            }
        }
    }
}

private fun nodeBackground(kind: NodeKind): Color = when (kind) {
    NodeKind.IMPORT -> Color(0xFF25323B)
    NodeKind.SERIAL -> Color(0xFF333239)
    NodeKind.PARALLEL -> Color(0xFF3A2F48)
    NodeKind.MIX -> Color(0xFF3B3325)
    NodeKind.OUTPUT -> Color(0xFF26382E)
}

private fun nodeKindText(kind: NodeKind): String = when (kind) {
    NodeKind.IMPORT -> "IMPORT"
    NodeKind.SERIAL -> "SERIAL"
    NodeKind.PARALLEL -> "PARALLEL"
    NodeKind.MIX -> "MIX"
    NodeKind.OUTPUT -> "OUTPUT"
}

private fun timeText(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000L)
    val minutes = total / 60L
    val seconds = total % 60L
    return "%02d:%02d".format(minutes, seconds)
}
