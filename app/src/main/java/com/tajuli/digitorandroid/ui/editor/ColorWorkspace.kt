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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Colorize
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.model.ColorNode
import com.tajuli.digitorandroid.editor.model.ColorWheelValue
import com.tajuli.digitorandroid.editor.model.Curve5
import com.tajuli.digitorandroid.editor.model.CurvePoint
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.TimelineClip
import kotlin.math.abs
import kotlin.math.hypot

private val CWPanel = Color(0xFF0B0B0F)
private val CWRaised = Color(0xFF17171C)
private val CWDivider = Color(0xFF292930)
private val CWMuted = Color(0xFF909098)
private val CWAccent = Color(0xFF30E0C3)
private val CWWheelCardWidth = 184.dp
private val CWWheelSize = 148.dp

private enum class ColorPage(val title: String) {
    PRIMARY("Primary Wheels"),
    LOG("Log Wheels"),
    CURVES("RGB Curves"),
    INPUT("Input Color"),
    QUALIFIER("HSL Qualifier"),
}

@Composable
fun ColorWorkspaceV4(
    clip: TimelineClip?,
    vm: EditorViewModelV4,
    modifier: Modifier = Modifier,
) {
    val node = clip?.nodeGraph?.selectedNode()
    if (clip == null || node == null) {
        EmptyColorPanel("Select a clip and a node", modifier)
        return
    }
    if (node.kind != NodeKind.SERIAL && node.kind != NodeKind.PARALLEL) {
        EmptyColorPanel("Select a Serial or Parallel node", modifier)
        return
    }

    var page by remember { mutableStateOf(ColorPage.PRIMARY) }
    Column(modifier.background(CWPanel)) {
        Row(
            Modifier.fillMaxWidth().height(36.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            ColorPage.entries.forEach { item ->
                FilledTonalButton(
                    onClick = { page = item },
                    modifier = Modifier.height(30.dp),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        item.title,
                        fontSize = 8.sp,
                        color = if (page == item) CWAccent else Color.White.copy(alpha = .72f),
                    )
                }
            }
        }
        HorizontalDivider(color = CWDivider)
        when (page) {
            ColorPage.PRIMARY -> PrimaryPage(node, vm, Modifier.fillMaxSize())
            ColorPage.LOG -> LogPage(node, vm, Modifier.fillMaxSize())
            ColorPage.CURVES -> CurvesPage(node, vm, Modifier.fillMaxSize())
            ColorPage.INPUT -> InputColorProfileBarV1(clip, vm, Modifier.fillMaxSize())
            ColorPage.QUALIFIER -> ResolveQualifierPanelV4(node, vm, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun PrimaryPage(node: ColorNode, vm: EditorViewModelV4, modifier: Modifier) {
    val p = node.advancedColor.primary
    Column(modifier.verticalScroll(rememberScrollState())) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val sideSpace = ((maxWidth - CWWheelCardWidth) / 2).coerceAtLeast(20.dp)
            Row(
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = sideSpace, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ResolveWheelCard(
                    title = "Lift",
                    value = p.lift,
                    onPuck = { x, y -> vm.setPrimaryWheelPuck("Lift", x, y) },
                    onComponent = { component, value -> vm.setPrimaryWheel("Lift", component, value) },
                    onReset = {
                        vm.setPrimaryWheelPuck("Lift", 0f, 0f)
                        vm.setPrimaryWheel("Lift", "luma", 0f)
                    },
                )
                ResolveWheelCard(
                    title = "Gamma",
                    value = p.gamma,
                    onPuck = { x, y -> vm.setPrimaryWheelPuck("Gamma", x, y) },
                    onComponent = { component, value -> vm.setPrimaryWheel("Gamma", component, value) },
                    onReset = {
                        vm.setPrimaryWheelPuck("Gamma", 0f, 0f)
                        vm.setPrimaryWheel("Gamma", "luma", 0f)
                    },
                )
                ResolveWheelCard(
                    title = "Gain",
                    value = p.gain,
                    onPuck = { x, y -> vm.setPrimaryWheelPuck("Gain", x, y) },
                    onComponent = { component, value -> vm.setPrimaryWheel("Gain", component, value) },
                    onReset = {
                        vm.setPrimaryWheelPuck("Gain", 0f, 0f)
                        vm.setPrimaryWheel("Gain", "luma", 0f)
                    },
                )
                ResolveWheelCard(
                    title = "Offset",
                    value = p.offset,
                    onPuck = { x, y -> vm.setPrimaryWheelPuck("Offset", x, y) },
                    onComponent = { component, value -> vm.setPrimaryWheel("Offset", component, value) },
                    onReset = {
                        vm.setPrimaryWheelPuck("Offset", 0f, 0f)
                        vm.setPrimaryWheel("Offset", "luma", 0f)
                    },
                )
            }
        }
    }
}

@Composable
private fun LogPage(node: ColorNode, vm: EditorViewModelV4, modifier: Modifier) {
    val log = node.advancedColor.log
    Column(modifier.verticalScroll(rememberScrollState())) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val sideSpace = ((maxWidth - CWWheelCardWidth) / 2).coerceAtLeast(20.dp)
            Row(
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = sideSpace, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ResolveWheelCard(
                    title = "Shadows",
                    value = log.shadows,
                    onPuck = { x, y -> vm.setLogWheelPuck("Shadows", x, y) },
                    onComponent = { component, value -> vm.setLogWheel("Shadows", component, value) },
                    onReset = {
                        vm.setLogWheelPuck("Shadows", 0f, 0f)
                        vm.setLogWheel("Shadows", "luma", 0f)
                    },
                )
                ResolveWheelCard(
                    title = "Midtones",
                    value = log.midtones,
                    onPuck = { x, y -> vm.setLogWheelPuck("Midtones", x, y) },
                    onComponent = { component, value -> vm.setLogWheel("Midtones", component, value) },
                    onReset = {
                        vm.setLogWheelPuck("Midtones", 0f, 0f)
                        vm.setLogWheel("Midtones", "luma", 0f)
                    },
                )
                ResolveWheelCard(
                    title = "Highlights",
                    value = log.highlights,
                    onPuck = { x, y -> vm.setLogWheelPuck("Highlights", x, y) },
                    onComponent = { component, value -> vm.setLogWheel("Highlights", component, value) },
                    onReset = {
                        vm.setLogWheelPuck("Highlights", 0f, 0f)
                        vm.setLogWheel("Highlights", "luma", 0f)
                    },
                )
                ResolveWheelCard(
                    title = "Global",
                    value = log.global,
                    onPuck = { x, y -> vm.setLogWheelPuck("Global", x, y) },
                    onComponent = { component, value -> vm.setLogWheel("Global", component, value) },
                    onReset = {
                        vm.setLogWheelPuck("Global", 0f, 0f)
                        vm.setLogWheel("Global", "luma", 0f)
                    },
                )
            }
        }
        CompactSlider("Shadow range", log.shadowRange, .05f..48f / 100f) { vm.setLogRange(shadowRange = it) }
        CompactSlider("Highlight range", log.highlightRange, .52f..95f / 100f) { vm.setLogRange(highlightRange = it) }
    }
}

