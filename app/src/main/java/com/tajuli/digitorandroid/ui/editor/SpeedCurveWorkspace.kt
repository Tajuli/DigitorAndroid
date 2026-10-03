package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.model.*
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

private val SpeedAccent = Color(0xFF30E0C3)
private val SpeedMuted = Color(0xFF909098)
private fun speedLabel(speed: Float) = "${(speed * 100).roundToInt() / 100f}x"

@Composable
internal fun SpeedCurveWorkspace(clip: TimelineClip, busyOperation: String?, vm: EditorViewModel) {
    // A bake replaces the media while retaining the clip ID. Start a fresh draft on that result.
    var curveMode by remember(clip.id, clip.uri) { mutableStateOf(false) }
    var normal by remember(clip.id, clip.uri) { mutableStateOf(1f) }
    var preset by remember(clip.id, clip.uri) { mutableStateOf<SpeedCurvePreset?>(null) }
    var points by remember(clip.id, clip.uri) { mutableStateOf(SpeedCurveSpec.preset(SpeedCurvePreset.CUSTOM).points) }
    var smooth by remember(clip.id, clip.uri) { mutableStateOf(false) }
    var selected by remember(clip.id, clip.uri) { mutableIntStateOf(2) }
    val enabled = busyOperation == null
    val spec = remember(curveMode, normal, points, smooth) {
        if (curveMode) SpeedCurveSpec(points, preset ?: SpeedCurvePreset.CUSTOM, smooth)
        else SpeedCurveSpec.constant(normal, smooth)
    }
    val outputUs = remember(spec, clip.durationUs) { spec.sampledSchedule(clip.durationUs).outputDurationUs }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(false to "Normal", true to "Curve").forEach { (mode, label) ->
            FilterChip(selected = curveMode == mode, enabled = enabled,
                onClick = { curveMode = mode }, label = { Text(label, fontSize = 13.sp) })
        }
    }
    if (!curveMode) {
        Text(speedLabel(normal), color = SpeedAccent, fontSize = 22.sp)
        Slider(value = (log10(normal) + 1f) / 3f, enabled = enabled,
            onValueChange = { normal = (10f.pow(it * 3f - 1f) * 100).roundToInt().div(100f).coerceIn(.1f, 100f) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(.1f, 1f, 10f, 100f).forEach { speed ->
                Text(speedLabel(speed), color = SpeedMuted, fontSize = 12.sp,
                    modifier = Modifier.clickable(enabled) { normal = speed }.padding(vertical = 12.dp))
            }
        }
    } else {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val presets = listOf(null, SpeedCurvePreset.CUSTOM) + SpeedCurvePreset.entries.filter { it != SpeedCurvePreset.CUSTOM }
            presets.forEach { item ->
                Column(Modifier.width(76.dp)
                    .border(1.dp, if (preset == item) SpeedAccent else Color.DarkGray, RoundedCornerShape(8.dp))
                    .clickable(enabled) {
                        preset = item
                        points = SpeedCurveSpec.preset(item ?: SpeedCurvePreset.CUSTOM).points
                        selected = 2
                    }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CurveCanvas(SpeedCurveSpec.preset(item ?: SpeedCurvePreset.CUSTOM).points,
                        Modifier.fillMaxWidth().height(36.dp))
                    Text(item?.label ?: "None", fontSize = 11.sp, color = if (preset == item) SpeedAccent else Color.White)
                }
            }
        }
        Text("Drag a point to change speed and timing", color = SpeedMuted, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("10x", color = SpeedMuted, fontSize = 11.sp)
            Text(speedLabel(points[selected].speed), color = SpeedAccent, fontSize = 13.sp)
        }
        InteractiveCurve(points, selected, enabled, onSelect = { selected = it }, onChange = {
            points = it
            preset = SpeedCurvePreset.CUSTOM
        })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0.1x · Start", color = SpeedMuted, fontSize = 11.sp)
            Text("End", color = SpeedMuted, fontSize = 11.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(enabled = enabled && points.size < 9, onClick = {
                val index = points.zipWithNext().indices.maxByOrNull { points[it + 1].position - points[it].position } ?: 0
                val position = (points[index].position + points[index + 1].position) / 2f
                val point = SpeedCurvePoint(position, SpeedCurveSpec(points).speedAt(position))
                points = points.toMutableList().apply { add(index + 1, point) }
                selected = index + 1
                preset = SpeedCurvePreset.CUSTOM
            }) { Text("+ Add point", fontSize = 12.sp) }
            TextButton(enabled = enabled && selected > 0 && selected < points.lastIndex, onClick = {
                points = points.filterIndexed { index, _ -> index != selected }
                selected = (selected - 1).coerceAtLeast(0)
                preset = SpeedCurvePreset.CUSTOM
            }) { Text("Delete point", fontSize = 12.sp) }
        }
        // Also provides an accessible, fine-grained alternative to dragging the canvas.
        Text("Selected point · ${(points[selected].position * 100).roundToInt()}%", color = SpeedMuted, fontSize = 12.sp)
        Slider(value = (log10(points[selected].speed) + 1f) / 2f, enabled = enabled,
            onValueChange = { value ->
                points = points.mapIndexed { index, point ->
                    if (index == selected) point.copy(speed = 10f.pow(value * 2f - 1f)) else point
                }
                preset = SpeedCurvePreset.CUSTOM
            })
    }
    Text("Duration  ${"%.2f".format(clip.durationUs / 1_000_000.0)}s → ${"%.2f".format(outputUs / 1_000_000.0)}s",
        color = SpeedMuted, fontSize = 12.sp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = smooth, enabled = enabled, onCheckedChange = { smooth = it })
        Text("Smooth slow motion", fontSize = 13.sp)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(enabled = enabled, onClick = {
            normal = 1f
            preset = null
            points = SpeedCurveSpec.preset(SpeedCurvePreset.CUSTOM).points
            selected = 2
            smooth = false
        }) { Text("Reset") }
        Button(enabled = enabled && spec.points.any { kotlin.math.abs(it.speed - 1f) > .001f },
            onClick = { vm.bakeSelectedSpeedCurve(spec.copy(preset = preset ?: SpeedCurvePreset.CUSTOM)) }) {
            Text(if (enabled) "✓ Apply" else "Processing…")
        }
    }
}

