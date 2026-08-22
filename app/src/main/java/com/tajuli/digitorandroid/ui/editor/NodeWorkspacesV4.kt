package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.visibleEffects
import kotlin.math.abs
import kotlin.math.max

private val N4Panel = Color(0xFF0B0B0F)
private val N4Raised = Color(0xFF17171C)
private val N4Divider = Color(0xFF292930)
private val N4Muted = Color(0xFF909098)
private val N4Accent = Color(0xFF30E0C3)

@Composable
fun NodeGraphV4(clip: TimelineClip?, vm: EditorViewModelV4, modifier: Modifier = Modifier) {
    if (clip == null) {
        NodeEmptyV4("Select a clip to open its node graph", modifier)
        return
    }
    val graph = clip.nodeGraph
    val density = LocalDensity.current
    val graphWidth = max(640f, (graph.nodes.maxOfOrNull { it.position.x } ?: 420f) + 150f).dp
    val graphHeight = max(230f, (graph.nodes.maxOfOrNull { it.position.y } ?: 160f) + 110f).dp
    var menuNodeId by remember { mutableStateOf<String?>(null) }

    Column(modifier.background(N4Panel)) {
        Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Node Graph · ${clip.label}", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("Tap select · hold drag · hold/release actions", fontSize = 7.sp, color = N4Muted)
        }
        HorizontalDivider(color = N4Divider)
        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            Box(Modifier.requiredWidth(graphWidth).height(graphHeight)) {
                Canvas(Modifier.fillMaxSize()) {
                    val byId = graph.nodes.associateBy { it.id }
                    graph.edges.forEach { edge ->
                        val from = byId[edge.fromId] ?: return@forEach
                        val to = byId[edge.toId] ?: return@forEach
                        drawLine(
                            Color.White.copy(alpha = .28f),
                            androidx.compose.ui.geometry.Offset(from.position.x.dp.toPx() + 88.dp.toPx(), from.position.y.dp.toPx() + 26.dp.toPx()),
                            androidx.compose.ui.geometry.Offset(to.position.x.dp.toPx(), to.position.y.dp.toPx() + 26.dp.toPx()),
                            2f,
                        )
                    }
                }
                graph.nodes.forEach { node ->
                    val selected = graph.selectedNodeId == node.id
                    var moved by remember(node.id) { mutableStateOf(false) }
                    Box(
                        Modifier.offset(x = node.position.x.dp, y = node.position.y.dp)
                            .width(88.dp).height(52.dp).clip(RoundedCornerShape(6.dp))
                            .background(nodeColorV4(node.kind))
                            .border(if (selected) 2.dp else 1.dp, if (selected) N4Accent else Color.White.copy(alpha = .2f), RoundedCornerShape(6.dp))
                            .pointerInput(node.id, node.position) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { moved = false; vm.selectNode(node.id) },
                                    onDragEnd = { if (!moved) menuNodeId = node.id; moved = false },
                                    onDragCancel = { moved = false },
                                ) { change, drag ->
                                    change.consume()
                                    if (abs(drag.x) + abs(drag.y) > 1f) moved = true
                                    val dx = with(density) { drag.x.toDp().value }
                                    val dy = with(density) { drag.y.toDp().value }
                                    vm.moveNode(node.id, dx, dy)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(node.label, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Text(node.kind.name, fontSize = 6.sp, color = Color.White.copy(alpha = .52f))
                            if (node.visibleEffects().isNotEmpty()) Text("FX ${node.visibleEffects().size}", fontSize = 6.sp, color = N4Accent)
                        }
                        DropdownMenu(expanded = menuNodeId == node.id, onDismissRequest = { menuNodeId = null }) {
                            DropdownMenuItem(
                                text = { Text("Add Serial") },
                                enabled = node.kind != NodeKind.OUTPUT,
                                onClick = { menuNodeId = null; vm.addSerialNode(node.id) },
                            )
                            DropdownMenuItem(
                                text = { Text("Add Parallel") },
                                enabled = node.kind == NodeKind.SERIAL || node.kind == NodeKind.PARALLEL,
                                onClick = { menuNodeId = null; vm.addParallelNode(node.id) },
                            )
                            HorizontalDivider(color = N4Divider)
                            DropdownMenuItem(
                                text = { Text("Delete Node") },
                                enabled = node.kind == NodeKind.SERIAL || node.kind == NodeKind.PARALLEL,
                                onClick = { menuNodeId = null; vm.deleteNode(node.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CorrectionWorkspaceV4(clip: TimelineClip?, vm: EditorViewModelV4, modifier: Modifier = Modifier) {
    val node = clip?.nodeGraph?.selectedNode()
    if (clip == null || node == null) { NodeEmptyV4("Select a clip and node", modifier); return }
    if (node.kind != NodeKind.SERIAL && node.kind != NodeKind.PARALLEL) { NodeEmptyV4("Select Serial or Parallel node", modifier); return }
    val c = node.corrections
    Column(modifier.background(N4Panel)) {
        WorkspaceTitleV4("Correction · ${node.label}", "Selected node only")
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp)) {
            CorrectionSliderV4("Exposure", c.exposure, -5f..5f) { vm.setSelectedNodeCorrection("Exposure", it) }
            CorrectionSliderV4("Contrast", c.contrast, -100f..100f) { vm.setSelectedNodeCorrection("Contrast", it) }
            CorrectionSliderV4("Saturation", c.saturation, -100f..100f) { vm.setSelectedNodeCorrection("Saturation", it) }
            CorrectionSliderV4("Temperature", c.temperature, -100f..100f) { vm.setSelectedNodeCorrection("Temperature", it) }
            CorrectionSliderV4("Tint", c.tint, -100f..100f) { vm.setSelectedNodeCorrection("Tint", it) }
            CorrectionSliderV4("Highlights", c.highlights, -100f..100f) { vm.setSelectedNodeCorrection("Highlights", it) }
            CorrectionSliderV4("Shadows", c.shadows, -100f..100f) { vm.setSelectedNodeCorrection("Shadows", it) }
            CorrectionSliderV4("Hue", c.hue, -180f..180f) { vm.setSelectedNodeCorrection("Hue", it) }
        }
    }
}

@Composable
fun EffectsWorkspaceV4(clip: TimelineClip?, vm: EditorViewModelV4, modifier: Modifier = Modifier) {
    val node = clip?.nodeGraph?.selectedNode()
    if (clip == null || node == null) { NodeEmptyV4("Select a clip and node", modifier); return }
    if (node.kind != NodeKind.SERIAL && node.kind != NodeKind.PARALLEL) { NodeEmptyV4("Select Serial or Parallel node", modifier); return }
    Column(modifier.background(N4Panel)) {
        WorkspaceTitleV4("Effects · ${node.label}", "Selected node only")
        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Blur", "Sharpen", "Glow", "Film Grain").forEach { name ->
                FilledTonalButton(onClick = { vm.addEffectToSelectedNode(name) }) { Text(name, fontSize = 8.sp) }
            }
        }
        HorizontalDivider(color = N4Divider)
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            val visibleEffects = node.visibleEffects()
            if (visibleEffects.isEmpty()) Text("No effects on this node", fontSize = 9.sp, color = N4Muted)
            visibleEffects.forEach { effect ->
                Row(Modifier.fillMaxWidth().background(N4Raised, RoundedCornerShape(5.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(effect.name, fontSize = 9.sp, modifier = Modifier.weight(1f))
                    Text("${(effect.amount * 100).toInt()}%", fontSize = 8.sp, color = N4Accent)
                }
            }
        }
    }
}

@Composable
private fun CorrectionSliderV4(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onValue: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(82.dp), fontSize = 8.sp, color = Color.White.copy(alpha = .72f))
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onValue, valueRange = range, modifier = Modifier.weight(1f))
        Text(String.format("%.1f", value), Modifier.width(42.dp), fontSize = 7.sp, color = N4Muted)
    }
}

@Composable
private fun WorkspaceTitleV4(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text(subtitle, fontSize = 7.sp, color = N4Muted)
    }
    HorizontalDivider(color = N4Divider)
}

@Composable
private fun NodeEmptyV4(message: String, modifier: Modifier) {
    Box(modifier.background(N4Panel), contentAlignment = Alignment.Center) {
        Text(message, fontSize = 10.sp, color = N4Muted)
    }
}

private fun nodeColorV4(kind: NodeKind): Color = when (kind) {
    NodeKind.IMPORT -> Color(0xFF25323B)
    NodeKind.SERIAL -> Color(0xFF333239)
    NodeKind.PARALLEL -> Color(0xFF3A2F48)
    NodeKind.MIX -> Color(0xFF3B3325)
    NodeKind.OUTPUT -> Color(0xFF26382E)
}