@Composable
private fun ResolveWheelCard(
    title: String,
    value: ColorWheelValue,
    onPuck: (Float, Float) -> Unit,
    onComponent: (String, Float) -> Unit,
    onReset: () -> Unit,
) {
    Column(
        Modifier.width(CWWheelCardWidth)
            .background(CWRaised, RoundedCornerShape(10.dp))
            .border(1.dp, CWDivider, RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = onReset, modifier = Modifier.size(26.dp)) {
                Icon(Icons.Rounded.RestartAlt, contentDescription = "Reset $title", modifier = Modifier.size(14.dp), tint = CWMuted)
            }
        }
        ColorWheel(
            value = value,
            onPuck = onPuck,
            modifier = Modifier.size(CWWheelSize),
        )
        Spacer(Modifier.height(4.dp))
        WheelChannelSlider("Y", value.luma, Color.White.copy(alpha = .82f)) { onComponent("luma", it) }
        WheelChannelSlider("R", value.red, channelColor("R")) { onComponent("red", it) }
        WheelChannelSlider("G", value.green, channelColor("G")) { onComponent("green", it) }
        WheelChannelSlider("B", value.blue, channelColor("B")) { onComponent("blue", it) }
    }
}

@Composable
private fun WheelChannelSlider(
    label: String,
    value: Float,
    channelTint: Color,
    onValue: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(25.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(14.dp), fontSize = 8.sp, color = channelTint, fontWeight = FontWeight.SemiBold)
        Slider(
            value = value.coerceIn(-1f, 1f),
            onValueChange = onValue,
            valueRange = -1f..1f,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = channelTint,
                activeTrackColor = channelTint,
                inactiveTrackColor = channelTint.copy(alpha = .18f),
            ),
        )
        Text(fmt(value), Modifier.width(38.dp), fontSize = 6.sp, color = CWMuted)
    }
}

