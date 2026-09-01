package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tajuli.digitorandroid.editor.model.EFFECT_MIN_DURATION_US_V26
import com.tajuli.digitorandroid.editor.model.NodeEffect
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.TextOverlayClip
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.US_PER_SECOND
import com.tajuli.digitorandroid.editor.model.resolvedSourceEndUsV26
import com.tajuli.digitorandroid.editor.model.resolvedSourceStartUsV26
import com.tajuli.digitorandroid.editor.model.resolvedVisualOverlaysV19
import com.tajuli.digitorandroid.editor.model.textOverlaysForVideoTrackV3
import com.tajuli.digitorandroid.editor.model.visibleEffects
import com.tajuli.digitorandroid.editor.model.visualOverlaysForVideoTrackV19
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val T4Panel = Color(0xFF0B0B0F)
private val T4Divider = Color(0xFF292930)
private val T4Muted = Color(0xFF909098)
private val T4Accent = Color(0xFF30E0C3)
private val T4Playhead = Color(0xFFFF4D4D)
private val T4Magnet = Color(0xFFFFC857)
private val T4Video = Color(0xFF385B78)
private val T4Audio = Color(0xFF315F57)
private val T4Text = Color(0xFF675089)
private val T4Effect = Color(0xFF7657A8)
private const val T4_TRACK_HEIGHT = 38f
private const val T4_DELETE_TRACK_ACTION = "__digitor_delete_track__:"
private const val T4_MIN_TRIM_US = 100_000L

