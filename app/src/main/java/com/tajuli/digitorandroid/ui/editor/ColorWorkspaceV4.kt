package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.model.ColorNode
import com.tajuli.digitorandroid.editor.model.ColorWheelValue
import com.tajuli.digitorandroid.editor.model.Curve5
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.TimelineClip

private val CWPanel = Color(0xFF0B0B0F)
private val CWRaised = Color(0xFF17171C)
private val CWDivider = Color(0xFF292930)
private val CWMuted = Color(0xFF909098)
private val CWAccent = Color(0xFF30E0C3)

private enum class ColorPage(val title: String) {
    PRIMARY("Primary Wheels"),
    LOG("Log Wheels"),
    CURVES("RGB Curves"),
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
                    Text(item.title, fontSize = 8.sp, color = if (page == item) CWAccent else Color.White.copy(alpha = .72f))
                }
            }
        }
        HorizontalDivider(color = CWDivider)
        when (page) {
            ColorPage.PRIMARY -> PrimaryPage(node, vm, Modifier.fillMaxSize())
            ColorPage.LOG -> LogPage(node, vm, Modifier.fillMaxSize())
            ColorPage.CURVES -> CurvesPage(node, vm, Modifier.fillMaxSize())
            ColorPage.QUALIFIER -> QualifierPage(node, vm, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun PrimaryPage(node: ColorNode, vm: EditorViewModelV4, modifier: Modifier) {
    val p = node.advancedColor.primary
    Row(modifier.horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WheelCard("Lift", p.lift) { component, value -> vm.setPrimaryWheel("Lift", component, value) }
        WheelCard("Gamma", p.gamma) { component, value -> vm.setPrimaryWheel("Gamma", component, value) }
        WheelCard("Gain", p.gain) { component, value -> vm.setPrimaryWheel("Gain", component, value) }
        WheelCard("Offset", p.offset) { component, value -> vm.setPrimaryWheel("Offset", component, value) }
    }
}

@Composable
private fun LogPage(node: ColorNode, vm: EditorViewModelV4, modifier: Modifier) {
    val log = node.advancedColor.log
    Column(modifier.verticalScroll(rememberScrollState())) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WheelCard("Shadows", log.shadows) { c, v -> vm.setLogWheel("Shadows", c, v) }
            WheelCard("Midtones", log.midtones) { c, v -> vm.setLogWheel("Midtones", c, v) }
            WheelCard("Highlights", log.highlights) { c, v -> vm.setLogWheel("Highlights", c, v) }
        }
        CompactSlider("Shadow range", log.shadowRange, .05f..48f / 100f) { vm.setLogRange(shadowRange = it) }
        CompactSlider("Highlight range", log.highlightRange, .52f..95f / 100f) { vm.setLogRange(highlightRange = it) }
    }
}

@Composable
private fun WheelCard(
    title: String,
    value: ColorWheelValue,
    onChange: (String, Float) -> Unit,
) {
    Column(
        Modifier.width(176.dp).background(CWRaised, RoundedCornerShape(9.dp)).border(1.dp, CWDivider, RoundedCornerShape(9.dp)).padding(8.dp),
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 10.sp)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(38.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(36.dp)) {
                drawCircle(Color(0xFF25252B))
                drawCircle(Color.Red.copy(alpha = .70f), radius = size.minDimension * .10f, center = Offset(size.width * .72f, size.height * .50f))
                drawCircle(Color.Green.copy(alpha = .70f), radius = size.minDimension * .10f, center = Offset(size.width * .36f, size.height * .25f))
                drawCircle(Color.Blue.copy(alpha = .75f), radius = size.minDimension * .10f, center = Offset(size.width * .36f, size.height * .75f))
            }
            Text("R ${fmt(value.red)}  G ${fmt(value.green)}  B ${fmt(value.blue)}", fontSize = 7.sp, color = CWMuted, modifier = Modifier.align(Alignment.BottomCenter))
        }
        MiniSlider("R", value.red) { onChange("red", it) }
        MiniSlider("G", value.green) { onChange("green", it) }
        MiniSlider("B", value.blue) { onChange("blue", it) }
        MiniSlider("Y", value.luma) { onChange("luma", it) }
    }
}

@Composable
private fun MiniSlider(label: String, value: Float, onValue: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().height(25.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(14.dp), fontSize = 7.sp, color = CWMuted)
        Slider(value = value.coerceIn(-1f, 1f), onValueChange = onValue, valueRange = -1f..1f, modifier = Modifier.weight(1f))
        Text(fmt(value), Modifier.width(34.dp), fontSize = 7.sp, color = CWMuted)
    }
}