@Composable
private fun ColorWheel(
    value: ColorWheelValue,
    onPuck: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
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
        val hueBrush = Brush.sweepGradient(
            listOf(
                Color.Red,
                Color.Magenta,
                Color.Blue,
                Color.Cyan,
                Color.Green,
                Color.Yellow,
                Color.Red,
            ),
            center,
        )
        drawCircle(hueBrush, radius, center)
        drawCircle(
            Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = .96f), Color.White.copy(alpha = .35f), Color.Transparent),
                center = center,
                radius = radius,
            ),
            radius,
            center,
        )
        drawCircle(Color.Black.copy(alpha = .20f), radius = radius, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
        drawLine(Color.White.copy(alpha = .10f), Offset(center.x - radius, center.y), Offset(center.x + radius, center.y))
        drawLine(Color.White.copy(alpha = .10f), Offset(center.x, center.y - radius), Offset(center.x, center.y + radius))
        val puck = Offset(center.x + value.puckX * radius, center.y + value.puckY * radius)
        drawCircle(Color.Black.copy(alpha = .75f), radius = 7.2f, center = puck)
        drawCircle(Color.White, radius = 5.4f, center = puck, style = androidx.compose.ui.graphics.drawscope.Stroke(2.2f))
    }
}

@Composable
private fun CurvesPage(node: ColorNode, vm: EditorViewModelV4, modifier: Modifier) {
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
    val canDeleteSelected = selectedIndex?.let { it > 0 && it < curve.points.lastIndex } == true

    Column(modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("RGB", "R", "G", "B").forEach { item ->
                Text(
                    item,
                    modifier = Modifier
                        .background(if (channel == item) CWAccent.copy(alpha = .16f) else CWRaised, RoundedCornerShape(5.dp))
                        .clickable {
                            channel = item
                            selectedIndex = null
                        }
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    fontSize = 8.sp,
                    color = if (channel == item) channelColor(item) else Color.White.copy(alpha = .68f),
                )
            }
            Spacer(Modifier.weight(1f))
            if (canDeleteSelected) {
                FilledTonalButton(
                    onClick = {
                        val index = selectedIndex
                        if (index != null && index > 0 && index < currentCurve.points.lastIndex) {
                            vm.deleteCurvePoint(channel, index)
                            selectedIndex = null
                        }
                    },
                    modifier = Modifier.height(30.dp),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Icon(Icons.Rounded.DeleteOutline, "Delete selected point", modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Delete point", fontSize = 8.sp)
                }
            } else if (selectedIndex != null) {
                Text("End point", fontSize = 7.sp, color = CWMuted)
            }
        }
        Spacer(Modifier.height(6.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            val graphWidth = when {
                maxWidth > 310.dp -> 260.dp
                maxWidth > 220.dp -> maxWidth * .82f
                else -> maxWidth
            }
            CurveEditorGraph(
                curve = curve,
                channel = channel,
                selectedIndex = selectedIndex,
                onSelected = { selectedIndex = it },
                onInsert = { x, y ->
                    val insertedIndex = currentCurve.points.count { it.x < x }
                        .coerceIn(1, currentCurve.points.lastIndex)
                    vm.insertCurvePoint(channel, x, y)
                    selectedIndex = insertedIndex
                },
                onMoveFinished = { index, x, y -> vm.setCurvePoint(channel, index, x, y) },
                hitRadiusPx = hitRadiusPx,
                pointRadiusPx = pointRadiusPx,
                selectedPointRadiusPx = selectedPointRadiusPx,
                modifier = Modifier.width(graphWidth).height(170.dp),
            )
        }
        Text(
            when {
                selectedIndex == null -> "Tap or drag a point · press & hold empty area to add"
                canDeleteSelected -> "Selected point ${selectedIndex!! + 1} · drag smoothly · Delete point removes it"
                else -> "Start/end point selected · movable, but cannot be deleted"
            },
            fontSize = 7.sp,
            color = CWMuted,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 3.dp),
        )
    }
}

