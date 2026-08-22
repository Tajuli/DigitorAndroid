package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.US_PER_SECOND
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val T4Panel = Color(0xFF0B0B0F)
private val T4Divider = Color(0xFF292930)
private val T4Muted = Color(0xFF909098)
private val T4Accent = Color(0xFF30E0C3)
private val T4Playhead = Color(0xFFFF4D4D)
private val T4Video = Color(0xFF385B78)
private val T4Audio = Color(0xFF315F57)
private const val T4_TRACK_HEIGHT = 38f

@Composable
fun TimelineEditorV4(
    project: TimelineProject,
    selectedTrackId: String?,
    selectedClipIds: Set<String>,
    cursorUs: Long,
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
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    var zoom by remember { mutableStateOf(.18f) }

    BoxWithConstraints(modifier.background(T4Panel)) {
        val viewportDp = (maxWidth - 56.dp).coerceAtLeast(120.dp)
        val viewportPx = with(density) { viewportDp.toPx() }
        val durationSec = max(project.durationUs / US_PER_SECOND.toFloat(), 1f)
        val overviewPps = (viewportPx * .08f / durationSec).coerceAtLeast(.02f)
        val fitPps = (viewportPx / durationSec).coerceAtLeast(overviewPps)
        val oneFramePps = max(fitPps, with(density) { 24.dp.toPx() } * project.frameRate.coerceAtLeast(1))
        val ratio = (oneFramePps / overviewPps).coerceAtLeast(1f)
        val pps = if (ratio <= 1.0001f) overviewPps else overviewPps * ratio.toDouble().pow(zoom.toDouble()).toFloat()
        val fitFraction = if (ratio <= 1.0001f) 0f else (ln((fitPps / overviewPps).toDouble()) / ln(ratio.toDouble())).toFloat().coerceIn(0f, 1f)
        val contentWidthPx = max(viewportPx, durationSec * pps)
        val contentWidth = with(density) { contentWidthPx.toDp() }
        val frameUs = (US_PER_SECOND.toDouble() / project.frameRate.coerceAtLeast(1)).roundToLong().coerceAtLeast(1L)

        fun xToTime(xPx: Float): Long {
            val raw = xPx / pps * US_PER_SECOND
            val snapped = (raw / frameUs).roundToLong() * frameUs
            return snapped.coerceIn(0L, project.durationUs.coerceAtLeast(0L))
        }

        Column(Modifier.fillMaxSize()) {
            TimelineToolbarV4(
                selectedCount = selectedClipIds.size,
                zoom = zoom,
                onZoom = { zoom = it.coerceIn(0f, 1f) },
                onOverview = { zoom = 0f },
                onFit = { zoom = fitFraction },
                onOneFrame = { zoom = 1f },
                onAddVideoTrack = onAddVideoTrack,
                onAddAudioTrack = onAddAudioTrack,
                onSplit = onSplit,
                onDelete = onDelete,
                onUnlink = onUnlink,
                onImport = onImport,
            )

            Row(Modifier.weight(1f)) {
                Column(Modifier.width(56.dp)) {
                    Box(Modifier.fillMaxWidth().height(24.dp).background(Color(0xFF111116)), contentAlignment = Alignment.Center) {
                        Text("TC", fontSize = 8.sp, color = T4Muted)
                    }
                    project.tracks.forEach { track -> TrackHeaderV4(track, track.id == selectedTrackId, onSelectTrack) }
                }

                Column(
                    Modifier.horizontalScroll(scroll)
                        .requiredWidth(contentWidth)
                        .pointerInput(overviewPps, oneFramePps) {
                            detectTransformGestures { _, _, zoomChange, _ ->
                                if (ratio > 1.0001f && zoomChange > 0f) {
                                    val delta = (ln(zoomChange.toDouble()) / ln(ratio.toDouble())).toFloat()
                                    zoom = (zoom + delta).coerceIn(0f, 1f)
                                }
                            }
                        },
                ) {
                    Box(
                        Modifier.requiredWidth(contentWidth).height(24.dp)
                            .background(Color(0xFF111116))
                            .pointerInput(pps) { detectTapGestures { onSeek(xToTime(it.x)) } }
                            .pointerInput(pps) {
                                detectDragGestures(
                                    onDragStart = { onSeek(xToTime(it.x)) },
                                ) { change, _ ->
                                    change.consume()
                                    onSeek(xToTime(change.position.x))
                                }
                            },
                    ) {
                        TimelineRulerV4(contentWidth, durationSec, pps, project.frameRate, scroll.value.toFloat(), viewportPx)
                        val knobX = with(density) { (cursorUs / US_PER_SECOND.toFloat() * pps).toDp() }
                        Box(
                            Modifier.offset(x = knobX - 6.dp).size(12.dp, 9.dp)
                                .background(T4Playhead, RoundedCornerShape(bottomStart = 5.dp, bottomEnd = 5.dp)),
                        )
                    }

                    Box(Modifier.requiredWidth(contentWidth).weight(1f)) {
                        Column(Modifier.fillMaxSize()) {
                            project.tracks.forEach { track ->
                                TimelineLaneV4(
                                    project = project,
                                    track = track,
                                    selectedClipIds = selectedClipIds,
                                    pps = pps,
                                    width = contentWidth,
                                    onSelectClip = onSelectClip,
                                    onMoveClip = onMoveClip,
                                    onMoveClipToTrack = onMoveClipToTrack,
                                )
                            }
                        }
                        val cursorXPx = cursorUs / US_PER_SECOND.toFloat() * pps
                        val cursorX = with(density) { cursorXPx.toDp() }
                        Box(
                            Modifier.offset(x = cursorX - 12.dp).width(24.dp).fillMaxHeight()
                                .pointerInput(pps) {
                                    detectDragGestures { change, drag ->
                                        change.consume()
                                        val next = cursorXPx + drag.x
                                        onSeek(xToTime(next))
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.width(1.dp).fillMaxHeight().background(T4Playhead))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineToolbarV4(
    selectedCount: Int,
    zoom: Float,
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
        Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TinyActionV4("+V", onAddVideoTrack)
            TinyActionV4("+A", onAddAudioTrack)
            TinyActionV4("Split", onSplit, selectedCount > 0, Icons.Rounded.ContentCut)
            TinyActionV4("Delete", onDelete, selectedCount > 0, Icons.Rounded.Delete)
            TinyActionV4("Unlink", onUnlink, selectedCount > 1, Icons.Rounded.LinkOff)
            Spacer(Modifier.weight(1f))
            TinyActionV4("Import", onImport, icon = Icons.Rounded.AddPhotoAlternate)
        }
        Row(Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onOverview, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)) { Text("Overview", fontSize = 7.sp) }
            IconButton(onClick = onFit, modifier = Modifier.size(28.dp)) { Icon(Icons.Rounded.FitScreen, "Fit", modifier = Modifier.size(14.dp)) }
            Slider(value = zoom, onValueChange = onZoom, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
            Text("1F", Modifier.clickable(onClick = onOneFrame).padding(5.dp), fontSize = 8.sp, color = T4Accent)
        }
    }
}

@Composable
private fun TinyActionV4(label: String, onClick: () -> Unit, enabled: Boolean = true, icon: ImageVector? = null) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 5.dp, vertical = 0.dp)) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(11.dp))
            Spacer(Modifier.width(2.dp))
        }
        Text(label, fontSize = 7.sp)
    }
}