@Composable
private fun CurvesPage(node: ColorNode, vm: EditorViewModelV4, modifier: Modifier) {
    var channel by remember { mutableStateOf("RGB") }
    val curves = node.advancedColor.curves
    val curve = when (channel) {
        "R" -> curves.red
        "G" -> curves.green
        "B" -> curves.blue
        else -> curves.master
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("RGB", "R", "G", "B").forEach { item ->
                Text(
                    item,
                    modifier = Modifier.background(if (channel == item) CWAccent.copy(alpha = .16f) else CWRaised, RoundedCornerShape(5.dp))
                        .clickable { channel = item }.padding(horizontal = 13.dp, vertical = 6.dp),
                    fontSize = 9.sp,
                    color = if (channel == item) CWAccent else Color.White.copy(alpha = .7f),
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        CurveGraph(curve, Modifier.fillMaxWidth().height(112.dp))
        Spacer(Modifier.height(4.dp))
        val values = listOf(curve.p0, curve.p1, curve.p2, curve.p3, curve.p4)
        values.forEachIndexed { index, value ->
            Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("P$index", Modifier.width(25.dp), fontSize = 8.sp, color = CWMuted)
                Slider(
                    value = value,
                    onValueChange = { vm.setCurvePoint(channel, index, it) },
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(1f),
                )
                Text(String.format("%.2f", value), Modifier.width(36.dp), fontSize = 7.sp, color = CWMuted)
            }
        }
    }
}

@Composable
private fun CurveGraph(curve: Curve5, modifier: Modifier) {
    Canvas(modifier.background(Color(0xFF101014)).border(1.dp, CWDivider)) {
        for (i in 1..3) {
            val x = size.width * i / 4f
            val y = size.height * i / 4f
            drawLine(Color.White.copy(alpha = .08f), Offset(x, 0f), Offset(x, size.height))
            drawLine(Color.White.copy(alpha = .08f), Offset(0f, y), Offset(size.width, y))
        }
        var previous = Offset(0f, size.height * (1f - curve.valueAt(0f)))
        for (i in 1..100) {
            val xNorm = i / 100f
            val point = Offset(size.width * xNorm, size.height * (1f - curve.valueAt(xNorm)))
            drawLine(CWAccent, previous, point, strokeWidth = 2f)
            previous = point
        }
        val ys = listOf(curve.p0, curve.p1, curve.p2, curve.p3, curve.p4)
        ys.forEachIndexed { i, y ->
            drawCircle(CWAccent, radius = 4f, center = Offset(size.width * i / 4f, size.height * (1f - y)))
        }
    }
}

@Composable
private fun QualifierPage(node: ColorNode, vm: EditorViewModelV4, modifier: Modifier) {
    val q = node.advancedColor.qualifier
    Column(modifier.verticalScroll(rememberScrollState()).padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Enable qualifier", fontSize = 10.sp, modifier = Modifier.weight(1f))
            Switch(checked = q.enabled, onCheckedChange = vm::setQualifierEnabled)
        }
        HueStrip(Modifier.fillMaxWidth().height(18.dp))
        CompactSlider("Hue center", q.hueCenterDegrees, 0f..360f) { vm.setQualifier("hue", it) }
        CompactSlider("Hue width", q.hueWidthDegrees, 1f..360f) { vm.setQualifier("width", it) }
        CompactSlider("Saturation min", q.saturationMin, 0f..1f) { vm.setQualifier("satmin", it) }
        CompactSlider("Saturation max", q.saturationMax, 0f..1f) { vm.setQualifier("satmax", it) }
        CompactSlider("Luminance min", q.luminanceMin, 0f..1f) { vm.setQualifier("lummin", it) }
        CompactSlider("Luminance max", q.luminanceMax, 0f..1f) { vm.setQualifier("lummax", it) }
        CompactSlider("Softness", q.softness, 0f..1f) { vm.setQualifier("softness", it) }
        HorizontalDivider(color = CWDivider, modifier = Modifier.padding(vertical = 4.dp))
        Text("Qualified correction", fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
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
            drawRect(color, topLeft = Offset(x0, 0f), size = androidx.compose.ui.geometry.Size(x1 - x0 + 1f, size.height))
        }
    }
}

@Composable
private fun CompactSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onValue: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(92.dp), fontSize = 8.sp, color = Color.White.copy(alpha = .72f))
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onValue, valueRange = range, modifier = Modifier.weight(1f))
        Text(String.format("%.2f", value), Modifier.width(44.dp), fontSize = 7.sp, color = CWMuted)
    }
}

@Composable
private fun EmptyColorPanel(message: String, modifier: Modifier) {
    Box(modifier.background(CWPanel), contentAlignment = Alignment.Center) {
        Text(message, fontSize = 10.sp, color = CWMuted)
    }
}

private fun fmt(value: Float): String = String.format("%+.2f", value)
