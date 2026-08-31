package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.model.ClipNodeGraph
import com.tajuli.digitorandroid.editor.model.ColorNode
import com.tajuli.digitorandroid.editor.model.ColorWheelValue
import com.tajuli.digitorandroid.editor.model.Curve5
import com.tajuli.digitorandroid.editor.model.CurvePoint
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.VisualOverlayClipV19
import kotlin.math.hypot

private val IG20Raised = Color(0xFF17171C)
private val IG20Divider = Color(0xFF292930)
private val IG20Muted = Color(0xFF909098)
private val IG20Accent = Color(0xFF30E0C3)
private val IG20WheelWidth = 184.dp
private val IG20WheelSize = 142.dp

private enum class ImageColorPageV20(val title: String) {
    PRIMARY("Primary"),
    LOG("Log"),
    CURVES("Curves"),
    QUALIFIER("HSL"),
}

@Composable
internal fun ImageCorrectionWorkspaceV20(
    overlay: VisualOverlayClipV19,
    vm: EditorViewModelV4,
) {
    val node = imageGradeNodeV20(overlay) ?: return
    val c = node.corrections
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text("Image Correction · ${node.label}", fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        Text("Same node correction math used by video clips", fontSize = 7.sp, color = IG20Muted)
        ImageGradeSliderV20("Exposure", c.exposure, -5f..5f) { vm.setImageNodeCorrectionV20("Exposure", it) }
        ImageGradeSliderV20("Contrast", c.contrast, -100f..100f) { vm.setImageNodeCorrectionV20("Contrast", it) }
        ImageGradeSliderV20("Saturation", c.saturation, -100f..100f) { vm.setImageNodeCorrectionV20("Saturation", it) }
        ImageGradeSliderV20("Temperature", c.temperature, -100f..100f) { vm.setImageNodeCorrectionV20("Temperature", it) }
        ImageGradeSliderV20("Tint", c.tint, -100f..100f) { vm.setImageNodeCorrectionV20("Tint", it) }
        ImageGradeSliderV20("Highlights", c.highlights, -100f..100f) { vm.setImageNodeCorrectionV20("Highlights", it) }
        ImageGradeSliderV20("Shadows", c.shadows, -100f..100f) { vm.setImageNodeCorrectionV20("Shadows", it) }
        ImageGradeSliderV20("Hue", c.hue, -180f..180f) { vm.setImageNodeCorrectionV20("Hue", it) }
    }
}