@Composable
private fun TrackHeaderV4(track: TimelineTrack, selected: Boolean, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(T4_TRACK_HEIGHT.dp)
            .background(if (selected) T4Accent.copy(alpha = .12f) else Color(0xFF121217))
            .border(.5.dp, T4Divider).clickable { onSelect(track.id) }.padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(if (track.kind == TrackKind.VIDEO) Color(0xFF607D9B) else Color(0xFF3E7569)))
        Spacer(Modifier.width(5.dp))
        Text(track.name, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = .75f))
    }
}

@Composable
private fun TimelineRulerV4(width: Dp, durationSec: Float, pps: Float, frameRate: Int, scrollPx: Float, viewportPx: Float) {
    Canvas(Modifier.requiredWidth(width).height(24.dp)) {
        val startSec = (scrollPx / pps).coerceAtLeast(0f)
        val endSec = ((scrollPx + viewportPx) / pps).coerceAtMost(durationSec)
        val framePx = pps / frameRate.coerceAtLeast(1)
        if (framePx >= 6f) {
            val first = (startSec * frameRate).toInt().coerceAtLeast(0)
            val last = ceil(endSec * frameRate).toInt()
            for (frame in first..last) {
                val x = frame / frameRate.toFloat() * pps
                val major = frame % frameRate == 0
                drawLine(Color.White.copy(alpha = if (major) .30f else .11f), androidx.compose.ui.geometry.Offset(x, if (major) 5f else 14f), androidx.compose.ui.geometry.Offset(x, size.height), 1f)
            }
        } else {
            val step = when {
                pps >= 100f -> 1f
                pps >= 30f -> 2f
                pps >= 12f -> 5f
                pps >= 5f -> 10f
                pps >= 1f -> 30f
                else -> 60f
            }
            var sec = kotlin.math.floor(startSec / step) * step
            while (sec <= endSec + step) {
                val x = sec * pps
                drawLine(Color.White.copy(alpha = .22f), androidx.compose.ui.geometry.Offset(x, 9f), androidx.compose.ui.geometry.Offset(x, size.height), 1f)
                sec += step
            }
        }
    }
}

