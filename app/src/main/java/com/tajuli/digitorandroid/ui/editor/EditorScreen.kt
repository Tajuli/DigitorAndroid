package com.tajuli.digitorandroid.ui.editor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.US_PER_SECOND
import kotlin.math.roundToLong

private const val PX_PER_SECOND = 72f

@UnstableApi
@Composable
fun EditorScreen(vm: EditorViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val selectedClip = state.project.tracks.flatMap { it.clips }.firstOrNull { it.id == state.selectedClipId }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        vm.importUris(uris)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(top = 28.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Digitor", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Button(onClick = { picker.launch(arrayOf("video/*", "audio/*")) }) { Text("Import") }
                Button(onClick = { vm.addTrack(TrackKind.VIDEO) }) { Text("+V") }
                Button(onClick = { vm.addTrack(TrackKind.AUDIO) }) { Text("+A") }
                Button(onClick = vm::export) { Text("Export") }
            }

            PreviewPanel(
                clip = selectedClip,
                modifier = Modifier.fillMaxWidth().weight(0.42f).padding(horizontal = 12.dp),
            )

            if (state.exportFraction != null) {
                LinearProgressIndicator(
                    progress = { state.exportFraction!!.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
            Text(
                buildString {
                    append(state.status)
                    state.lastBackend?.let { append(" • backend: $it") }
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                maxLines = 2,
            )

            Timeline(
                tracks = state.project.tracks,
                selectedTrackId = state.selectedTrackId,
                onSelectTrack = vm::selectTrack,
                onSelectClip = vm::selectClip,
                onMoveClip = vm::moveClip,
                modifier = Modifier.weight(0.58f),
            )
        }
    }
}

@Composable
private fun PreviewPanel(clip: TimelineClip?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }

    LaunchedEffect(clip?.id) {
        player.stop()
        player.clearMediaItems()
        if (clip != null) {
            val mediaItem = MediaItem.Builder()
                .setUri(clip.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.sourceInUs / 1000L)
                        .setEndPositionMs(clip.sourceOutUs / 1000L)
                        .build()
                )
                .build()
            player.setMediaItem(mediaItem)
            player.prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    Box(
        modifier.background(Color.Black, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (clip == null) {
            Text("Import media and select a clip", color = Color.Gray)
        } else {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = true
                        this.player = player
                    }
                },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun Timeline(
    tracks: List<TimelineTrack>,
    selectedTrackId: String?,
    onSelectTrack: (String) -> Unit,
    onSelectClip: (String) -> Unit,
    onMoveClip: (String, String, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val maxEndUs = tracks.flatMap { it.clips }.maxOfOrNull { it.timelineEndUs } ?: 10 * US_PER_SECOND
    val timelineWidth = ((maxEndUs / US_PER_SECOND.toFloat()).coerceAtLeast(10f) * PX_PER_SECOND).dp

    Column(modifier.background(MaterialTheme.colorScheme.surface).padding(vertical = 8.dp)) {
        Text("MULTITRACK TIMELINE", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        Row(Modifier.weight(1f)) {
            Column(Modifier.width(56.dp)) {
                tracks.forEach { track ->
                    Box(
                        Modifier.fillMaxWidth().height(58.dp)
                            .background(if (track.id == selectedTrackId) MaterialTheme.colorScheme.primary.copy(alpha = .16f) else Color.Transparent)
                            .padding(4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Button(onClick = { onSelectTrack(track.id) }, modifier = Modifier.fillMaxWidth()) { Text(track.name) }
                    }
                }
            }
            Column(Modifier.horizontalScroll(scroll).requiredWidth(timelineWidth)) {
                tracks.forEach { track -> TrackRow(track, onSelectClip, onMoveClip, timelineWidth) }
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: TimelineTrack,
    onSelectClip: (String) -> Unit,
    onMoveClip: (String, String, Long) -> Unit,
    timelineWidth: androidx.compose.ui.unit.Dp,
) {
    Box(
        Modifier.requiredWidth(timelineWidth).height(58.dp)
            .background(if (track.kind == TrackKind.VIDEO) Color(0xFF171D27) else Color(0xFF1D241B))
    ) {
        track.clips.forEach { clip -> ClipBox(track, clip, onSelectClip, onMoveClip) }
    }
}

@Composable
private fun ClipBox(
    track: TimelineTrack,
    clip: TimelineClip,
    onSelectClip: (String) -> Unit,
    onMoveClip: (String, String, Long) -> Unit,
) {
    val startDp = (clip.timelineStartUs / US_PER_SECOND.toFloat() * PX_PER_SECOND).dp
    val widthDp = (clip.durationUs / US_PER_SECOND.toFloat() * PX_PER_SECOND).coerceAtLeast(36f).dp
    Box(
        Modifier.padding(start = startDp, top = 5.dp)
            .width(widthDp).height(48.dp)
            .background(
                if (track.kind == TrackKind.VIDEO) Color(0xFF315E91) else Color(0xFF3D7142),
                RoundedCornerShape(6.dp),
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
                        val seconds = carriedPx / PX_PER_SECOND
                        val snappedUs = (seconds * US_PER_SECOND / 50_000f).roundToLong() * 50_000L
                        if (snappedUs != 0L) {
                            onMoveClip(track.id, clip.id, snappedUs)
                            carriedPx = 0f
                        }
                    }
                }
            }
            .padding(6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(clip.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
    }
}