@Composable
internal fun ImageColorWorkspaceV20(
    overlay: VisualOverlayClipV19,
    vm: EditorViewModelV4,
) {
    val node = imageGradeNodeV20(overlay) ?: return
    var page by remember(overlay.id) { mutableStateOf(ImageColorPageV20.PRIMARY) }
    Column {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            ImageColorPageV20.entries.forEach { item ->
                Text(
                    item.title,
                    fontSize = 8.sp,
                    color = if (page == item) IG20Accent else Color.White.copy(alpha = .72f),
                    modifier = Modifier
                        .background(if (page == item) IG20Accent.copy(alpha = .12f) else IG20Raised, RoundedCornerShape(5.dp))
                        .clickable { page = item }
                        .padding(horizontal = 9.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.width(3.dp))
            FilledTonalButton(onClick = vm::resetSelectedImageGradeV20, modifier = Modifier.height(30.dp)) {
                Icon(Icons.Rounded.RestartAlt, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Reset grade", fontSize = 8.sp)
            }
        }
        Spacer(Modifier.height(5.dp))
        when (page) {
            ImageColorPageV20.PRIMARY -> ImagePrimaryPageV20(node, vm)
            ImageColorPageV20.LOG -> ImageLogPageV20(node, vm)
            ImageColorPageV20.CURVES -> ImageCurvesPageV20(node, vm)
            ImageColorPageV20.QUALIFIER -> ImageQualifierPageV20(node, vm)
        }
    }
}

@Composable
private fun imageGradeNodeV20(overlay: VisualOverlayClipV19): ColorNode? {
    val fallback = remember(overlay.id) { ClipNodeGraph.default() }
    return (overlay.imageNodeGraphV20 ?: fallback).selectedNode()
        ?.takeIf { it.kind == NodeKind.SERIAL || it.kind == NodeKind.PARALLEL }
}

@Composable
private fun ImagePrimaryPageV20(node: ColorNode, vm: EditorViewModelV4) {
    val p = node.advancedColor.primary
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ImageWheelCardV20("Lift", p.lift,
            onPuck = { x, y -> vm.setImagePrimaryWheelPuckV20("Lift", x, y) },
            onComponent = { component, value -> vm.setImagePrimaryWheelV20("Lift", component, value) },
            onReset = {
                vm.setImagePrimaryWheelPuckV20("Lift", 0f, 0f)
                vm.setImagePrimaryWheelV20("Lift", "luma", 0f)
            })
        ImageWheelCardV20("Gamma", p.gamma,
            onPuck = { x, y -> vm.setImagePrimaryWheelPuckV20("Gamma", x, y) },
            onComponent = { component, value -> vm.setImagePrimaryWheelV20("Gamma", component, value) },
            onReset = {
                vm.setImagePrimaryWheelPuckV20("Gamma", 0f, 0f)
                vm.setImagePrimaryWheelV20("Gamma", "luma", 0f)
            })
        ImageWheelCardV20("Gain", p.gain,
            onPuck = { x, y -> vm.setImagePrimaryWheelPuckV20("Gain", x, y) },
            onComponent = { component, value -> vm.setImagePrimaryWheelV20("Gain", component, value) },
            onReset = {
                vm.setImagePrimaryWheelPuckV20("Gain", 0f, 0f)
                vm.setImagePrimaryWheelV20("Gain", "luma", 0f)
            })
        ImageWheelCardV20("Offset", p.offset,
            onPuck = { x, y -> vm.setImagePrimaryWheelPuckV20("Offset", x, y) },
            onComponent = { component, value -> vm.setImagePrimaryWheelV20("Offset", component, value) },
            onReset = {
                vm.setImagePrimaryWheelPuckV20("Offset", 0f, 0f)
                vm.setImagePrimaryWheelV20("Offset", "luma", 0f)
            })
    }
}

@Composable
private fun ImageLogPageV20(node: ColorNode, vm: EditorViewModelV4) {
    val log = node.advancedColor.log
    Column {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ImageWheelCardV20("Shadows", log.shadows,
                onPuck = { x, y -> vm.setImageLogWheelPuckV20("Shadows", x, y) },
                onComponent = { component, value -> vm.setImageLogWheelV20("Shadows", component, value) },
                onReset = {
                    vm.setImageLogWheelPuckV20("Shadows", 0f, 0f)
                    vm.setImageLogWheelV20("Shadows", "luma", 0f)
                })
            ImageWheelCardV20("Midtones", log.midtones,
                onPuck = { x, y -> vm.setImageLogWheelPuckV20("Midtones", x, y) },
                onComponent = { component, value -> vm.setImageLogWheelV20("Midtones", component, value) },
                onReset = {
                    vm.setImageLogWheelPuckV20("Midtones", 0f, 0f)
                    vm.setImageLogWheelV20("Midtones", "luma", 0f)
                })
            ImageWheelCardV20("Highlights", log.highlights,
                onPuck = { x, y -> vm.setImageLogWheelPuckV20("Highlights", x, y) },
                onComponent = { component, value -> vm.setImageLogWheelV20("Highlights", component, value) },
                onReset = {
                    vm.setImageLogWheelPuckV20("Highlights", 0f, 0f)
                    vm.setImageLogWheelV20("Highlights", "luma", 0f)
                })
            ImageWheelCardV20("Global", log.global,
                onPuck = { x, y -> vm.setImageLogWheelPuckV20("Global", x, y) },
                onComponent = { component, value -> vm.setImageLogWheelV20("Global", component, value) },
                onReset = {
                    vm.setImageLogWheelPuckV20("Global", 0f, 0f)
                    vm.setImageLogWheelV20("Global", "luma", 0f)
                })
        }
        ImageGradeSliderV20("Shadow range", log.shadowRange, .05f..48f / 100f) {
            vm.setImageLogRangeV20(shadowRange = it)
        }
        ImageGradeSliderV20("Highlight range", log.highlightRange, .52f..95f / 100f) {
            vm.setImageLogRangeV20(highlightRange = it)
        }
    }
}

@Composable
private fun ImageWheelCardV20(
    title: String,
    value: ColorWheelValue,
    onPuck: (Float, Float) -> Unit,
    onComponent: (String, Float) -> Unit,
    onReset: () -> Unit,
) {
    Column(
        Modifier.width(IG20WheelWidth)
            .background(IG20Raised, RoundedCornerShape(9.dp))
            .border(1.dp, IG20Divider, RoundedCornerShape(9.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = onReset, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Rounded.RestartAlt, "Reset $title", modifier = Modifier.size(13.dp), tint = IG20Muted)
            }
        }
        ImageColorWheelV20(value, onPuck, Modifier.size(IG20WheelSize))
        ImageWheelSliderV20("Y", value.luma, Color.White.copy(alpha = .85f)) { onComponent("luma", it) }
        ImageWheelSliderV20("R", value.red, imageChannelColorV20("R")) { onComponent("red", it) }
        ImageWheelSliderV20("G", value.green, imageChannelColorV20("G")) { onComponent("green", it) }
        ImageWheelSliderV20("B", value.blue, imageChannelColorV20("B")) { onComponent("blue", it) }
    }
}

