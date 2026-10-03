package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.model.SpeedCurvePoint
import com.tajuli.digitorandroid.editor.model.SpeedCurvePreset
import com.tajuli.digitorandroid.editor.model.SpeedCurveSpec
import com.tajuli.digitorandroid.editor.model.TimelineClip
import kotlin.math.max

private val SpeedCurveAccent = Color(0xFF30E0C3)
private val SpeedCurveMuted = Color(0xFF909098)

@Composable
internal fun SpeedCurveWorkspace(
    clip: TimelineClip,
    busyOperation: String?,
    vm: EditorViewModel,
) {
    var preset by remember(clip.id) { mutableStateOf(SpeedCurvePreset.MONTAGE) }
    var smooth by remember(clip.id) { mutableStateOf(true) }
    var points by remember(clip.id) {
        mutableStateOf(SpeedCurveSpec.preset(SpeedCurvePreset.MONTAGE).points)
    }

    Text("Normal speed", fontSize = 8.sp, color = SpeedCurveMuted)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        listOf(.25f, .5f, .75f, 1.25f, 1.5f, 2f, 3f, 4f).forEach { speed ->
            FilledTonalButton(
                enabled = busyOperation == null,
                onClick = { vm.bakeSelectedSpeed(speed) },
            ) {
                Text("${speed}x", fontSize = 8.sp)
            }
        }
    }

    Text("Velocity curve", fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        SpeedCurvePreset.entries.forEach { item ->
            FilledTonalButton(
                enabled = busyOperation == null,
                onClick = {
                    preset = item
                    points = SpeedCurveSpec.preset(item, smooth).points
                },
            ) {
                Text(if (preset == item) "✓ ${item.label}" else item.label, fontSize = 7.sp)
            }
        }
    }

    SpeedCurveGraph(points)

    val editable = points.sortedBy { it.position }
    editable.forEachIndexed { index, point ->
        val endpoint = index == 0 || index == editable.lastIndex
        Column {
            Row {
                Text(
                    if (endpoint) {
                        if (index == 0) "Start" else "End"
                    } else {
                        "${(point.position * 100).toInt()}%"
                    },
                    modifier = Modifier.width(44.dp),
                    fontSize = 7.sp,
                    color = SpeedCurveMuted,
                )
                Slider(
                    value = point.speed.coerceIn(.1f, 10f),
                    onValueChange = { next ->
                        points = editable.mapIndexed { i, old ->
                            if (i == index) old.copy(speed = next) else old
                        }
                        preset = SpeedCurvePreset.CUSTOM
                    },
                    valueRange = .1f..10f,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    String.format("%.2fx", point.speed),
                    modifier = Modifier.width(48.dp),
                    fontSize = 7.sp,
                    color = if (point.speed < 1f) SpeedCurveAccent else Color.White.copy(alpha = .72f),
                )
            }
            if (!endpoint) {
                Row {
                    Text("Position", modifier = Modifier.width(44.dp), fontSize = 7.sp, color = SpeedCurveMuted)
                    val leftBound = editable[index - 1].position + .03f
                    val rightBound = editable[index + 1].position - .03f
                    Slider(
                        value = point.position.coerceIn(leftBound, rightBound),
                        onValueChange = { next ->
                            points = editable.mapIndexed { i, old ->
                                if (i == index) old.copy(position = next) else old
                            }.sortedBy { it.position }
                            preset = SpeedCurvePreset.CUSTOM
                        },
                        valueRange = leftBound..max(leftBound, rightBound),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            points = editable.filterIndexed { i, _ -> i != index }
                            preset = SpeedCurvePreset.CUSTOM
                        },
                    ) { Text("Remove", fontSize = 7.sp) }
                }
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(
            enabled = editable.size < 9,
            onClick = {
                val widest = editable.zipWithNext().withIndex()
                    .maxByOrNull { (_, pair) -> pair.second.position - pair.first.position }
                if (widest != null) {
                    val left = widest.value.first
                    val right = widest.value.second
                    val position = (left.position + right.position) * .5f
                    val speed = (left.speed + right.speed) * .5f
                    points = (editable + SpeedCurvePoint(position, speed)).sortedBy { it.position }
                    preset = SpeedCurvePreset.CUSTOM
                }
            },
        ) { Text("+ Point", fontSize = 7.sp) }
        TextButton(
            onClick = {
                preset = SpeedCurvePreset.CUSTOM
                points = SpeedCurveSpec.preset(SpeedCurvePreset.CUSTOM, smooth).points
            },
        ) { Text("Reset", fontSize = 7.sp) }
    }

    Row {
        Checkbox(checked = smooth, onCheckedChange = { smooth = it })
        Column {
            Text("Smooth Slow Motion", fontSize = 8.sp)
            Text(
                "Generates intermediate frames for sections below 1x instead of relying on repeated frames.",
                fontSize = 7.sp,
                color = SpeedCurveMuted,
            )
        }
    }

    Button(
        enabled = busyOperation == null,
        onClick = {
            vm.bakeSelectedSpeedCurve(
                SpeedCurveSpec(
                    points = points,
                    preset = preset,
                    smoothSlowMotion = smooth,
                ),
            )
        },
    ) {
        Text(if (smooth) "Apply Velocity + Smooth" else "Apply Velocity", fontSize = 8.sp)
    }
}

@Composable
private fun SpeedCurveGraph(points: List<SpeedCurvePoint>) {
    val spec = remember(points) { SpeedCurveSpec(points).normalized() }
    Canvas(Modifier.fillMaxWidth().height(92.dp)) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val maxSpeed = max(4f, spec.points.maxOfOrNull { it.speed } ?: 4f)
        val path = Path()
        val samples = 80
        for (i in 0..samples) {
            val p = i / samples.toFloat()
            val speed = spec.speedAt(p)
            val x = p * size.width
            val normalizedY = (speed / maxSpeed).coerceIn(0f, 1f)
            val y = size.height - normalizedY * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawLine(
            color = Color.White.copy(alpha = .18f),
            start = Offset(0f, size.height - (1f / maxSpeed) * size.height),
            end = Offset(size.width, size.height - (1f / maxSpeed) * size.height),
            strokeWidth = 1f,
        )
        drawPath(path, color = SpeedCurveAccent, style = Stroke(width = 3f))
    }
}