@Composable
private fun CurveEditorGraph(
    curve: Curve5,
    channel: String,
    selectedIndex: Int?,
    onSelected: (Int?) -> Unit,
    onInsert: (Float, Float) -> Unit,
    onMoveFinished: (Int, Float, Float) -> Unit,
    hitRadiusPx: Float,
    pointRadiusPx: Float,
    selectedPointRadiusPx: Float,
    modifier: Modifier = Modifier,
) {
    val currentCurve by rememberUpdatedState(curve)
    var dragIndex by remember(channel) { mutableStateOf<Int?>(null) }
    var dragCurve by remember(channel) { mutableStateOf<Curve5?>(null) }

    fun pointOffset(point: CurvePoint, width: Float, height: Float): Offset =
        Offset(point.x * width, (1f - point.y) * height)

    fun displayedCurve(): Curve5 = dragCurve ?: currentCurve

    fun nearest(position: Offset, width: Float, height: Float): Int? {
        var best: Int? = null
        var bestDistance = Float.MAX_VALUE
        displayedCurve().points.forEachIndexed { index, point ->
            val p = pointOffset(point, width, height)
            val d = hypot((p.x - position.x).toDouble(), (p.y - position.y).toDouble()).toFloat()
            if (d <= hitRadiusPx && d < bestDistance) {
                best = index
                bestDistance = d
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
            .border(1.dp, CWDivider, RoundedCornerShape(5.dp))
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
                    onDragCancel = {
                        dragIndex = null
                        dragCurve = null
                    },
                ) { change, _ ->
                    val index = dragIndex ?: return@detectDragGestures
                    change.consume()
                    var x = (change.position.x / size.width).coerceIn(0f, 1f)
                    var y = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                    val baseCurve = dragCurve ?: currentCurve
                    val points = baseCurve.points
                    if (index == 0) {
                        if (x <= y) x = 0f else y = 0f
                    } else if (index == points.lastIndex) {
                        if (1f - x <= 1f - y) x = 1f else y = 1f
                    }
                    dragCurve = baseCurve.withPoint(index, x, y)
                }
            },
    ) {
        for (i in 1..3) {
            val x = size.width * i / 4f
            val y = size.height * i / 4f
            drawLine(Color.White.copy(alpha = .08f), Offset(x, 0f), Offset(x, size.height))
            drawLine(Color.White.copy(alpha = .08f), Offset(0f, y), Offset(size.width, y))
        }
        drawLine(Color.White.copy(alpha = .12f), Offset(0f, size.height), Offset(size.width, 0f), strokeWidth = 1f)

        val display = dragCurve ?: curve
        val lineColor = channelColor(channel)
        var previous = Offset(0f, size.height * (1f - display.valueAt(0f)))
        for (i in 1..160) {
            val xNorm = i / 160f
            val point = Offset(size.width * xNorm, size.height * (1f - display.valueAt(xNorm)))
            drawLine(lineColor, previous, point, strokeWidth = 2.2f)
            previous = point
        }
        display.points.forEachIndexed { index, point ->
            val pos = pointOffset(point, size.width, size.height)
            if (selectedIndex == index) {
                drawCircle(Color.Black.copy(alpha = .82f), radius = selectedPointRadiusPx + 2.5f, center = pos)
                drawCircle(Color.White, radius = selectedPointRadiusPx, center = pos)
                drawCircle(lineColor, radius = pointRadiusPx, center = pos)
            } else {
                drawCircle(Color.Black.copy(alpha = .72f), radius = pointRadiusPx + 2.5f, center = pos)
                drawCircle(lineColor, radius = pointRadiusPx, center = pos)
            }
        }
    }
}

