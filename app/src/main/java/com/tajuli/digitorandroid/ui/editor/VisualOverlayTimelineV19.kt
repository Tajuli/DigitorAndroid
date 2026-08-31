package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.US_PER_SECOND
import com.tajuli.digitorandroid.editor.model.VisualOverlayClipV19
import com.tajuli.digitorandroid.editor.model.VisualOverlayKindV19
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val VOT19Fill = Color(0xFF7B5B3C)
private val VOT19Accent = Color(0xFF30E0C3)
private val VOT19Border = Color.White.copy(alpha = .18f)
private const val VOT19_TRACK_HEIGHT = 38f
private const val VOT19_MIN_DURATION_US = 100_000L

@Composable
internal fun VisualOverlayTimelineItemV19(
    project: TimelineProject,
    track: TimelineTrack,
    overlay: VisualOverlayClipV19,
    pps: Float,
    vm: EditorViewModelV4,
) {
    if (track.kind != TrackKind.VIDEO) return
    val selectedId by VisualOverlaySelectionBusV19.selectedId.collectAsState()
    val selected = selectedId == overlay.id
    val density = LocalDensity.current
    val ppsDp = with(density) { pps.toDp().value }
    val compatible = project.tracks.filter { it.kind == TrackKind.VIDEO }
    val sourceIndex = compatible.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
    val trackPx = with(density) { VOT19_TRACK_HEIGHT.dp.toPx() }
    val frameUs = (US_PER_SECOND.toDouble() / project.frameRate.coerceAtLeast(1)).roundToLong().coerceAtLeast(1L)
    var rawDragX by remember(overlay.id) { mutableFloatStateOf(0f) }
    var dragY by remember(overlay.id) { mutableFloatStateOf(0f) }
    var displayDeltaUs by remember(overlay.id) { mutableStateOf(0L) }
    var previewStartUs by remember(overlay.id) { mutableStateOf<Long?>(null) }
    var previewEndUs by remember(overlay.id) { mutableStateOf<Long?>(null) }

    val shownStart = previewStartUs ?: overlay.timelineStartUs
    val shownEnd = previewEndUs ?: overlay.timelineEndUs
    val start = (shownStart / US_PER_SECOND.toFloat() * ppsDp).dp
    val width = ((shownEnd - shownStart).coerceAtLeast(1L) / US_PER_SECOND.toFloat() * ppsDp).coerceAtLeast(3f).dp

    Box(
        Modifier.offset(x = start, y = 3.dp)
            .width(width)
            .height(31.dp)
            .graphicsLayer {
                translationX = displayDeltaUs / US_PER_SECOND.toFloat() * pps
                translationY = dragY
            }
            .clip(RoundedCornerShape(4.dp))
            .background(VOT19Fill)
            .border(if (selected) 2.dp else .5.dp, if (selected) VOT19Accent else VOT19Border, RoundedCornerShape(4.dp))
            .clickable { vm.selectVisualOverlayV19(overlay.id) }
            .pointerInput(overlay.id, track.id, pps, project) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        rawDragX = 0f
                        dragY = 0f
                        displayDeltaUs = 0L
                        vm.selectVisualOverlayV19(overlay.id)
                    },
                    onDragEnd = {
                        if (displayDeltaUs != 0L) vm.moveVisualOverlayV19(overlay.id, displayDeltaUs)
                        val shift = (dragY / trackPx).roundToInt()
                        if (shift != 0 && compatible.isNotEmpty()) {
                            val target = compatible[(sourceIndex + shift).coerceIn(0, compatible.lastIndex)]
                            if (target.id != track.id) vm.moveVisualOverlayToTrackV19(overlay.id, target.id)
                        }
                        rawDragX = 0f
                        dragY = 0f
                        displayDeltaUs = 0L
                    },
                    onDragCancel = {
                        rawDragX = 0f
                        dragY = 0f
                        displayDeltaUs = 0L
                    },
                ) { change, drag ->
                    change.consume()
                    rawDragX += drag.x
                    dragY += drag.y
                    val rawUs = rawDragX / pps.coerceAtLeast(.001f) * US_PER_SECOND
                    displayDeltaUs = (rawUs / frameUs).roundToLong() * frameUs
                }
            }
            .padding(horizontal = if (selected) 9.dp else 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (width > 18.dp) {
            Text(
                "${overlay.kind.shortV19()} · ${overlay.label}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 7.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = .94f),
            )
        }
        if (selected) {
            VisualEdgeHandleV19(
                left = true,
                onPreview = { px ->
                    val deltaUs = px / pps.coerceAtLeast(.001f) * US_PER_SECOND
                    previewStartUs = (overlay.timelineStartUs + deltaUs.roundToLong())
                        .coerceIn(0L, overlay.timelineEndUs - VOT19_MIN_DURATION_US)
                },
                onCommit = { px ->
                    val deltaUs = px / pps.coerceAtLeast(.001f) * US_PER_SECOND
                    vm.resizeVisualStartV19(
                        overlay.id,
                        (overlay.timelineStartUs + deltaUs.roundToLong())
                            .coerceIn(0L, overlay.timelineEndUs - VOT19_MIN_DURATION_US),
                    )
                    previewStartUs = null
                },
                onCancel = { previewStartUs = null },
            )
            VisualEdgeHandleV19(
                left = false,
                onPreview = { px ->
                    val deltaUs = px / pps.coerceAtLeast(.001f) * US_PER_SECOND
                    previewEndUs = (overlay.timelineEndUs + deltaUs.roundToLong())
                        .coerceAtLeast(overlay.timelineStartUs + VOT19_MIN_DURATION_US)
                },
                onCommit = { px ->
                    val deltaUs = px / pps.coerceAtLeast(.001f) * US_PER_SECOND
                    vm.resizeVisualEndV19(
                        overlay.id,
                        (overlay.timelineEndUs + deltaUs.roundToLong())
                            .coerceAtLeast(overlay.timelineStartUs + VOT19_MIN_DURATION_US),
                    )
                    previewEndUs = null
                },
                onCancel = { previewEndUs = null },
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.VisualEdgeHandleV19(
    left: Boolean,
    onPreview: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    onCancel: () -> Unit,
) {
    var totalPx by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier.align(if (left) Alignment.CenterStart else Alignment.CenterEnd)
            .width(8.dp)
            .fillMaxHeight()
            .background(Color.White.copy(alpha = .22f))
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

private fun VisualOverlayKindV19.shortV19(): String = when (this) {
    VisualOverlayKindV19.IMAGE -> "IMG"
    VisualOverlayKindV19.STICKER -> "STK"
    VisualOverlayKindV19.SHAPE -> "SHP"
}