@Composable
private fun TimelineLaneV4(
    project: TimelineProject,
    track: TimelineTrack,
    selectedClipIds: Set<String>,
    pps: Float,
    width: Dp,
    onSelectClip: (String) -> Unit,
    onMoveClip: (String, String, Long) -> Unit,
    onMoveClipToTrack: (String, String) -> Unit,
) {
    Box(
        Modifier.requiredWidth(width).height(T4_TRACK_HEIGHT.dp)
            .background(if (track.kind == TrackKind.VIDEO) Color(0xFF10141A) else Color(0xFF101713))
            .border(.5.dp, T4Divider),
    ) {
        track.clips.forEach { clip -> ClipV4(project, track, clip, clip.id in selectedClipIds, pps, onSelectClip, onMoveClip, onMoveClipToTrack) }
    }
}

@Composable
private fun ClipV4(
    project: TimelineProject,
    track: TimelineTrack,
    clip: TimelineClip,
    selected: Boolean,
    pps: Float,
    onSelectClip: (String) -> Unit,
    onMoveClip: (String, String, Long) -> Unit,
    onMoveClipToTrack: (String, String) -> Unit,
) {
    val density = LocalDensity.current
    val ppsDp = with(density) { pps.toDp().value }
    val start = (clip.timelineStartUs / US_PER_SECOND.toFloat() * ppsDp).dp
    val width = (clip.durationUs / US_PER_SECOND.toFloat() * ppsDp).coerceAtLeast(3f).dp
    val frameUs = (US_PER_SECOND.toDouble() / project.frameRate.coerceAtLeast(1)).roundToLong().coerceAtLeast(1L)
    val compatible = project.tracks.filter { it.kind == track.kind }
    val sourceIndex = compatible.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
    val trackPx = with(density) { T4_TRACK_HEIGHT.dp.toPx() }
    var dragX by remember(clip.id) { mutableStateOf(0f) }
    var dragY by remember(clip.id) { mutableStateOf(0f) }

    Box(
        Modifier.offset(x = start, y = 3.dp).width(width).height(31.dp)
            .graphicsLayer {
                translationX = dragX
                translationY = dragY
            }
            .clip(RoundedCornerShape(4.dp))
            .background(if (track.kind == TrackKind.VIDEO) T4Video else T4Audio)
            .border(if (selected) 2.dp else .5.dp, if (selected) T4Accent else Color.White.copy(alpha = .14f), RoundedCornerShape(4.dp))
            .clickable { onSelectClip(clip.id) }
            .pointerInput(clip.id, track.id, pps, project.frameRate) {
                detectDragGestures(
                    onDragStart = {
                        dragX = 0f
                        dragY = 0f
                        onSelectClip(clip.id)
                    },
                    onDragEnd = {
                        val rawUs = dragX / pps * US_PER_SECOND
                        val frames = (rawUs / frameUs).roundToLong()
                        val delta = frames * frameUs
                        if (delta != 0L) {
                            onMoveClip(track.id, clip.id, delta)
                        }

                        val shift = (dragY / trackPx).roundToInt()
                        if (shift != 0 && compatible.isNotEmpty()) {
                            val target = compatible[(sourceIndex + shift).coerceIn(0, compatible.lastIndex)]
                            if (target.id != track.id) onMoveClipToTrack(clip.id, target.id)
                        }
                        dragX = 0f
                        dragY = 0f
                    },
                    onDragCancel = {
                        dragX = 0f
                        dragY = 0f
                    },
                ) { change, drag ->
                    change.consume()
                    dragX += drag.x
                    dragY += drag.y
                }
            }
            .padding(horizontal = 3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (width > 24.dp) {
            Column {
                Text(clip.label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 8.sp, color = Color.White.copy(alpha = .9f))
                if (clip.linkGroupId != null) Text("linked", fontSize = 6.sp, color = Color.White.copy(alpha = .5f))
            }
        }
    }
}