@Composable
private fun ImageColorWheelV20(
    value: ColorWheelValue,
    onPuck: (Float, Float) -> Unit,
    modifier: Modifier,
) {
    fun normalized(position: Offset, width: Float, height: Float): Pair<Float, Float> {
        val cx = width * .5f
        val cy = height * .5f
        val radius = minOf(width, height) * .45f
        var x = (position.x - cx) / radius
        var y = (position.y - cy) / radius
        val length = hypot(x.toDouble(), y.toDouble()).toFloat()
        if (length > 1f) {
            x /= length
            y /= length
        }
        return x to y
    }

    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val p = normalized(pos, size.width.toFloat(), size.height.toFloat())
                    onPuck(p.first, p.second)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { pos ->
                        val p = normalized(pos, size.width.toFloat(), size.height.toFloat())
                        onPuck(p.first, p.second)
                    },
                ) { change, _ ->
                    change.consume()
                    val p = normalized(change.position, size.width.toFloat(), size.height.toFloat())
                    onPuck(p.first, p.second)
                }
            },
    ) {
        val radius = size.minDimension * .45f
        val center = Offset(size.width * .5f, size.height * .5f)
        drawCircle(
            Brush.sweepGradient(
                listOf(Color.Red, Color.Magenta, Color.Blue, Color.Cyan, Color.Green, Color.Yellow, Color.Red),
                center,
            ),
            radius,
            center,
        )
        drawCircle(
            Brush.radialGradient(
                listOf(Color.White.copy(alpha = .96f), Color.White.copy(alpha = .35f), Color.Transparent),
                center,
                radius,
            ),
            radius,
            center,
        )
        drawCircle(Color.Black.copy(alpha = .20f), radius, center, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
        val puck = Offset(center.x + value.puckX * radius, center.y + value.puckY * radius)
        drawCircle(Color.Black.copy(alpha = .75f), 7f, puck)
        drawCircle(Color.White, 5.2f, puck, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
    }
}

@Composable
private fun ImageWheelSliderV20(
    label: String,
    value: Float,
    tint: Color,
    onValue: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(14.dp), fontSize = 8.sp, color = tint, fontWeight = FontWeight.SemiBold)
        Slider(
            value = value.coerceIn(-1f, 1f),
            onValueChange = onValue,
            valueRange = -1f..1f,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = tint,
                activeTrackColor = tint,
                inactiveTrackColor = tint.copy(alpha = .18f),
            ),
        )
        Text(String.format("%+.2f", value), Modifier.width(38.dp), fontSize = 6.sp, color = IG20Muted)
    }
}