@Composable
private fun InteractiveCurve(points: List<SpeedCurvePoint>, selected: Int, enabled: Boolean,
    onSelect: (Int) -> Unit, onChange: (List<SpeedCurvePoint>) -> Unit) {
    // Keep gesture detectors alive as drag updates recompose the curve.
    val latest by rememberUpdatedState(points)
    val select by rememberUpdatedState(onSelect)
    val change by rememberUpdatedState(onChange)
    var dragging by remember { mutableIntStateOf(-1) }
    fun location(point: SpeedCurvePoint, width: Float, height: Float, inset: Float) = Offset(
        inset + point.position * (width - 2 * inset),
        inset + (1f - (log10(point.speed) + 1f) / 2f) * (height - 2 * inset))
    CurveCanvas(points, Modifier.fillMaxWidth().height(180.dp)
        .background(Color(0xFF18181C), RoundedCornerShape(8.dp))
        .semantics { contentDescription = "Speed curve. Drag control points; selected speed ${speedLabel(points[selected].speed)}" }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectTapGestures { tap ->
                latest.indices.minByOrNull { (location(latest[it], size.width.toFloat(), size.height.toFloat(), 14.dp.toPx()) - tap).getDistance() }
                    ?.let { select(it) }
            }
        }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            val inset = 14.dp.toPx()
            detectDragGestures(onDragStart = { touch ->
                dragging = latest.indices.minByOrNull { (location(latest[it], size.width.toFloat(), size.height.toFloat(), inset) - touch).getDistance() } ?: -1
                if (dragging >= 0) select(dragging)
            }, onDragEnd = { dragging = -1 }, onDragCancel = { dragging = -1 }) { event, _ ->
                val index = dragging
                if (index in latest.indices) {
                    event.consume()
                    val width = (size.width - 2 * inset).coerceAtLeast(1f)
                    val height = (size.height - 2 * inset).coerceAtLeast(1f)
                    val old = latest[index]
                    val x = if (index == 0 || index == latest.lastIndex) old.position else
                        ((event.position.x - inset) / width).coerceIn(latest[index - 1].position + .01f, latest[index + 1].position - .01f)
                    val y = ((event.position.y - inset) / height).coerceIn(0f, 1f)
                    change(latest.mapIndexed { i, point -> if (i == index) SpeedCurvePoint(x, 10f.pow(1f - 2f * y)) else point })
                }
            }
        }, selected)
}

@Composable
private fun CurveCanvas(points: List<SpeedCurvePoint>, modifier: Modifier, selected: Int? = null) {
    val spec = remember(points) { SpeedCurveSpec(points) }
    Canvas(modifier) {
        val inset = if (selected == null) 2.dp.toPx() else 14.dp.toPx()
        val width = (size.width - 2 * inset).coerceAtLeast(1f)
        val height = (size.height - 2 * inset).coerceAtLeast(1f)
        fun xy(position: Float, speed: Float) = Offset(inset + position * width,
            inset + (1f - (log10(speed.coerceIn(.1f, 10f)) + 1f) / 2f) * height)
        drawLine(Color.White.copy(alpha = .25f), xy(0f, 1f), xy(1f, 1f), 1.dp.toPx())
        val path = Path()
        for (i in 0..120) {
            val p = i / 120f
            val point = xy(p, spec.speedAt(p))
            if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        drawPath(path, SpeedAccent, style = Stroke(2.dp.toPx()))
        if (selected != null) points.forEachIndexed { index, point ->
            val center = xy(point.position, point.speed)
            drawCircle(if (index == selected) SpeedAccent else Color.White, 6.dp.toPx(), center)
            if (index == selected) drawCircle(SpeedAccent.copy(alpha = .3f), 11.dp.toPx(), center, style = Stroke(2.dp.toPx()))
        }
    }
}