@Composable
private fun QualifierPage(node: ColorNode, vm: EditorViewModelV4, modifier: Modifier) {
    val q = node.advancedColor.qualifier
    val uiState by vm.state.collectAsState()
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(
                onClick = { vm.setQualifierPickerActive(!uiState.qualifierPickerActive) },
                modifier = Modifier.height(32.dp),
            ) {
                Icon(Icons.Rounded.Colorize, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text(if (uiState.qualifierPickerActive) "Tap Preview…" else "Pick Color", fontSize = 9.sp)
            }
            Spacer(Modifier.width(8.dp))
            if (q.pickedRed != null && q.pickedGreen != null && q.pickedBlue != null) {
                Box(
                    Modifier.size(28.dp)
                        .background(Color(q.pickedRed, q.pickedGreen, q.pickedBlue, 1f), RoundedCornerShape(5.dp))
                        .border(1.dp, Color.White.copy(alpha = .35f), RoundedCornerShape(5.dp)),
                )
                Spacer(Modifier.width(7.dp))
                Text("Sampled color", fontSize = 8.sp, color = CWMuted)
            } else {
                Text("Use eyedropper, then tap the exact color in Preview", fontSize = 8.sp, color = CWMuted)
            }
            Spacer(Modifier.weight(1f))
            Switch(checked = q.enabled, onCheckedChange = vm::setQualifierEnabled)
        }

        Spacer(Modifier.height(5.dp))
        Text("HUE", fontSize = 7.sp, color = CWMuted)
        HueStrip(Modifier.fillMaxWidth().height(17.dp))
        CompactSlider("Hue center", q.hueCenterDegrees, 0f..360f) { vm.setQualifier("hue", it) }
        CompactSlider("Hue width", q.hueWidthDegrees, 1f..360f) { vm.setQualifier("width", it) }

        Text("SATURATION", fontSize = 7.sp, color = CWMuted)
        SaturationStrip(Modifier.fillMaxWidth().height(14.dp), q.hueCenterDegrees)
        CompactSlider("Sat min", q.saturationMin, 0f..1f) { vm.setQualifier("satmin", it.coerceAtMost(q.saturationMax)) }
        CompactSlider("Sat max", q.saturationMax, 0f..1f) { vm.setQualifier("satmax", it.coerceAtLeast(q.saturationMin)) }

        Text("LUMINANCE", fontSize = 7.sp, color = CWMuted)
        LuminanceStrip(Modifier.fillMaxWidth().height(14.dp))
        CompactSlider("Lum min", q.luminanceMin, 0f..1f) { vm.setQualifier("lummin", it.coerceAtMost(q.luminanceMax)) }
        CompactSlider("Lum max", q.luminanceMax, 0f..1f) { vm.setQualifier("lummax", it.coerceAtLeast(q.luminanceMin)) }
        CompactSlider("Softness", q.softness, 0f..1f) { vm.setQualifier("softness", it) }

        HorizontalDivider(color = CWDivider, modifier = Modifier.padding(vertical = 5.dp))
        Text("QUALIFIED COLOR ONLY", fontSize = 8.sp, fontWeight = FontWeight.SemiBold, color = CWAccent)
        Text("These controls affect only pixels inside the picked H/S/L key.", fontSize = 7.sp, color = CWMuted)
        CompactSlider("Hue shift", q.hueShiftDegrees, -180f..180f) { vm.setQualifier("hueshift", it) }
        CompactSlider("Saturation", q.saturationShift, -1f..1f) { vm.setQualifier("satshift", it) }
        CompactSlider("Luminance", q.luminanceShift, -1f..1f) { vm.setQualifier("lumshift", it) }
    }
}

@Composable
private fun HueStrip(modifier: Modifier) {
    Canvas(modifier) {
        val steps = 120
        for (i in 0 until steps) {
            val hue = i * 360f / steps
            val color = Color.hsv(hue, 1f, 1f)
            val x0 = size.width * i / steps
            val x1 = size.width * (i + 1) / steps
            drawRect(color, topLeft = Offset(x0, 0f), size = Size(x1 - x0 + 1f, size.height))
        }
    }
}

@Composable
private fun SaturationStrip(modifier: Modifier, hue: Float) {
    Canvas(modifier) {
        drawRect(
            Brush.horizontalGradient(listOf(Color.hsv(hue, 0f, .65f), Color.hsv(hue, 1f, 1f))),
        )
    }
}

@Composable
private fun LuminanceStrip(modifier: Modifier) {
    Canvas(modifier) {
        drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.Gray, Color.White)))
    }
}

@Composable
private fun CompactSlider(
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
        Text(String.format("%.2f", value), Modifier.width(40.dp), fontSize = 6.sp, color = CWMuted)
    }
}

@Composable
private fun EmptyColorPanel(message: String, modifier: Modifier) {
    Box(modifier.background(CWPanel), contentAlignment = Alignment.Center) {
        Text(message, fontSize = 10.sp, color = CWMuted)
    }
}

private fun channelColor(channel: String): Color = when (channel) {
    "R" -> Color(0xFFFF5F5F)
    "G" -> Color(0xFF61E78B)
    "B" -> Color(0xFF5CB7FF)
    else -> CWAccent
}

private fun fmt(value: Float): String = String.format("%+.2f", value)