@Composable
private fun ImageCurvesPageV20(node: ColorNode, vm: EditorViewModelV4) {
    var channel by remember { mutableStateOf("RGB") }
    var selectedIndex by remember(channel) { mutableStateOf<Int?>(null) }
    val curves = node.advancedColor.curves
    val curve = when (channel) {
        "R" -> curves.red
        "G" -> curves.green
        "B" -> curves.blue
        else -> curves.master
    }
    val currentCurve by rememberUpdatedState(curve)
    val density = LocalDensity.current
    val hitRadiusPx = with(density) { 24.dp.toPx() }
    val pointRadiusPx = with(density) { 5.5.dp.toPx() }
    val selectedPointRadiusPx = with(density) { 7.5.dp.toPx() }
    val canDelete = selectedIndex?.let { it > 0 && it < curve.points.lastIndex } == true

    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("RGB", "R", "G", "B").forEach { item ->
                Text(
                    item,
                    fontSize = 8.sp,
                    color = if (channel == item) imageChannelColorV20(item) else Color.White.copy(alpha = .68f),
                    modifier = Modifier
                        .background(if (channel == item) IG20Accent.copy(alpha = .14f) else IG20Raised, RoundedCornerShape(5.dp))
                        .clickable { channel = item; selectedIndex = null }
                        .padding(horizontal = 11.dp, vertical = 5.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (canDelete) {
                FilledTonalButton(
                    onClick = {
                        val index = selectedIndex ?: return@FilledTonalButton
                        vm.deleteImageCurvePointV20(channel, index)
                        selectedIndex = null
                    },
                    modifier = Modifier.height(30.dp),
                ) {
                    Icon(Icons.Rounded.DeleteOutline, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("Delete", fontSize = 8.sp)
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            val graphWidth = when {
                maxWidth > 310.dp -> 260.dp
                maxWidth > 220.dp -> maxWidth * .82f
                else -> maxWidth
            }
            ImageCurveGraphV20(
                curve = curve,
                channel = channel,
                selectedIndex = selectedIndex,
                onSelected = { selectedIndex = it },
                onInsert = { x, y ->
                    val index = currentCurve.points.count { it.x < x }.coerceIn(1, currentCurve.points.lastIndex)
                    vm.insertImageCurvePointV20(channel, x, y)
                    selectedIndex = index
                },
                onMoveFinished = { index, x, y -> vm.setImageCurvePointV20(channel, index, x, y) },
                hitRadiusPx = hitRadiusPx,
                pointRadiusPx = pointRadiusPx,
                selectedPointRadiusPx = selectedPointRadiusPx,
                modifier = Modifier.width(graphWidth).height(160.dp),
            )
        }
        Text("Drag points · long-press empty graph to add", fontSize = 7.sp, color = IG20Muted, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
private fun ImageCurveGraphV20(
    curve: Curve5,
    channel: String,
    selectedIndex: Int?,
    onSelected: (Int?) -> Unit,
    onInsert: (Float, Float) -> Unit,
    onMoveFinished: (Int, Float, Float) -> Unit,
    hitRadiusPx: Float,
    pointRadiusPx: Float,
    selectedPointRadiusPx: Float,
    modifier: Modifier,
) {
    val currentCurve by rememberUpdatedState(curve)
    var dragIndex by remember(channel) { mutableStateOf<Int?>(null) }
    var dragCurve by remember(channel) { mutableStateOf<Curve5?>(null) }

    fun pointOffset(point: CurvePoint, width: Float, height: Float): Offset =
        Offset(point.x * width, (1f - point.y) * height)

    fun displayed(): Curve5 = dragCurve ?: currentCurve

    fun nearest(position: Offset, width: Float, height: Float): Int? {
        var best: Int? = null
        var bestDistance = Float.MAX_VALUE
        displayed().points.forEachIndexed { index, point ->
            val p = pointOffset(point, width, height)
            val distance = hypot((p.x - position.x).toDouble(), (p.y - position.y).toDouble()).toFloat()
            if (distance <= hitRadiusPx && distance < bestDistance) {
                best = index
                bestDistance = distance
            }
        }
        return best
    }

    fun commitDrag() {
        val index = dragIndex
        val finalCurve = dragCurve
        if (index != null && finalCurve != null && index in finalCurve.points.indices) {
            val point = finalCurve.points[index]
            onMoveFinished(index, point.x, point.y)
        }
        dragIndex = null
        dragCurve = null
    }

    Canvas(
        modifier
            .background(Color(0xFF101014), RoundedCornerShape(5.dp))
            .border(1.dp, IG20Divider, RoundedCornerShape(5.dp))
            .pointerInput(channel, hitRadiusPx) {
                detectTapGestures(
                    onTap = { pos -> onSelected(nearest(pos, size.width.toFloat(), size.height.toFloat())) },
                    onLongPress = { pos ->
                        if (nearest(pos, size.width.toFloat(), size.height.toFloat()) == null) {
                            val x = (pos.x / size.width).coerceIn(0f, 1f)
                            val y = (1f - pos.y / size.height).coerceIn(0f, 1f)
                            onInsert(x, y)
                        }
                    },
                )
            }
            .pointerInput(channel, hitRadiusPx) {
                detectDragGestures(
                    onDragStart = { pos ->
                        dragIndex = nearest(pos, size.width.toFloat(), size.height.toFloat())
                        dragCurve = if (dragIndex != null) currentCurve else null
                        onSelected(dragIndex)
                    },
                    onDragEnd = { commitDrag() },
                    onDragCancel = { dragIndex = null; dragCurve = null },
                ) { change, _ ->
                    val index = dragIndex ?: return@detectDragGestures
                    change.consume()
                    var x = (change.position.x / size.width).coerceIn(0f, 1f)
                    var y = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                    val base = dragCurve ?: currentCurve
                    if (index == 0) {
                        if (x <= y) x = 0f else y = 0f
                    } else if (index == base.points.lastIndex) {
                        if (1f - x <= 1f - y) x = 1f else y = 1f
                    }
                    dragCurve = base.withPoint(index, x, y)
                }
            },
    ) {
        for (i in 1..3) {
            val x = size.width * i / 4f
            val y = size.height * i / 4f
            drawLine(Color.White.copy(alpha = .08f), Offset(x, 0f), Offset(x, size.height))
            drawLine(Color.White.copy(alpha = .08f), Offset(0f, y), Offset(size.width, y))
        }
        drawLine(Color.White.copy(alpha = .12f), Offset(0f, size.height), Offset(size.width, 0f), 1f)
        val display = dragCurve ?: curve
        val lineColor = imageChannelColorV20(channel)
        var previous = Offset(0f, size.height * (1f - display.valueAt(0f)))
        for (i in 1..160) {
            val xNorm = i / 160f
            val point = Offset(size.width * xNorm, size.height * (1f - display.valueAt(xNorm)))
            drawLine(lineColor, previous, point, 2.2f)
            previous = point
        }
        display.points.forEachIndexed { index, point ->
            val pos = pointOffset(point, size.width, size.height)
            if (selectedIndex == index) {
                drawCircle(Color.Black.copy(alpha = .82f), selectedPointRadiusPx + 2.5f, pos)
                drawCircle(Color.White, selectedPointRadiusPx, pos)
                drawCircle(lineColor, pointRadiusPx, pos)
            } else {
                drawCircle(Color.Black.copy(alpha = .72f), pointRadiusPx + 2.5f, pos)
                drawCircle(lineColor, pointRadiusPx, pos)
            }
        }
    }
}

@Composable
private fun ImageQualifierPageV20(node: ColorNode, vm: EditorViewModelV4) {
    val q = node.advancedColor.qualifier
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("HSL Qualifier", fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                Text("Manual H/S/L key for this image", fontSize = 7.sp, color = IG20Muted)
            }
            Switch(checked = q.enabled, onCheckedChange = vm::setImageQualifierEnabledV20)
        }
        Text("HUE", fontSize = 7.sp, color = IG20Muted)
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val steps = 120
            for (i in 0 until steps) {
                val hue = i * 360f / steps
                val x0 = size.width * i / steps
                val x1 = size.width * (i + 1) / steps
                drawRect(Color.hsv(hue, 1f, 1f), Offset(x0, 0f), androidx.compose.ui.geometry.Size(x1 - x0 + 1f, size.height))
            }
        }
        ImageGradeSliderV20("Hue center", q.hueCenterDegrees, 0f..360f) { vm.setImageQualifierV20("hue", it) }
        ImageGradeSliderV20("Hue width", q.hueWidthDegrees, 1f..360f) { vm.setImageQualifierV20("width", it) }
        ImageGradeSliderV20("Sat min", q.saturationMin, 0f..1f) { vm.setImageQualifierV20("satmin", it.coerceAtMost(q.saturationMax)) }
        ImageGradeSliderV20("Sat max", q.saturationMax, 0f..1f) { vm.setImageQualifierV20("satmax", it.coerceAtLeast(q.saturationMin)) }
        ImageGradeSliderV20("Lum min", q.luminanceMin, 0f..1f) { vm.setImageQualifierV20("lummin", it.coerceAtMost(q.luminanceMax)) }
        ImageGradeSliderV20("Lum max", q.luminanceMax, 0f..1f) { vm.setImageQualifierV20("lummax", it.coerceAtLeast(q.luminanceMin)) }
        ImageGradeSliderV20("Softness", q.softness, 0f..1f) { vm.setImageQualifierV20("softness", it) }
        HorizontalDivider(color = IG20Divider, modifier = Modifier.padding(vertical = 4.dp))
        ImageGradeSliderV20("Hue shift", q.hueShiftDegrees, -180f..180f) { vm.setImageQualifierV20("hueshift", it) }
        ImageGradeSliderV20("Saturation", q.saturationShift, -1f..1f) { vm.setImageQualifierV20("satshift", it) }
        ImageGradeSliderV20("Luminance", q.luminanceShift, -1f..1f) { vm.setImageQualifierV20("lumshift", it) }
    }
}

@Composable
private fun ImageGradeSliderV20(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValue: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(29.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(84.dp), fontSize = 7.sp, color = Color.White.copy(alpha = .72f))
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValue,
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
        Text(String.format("%.2f", value), Modifier.width(42.dp), fontSize = 6.sp, color = IG20Muted)
    }
}

private fun imageChannelColorV20(channel: String): Color = when (channel) {
    "R" -> Color(0xFFFF5F5F)
    "G" -> Color(0xFF61E78B)
    "B" -> Color(0xFF5CB7FF)
    else -> IG20Accent
}
