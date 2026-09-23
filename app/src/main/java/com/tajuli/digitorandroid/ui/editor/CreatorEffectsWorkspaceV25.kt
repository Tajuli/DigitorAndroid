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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.model.CreatorEffectCatalogV25
import com.tajuli.digitorandroid.editor.model.CreatorEffectPresetV25
import com.tajuli.digitorandroid.editor.model.CutoutAnalysisQualityV47
import com.tajuli.digitorandroid.editor.model.NodeAnimationDomain
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.visibleEffects
import com.tajuli.digitorandroid.editor.model.resolvedCutoutV43
import com.tajuli.digitorandroid.editor.model.CutoutModeV43
import com.tajuli.digitorandroid.editor.processing.BeautyFaceAnalyzerV28
import com.tajuli.digitorandroid.editor.processing.GpuPersonCutoutAnalyzerV47
import com.tajuli.digitorandroid.editor.processing.hasPersonCutoutCoverageV43
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Fx25Panel = Color(0xFF0B0B0F)
private val Fx25Raised = Color(0xFF17171C)
private val Fx25Divider = Color(0xFF292930)
private val Fx25Muted = Color(0xFF909098)
private val Fx25Accent = Color(0xFF30E0C3)

@Composable
fun CreatorEffectsWorkspace(
    clip: TimelineClip?,
    vm: EditorViewModel,
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

    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val timelineSelection by EffectTimelineSelectionBusV26.selection.collectAsState()
    val selectedEffectId = timelineSelection
        ?.takeIf { it.clipId == clip.id && it.nodeId == node.id }
        ?.effectId
    var category by remember { mutableStateOf("Trending") }
    val categoryPresets = remember(category) { CreatorEffectCatalogV25.inCategory(category) }
    val nodeEffects = node.visibleEffects()
    val selectedEffect = nodeEffects.firstOrNull { it.id == selectedEffectId }
    val selectedEffectName = selectedEffect?.name

    fun selectEffect(effectId: String) {
        TimelineTextSelectionBusV10.clear()
        VisualOverlaySelectionBusV19.clear()
        EffectTimelineSelectionBusV26.select(clip.id, node.id, effectId)
    }

    fun refineTrackedSubjectInBackground(preset: CreatorEffectPresetV25) {
        val v = preset.vector
        val needsFaceTracking = v.clone > .001f || v.fireEyes > .001f ||
            v.electricEyes > .001f || v.laserEyes > .001f ||
            v.bodyElectric > .001f || v.bodyAura > .001f ||
            v.stroke > .001f || v.bodyFire > .001f
        val needsPersonMatte = v.clone > .001f || v.bodyElectric > .001f ||
            v.bodyAura > .001f || v.stroke > .001f || v.bodyFire > .001f
        if (!needsFaceTracking && !needsPersonMatte) return

        vm.setEditorStatusV19(preset.name + " active · analyzing subject for clean body tracking…")
        scope.launch {
            val analysisClip = vm.state.value.project.clip(clip.id) ?: clip

            val faceTrack = if (needsFaceTracking) {
                runCatching {
                    withContext(Dispatchers.Default) {
                        BeautyFaceAnalyzerV28(context).analyzeAndStore(
                            analysisClip,
                            requireHairMask = false,
                            requireSkinMask = false,
                        )
                    }
                }.getOrNull()
            } else {
                null
            }

            var matteReady = !needsPersonMatte
            var matteError: Throwable? = null
            if (needsPersonMatte) {
                val bodyTrackingClip = analysisClip.copy(
                    cutoutV43 = analysisClip.resolvedCutoutV43().copy(
                        mode = CutoutModeV43.PERSON,
                        analysisQualityV47 = CutoutAnalysisQualityV47.MEDIUM,
                        mattingSizeV69 = 384,
                        portraitLensBlurV99 = false,
                    ),
                )
                if (hasPersonCutoutCoverageV43(context, bodyTrackingClip)) {
                    matteReady = true
                } else {
                    runCatching {
                        withContext(Dispatchers.Default) {
                            GpuPersonCutoutAnalyzerV47(context).analyzeAndStore(
                                bodyTrackingClip,
                                prioritySourceUs = animationSourceTimeUs ?: bodyTrackingClip.sourceInUs,
                            )
                        }
                    }.onSuccess {
                        matteReady = true
                    }.onFailure { error ->
                        matteError = error
                    }
                }
            }

            val faceReady = !needsFaceTracking || faceTrack?.samples?.any { it.geometry != null } == true
            vm.setEditorStatusV19(
                when {
                    faceReady && matteReady ->
                        preset.name + " ready · tracked eyes/body + PP-MattingV2 silhouette"
                    faceReady ->
                        preset.name + " active · face tracking ready; silhouette fallback: " +
                            (matteError?.message ?: "matting unavailable")
                    matteReady ->
                        preset.name + " active · silhouette ready; face fallback in use"
                    else ->
                        preset.name + " active · center fallback in use"
                },
            )
        }
    }

    Column(modifier.background(Fx25Panel)) {
        Row(
            Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Effects · ${node.label}", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("${CreatorEffectCatalogV25.presets.size} presets · timed timeline bars", fontSize = 7.sp, color = Fx25Muted)
        }
        HorizontalDivider(color = Fx25Divider)

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            (CreatorEffectCatalogV25.categories + "Portrait").forEach { name ->
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

        if (category == "Portrait") {
            PortraitLensBlurControlsV99(clip, vm, Modifier.weight(1f))
            return@Column
        }

        LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "none-" + category) {
                EffectNoneCardV98(
                    active = nodeEffects.isEmpty() && !(clip.resolvedCutoutV43().mode == CutoutModeV43.PERSON && clip.resolvedCutoutV43().portraitLensBlurV99),
                    onClick = { clearCreatorEffectsV25(vm, clip.id, node.id) },
                )
            }
            items(categoryPresets, key = { it.name }) { preset ->
                val appliedEffect = nodeEffects.lastOrNull { it.name == preset.name }
                val applied = appliedEffect != null
                val selected = selectedEffectName == preset.name
                Column(
                    Modifier
                        .width(170.dp)
                        .background(Fx25Raised, RoundedCornerShape(8.dp))
                        .border(
                            if (selected) 1.5.dp else if (applied) 1.dp else .5.dp,
                            if (selected) Fx25Accent else if (applied) Color.White.copy(alpha = .45f) else Color.White.copy(alpha = .08f),
                            RoundedCornerShape(8.dp),
                        )
                        .clickable {
                            val liveNode = vm.state.value.project.clip(clip.id)
                                ?.nodeGraph?.nodes?.firstOrNull { it.id == node.id }
                            val liveEffect = liveNode?.visibleEffects()?.lastOrNull { it.name == preset.name }
                            if (liveEffect != null) {
                                vm.deleteEffectTimelineV26(
                                    EffectTimelineSelectionV26(clip.id, node.id, liveEffect.id),
                                )
                            } else {
                                vm.addEffectToSelectedNode(preset.name)
                                refineTrackedSubjectInBackground(preset)
                                val updatedNode = vm.state.value.project.clip(clip.id)
                                    ?.nodeGraph?.nodes?.firstOrNull { it.id == node.id }
                                updatedNode?.effects?.lastOrNull { it.name == preset.name }?.let { selectEffect(it.id) }
                            }
                        }
                        .padding(5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.fillMaxWidth().height(90.dp)
                            .clip(RoundedCornerShape(6.dp)),
                    ) {
                        EffectThumbnailV98(
                            effectName = preset.name,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (applied) {
                            Text(
                                "✓",
                                fontSize = 10.sp,
                                color = Color.White,
                                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        preset.name,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = .90f),
                        maxLines = 1,
                    )
                }
            }
        }

        HorizontalDivider(color = Fx25Divider)

        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(selectedEffect?.name ?: "Select an effect", fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (selectedEffect != null) {
                    Text("${(selectedEffect.amount.coerceIn(0f, 1f) * 100f).toInt()}%", fontSize = 9.sp, color = Fx25Accent)
                    TextButton(
                        onClick = {
                            vm.deleteEffectTimelineV26(
                                EffectTimelineSelectionV26(clip.id, node.id, selectedEffect.id),
                            )
                        },
                    ) {
                        Text("Remove", fontSize = 7.sp, color = Color(0xFFFF7777))
                    }
                }
            }
            Slider(
                value = selectedEffect?.amount?.coerceIn(0f, 1f) ?: 0f,
                onValueChange = { amount ->
                    selectedEffect?.let { effect ->
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
                    }
                },
                valueRange = 0f..1f,
                enabled = selectedEffect != null,
                modifier = Modifier.fillMaxWidth().height(30.dp),
            )
            Text(
                if (selectedEffect == null) {
                    "Tap an effect thumbnail to select it, then adjust its amount here."
                } else {
                    "Effect amount control. Duration and timeline controls remain below."
                },
                fontSize = 7.sp,
                color = Fx25Muted,
            )
        }

        HorizontalDivider(color = Fx25Divider)

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val effects = nodeEffects
            if (effects.isEmpty()) {
                Text("None keeps the image untouched. Every effect thumbnail shows the full effect at 100% preview strength.", fontSize = 9.sp, color = Fx25Muted)
            } else {
                Text(
                    "Preset thumbnails show the full effect on the whole image. Tap None to clear effects, or tap an active effect again to remove it.",
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
                    if (effect.name.equals("Video Denoise", ignoreCase = true)) {
                        Text(
                            "Edge-aware noise reduction. Higher strength smooths low-light grain while protecting strong edges.",
                            fontSize = 7.sp,
                            color = Fx25Muted,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
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
private fun EffectNoneCardV98(
    active: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .width(170.dp)
            .background(Fx25Raised, RoundedCornerShape(8.dp))
            .border(
                if (active) 1.5.dp else .5.dp,
                if (active) Fx25Accent else Color.White.copy(alpha = .08f),
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().height(90.dp)
                .clip(RoundedCornerShape(6.dp)),
        ) {
            IdentityThumbnailV98(modifier = Modifier.fillMaxSize())
            if (active) {
                Text(
                    "✓",
                    fontSize = 10.sp,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "None",
            fontSize = 8.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = .90f),
            maxLines = 1,
        )
    }
}

private fun clearCreatorEffectsV25(vm: EditorViewModel, clipId: String, nodeId: String) {
    val target = ActiveEditorVmRegistry.current() ?: vm
    val current = target.state.value.project
    val liveClip = current.clip(clipId) ?: return
    val liveNode = liveClip.nodeGraph.nodes.firstOrNull { it.id == nodeId } ?: return
    val creatorEffectIds = liveNode.effects
        .filter { CreatorEffectCatalogV25.find(it.name) != null }
        .map { it.id }
        .toSet()
    val clearPortrait = liveClip.resolvedCutoutV43().let { it.mode == CutoutModeV43.PERSON && it.portraitLensBlurV99 }
    if (creatorEffectIds.isEmpty() && !clearPortrait) return

    val next = current.copy(
        tracks = current.tracks.map { track ->
            track.copy(
                clips = track.clips.map { currentClip ->
                    if (currentClip.id != clipId) currentClip
                    else currentClip.copy(
                        cutoutV43 = if (clearPortrait) currentClip.resolvedCutoutV43().copy(
                            mode = CutoutModeV43.NONE, portraitLensBlurV99 = false,
                        ) else currentClip.cutoutV43,
                        nodeGraph = currentClip.nodeGraph.copy(
                            nodes = currentClip.nodeGraph.nodes.map { currentNode ->
                                if (currentNode.id != nodeId) currentNode
                                else currentNode.copy(
                                    effects = currentNode.effects.filterNot { it.id in creatorEffectIds },
                                )
                            },
                            revision = currentClip.nodeGraph.revision + 1L,
                        ),
                    )
                },
            )
        },
    )
    target.commitProjectV19("effect-none-v98", next, "All creator effects removed")
    EffectTimelineSelectionBusV26.clear()
}

@Composable
private fun FxEmptyV25(message: String, modifier: Modifier) {
    Box(modifier.background(Fx25Panel), contentAlignment = Alignment.Center) {
        Text(message, fontSize = 10.sp, color = Fx25Muted)
    }
}
