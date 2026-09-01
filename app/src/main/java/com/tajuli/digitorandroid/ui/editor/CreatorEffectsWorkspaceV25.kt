package com.tajuli.digitorandroid.ui.editor

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.model.CreatorEffectCatalogV25
import com.tajuli.digitorandroid.editor.model.NodeAnimationDomain
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.visibleEffects

private val Fx25Panel = Color(0xFF0B0B0F)
private val Fx25Raised = Color(0xFF17171C)
private val Fx25Divider = Color(0xFF292930)
private val Fx25Muted = Color(0xFF909098)
private val Fx25Accent = Color(0xFF30E0C3)

@Composable
fun CreatorEffectsWorkspaceV25(
    clip: TimelineClip?,
    vm: EditorViewModelV4,
    modifier: Modifier = Modifier,
    animationSourceTimeUs: Long? = null,
) {
    val node = clip?.nodeGraph?.selectedNode()
    if (clip == null || node == null) {
        FxEmptyV25("Select a clip and node", modifier)
        return
    }
    if (node.kind != NodeKind.SERIAL && node.kind != NodeKind.PARALLEL) {
        FxEmptyV25("Select Serial or Parallel node", modifier)
        return
    }

    val timelineSelection by EffectTimelineSelectionBusV26.selection.collectAsState()
    val selectedEffectId = timelineSelection
        ?.takeIf { it.clipId == clip.id && it.nodeId == node.id }
        ?.effectId
    var category by remember { mutableStateOf("Basic") }
    val categoryPresets = remember(category) { CreatorEffectCatalogV25.inCategory(category) }

    fun selectEffect(effectId: String) {
        TimelineTextSelectionBusV10.clear()
        VisualOverlaySelectionBusV19.clear()
        EffectTimelineSelectionBusV26.select(clip.id, node.id, effectId)
    }

    Column(modifier.background(Fx25Panel)) {
        Row(
            Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Effects · ${node.label}", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("50 presets · timed timeline bars", fontSize = 7.sp, color = Fx25Muted)
        }
        HorizontalDivider(color = Fx25Divider)

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CreatorEffectCatalogV25.categories.forEach { name ->
                val selected = name == category
                Box(
                    Modifier
                        .background(if (selected) Fx25Accent.copy(alpha = .16f) else Fx25Raised, RoundedCornerShape(8.dp))
                        .clickable { category = name }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                ) {
                    Text(name, fontSize = 8.sp, color = if (selected) Fx25Accent else Color.White.copy(alpha = .82f))
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            categoryPresets.forEach { preset ->
                Box(
                    Modifier
                        .width(82.dp)
                        .height(52.dp)
                        .background(Fx25Raised, RoundedCornerShape(8.dp))
                        .clickable {
                            vm.addEffectToSelectedNode(preset.name)
                            val updatedNode = vm.state.value.project.clip(clip.id)
                                ?.nodeGraph?.nodes?.firstOrNull { it.id == node.id }
                            updatedNode?.effects?.lastOrNull { it.name == preset.name }?.let { selectEffect(it.id) }
                        }
                        .padding(horizontal = 7.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        preset.name,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = .90f),
                    )
                }
            }
        }

        HorizontalDivider(color = Fx25Divider)

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val effects = node.visibleEffects()
            if (effects.isEmpty()) {
                Text("Choose an effect above to add it to this node", fontSize = 9.sp, color = Fx25Muted)
            } else {
                Text(
                    "Select an effect here or on its timeline bar. Drag the bar edges for duration; hold-drag the bar to move it.",
                    fontSize = 7.sp,
                    color = Fx25Muted,
                )
            }

            effects.forEach { effect ->
                val selected = effect.id == selectedEffectId
                Column(
                    Modifier.fillMaxWidth()
                        .background(Fx25Raised, RoundedCornerShape(7.dp))
                        .border(
                            if (selected) 1.5.dp else .5.dp,
                            if (selected) Fx25Accent else Color.White.copy(alpha = .08f),
                            RoundedCornerShape(7.dp),
                        )
                        .clickable { selectEffect(effect.id) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(effect.name, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("${(effect.amount.coerceIn(0f, 1f) * 100).toInt()}%", fontSize = 8.sp, color = Fx25Accent)
                        if (selected) {
                            TextButton(
                                onClick = {
                                    vm.deleteEffectTimelineV26(EffectTimelineSelectionV26(clip.id, node.id, effect.id))
                                },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                            ) {
                                Text("Delete", fontSize = 7.sp, color = Color(0xFFFF7777))
                            }
                        }
                    }
                    Slider(
                        value = effect.amount.coerceIn(0f, 1f),
                        onValueChange = { amount ->
                            selectEffect(effect.id)
                            val safe = amount.coerceIn(0f, 1f)
                            if (animationSourceTimeUs != null &&
                                clip.nodeAnimations.hasAnimation(node.id, NodeAnimationDomain.EFFECTS)
                            ) {
                                val keyedNode = node.copy(
                                    effects = node.effects.map { current ->
                                        if (current.id == effect.id) current.copy(amount = safe, enabled = true) else current
                                    },
                                )
                                clip.nodeAnimations.upsertIfAnimated(
                                    keyedNode,
                                    NodeAnimationDomain.EFFECTS,
                                    animationSourceTimeUs,
                                )
                            }
                            vm.addEffectToSelectedNode(effect.name, safe)
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.fillMaxWidth().height(28.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FxEmptyV25(message: String, modifier: Modifier) {
    Box(modifier.background(Fx25Panel), contentAlignment = Alignment.Center) {
        Text(message, fontSize = 10.sp, color = Fx25Muted)
    }
}
