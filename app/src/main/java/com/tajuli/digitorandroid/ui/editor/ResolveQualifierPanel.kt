package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.model.ColorNode
import com.tajuli.digitorandroid.editor.model.QualifierFinesseKeys
import com.tajuli.digitorandroid.editor.model.qualifierFinesse

private val QPanel = Color(0xFF2B2C31)
private val QRaised = Color(0xFF202126)
private val QLine = Color(0xFF111216)
private val QMuted = Color(0xFFB8B8BC)
private val QAccent = Color(0xFF55B7FF)

@Composable
fun ResolveQualifierPanelV4(
    node: ColorNode,
    vm: EditorViewModelV4,
    modifier: Modifier = Modifier,
) {
    val q = node.advancedColor.qualifier
    val state by vm.state.collectAsState()
    val horizontal = rememberScrollState()

    Row(
        modifier
            .background(QPanel)
            .horizontalScroll(horizontal)
            .padding(vertical = 4.dp),
    ) {
        Column(
            Modifier.width(700.dp).fillMaxHeight().verticalScroll(rememberScrollState())
                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
        ) {
            QualifierToolbar(node, vm, state.qualifierPickerActive)
            Spacer(Modifier.height(4.dp))

            QualifierSectionTitle("Hue")
            HueBand(
                center = q.hueCenterDegrees,
                width = q.hueWidthDegrees,
                modifier = Modifier.fillMaxWidth().height(25.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                MiniControl(
                    "Center", q.hueCenterDegrees, 0f..360f,
                    display = { "%.1f".format(it) },
                    onValue = { vm.setQualifier("hue", it) },
                    modifier = Modifier.weight(1f),
                )
                MiniControl(
                    "Width", q.hueWidthDegrees, 1f..360f,
                    display = { "%.1f".format(it) },
                    onValue = { vm.setQualifier("width", it) },
                    modifier = Modifier.weight(1f),
                )
                MiniControl(
                    "Soft", q.softness, 0f..1f,
                    display = { "%.1f".format(it * 100f) },
                    onValue = { vm.setQualifier("softness", it) },
                    modifier = Modifier.weight(1f),
                )
                MiniControl(
                    "Sym", node.qualifierFinesse(QualifierFinesseKeys.HUE_SYMMETRY, .5f), 0f..1f,
                    display = { "%.1f".format(it * 100f) },
                    onValue = { vm.addEffectToSelectedNode(QualifierFinesseKeys.HUE_SYMMETRY, it) },
                    modifier = Modifier.weight(1f),
                )
            }

            QualifierSectionTitle("Saturation")
            SaturationBand(
                hue = q.hueCenterDegrees,
                low = q.saturationMin,
                high = q.saturationMax,
                modifier = Modifier.fillMaxWidth().height(23.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                MiniControl(
                    "Low", q.saturationMin, 0f..1f,
                    display = { "%.1f".format(it * 100f) },
                    onValue = { vm.setQualifier("satmin", it.coerceAtMost(q.saturationMax)) },
                    modifier = Modifier.weight(1f),
                )
                MiniControl(
                    "High", q.saturationMax, 0f..1f,
                    display = { "%.1f".format(it * 100f) },
                    onValue = { vm.setQualifier("satmax", it.coerceAtLeast(q.saturationMin)) },
                    modifier = Modifier.weight(1f),
                )
                MiniControl(
                    "L. Soft", node.qualifierFinesse(QualifierFinesseKeys.SAT_LOW_SOFT, .08f), 0f..1f,
                    display = { "%.1f".format(it * 100f) },
                    onValue = { vm.addEffectToSelectedNode(QualifierFinesseKeys.SAT_LOW_SOFT, it) },
                    modifier = Modifier.weight(1f),
                )
                MiniControl(
                    "H. Soft", node.qualifierFinesse(QualifierFinesseKeys.SAT_HIGH_SOFT, .08f), 0f..1f,
                    display = { "%.1f".format(it * 100f) },
                    onValue = { vm.addEffectToSelectedNode(QualifierFinesseKeys.SAT_HIGH_SOFT, it) },
                    modifier = Modifier.weight(1f),
                )
            }

            QualifierSectionTitle("Luminance")
            LuminanceBand(
                low = q.luminanceMin,
                high = q.luminanceMax,
                modifier = Modifier.fillMaxWidth().height(23.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                MiniControl(
                    "Low", q.luminanceMin, 0f..1f,
                    display = { "%.1f".format(it * 100f) },
                    onValue = { vm.setQualifier("lummin", it.coerceAtMost(q.luminanceMax)) },
                    modifier = Modifier.weight(1f),
                )
                MiniControl(
                    "High", q.luminanceMax, 0f..1f,
                    display = { "%.1f".format(it * 100f) },
                    onValue = { vm.setQualifier("lummax", it.coerceAtLeast(q.luminanceMin)) },
                    modifier = Modifier.weight(1f),
                )
                MiniControl(
                    "L. Soft", node.qualifierFinesse(QualifierFinesseKeys.LUM_LOW_SOFT, .08f), 0f..1f,
                    display = { "%.1f".format(it * 100f) },
                    onValue = { vm.addEffectToSelectedNode(QualifierFinesseKeys.LUM_LOW_SOFT, it) },
                    modifier = Modifier.weight(1f),
                )
                MiniControl(
                    "H. Soft", node.qualifierFinesse(QualifierFinesseKeys.LUM_HIGH_SOFT, .08f), 0f..1f,
                    display = { "%.1f".format(it * 100f) },
                    onValue = { vm.addEffectToSelectedNode(QualifierFinesseKeys.LUM_HIGH_SOFT, it) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Box(Modifier.width(1.dp).fillMaxHeight().background(Color.Black))
        MatteFinessePanel(node, vm, Modifier.width(250.dp).fillMaxHeight())
    }
}

@Composable
private fun QualifierToolbar(node: ColorNode, vm: EditorViewModelV4, pickerActive: Boolean) {
    val q = node.advancedColor.qualifier
    Row(
        Modifier.fillMaxWidth().height(38.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalButton(
            onClick = { vm.setQualifierPickerActive(!pickerActive) },
            modifier = Modifier.height(32.dp),
            shape = RoundedCornerShape(5.dp),
        ) {
            Icon(Icons.Rounded.Colorize, null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
            Text(if (pickerActive) "Tap Preview…" else "Pick Color", fontSize = 8.sp)
        }
        Spacer(Modifier.width(6.dp))
        if (q.pickedRed != null && q.pickedGreen != null && q.pickedBlue != null) {
            Box(
                Modifier.size(24.dp)
                    .background(Color(q.pickedRed, q.pickedGreen, q.pickedBlue, 1f), RoundedCornerShape(3.dp))
                    .border(1.dp, Color.White.copy(alpha = .45f), RoundedCornerShape(3.dp)),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text("Node HSL key", fontSize = 8.sp, color = QMuted)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { resetQualifier(vm) }, modifier = Modifier.size(30.dp)) {
            Icon(Icons.Rounded.RestartAlt, "Reset qualifier", modifier = Modifier.size(17.dp), tint = QMuted)
        }
        Text("Enable", fontSize = 7.sp, color = QMuted)
        Switch(checked = q.enabled, onCheckedChange = vm::setQualifierEnabled)
    }
}

@Composable
private fun QualifierSectionTitle(title: String) {
    Row(
        Modifier.fillMaxWidth().height(22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(Color(0xFFEB454A), RoundedCornerShape(50)))
        Spacer(Modifier.width(7.dp))
        Text(title, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = Color.White)
    }
}

@Composable
private fun HueBand(center: Float, width: Float, modifier: Modifier) {
    val low = ((center - width * .5f) / 360f).coerceIn(0f, 1f)
    val high = ((center + width * .5f) / 360f).coerceIn(0f, 1f)
    Canvas(modifier.background(Color.Black, RoundedCornerShape(3.dp)).border(1.dp, QLine, RoundedCornerShape(3.dp))) {
        val steps = 180
        for (i in 0 until steps) {
            val x0 = size.width * i / steps
            val x1 = size.width * (i + 1) / steps
            drawRect(
                Color.hsv(i * 360f / steps, 1f, .92f),
                topLeft = Offset(x0, 2f),
                size = Size(x1 - x0 + 1f, size.height - 4f),
            )
        }
        drawRangeOverlay(low, high)
    }
}

@Composable
private fun SaturationBand(hue: Float, low: Float, high: Float, modifier: Modifier) {
    Canvas(modifier.background(Color.Black, RoundedCornerShape(3.dp)).border(1.dp, QLine, RoundedCornerShape(3.dp))) {
        drawRect(
            Brush.horizontalGradient(listOf(Color.hsv(hue, 0f, .7f), Color.hsv(hue, 1f, .92f))),
            topLeft = Offset(0f, 2f),
            size = Size(size.width, size.height - 4f),
        )
        drawRangeOverlay(low, high)
    }
}

@Composable
private fun LuminanceBand(low: Float, high: Float, modifier: Modifier) {
    Canvas(modifier.background(Color.Black, RoundedCornerShape(3.dp)).border(1.dp, QLine, RoundedCornerShape(3.dp))) {
        drawRect(
            Brush.horizontalGradient(listOf(Color.Black, Color(0xFF77756F), Color(0xFFE1DED6))),
            topLeft = Offset(0f, 2f),
            size = Size(size.width, size.height - 4f),
        )
        drawRangeOverlay(low, high)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRangeOverlay(low0: Float, high0: Float) {
    val low = low0.coerceIn(0f, 1f)
    val high = high0.coerceIn(low, 1f)
    val left = size.width * low
    val right = size.width * high
    if (left > 0f) drawRect(Color.Black.copy(alpha = .72f), size = Size(left, size.height))
    if (right < size.width) {
        drawRect(Color.Black.copy(alpha = .72f), topLeft = Offset(right, 0f), size = Size(size.width - right, size.height))
    }
    drawLine(Color.White, Offset(left, 0f), Offset(left, size.height), strokeWidth = 2f)
    drawLine(Color.White, Offset(right, 0f), Offset(right, size.height), strokeWidth = 2f)
    drawCircle(Color.White, radius = 3.2f, center = Offset(left, size.height * .5f))
    drawCircle(Color.White, radius = 3.2f, center = Offset(right, size.height * .5f))
}

@Composable
private fun MiniControl(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String,
    onValue: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 7.sp, color = QMuted)
            Spacer(Modifier.width(5.dp))
            Text(
                display(value),
                modifier = Modifier.background(Color(0xFF101114), RoundedCornerShape(2.dp))
                    .border(1.dp, Color.Black, RoundedCornerShape(2.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
                fontSize = 7.sp,
                color = Color.White,
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValue,
            valueRange = range,
            modifier = Modifier.fillMaxWidth().height(23.dp),
        )
    }
}

@Composable
private fun MatteFinessePanel(node: ColorNode, vm: EditorViewModelV4, modifier: Modifier = Modifier) {
    Column(modifier.background(Color(0xFF292A2F)).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text("Matte Finesse", fontSize = 10.sp, fontWeight = FontWeight.Medium, color = Color.White)
        Spacer(Modifier.height(2.dp))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            FinesseControl("Pre-Filter", node.qualifierFinesse(QualifierFinesseKeys.PRE_FILTER, 0f), 0f..1f, vm, QualifierFinesseKeys.PRE_FILTER)
            FinesseControl("Clean Black", node.qualifierFinesse(QualifierFinesseKeys.CLEAN_BLACK, 0f), 0f..1f, vm, QualifierFinesseKeys.CLEAN_BLACK)
            FinesseControl("Clean White", node.qualifierFinesse(QualifierFinesseKeys.CLEAN_WHITE, 0f), 0f..1f, vm, QualifierFinesseKeys.CLEAN_WHITE)
            FinesseControl("Black Clip", node.qualifierFinesse(QualifierFinesseKeys.BLACK_CLIP, 0f), 0f..1f, vm, QualifierFinesseKeys.BLACK_CLIP, percent = true)
            FinesseControl("White Clip", node.qualifierFinesse(QualifierFinesseKeys.WHITE_CLIP, 1f), 0f..1f, vm, QualifierFinesseKeys.WHITE_CLIP, percent = true)
            FinesseControl("Blur Radius", node.qualifierFinesse(QualifierFinesseKeys.BLUR_RADIUS, 0f), 0f..10f, vm, QualifierFinesseKeys.BLUR_RADIUS)
            FinesseControl("In/Out Ratio", node.qualifierFinesse(QualifierFinesseKeys.IN_OUT_RATIO, 0f), -1f..1f, vm, QualifierFinesseKeys.IN_OUT_RATIO)
        }
    }
}

@Composable
private fun FinesseControl(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    vm: EditorViewModelV4,
    key: String,
    percent: Boolean = false,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 7.sp, color = QMuted, modifier = Modifier.weight(1f))
            Text(
                if (percent) "%.1f".format(value * 100f) else "%.1f".format(value),
                fontSize = 7.sp,
                color = Color.White,
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = { vm.addEffectToSelectedNode(key, it) },
            valueRange = range,
            modifier = Modifier.fillMaxWidth().height(24.dp),
        )
    }
}

private fun resetQualifier(vm: EditorViewModelV4) {
    vm.setQualifier("hue", 0f)
    vm.setQualifier("width", 360f)
    vm.setQualifier("satmin", 0f)
    vm.setQualifier("satmax", 1f)
    vm.setQualifier("lummin", 0f)
    vm.setQualifier("lummax", 1f)
    vm.setQualifier("softness", .08f)
    vm.addEffectToSelectedNode(QualifierFinesseKeys.HUE_SYMMETRY, .5f)
    vm.addEffectToSelectedNode(QualifierFinesseKeys.SAT_LOW_SOFT, .08f)
    vm.addEffectToSelectedNode(QualifierFinesseKeys.SAT_HIGH_SOFT, .08f)
    vm.addEffectToSelectedNode(QualifierFinesseKeys.LUM_LOW_SOFT, .08f)
    vm.addEffectToSelectedNode(QualifierFinesseKeys.LUM_HIGH_SOFT, .08f)
    vm.addEffectToSelectedNode(QualifierFinesseKeys.PRE_FILTER, 0f)
    vm.addEffectToSelectedNode(QualifierFinesseKeys.CLEAN_BLACK, 0f)
    vm.addEffectToSelectedNode(QualifierFinesseKeys.CLEAN_WHITE, 0f)
    vm.addEffectToSelectedNode(QualifierFinesseKeys.BLACK_CLIP, 0f)
    vm.addEffectToSelectedNode(QualifierFinesseKeys.WHITE_CLIP, 1f)
    vm.addEffectToSelectedNode(QualifierFinesseKeys.BLUR_RADIUS, 0f)
    vm.addEffectToSelectedNode(QualifierFinesseKeys.IN_OUT_RATIO, 0f)
}