private data class T4SnapResult(val deltaUs: Long, val magnet: Boolean)

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
    onSelectText: (String) -> Unit = {},
    onMoveText: (String, Long) -> Unit = { _, _ -> },
    onMoveTextToTrack: (String, String) -> Unit = { _, _ -> },
    onDeleteText: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val vm: EditorViewModelV4 = viewModel()
    val scroll = rememberScrollState()
    val verticalScroll = rememberScrollState()
    val density = LocalDensity.current
    val selectedTextId by TimelineTextSelectionBusV10.selectedTextId.collectAsState()
    val selectedVisualId by VisualOverlaySelectionBusV19.selectedId.collectAsState()
    val selectedEffect by EffectTimelineSelectionBusV26.selection.collectAsState()
    val effectSelected = project.effectSelectionExistsV26(selectedEffect)
    var zoom by remember { mutableFloatStateOf(.18f) }
    var transitionTargetClipId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedTextId, selectedVisualId) {
        if (selectedTextId != null || selectedVisualId != null) EffectTimelineSelectionBusV26.clear()
    }
    LaunchedEffect(project, selectedEffect) {
        if (selectedEffect != null && !project.effectSelectionExistsV26(selectedEffect)) {
            EffectTimelineSelectionBusV26.clear()
        }
    }

    BoxWithConstraints(modifier.background(T4Panel)) {
        val viewportDp = (maxWidth - 56.dp).coerceAtLeast(120.dp)
        val viewportPx = with(density) { viewportDp.toPx() }
        val timelineDurationUs = project.durationUs
        val durationSec = max(timelineDurationUs / US_PER_SECOND.toFloat(), 1f)
        val overviewPps = (viewportPx * .08f / durationSec).coerceAtLeast(.02f)
        val fitPps = (viewportPx / durationSec).coerceAtLeast(overviewPps)
        val oneFramePps = max(fitPps, with(density) { 24.dp.toPx() } * project.frameRate.coerceAtLeast(1))
        val ratio = (oneFramePps / overviewPps).coerceAtLeast(1f)
        val pps = if (ratio <= 1.0001f) overviewPps else overviewPps * ratio.toDouble().pow(zoom.toDouble()).toFloat()
        val contentWidthPx = max(viewportPx, durationSec * pps)
        val contentWidth = with(density) { contentWidthPx.toDp() }
        val frameUs = (US_PER_SECOND.toDouble() / project.frameRate.coerceAtLeast(1)).roundToLong().coerceAtLeast(1L)

        fun xToTime(xPx: Float): Long {
            val raw = xPx / pps * US_PER_SECOND
            val snapped = (raw / frameUs).roundToLong() * frameUs
            return snapped.coerceAtLeast(0L)
        }

        transitionTargetClipId?.let { targetClipId ->
            CapCutTransitionSheetV23(
                project = project,
                targetClipId = targetClipId,
                vm = vm,
                onSeek = onSeek,
                onDismiss = { transitionTargetClipId = null },
            )
        }

        Column(Modifier.fillMaxSize()) {
            TimelineToolbarV4(
                selectedClipCount = selectedClipIds.size,
                textSelected = selectedTextId != null,
                visualSelected = selectedVisualId != null,
                effectSelected = effectSelected,
                zoom = zoom,
                onZoom = { zoom = it.coerceIn(0f, 1f) },
                onAddVideoTrack = onAddVideoTrack,
                onAddAudioTrack = onAddAudioTrack,
                onSplit = onSplit,
                onDelete = {
                    when {
                        selectedVisualId != null -> vm.deleteSelectedVisualV19()
                        selectedTextId != null -> onDeleteText()
                        effectSelected && selectedEffect != null -> vm.deleteEffectTimelineV26(selectedEffect!!)
                        else -> onDelete()
                    }
                },
                onUnlink = onUnlink,
                onImport = onImport,
            )

            Row(Modifier.weight(1f)) {
                Column(Modifier.width(56.dp)) {
                    Box(Modifier.fillMaxWidth().height(24.dp).background(Color(0xFF111116)))
                    Column(Modifier.fillMaxHeight().verticalScroll(verticalScroll)) {
                        project.tracks.forEach { track -> TrackHeaderV4(track, track.id == selectedTrackId, onSelectTrack) }
                    }
                }

                Column(Modifier.horizontalScroll(scroll).requiredWidth(contentWidth)) {
                    Box(
                        Modifier.requiredWidth(contentWidth).height(24.dp)
                            .background(Color(0xFF111116))
                            .pointerInput(pps) { detectTapGestures { onSeek(xToTime(it.x)) } }
                            .pointerInput(pps) {
                                detectDragGestures(onDragStart = { onSeek(xToTime(it.x)) }) { change, _ ->
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
                        Column(Modifier.fillMaxSize().verticalScroll(verticalScroll)) {
                            project.tracks.forEach { track ->
                                TimelineLaneV4(
                                    project = project,
                                    track = track,
                                    textOverlays = if (track.kind == TrackKind.VIDEO) project.textOverlaysForVideoTrackV3(track.id) else emptyList(),
                                    selectedTextId = selectedTextId,
                                    selectedClipIds = selectedClipIds,
                                    cursorUs = cursorUs,
                                    pps = pps,
                                    width = contentWidth,
                                    vm = vm,
                                    onSelectClip = onSelectClip,
                                    onMoveClip = onMoveClip,
                                    onMoveClipToTrack = onMoveClipToTrack,
                                    onSelectText = { overlay ->
                                        EffectTimelineSelectionBusV26.clear()
                                        VisualOverlaySelectionBusV19.clear()
                                        TimelineTextSelectionBusV10.select(overlay.id)
                                        onSelectText(overlay.id)
                                        onSelectTrack(track.id)
                                        onSeek(overlay.timelineStartUs)
                                    },
                                    onMoveText = onMoveText,
                                    onMoveTextToTrack = onMoveTextToTrack,
                                    onTransitionCut = { target ->
                                        EffectTimelineSelectionBusV26.clear()
                                        TimelineTextSelectionBusV10.clear()
                                        VisualOverlaySelectionBusV19.clear()
                                        onSelectTrack(target.trackId)
                                        onSelectClip(target.incoming.id)
                                        onSeek(target.cutUs)
                                        transitionTargetClipId = target.incoming.id
                                    },
                                )
                            }
                        }
                        val cursorXPx = cursorUs / US_PER_SECOND.toFloat() * pps
                        val cursorX = with(density) { cursorXPx.toDp() }
                        Box(
                            Modifier.offset(x = cursorX - 12.dp).width(24.dp).fillMaxHeight()
                                .pointerInput(pps) {
                                    var totalDragX = 0f
                                    detectDragGestures(
                                        onDragStart = { totalDragX = 0f },
                                        onDragEnd = { totalDragX = 0f },
                                        onDragCancel = { totalDragX = 0f },
                                    ) { change, drag ->
                                        change.consume()
                                        totalDragX += drag.x
                                        onSeek(xToTime(cursorXPx + totalDragX))
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
    selectedClipCount: Int,
    textSelected: Boolean,
    visualSelected: Boolean,
    effectSelected: Boolean,
    zoom: Float,
    onZoom: (Float) -> Unit,
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
            TinyActionV4("Split", onSplit, selectedClipCount > 0 && !effectSelected, Icons.Rounded.ContentCut)
            TinyActionV4("Delete", onDelete, selectedClipCount > 0 || textSelected || visualSelected || effectSelected, Icons.Rounded.Delete)
            TinyActionV4("Unlink", onUnlink, selectedClipCount > 1 && !effectSelected, Icons.Rounded.LinkOff)
            Spacer(Modifier.weight(1f))
            TinyActionV4("Import", onImport, icon = Icons.Rounded.AddPhotoAlternate)
        }
        Slider(value = zoom, onValueChange = onZoom, modifier = Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 10.dp))
    }
}

@Composable
private fun TinyActionV4(label: String, onClick: () -> Unit, enabled: Boolean = true, icon: ImageVector? = null) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 5.dp, vertical = 0.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(11.dp), tint = Color.White)
            Spacer(Modifier.width(2.dp))
        }
        Text(label, fontSize = 7.sp, color = if (enabled) Color.White else T4Muted)
    }
}

@Composable
private fun TrackHeaderV4(track: TimelineTrack, selected: Boolean, onSelect: (String) -> Unit) {
    var confirmDelete by remember(track.id) { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${track.name}?") },
            text = { Text(if (track.clips.isEmpty()) "This track is empty." else "This will remove ${track.clips.size} media clip(s) from ${track.name}.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onSelect(T4_DELETE_TRACK_ACTION + track.id) }) {
                    Text("Delete", color = Color(0xFFFF7474))
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    Row(
        Modifier.fillMaxWidth().height(T4_TRACK_HEIGHT.dp)
            .background(if (selected) T4Accent.copy(alpha = .12f) else Color(0xFF121217))
            .border(.5.dp, T4Divider)
            .pointerInput(track.id) {
                detectTapGestures(onTap = { onSelect(track.id) }, onLongPress = { confirmDelete = true })
            }
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(3.dp).fillMaxHeight()
                .background(if (track.kind == TrackKind.VIDEO) Color(0xFF607D9B) else Color(0xFF3E7569)),
        )
        Spacer(Modifier.width(5.dp))
        Text(track.name, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = .8f))
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
                drawLine(
                    Color.White.copy(alpha = if (major) .30f else .11f),
                    androidx.compose.ui.geometry.Offset(x, if (major) 5f else 14f),
                    androidx.compose.ui.geometry.Offset(x, size.height),
                    1f,
                )
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
    textOverlays: List<TextOverlayClip>,
    selectedTextId: String?,
    selectedClipIds: Set<String>,
    cursorUs: Long,
    pps: Float,
    width: Dp,
    vm: EditorViewModelV4,
    onSelectClip: (String) -> Unit,
    onMoveClip: (String, String, Long) -> Unit,
    onMoveClipToTrack: (String, String) -> Unit,
    onSelectText: (TextOverlayClip) -> Unit,
    onMoveText: (String, Long) -> Unit,
    onMoveTextToTrack: (String, String) -> Unit,
    onTransitionCut: (TransitionCutTargetV23) -> Unit,
) {
    val isVideo = track.kind == TrackKind.VIDEO
    Box(
        Modifier.requiredWidth(width).height(T4_TRACK_HEIGHT.dp)
            .background(if (isVideo) Color(0xFF10141A) else Color(0xFF101713))
            .border(.5.dp, T4Divider),
    ) {
        track.clips.forEach { clip ->
            ClipV4(
                project = project,
                track = track,
                clip = clip,
                selected = clip.id in selectedClipIds,
                cursorUs = cursorUs,
                pps = pps,
                vm = vm,
                onSelectClip = onSelectClip,
                onMoveClip = onMoveClip,
                onMoveClipToTrack = onMoveClipToTrack,
            )
        }
        if (isVideo) {
            textOverlays.forEach { overlay ->
                TextClipV10(
                    project = project,
                    track = track,
                    overlay = overlay,
                    selected = overlay.id == selectedTextId,
                    cursorUs = cursorUs,
                    pps = pps,
                    vm = vm,
                    onSelect = { onSelectText(overlay) },
                    onMoveText = onMoveText,
                    onMoveTextToTrack = onMoveTextToTrack,
                )
            }
            project.visualOverlaysForVideoTrackV19(track.id).forEach { overlay ->
                VisualOverlayTimelineItemV19(
                    project = project,
                    track = track,
                    overlay = overlay,
                    pps = pps,
                    vm = vm,
                )
            }
            track.capCutTransitionCutsV23().forEach { target ->
                CapCutTransitionCutButtonV23(
                    target = target,
                    pps = pps,
                    onClick = { onTransitionCut(target) },
                )
            }
        }
    }
}

@Composable
private fun TextClipV10(
    project: TimelineProject,
    track: TimelineTrack,
    overlay: TextOverlayClip,
    selected: Boolean,
    cursorUs: Long,
    pps: Float,
    vm: EditorViewModelV4,
    onSelect: () -> Unit,
    onMoveText: (String, Long) -> Unit,
    onMoveTextToTrack: (String, String) -> Unit,
) {
    val density = LocalDensity.current
    val ppsDp = with(density) { pps.toDp().value }
    val frameUs = (US_PER_SECOND.toDouble() / project.frameRate.coerceAtLeast(1)).roundToLong().coerceAtLeast(1L)
    val compatible = project.tracks.filter { it.kind == TrackKind.VIDEO }
    val sourceIndex = compatible.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
    val trackPx = with(density) { T4_TRACK_HEIGHT.dp.toPx() }
    var rawDragX by remember(overlay.id) { mutableFloatStateOf(0f) }
    var dragY by remember(overlay.id) { mutableFloatStateOf(0f) }
    var displayDeltaUs by remember(overlay.id) { mutableStateOf(0L) }
    var magnetActive by remember(overlay.id) { mutableStateOf(false) }
    var previewStartUs by remember(overlay.id) { mutableStateOf<Long?>(null) }
    var previewEndUs by remember(overlay.id) { mutableStateOf<Long?>(null) }

    val shownStartUs = previewStartUs ?: overlay.timelineStartUs
    val shownEndUs = previewEndUs ?: overlay.timelineEndUs
    val start = (shownStartUs / US_PER_SECOND.toFloat() * ppsDp).dp
    val width = ((shownEndUs - shownStartUs).coerceAtLeast(1L) / US_PER_SECOND.toFloat() * ppsDp).coerceAtLeast(3f).dp

    Box(
        Modifier.offset(x = start, y = 3.dp)
            .width(width)
            .height(31.dp)
            .graphicsLayer {
                translationX = displayDeltaUs / US_PER_SECOND.toFloat() * pps
                translationY = dragY
            }
            .clip(RoundedCornerShape(4.dp))
            .background(T4Text)
            .border(
                if (selected || magnetActive) 2.dp else .5.dp,
                when {
                    magnetActive -> T4Magnet
                    selected -> T4Accent
                    else -> Color.White.copy(alpha = .16f)
                },
                RoundedCornerShape(4.dp),
            )
            .clickable(onClick = onSelect)
            .pointerInput(overlay.id, track.id, pps, project, cursorUs) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        rawDragX = 0f; dragY = 0f; displayDeltaUs = 0L; magnetActive = false; onSelect()
                    },
                    onDragEnd = {
                        val delta = displayDeltaUs
                        if (delta != 0L) onMoveText(overlay.id, delta)
                        val shift = (dragY / trackPx).roundToInt()
                        if (shift != 0 && compatible.isNotEmpty()) {
                            val target = compatible[(sourceIndex + shift).coerceIn(0, compatible.lastIndex)]
                            if (target.id != track.id) onMoveTextToTrack(overlay.id, target.id)
                        }
                        rawDragX = 0f; dragY = 0f; displayDeltaUs = 0L; magnetActive = false
                    },
                    onDragCancel = { rawDragX = 0f; dragY = 0f; displayDeltaUs = 0L; magnetActive = false },
                ) { change, drag ->
                    change.consume()
                    rawDragX += drag.x
                    dragY += drag.y
                    val rawUs = rawDragX / pps * US_PER_SECOND
                    val result = resolveTextMagneticDelta(project, track.id, overlay.id, rawUs.roundToLong(), cursorUs, pps, frameUs)
                    displayDeltaUs = result.deltaUs
                    magnetActive = result.magnet
                }
            }
            .padding(horizontal = if (selected) 9.dp else 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (width > 20.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    overlay.text.ifBlank { "Text" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 8.sp,
                    color = Color.White.copy(alpha = .96f),
                    modifier = Modifier.weight(1f),
                )
                if (magnetActive) Text("SNAP", fontSize = 6.sp, color = T4Magnet)
                else Text("T", fontSize = 6.sp, color = Color.White.copy(alpha = .55f))
            }
        }

        if (selected) {
            EdgeHandleV14(
                left = true,
                onPreviewDeltaPx = { deltaPx ->
                    val deltaUs = deltaPx / pps * US_PER_SECOND
                    previewStartUs = (overlay.timelineStartUs + deltaUs.roundToLong())
                        .coerceIn(0L, overlay.timelineEndUs - T4_MIN_TRIM_US)
                },
                onCommitDeltaPx = { deltaPx ->
                    val deltaUs = deltaPx / pps * US_PER_SECOND
                    val targetStartUs = (overlay.timelineStartUs + deltaUs.roundToLong())
                        .coerceIn(0L, overlay.timelineEndUs - T4_MIN_TRIM_US)
                    vm.resizeTextStartV13(overlay.id, targetStartUs)
                    previewStartUs = null
                },
                onCancel = { previewStartUs = null },
            )
            EdgeHandleV14(
                left = false,
                onPreviewDeltaPx = { deltaPx ->
                    val deltaUs = deltaPx / pps * US_PER_SECOND
                    previewEndUs = (overlay.timelineEndUs + deltaUs.roundToLong())
                        .coerceAtLeast(overlay.timelineStartUs + T4_MIN_TRIM_US)
                },
                onCommitDeltaPx = { deltaPx ->
                    val deltaUs = deltaPx / pps * US_PER_SECOND
                    val targetEndUs = (overlay.timelineEndUs + deltaUs.roundToLong())
                        .coerceAtLeast(overlay.timelineStartUs + T4_MIN_TRIM_US)
                    vm.resizeTextEndV13(overlay.id, targetEndUs)
                    previewEndUs = null
                },
                onCancel = { previewEndUs = null },
            )
        }
    }
}

@Composable
private fun ClipV4(
    project: TimelineProject,
    track: TimelineTrack,
    clip: TimelineClip,
    selected: Boolean,
    cursorUs: Long,
    pps: Float,
    vm: EditorViewModelV4,
    onSelectClip: (String) -> Unit,
    onMoveClip: (String, String, Long) -> Unit,
    onMoveClipToTrack: (String, String) -> Unit,
) {
    val density = LocalDensity.current
    val ppsDp = with(density) { pps.toDp().value }
    val frameUs = (US_PER_SECOND.toDouble() / project.frameRate.coerceAtLeast(1)).roundToLong().coerceAtLeast(1L)
    val compatible = project.tracks.filter { it.kind == track.kind }
    val sourceIndex = compatible.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
    val trackPx = with(density) { T4_TRACK_HEIGHT.dp.toPx() }
    val selectedEffect by EffectTimelineSelectionBusV26.selection.collectAsState()
    val effectItems = if (track.kind == TrackKind.VIDEO) {
        clip.nodeGraph.nodes
            .filter { it.kind == NodeKind.SERIAL || it.kind == NodeKind.PARALLEL }
            .flatMap { node -> node.visibleEffects().map { effect -> node.id to effect } }
    } else {
        emptyList()
    }
    val effectSelectedOnClip = selectedEffect?.clipId == clip.id
    var rawDragX by remember(clip.id) { mutableFloatStateOf(0f) }
    var dragY by remember(clip.id) { mutableFloatStateOf(0f) }
    var displayDeltaUs by remember(clip.id) { mutableStateOf(0L) }
    var magnetActive by remember(clip.id) { mutableStateOf(false) }
    var previewStartUs by remember(clip.id) { mutableStateOf<Long?>(null) }
    var previewEndUs by remember(clip.id) { mutableStateOf<Long?>(null) }

    val shownStartUs = previewStartUs ?: clip.timelineStartUs
    val shownEndUs = previewEndUs ?: clip.timelineEndUs
    val start = (shownStartUs / US_PER_SECOND.toFloat() * ppsDp).dp
    val width = ((shownEndUs - shownStartUs).coerceAtLeast(1L) / US_PER_SECOND.toFloat() * ppsDp).coerceAtLeast(3f).dp

    Box(
        Modifier.offset(x = start, y = 3.dp).width(width).height(31.dp)
            .graphicsLayer {
                translationX = displayDeltaUs / US_PER_SECOND.toFloat() * pps
                translationY = dragY
            }
            .clip(RoundedCornerShape(4.dp))
            .background(if (track.kind == TrackKind.VIDEO) T4Video else T4Audio)
            .border(
                if (selected || magnetActive) 2.dp else .5.dp,
                when {
                    magnetActive -> T4Magnet
                    selected -> T4Accent
                    else -> Color.White.copy(alpha = .14f)
                },
                RoundedCornerShape(4.dp),
            )
            .clickable {
                EffectTimelineSelectionBusV26.clear()
                TimelineTextSelectionBusV10.clear()
                VisualOverlaySelectionBusV19.clear()
                onSelectClip(clip.id)
            }
            .pointerInput(clip.id, track.id, pps, project, cursorUs) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        rawDragX = 0f; dragY = 0f; displayDeltaUs = 0L; magnetActive = false
                        EffectTimelineSelectionBusV26.clear()
                        TimelineTextSelectionBusV10.clear(); VisualOverlaySelectionBusV19.clear(); onSelectClip(clip.id)
                    },
                    onDragEnd = {
                        val delta = displayDeltaUs
                        if (delta != 0L) onMoveClip(track.id, clip.id, delta)
                        val shift = (dragY / trackPx).roundToInt()
                        if (shift != 0 && compatible.isNotEmpty()) {
                            val target = compatible[(sourceIndex + shift).coerceIn(0, compatible.lastIndex)]
                            if (target.id != track.id) onMoveClipToTrack(clip.id, target.id)
                        }
                        rawDragX = 0f; dragY = 0f; displayDeltaUs = 0L; magnetActive = false
                    },
                    onDragCancel = { rawDragX = 0f; dragY = 0f; displayDeltaUs = 0L; magnetActive = false },
                ) { change, drag ->
                    change.consume()
                    rawDragX += drag.x
                    dragY += drag.y
                    val rawUs = rawDragX / pps * US_PER_SECOND
                    val result = resolveMagneticDelta(project, clip.id, rawUs.roundToLong(), cursorUs, pps, frameUs)
                    displayDeltaUs = result.deltaUs
                    magnetActive = result.magnet
                }
            }
            .padding(horizontal = if (selected && track.kind == TrackKind.VIDEO && !effectSelectedOnClip) 9.dp else 3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (track.kind == TrackKind.AUDIO) {
            TimelineAudioWaveformV15(clip, Modifier.fillMaxSize())
        }

        if (width > 24.dp) {
            Row(
                modifier = Modifier.padding(top = if (effectItems.isNotEmpty()) 11.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(clip.label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 8.sp, color = Color.White.copy(alpha = .9f), modifier = Modifier.weight(1f))
                if (magnetActive) Text("SNAP", fontSize = 6.sp, color = T4Magnet)
                else if (clip.linkGroupId != null) Text("link", fontSize = 6.sp, color = Color.White.copy(alpha = .45f))
            }
        }

        effectItems.forEachIndexed { index, (nodeId, effect) ->
            EffectBarV26(
                clip = clip,
                nodeId = nodeId,
                effect = effect,
                selected = selectedEffect?.clipId == clip.id && selectedEffect?.nodeId == nodeId && selectedEffect?.effectId == effect.id,
                row = index % 2,
                pps = pps,
                frameUs = frameUs,
                vm = vm,
                onSelectClip = onSelectClip,
            )
        }

        if (selected && track.kind == TrackKind.VIDEO && !effectSelectedOnClip) {
            EdgeHandleV14(
                left = true,
                onPreviewDeltaPx = { deltaPx ->
                    val deltaUs = deltaPx / pps * US_PER_SECOND
                    previewStartUs = (clip.timelineStartUs + deltaUs.roundToLong())
                        .coerceIn(0L, clip.timelineEndUs - T4_MIN_TRIM_US)
                },
                onCommitDeltaPx = { deltaPx ->
                    val deltaUs = deltaPx / pps * US_PER_SECOND
                    val targetStartUs = (clip.timelineStartUs + deltaUs.roundToLong())
                        .coerceIn(0L, clip.timelineEndUs - T4_MIN_TRIM_US)
                    vm.resizeVideoClipStartV13(clip.id, targetStartUs)
                    previewStartUs = null
                },
                onCancel = { previewStartUs = null },
            )
            EdgeHandleV14(
                left = false,
                onPreviewDeltaPx = { deltaPx ->
                    val deltaUs = deltaPx / pps * US_PER_SECOND
                    previewEndUs = (clip.timelineEndUs + deltaUs.roundToLong())
                        .coerceAtLeast(clip.timelineStartUs + T4_MIN_TRIM_US)
                },
                onCommitDeltaPx = { deltaPx ->
                    val deltaUs = deltaPx / pps * US_PER_SECOND
                    val targetEndUs = (clip.timelineEndUs + deltaUs.roundToLong())
                        .coerceAtLeast(clip.timelineStartUs + T4_MIN_TRIM_US)
                    vm.resizeVideoClipEndV13(clip.id, targetEndUs)
                    previewEndUs = null
                },
                onCancel = { previewEndUs = null },
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.EffectBarV26(
    clip: TimelineClip,
    nodeId: String,
    effect: NodeEffect,
    selected: Boolean,
    row: Int,
    pps: Float,
    frameUs: Long,
    vm: EditorViewModelV4,
    onSelectClip: (String) -> Unit,
) {
    val density = LocalDensity.current
    val ppsDp = with(density) { pps.toDp().value }
    val selection = EffectTimelineSelectionV26(clip.id, nodeId, effect.id)
    val baseStart = effect.resolvedSourceStartUsV26(clip)
    val baseEnd = effect.resolvedSourceEndUsV26(clip)
    var previewStart by remember(effect.id) { mutableStateOf<Long?>(null) }
    var previewEnd by remember(effect.id) { mutableStateOf<Long?>(null) }
    var rawMoveX by remember(effect.id) { mutableFloatStateOf(0f) }
    var moveDeltaUs by remember(effect.id) { mutableStateOf(0L) }
    val shownStart = previewStart ?: baseStart
    val shownEnd = previewEnd ?: baseEnd
    val localStart = (shownStart - clip.sourceInUs).coerceAtLeast(0L)
    val localEnd = (shownEnd - clip.sourceInUs).coerceAtLeast(localStart + 1L)
    val x = (localStart / US_PER_SECOND.toFloat() * ppsDp).dp
    val barWidth = ((localEnd - localStart) / US_PER_SECOND.toFloat() * ppsDp).coerceAtLeast(5f).dp

    fun select() {
        TimelineTextSelectionBusV10.clear()
        VisualOverlaySelectionBusV19.clear()
        onSelectClip(clip.id)
        vm.selectNode(nodeId)
        EffectTimelineSelectionBusV26.select(clip.id, nodeId, effect.id)
    }

    Box(
        Modifier.offset(x = x, y = (1 + row * 6).dp)
            .width(barWidth)
            .height(7.dp)
            .graphicsLayer { translationX = moveDeltaUs / US_PER_SECOND.toFloat() * pps }
            .clip(RoundedCornerShape(2.dp))
            .background(if (selected) T4Accent.copy(alpha = .92f) else T4Effect.copy(alpha = .90f))
            .border(
                if (selected) 1.dp else .5.dp,
                if (selected) Color.White else Color.White.copy(alpha = .24f),
                RoundedCornerShape(2.dp),
            )
            .clickable { select() }
            .pointerInput(effect.id, clip.id, pps, frameUs) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        rawMoveX = 0f
                        moveDeltaUs = 0L
                        select()
                    },
                    onDragEnd = {
                        if (moveDeltaUs != 0L) vm.moveEffectTimelineV26(selection, moveDeltaUs)
                        rawMoveX = 0f
                        moveDeltaUs = 0L
                    },
                    onDragCancel = {
                        rawMoveX = 0f
                        moveDeltaUs = 0L
                    },
                ) { change, drag ->
                    change.consume()
                    rawMoveX += drag.x
                    val rawUs = rawMoveX / pps.coerceAtLeast(.001f) * US_PER_SECOND
                    val snapped = (rawUs / frameUs).roundToLong() * frameUs
                    val minDelta = clip.sourceInUs - baseStart
                    val maxDelta = clip.sourceOutUs - baseEnd
                    moveDeltaUs = snapped.coerceIn(minDelta, maxDelta)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        if (barWidth > 34.dp) {
            Text(
                effect.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 5.sp,
                color = if (selected) Color(0xFF061612) else Color.White.copy(alpha = .90f),
                modifier = Modifier.padding(horizontal = if (selected) 6.dp else 3.dp),
            )
        }
        if (selected) {
            EffectEdgeHandleV26(
                left = true,
                onPreview = { deltaPx ->
                    val deltaUs = deltaPx / pps.coerceAtLeast(.001f) * US_PER_SECOND
                    val snapped = (deltaUs / frameUs).roundToLong() * frameUs
                    val minDuration = minOf(EFFECT_MIN_DURATION_US_V26, clip.durationUs).coerceAtLeast(1L)
                    previewStart = (baseStart + snapped)
                        .coerceIn(clip.sourceInUs, (baseEnd - minDuration).coerceAtLeast(clip.sourceInUs))
                },
                onCommit = { deltaPx ->
                    val deltaUs = deltaPx / pps.coerceAtLeast(.001f) * US_PER_SECOND
                    val snapped = (deltaUs / frameUs).roundToLong() * frameUs
                    val minDuration = minOf(EFFECT_MIN_DURATION_US_V26, clip.durationUs).coerceAtLeast(1L)
                    val target = (baseStart + snapped)
                        .coerceIn(clip.sourceInUs, (baseEnd - minDuration).coerceAtLeast(clip.sourceInUs))
                    vm.resizeEffectStartV26(selection, target)
                    previewStart = null
                },
                onCancel = { previewStart = null },
            )
            EffectEdgeHandleV26(
                left = false,
                onPreview = { deltaPx ->
                    val deltaUs = deltaPx / pps.coerceAtLeast(.001f) * US_PER_SECOND
                    val snapped = (deltaUs / frameUs).roundToLong() * frameUs
                    val minDuration = minOf(EFFECT_MIN_DURATION_US_V26, clip.durationUs).coerceAtLeast(1L)
                    previewEnd = (baseEnd + snapped)
                        .coerceIn((baseStart + minDuration).coerceAtMost(clip.sourceOutUs), clip.sourceOutUs)
                },
                onCommit = { deltaPx ->
                    val deltaUs = deltaPx / pps.coerceAtLeast(.001f) * US_PER_SECOND
                    val snapped = (deltaUs / frameUs).roundToLong() * frameUs
                    val minDuration = minOf(EFFECT_MIN_DURATION_US_V26, clip.durationUs).coerceAtLeast(1L)
                    val target = (baseEnd + snapped)
                        .coerceIn((baseStart + minDuration).coerceAtMost(clip.sourceOutUs), clip.sourceOutUs)
                    vm.resizeEffectEndV26(selection, target)
                    previewEnd = null
                },
                onCancel = { previewEnd = null },
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.EffectEdgeHandleV26(
    left: Boolean,
    onPreview: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    onCancel: () -> Unit,
) {
    var totalPx by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier.align(if (left) Alignment.CenterStart else Alignment.CenterEnd)
            .width(6.dp)
            .fillMaxHeight()
            .background(Color.White.copy(alpha = .62f))
            .pointerInput(left) {
                detectDragGestures(
                    onDragStart = { totalPx = 0f },
                    onDragEnd = {
                        onCommit(totalPx)
                        totalPx = 0f
                    },
                    onDragCancel = {
                        onCancel()
                        totalPx = 0f
                    },
                ) { change, drag ->
                    change.consume()
                    totalPx += drag.x
                    onPreview(totalPx)
                }
            },
    )
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.EdgeHandleV14(
    left: Boolean,
    onPreviewDeltaPx: (Float) -> Unit,
    onCommitDeltaPx: (Float) -> Unit,
    onCancel: () -> Unit,
) {
    var totalPx by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier
            .align(if (left) Alignment.CenterStart else Alignment.CenterEnd)
            .width(8.dp)
            .fillMaxHeight()
            .background(Color.White.copy(alpha = .22f))
            .pointerInput(left) {
                detectDragGestures(
                    onDragStart = { totalPx = 0f },
                    onDragEnd = {
                        val committedDeltaPx = totalPx
                        onCommitDeltaPx(committedDeltaPx)
                        totalPx = 0f
                    },
                    onDragCancel = {
                        onCancel()
                        totalPx = 0f
                    },
                ) { change, drag ->
                    change.consume()
                    totalPx += drag.x
                    onPreviewDeltaPx(totalPx)
                }
            },
    )
}

private fun resolveTextMagneticDelta(
    project: TimelineProject,
    trackId: String,
    textId: String,
    rawDeltaUs: Long,
    cursorUs: Long,
    pps: Float,
    frameUs: Long,
): T4SnapResult {
    val target = project.textOverlays.firstOrNull { it.id == textId } ?: return T4SnapResult(0L, false)
    val track = project.track(trackId)?.takeIf { it.kind == TrackKind.VIDEO } ?: return T4SnapResult(0L, false)
    val laneItems = buildList<Pair<Long, Long>> {
        track.clips.forEach { add(it.timelineStartUs to it.timelineEndUs) }
        project.textOverlaysForVideoTrackV3(trackId).filterNot { it.id == textId }.forEach { add(it.timelineStartUs to it.timelineEndUs) }
        project.visualOverlaysForVideoTrackV19(trackId).forEach { add(it.timelineStartUs to it.timelineEndUs) }
    }
    val previous = laneItems.filter { it.second <= target.timelineStartUs }.maxByOrNull { it.second }
    val next = laneItems.filter { it.first >= target.timelineEndUs }.minByOrNull { it.first }
    val lower = max(-target.timelineStartUs, previous?.let { it.second - target.timelineStartUs } ?: Long.MIN_VALUE / 4)
    val upper = next?.let { it.first - target.timelineEndUs } ?: Long.MAX_VALUE / 4
    if (lower > upper) return T4SnapResult(0L, false)

    val frameDelta = ((rawDeltaUs.toDouble() / frameUs).roundToLong() * frameUs).coerceIn(lower, upper)
    val thresholdUs = ((14f / pps.coerceAtLeast(.001f)) * US_PER_SECOND).roundToLong().coerceIn(frameUs, 500_000L)
    val anchors = linkedSetOf<Long>()
    anchors += 0L
    anchors += cursorUs.coerceAtLeast(0L)
    project.tracks.flatMap { it.clips }.forEach { anchors += it.timelineStartUs; anchors += it.timelineEndUs }
    project.textOverlays.filterNot { it.id == textId }.forEach { anchors += it.timelineStartUs; anchors += it.timelineEndUs }
    project.resolvedVisualOverlaysV19().forEach { anchors += it.timelineStartUs; anchors += it.timelineEndUs }

    var best = frameDelta
    var bestDistance = Long.MAX_VALUE
    longArrayOf(target.timelineStartUs, target.timelineEndUs).forEach { edge ->
        anchors.forEach { anchor ->
            val candidate = anchor - edge
            if (candidate in lower..upper) {
                val distance = abs(candidate - frameDelta)
                if (distance <= thresholdUs && distance < bestDistance) { best = candidate; bestDistance = distance }
            }
        }
    }
    return T4SnapResult(best.coerceIn(lower, upper), bestDistance != Long.MAX_VALUE && best != frameDelta)
}

private fun resolveMagneticDelta(
    project: TimelineProject,
    clipId: String,
    rawDeltaUs: Long,
    cursorUs: Long,
    pps: Float,
    frameUs: Long,
): T4SnapResult {
    val target = project.clip(clipId) ?: return T4SnapResult(0L, false)
    val movingIds = if (target.linkGroupId == null) setOf(target.id) else project.linkedClipIds(target.id)
    val movingClips = movingIds.mapNotNull(project::clip)
    if (movingClips.isEmpty()) return T4SnapResult(0L, false)

    var lower = Long.MIN_VALUE / 4
    var upper = Long.MAX_VALUE / 4
    for (moving in movingClips) {
        val owner = project.trackContaining(moving.id) ?: continue
        val mediaItems = owner.clips.filter { it.id !in movingIds }.map { it.timelineStartUs to it.timelineEndUs }
        val titleItems = if (owner.kind == TrackKind.VIDEO) {
            project.textOverlaysForVideoTrackV3(owner.id).map { it.timelineStartUs to it.timelineEndUs } +
                project.visualOverlaysForVideoTrackV19(owner.id).map { it.timelineStartUs to it.timelineEndUs }
        } else emptyList()
        val others = mediaItems + titleItems
        val previous = others.filter { it.second <= moving.timelineStartUs }.maxByOrNull { it.second }
        val next = others.filter { it.first >= moving.timelineEndUs }.minByOrNull { it.first }
        lower = max(lower, max(-moving.timelineStartUs, previous?.let { it.second - moving.timelineStartUs } ?: Long.MIN_VALUE / 4))
        upper = min(upper, next?.let { it.first - moving.timelineEndUs } ?: Long.MAX_VALUE / 4)
    }
    if (lower > upper) return T4SnapResult(0L, false)

    val frameDelta = ((rawDeltaUs.toDouble() / frameUs).roundToLong() * frameUs).coerceIn(lower, upper)
    val thresholdUs = ((14f / pps.coerceAtLeast(.001f)) * US_PER_SECOND).roundToLong().coerceIn(frameUs, 500_000L)
    val anchors = linkedSetOf<Long>()
    anchors += 0L
    anchors += cursorUs.coerceAtLeast(0L)
    project.tracks.flatMap { it.clips }.filter { it.id !in movingIds }.forEach { anchors += it.timelineStartUs; anchors += it.timelineEndUs }
    project.textOverlays.forEach { anchors += it.timelineStartUs; anchors += it.timelineEndUs }
    project.resolvedVisualOverlaysV19().forEach { anchors += it.timelineStartUs; anchors += it.timelineEndUs }

    var best = frameDelta
    var bestDistance = Long.MAX_VALUE
    movingClips.forEach { moving ->
        longArrayOf(moving.timelineStartUs, moving.timelineEndUs).forEach { edge ->
            anchors.forEach { anchor ->
                val candidate = anchor - edge
                if (candidate in lower..upper) {
                    val distance = abs(candidate - frameDelta)
                    if (distance <= thresholdUs && distance < bestDistance) { best = candidate; bestDistance = distance }
                }
            }
        }
    }
    return T4SnapResult(best.coerceIn(lower, upper), bestDistance != Long.MAX_VALUE && best != frameDelta)
}
