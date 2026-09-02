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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.model.AdvancedColorGrade
import com.tajuli.digitorandroid.editor.model.BEAUTY_EYE_POP_V28
import com.tajuli.digitorandroid.editor.model.BEAUTY_HAIR_BROW_DARK_V28
import com.tajuli.digitorandroid.editor.model.BEAUTY_PINK_LIP_V28
import com.tajuli.digitorandroid.editor.model.BEAUTY_SKIN_BRIGHT_V28
import com.tajuli.digitorandroid.editor.model.BEAUTY_SKIN_SMOOTH_V28
import com.tajuli.digitorandroid.editor.model.ClipNodeGraph
import com.tajuli.digitorandroid.editor.model.ColorNode
import com.tajuli.digitorandroid.editor.model.ColorWheelValue
import com.tajuli.digitorandroid.editor.model.LogWheels
import com.tajuli.digitorandroid.editor.model.NodeCorrections
import com.tajuli.digitorandroid.editor.model.NodeEdge
import com.tajuli.digitorandroid.editor.model.NodeEffect
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.NodePosition
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.processing.BeautyFaceAnalyzerV28
import com.tajuli.digitorandroid.editor.processing.BeautyFaceTrackStoreV28
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Filter27Panel = Color(0xFF0B0B0F)
private val Filter27Raised = Color(0xFF17171C)
private val Filter27Divider = Color(0xFF292930)
private val Filter27Muted = Color(0xFF909098)
private val Filter27Accent = Color(0xFF30E0C3)
private const val FILTER_NODE_PREFIX_V28 = "FilterV28 · "
private const val LEGACY_FILTER_NODE_PREFIX_V27 = "Filter · "

private enum class FilterGroupV28(val label: String) {
    LOOKS("Looks"),
    BEAUTY("Beauty"),
}

/**
 * Digitor-authored creator looks. Beauty entries are region-aware GPU layers; normal looks are
 * color-grade recipes. Every applied preset owns its own final serial node, so multiple presets can
 * be combined without overwriting Correction/Color work.
 */
private data class CreatorFilterPresetV28(
    val id: String,
    val name: String,
    val description: String,
    val group: FilterGroupV28,
    val corrections: NodeCorrections = NodeCorrections(),
    val shadows: ColorWheelValue = ColorWheelValue(),
    val midtones: ColorWheelValue = ColorWheelValue(),
    val highlights: ColorWheelValue = ColorWheelValue(),
    val global: ColorWheelValue = ColorWheelValue(),
    val beautyEffects: Map<String, Float> = emptyMap(),
    val swatchA: Color,
    val swatchB: Color,
)

private val CREATOR_FILTERS_V28 = listOf(
    CreatorFilterPresetV28(
        id = "fresh_lime", name = "Fresh Lime", description = "Clean fresh greens", group = FilterGroupV28.LOOKS,
        corrections = NodeCorrections(exposure = .08f, contrast = 7f, saturation = 13f, temperature = -4f, tint = -3f, highlights = -5f, shadows = 7f),
        shadows = ColorWheelValue(red = -.015f, green = .035f, blue = .005f),
        highlights = ColorWheelValue(red = .012f, green = .030f, blue = -.012f),
        swatchA = Color(0xFF84DDA4), swatchB = Color(0xFFEAF8B4),
    ),
    CreatorFilterPresetV28(
        id = "vivid_verse", name = "Vivid Verse", description = "Punchy social color", group = FilterGroupV28.LOOKS,
        corrections = NodeCorrections(exposure = .04f, contrast = 16f, saturation = 23f, temperature = 3f, tint = 2f, highlights = -6f, shadows = -3f),
        shadows = ColorWheelValue(red = -.020f, blue = .025f),
        highlights = ColorWheelValue(red = .025f, green = .006f, blue = -.014f),
        swatchA = Color(0xFF865DFF), swatchB = Color(0xFFFF7E70),
    ),
    CreatorFilterPresetV28(
        id = "soft_light", name = "Soft Light", description = "Airy soft highlights", group = FilterGroupV28.LOOKS,
        corrections = NodeCorrections(exposure = .16f, contrast = -12f, saturation = -4f, temperature = 5f, tint = 2f, highlights = -18f, shadows = 15f),
        midtones = ColorWheelValue(luma = .025f),
        highlights = ColorWheelValue(red = .018f, green = .010f, blue = .004f, luma = .020f),
        swatchA = Color(0xFFF8D9D3), swatchB = Color(0xFFF5F0E8),
    ),
    CreatorFilterPresetV28(
        id = "vhs", name = "VHS", description = "Muted retro camcorder", group = FilterGroupV28.LOOKS,
        corrections = NodeCorrections(exposure = -.03f, contrast = -7f, saturation = -17f, temperature = 7f, tint = 7f, highlights = -12f, shadows = 11f, hue = -2f),
        shadows = ColorWheelValue(red = -.020f, green = .002f, blue = .028f),
        highlights = ColorWheelValue(red = .030f, green = -.005f, blue = -.020f),
        swatchA = Color(0xFF6B7AA8), swatchB = Color(0xFFD88793),
    ),
    CreatorFilterPresetV28(
        id = "teal_orange", name = "Teal & Orange", description = "Cool shadow + warm skin", group = FilterGroupV28.LOOKS,
        corrections = NodeCorrections(exposure = .01f, contrast = 14f, saturation = 9f, temperature = 2f, highlights = -8f, shadows = -7f),
        shadows = ColorWheelValue(red = -.075f, green = .025f, blue = .090f),
        midtones = ColorWheelValue(red = .018f, green = .003f, blue = -.014f),
        highlights = ColorWheelValue(red = .085f, green = .022f, blue = -.065f),
        swatchA = Color(0xFF238B91), swatchB = Color(0xFFE79B63),
    ),
    CreatorFilterPresetV28(
        id = "warm_film", name = "Warm Film", description = "Warm cinematic film", group = FilterGroupV28.LOOKS,
        corrections = NodeCorrections(exposure = .03f, contrast = -4f, saturation = -5f, temperature = 17f, tint = 2f, highlights = -10f, shadows = 9f),
        shadows = ColorWheelValue(red = .018f, green = .006f, blue = -.020f),
        highlights = ColorWheelValue(red = .060f, green = .020f, blue = -.045f),
        swatchA = Color(0xFF8B694F), swatchB = Color(0xFFE6B47E),
    ),
    CreatorFilterPresetV28(
        id = "golden_hour", name = "Golden Hour", description = "Sunset warmth + glow", group = FilterGroupV28.LOOKS,
        corrections = NodeCorrections(exposure = .10f, contrast = 6f, saturation = 14f, temperature = 29f, tint = 4f, highlights = -7f, shadows = 7f),
        midtones = ColorWheelValue(red = .035f, green = .010f, blue = -.030f),
        highlights = ColorWheelValue(red = .090f, green = .035f, blue = -.070f, luma = .012f),
        swatchA = Color(0xFFD78845), swatchB = Color(0xFFFFD982),
    ),
    CreatorFilterPresetV28(
        id = "moody_cinema", name = "Moody Cinema", description = "Deep cinematic contrast", group = FilterGroupV28.LOOKS,
        corrections = NodeCorrections(exposure = -.16f, contrast = 21f, saturation = -12f, temperature = -5f, tint = 1f, highlights = -23f, shadows = -13f),
        shadows = ColorWheelValue(red = -.035f, green = .006f, blue = .060f, luma = -.025f),
        highlights = ColorWheelValue(red = .030f, green = .006f, blue = -.022f),
        swatchA = Color(0xFF243541), swatchB = Color(0xFF8A6B59),
    ),
    CreatorFilterPresetV28(
        id = "natural_portrait", name = "Natural Portrait", description = "Gentle natural skin", group = FilterGroupV28.LOOKS,
        corrections = NodeCorrections(exposure = .08f, contrast = -4f, saturation = 5f, temperature = 8f, tint = 3f, highlights = -9f, shadows = 11f),
        midtones = ColorWheelValue(red = .022f, green = .006f, blue = -.012f, luma = .010f),
        highlights = ColorWheelValue(red = .025f, green = .009f, blue = -.014f),
        swatchA = Color(0xFFC68E78), swatchB = Color(0xFFF0C9B4),
    ),
    CreatorFilterPresetV28(
        id = "fade_film", name = "Fade Film", description = "Lifted blacks + matte", group = FilterGroupV28.LOOKS,
        corrections = NodeCorrections(exposure = .03f, contrast = -19f, saturation = -10f, temperature = 6f, tint = 1f, highlights = -11f, shadows = 22f),
        shadows = ColorWheelValue(red = .012f, green = .006f, luma = .080f),
        highlights = ColorWheelValue(red = .025f, green = .008f, blue = -.016f, luma = -.010f),
        swatchA = Color(0xFF77736B), swatchB = Color(0xFFC9B99B),
    ),

    CreatorFilterPresetV28(
        id = "skin_bright", name = "Skin Bright", description = "Face-aware bright skin", group = FilterGroupV28.BEAUTY,
        beautyEffects = mapOf(BEAUTY_SKIN_BRIGHT_V28 to 1f),
        swatchA = Color(0xFFD6A98F), swatchB = Color(0xFFFFE3CE),
    ),
    CreatorFilterPresetV28(
        id = "skin_smooth", name = "Skin Smooth", description = "Texture-safe smoothing", group = FilterGroupV28.BEAUTY,
        beautyEffects = mapOf(BEAUTY_SKIN_SMOOTH_V28 to 1f),
        swatchA = Color(0xFFC89582), swatchB = Color(0xFFF1C6B7),
    ),
    CreatorFilterPresetV28(
        id = "pink_lips", name = "Pink Lips", description = "Soft natural pink lips", group = FilterGroupV28.BEAUTY,
        beautyEffects = mapOf(BEAUTY_PINK_LIP_V28 to 1f),
        swatchA = Color(0xFF9B4E61), swatchB = Color(0xFFF08FA8),
    ),
    CreatorFilterPresetV28(
        id = "hair_brows", name = "Hair & Brows", description = "Semantic hair + dark brows", group = FilterGroupV28.BEAUTY,
        beautyEffects = mapOf(BEAUTY_HAIR_BROW_DARK_V28 to 1f),
        swatchA = Color(0xFF111115), swatchB = Color(0xFF4E4240),
    ),
    CreatorFilterPresetV28(
        id = "eye_pop", name = "Eye Pop", description = "Clearer prominent eyes", group = FilterGroupV28.BEAUTY,
        beautyEffects = mapOf(BEAUTY_EYE_POP_V28 to 1f),
        swatchA = Color(0xFF394D65), swatchB = Color(0xFFD9E7F2),
    ),
    CreatorFilterPresetV28(
        id = "portrait_glow", name = "Portrait Glow", description = "Balanced beauty combo", group = FilterGroupV28.BEAUTY,
        beautyEffects = mapOf(
            BEAUTY_SKIN_BRIGHT_V28 to .68f,
            BEAUTY_SKIN_SMOOTH_V28 to .34f,
            BEAUTY_PINK_LIP_V28 to .44f,
            BEAUTY_HAIR_BROW_DARK_V28 to .34f,
            BEAUTY_EYE_POP_V28 to .38f,
        ),
        swatchA = Color(0xFFB66F72), swatchB = Color(0xFFF3C7A9),
    ),
)

@Composable
fun CreatorFiltersWorkspaceV27(
    clip: TimelineClip?,
    vm: EditorViewModelV4,
    modifier: Modifier = Modifier,
) {
    if (clip == null) {
        Box(modifier.background(Filter27Panel), contentAlignment = Alignment.Center) {
            Text("Select a video/image clip to use Filters", fontSize = 10.sp, color = Filter27Muted)
        }
        return
    }

    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var group by remember { mutableStateOf(FilterGroupV28.LOOKS) }
    val appliedIds = clip.nodeGraph.filterNodesV28().mapNotNull { it.filterPresetIdV28() }.toSet()
    var selectedPresetId by remember(clip.id) {
        mutableStateOf(clip.nodeGraph.filterNodesV28().lastOrNull()?.filterPresetIdV28())
    }
    val selectedPreset = CREATOR_FILTERS_V28.firstOrNull { it.id == selectedPresetId }
    val selectedNode = selectedPreset?.let { clip.nodeGraph.filterNodeForPresetV28(it.id) }
    val intensity = if (selectedPreset != null && selectedNode != null) inferIntensityV28(selectedNode, selectedPreset) else 0f
    val visiblePresets = CREATOR_FILTERS_V28.filter { it.group == group }

    fun applyPreset(preset: CreatorFilterPresetV28) {
        selectedPresetId = preset.id
        val alreadyApplied = preset.id in appliedIds

        // UX rule: selecting a filter must make its control active immediately. Beauty analysis can
        // take seconds on a long video, so never gate the node/slider behind ML preprocessing.
        if (!alreadyApplied) {
            applyFilterV28(vm, clip.id, preset, 1f, coalesce = false)
        }
        if (preset.group != FilterGroupV28.BEAUTY) return

        val needsHairMask = preset.beautyEffects.containsKey(BEAUTY_HAIR_BROW_DARK_V28)
        val faceReady = BeautyFaceTrackStoreV28.hasCoverage(context, clip)
        if (faceReady && !needsHairMask) return

        vm.setEditorStatusV19(
            if (needsHairMask) "Filter active · analyzing face + semantic hair in background…"
            else "Filter active · analyzing face in background…",
        )
        scope.launch {
            val track = runCatching {
                withContext(Dispatchers.Default) {
                    BeautyFaceAnalyzerV28(context).analyzeAndStore(clip, requireHairMask = needsHairMask)
                }
            }.getOrElse { error ->
                vm.setEditorStatusV19(error.message ?: "Beauty analysis failed")
                return@launch
            }
            if (track.samples.none { it.geometry != null }) {
                vm.setEditorStatusV19("Filter active · no clear face found in analyzed frames")
                return@launch
            }

            // The GPU beauty program may have been created before cached geometry/masks existed.
            // Re-commit the live node at its CURRENT intensity so resources are picked up without
            // resetting a slider that the user moved while background analysis was running.
            val liveClip = vm.state.value.project.clip(clip.id) ?: return@launch
            val liveNode = liveClip.nodeGraph.filterNodeForPresetV28(preset.id) ?: return@launch
            val liveIntensity = inferIntensityV28(liveNode, preset)
            applyFilterV28(vm, clip.id, preset, liveIntensity, coalesce = true)
            vm.setEditorStatusV19(
                if (needsHairMask) "${preset.name} ready · face + semantic hair analyzed"
                else "${preset.name} ready · face analyzed",
            )
        }
    }

    Column(modifier.background(Filter27Panel)) {
        Row(
            Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Filters · ${clip.label}", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("${appliedIds.size} active · stackable", fontSize = 7.sp, color = Filter27Muted)
            if (appliedIds.isNotEmpty()) {
                TextButton(onClick = {
                    clearFiltersV28(vm, clip.id)
                    selectedPresetId = null
                }) { Text("Clear all", fontSize = 7.sp) }
            }
        }
        HorizontalDivider(color = Filter27Divider)

        Row(
            Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterGroupV28.entries.forEach { item ->
                val active = group == item
                Box(
                    Modifier.background(if (active) Filter27Accent.copy(alpha = .16f) else Filter27Raised, RoundedCornerShape(7.dp))
                        .clickable { group = item }
                        .padding(horizontal = 11.dp, vertical = 6.dp),
                ) {
                    Text(item.label, fontSize = 8.sp, color = if (active) Filter27Accent else Color.White.copy(alpha = .75f))
                }
            }
            if (group == FilterGroupV28.BEAUTY) {
                Text("Face contours + HairSegmenter · video + image", fontSize = 7.sp, color = Filter27Muted)
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            visiblePresets.forEach { preset ->
                FilterCardV28(
                    name = preset.name,
                    description = preset.description,
                    applied = preset.id in appliedIds,
                    selected = preset.id == selectedPresetId,
                    swatchA = preset.swatchA,
                    swatchB = preset.swatchB,
                    onClick = { applyPreset(preset) },
                )
            }
        }

        HorizontalDivider(color = Filter27Divider)
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(selectedPreset?.name ?: "Select a filter", fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (selectedNode != null) {
                    Text("${(intensity * 100f).toInt()}%", fontSize = 9.sp, color = Filter27Accent)
                    TextButton(onClick = {
                        removeFilterV28(vm, clip.id, selectedPreset!!.id)
                        selectedPresetId = clip.nodeGraph.filterNodesV28()
                            .mapNotNull { it.filterPresetIdV28() }
                            .lastOrNull { it != selectedPreset.id }
                    }) { Text("Remove", fontSize = 7.sp, color = Color(0xFFFF7777)) }
                }
            }
            Slider(
                value = if (selectedNode == null) 0f else intensity,
                onValueChange = { next ->
                    selectedPreset?.let { preset ->
                        if (selectedNode != null) applyFilterV28(vm, clip.id, preset, next, coalesce = true)
                    }
                },
                valueRange = 0f..1f,
                enabled = selectedNode != null,
                modifier = Modifier.fillMaxWidth().height(30.dp),
            )
            Text(
                if (group == FilterGroupV28.BEAUTY) {
                    "Combine freely: Skin Bright + Hair & Brows + Pink Lips + Eye Pop. ML Kit localizes face features; MediaPipe HairSegmenter supplies a real semantic hair mask instead of a color/rectangle guess."
                } else {
                    "Looks are independent final serial nodes. You can stack multiple looks, and your existing Correction/Color nodes remain untouched."
                },
                fontSize = 7.sp,
                color = Filter27Muted,
            )
        }
    }
}

@Composable
private fun FilterCardV28(
    name: String,
    description: String,
    applied: Boolean,
    selected: Boolean,
    swatchA: Color,
    swatchB: Color,
    onClick: () -> Unit,
) {
    Column(
        Modifier.width(94.dp)
            .background(Filter27Raised, RoundedCornerShape(9.dp))
            .border(
                if (selected) 1.7.dp else if (applied) 1.dp else .5.dp,
                if (selected) Filter27Accent else if (applied) Color.White.copy(alpha = .48f) else Color.White.copy(alpha = .08f),
                RoundedCornerShape(9.dp),
            )
            .clickable(onClick = onClick)
            .padding(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().height(43.dp)
                .background(Brush.linearGradient(listOf(swatchA, swatchB)), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.TopEnd,
        ) {
            if (applied) {
                Text("✓", fontSize = 10.sp, color = Color.White, modifier = Modifier.padding(4.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(name, fontSize = 8.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, textAlign = TextAlign.Center)
        Text(description, fontSize = 6.sp, color = Filter27Muted, maxLines = 1, textAlign = TextAlign.Center)
    }
}

private fun applyFilterV28(
    vm: EditorViewModelV4,
    clipId: String,
    preset: CreatorFilterPresetV28,
    intensity: Float,
    coalesce: Boolean,
) {
    val state = vm.state.value
    val liveClip = state.project.clip(clipId) ?: return
    val safeIntensity = intensity.coerceIn(0f, 1f)
    var graph = liveClip.nodeGraph.ensureFilterNodeV28(preset)
    val filterNode = graph.filterNodeForPresetV28(preset.id) ?: return
    val nextNode = filterNode.copy(
        label = filterLabelV28(preset),
        corrections = preset.corrections.scaledV28(safeIntensity),
        advancedColor = AdvancedColorGrade(
            log = LogWheels(
                shadows = preset.shadows.scaledV28(safeIntensity),
                midtones = preset.midtones.scaledV28(safeIntensity),
                highlights = preset.highlights.scaledV28(safeIntensity),
                global = preset.global.scaledV28(safeIntensity),
            ),
        ),
        effects = preset.beautyEffects.map { (name, weight) ->
            NodeEffect(name = name, amount = (weight * safeIntensity).coerceIn(0f, 1.5f))
        },
    )
    graph = graph.copy(
        nodes = graph.nodes.map { node -> if (node.id == nextNode.id) nextNode else node },
        revision = graph.revision + 1L,
    )
    vm.commitProjectV19(
        label = "filter-stack-v28",
        project = state.project.withUpdatedClipV28(liveClip.copy(nodeGraph = graph)),
        status = "${preset.name} · ${(safeIntensity * 100f).toInt()}% · ${graph.filterNodesV28().size} filter(s)",
        coalesce = coalesce,
    )
}

private fun removeFilterV28(vm: EditorViewModelV4, clipId: String, presetId: String) {
    val state = vm.state.value
    val liveClip = state.project.clip(clipId) ?: return
    val target = liveClip.nodeGraph.filterNodeForPresetV28(presetId) ?: return
    val graph = liveClip.nodeGraph.deleteEditableNodeV4(target.id).let { deleted ->
        deleted.copy(revision = deleted.revision + 1L)
    }
    vm.commitProjectV19(
        label = "filter-stack-v28-remove",
        project = state.project.withUpdatedClipV28(liveClip.copy(nodeGraph = graph)),
        status = "Filter removed · ${graph.filterNodesV28().size} active",
    )
}

private fun clearFiltersV28(vm: EditorViewModelV4, clipId: String) {
    val state = vm.state.value
    val liveClip = state.project.clip(clipId) ?: return
    var graph = liveClip.nodeGraph
    val ids = graph.filterNodesV28().map { it.id }
    if (ids.isEmpty()) return
    ids.forEach { id -> graph = graph.deleteEditableNodeV4(id) }
    graph = graph.copy(revision = graph.revision + 1L)
    vm.commitProjectV19(
        label = "filter-stack-v28-clear",
        project = state.project.withUpdatedClipV28(liveClip.copy(nodeGraph = graph)),
        status = "All filters removed",
    )
}

private fun ClipNodeGraph.ensureFilterNodeV28(preset: CreatorFilterPresetV28): ClipNodeGraph {
    val existing = filterNodeForPresetV28(preset.id)
    if (existing != null) {
        // Migrate the single-filter V27 label if this branch had already been used locally.
        if (existing.label.startsWith(LEGACY_FILTER_NODE_PREFIX_V27)) {
            return copy(
                nodes = nodes.map { node -> if (node.id == existing.id) node.copy(label = filterLabelV28(preset)) else node },
                revision = revision + 1L,
            )
        }
        return this
    }
    val output = nodes.firstOrNull { it.kind == NodeKind.OUTPUT } ?: return this
    val incoming = edges.firstOrNull { it.toId == output.id } ?: return this
    val filter = ColorNode(
        kind = NodeKind.SERIAL,
        label = filterLabelV28(preset),
        position = NodePosition(output.position.x, output.position.y),
    )
    val shiftedNodes = nodes.map { node ->
        if (node.position.x >= output.position.x) node.copy(position = node.position.copy(x = node.position.x + 126f)) else node
    }
    val rebuiltEdges = edges.filterNot { it == incoming }.toMutableList().apply {
        add(NodeEdge(incoming.fromId, filter.id))
        add(NodeEdge(filter.id, output.id))
    }
    return copy(nodes = shiftedNodes + filter, edges = rebuiltEdges, revision = revision + 1L)
}

private fun ClipNodeGraph.filterNodesV28(): List<ColorNode> =
    nodes.filter { node ->
        node.kind == NodeKind.SERIAL &&
            (node.label.startsWith(FILTER_NODE_PREFIX_V28) || node.label.startsWith(LEGACY_FILTER_NODE_PREFIX_V27)) &&
            node.filterPresetIdV28() != null
    }.sortedBy { it.position.x }

private fun ClipNodeGraph.filterNodeForPresetV28(presetId: String): ColorNode? =
    filterNodesV28().firstOrNull { it.filterPresetIdV28() == presetId }

private fun ColorNode.filterPresetIdV28(): String? {
    if (label.startsWith(FILTER_NODE_PREFIX_V28)) {
        return label.removePrefix(FILTER_NODE_PREFIX_V28).substringBefore(" · ").takeIf { id ->
            CREATOR_FILTERS_V28.any { it.id == id }
        }
    }
    if (label.startsWith(LEGACY_FILTER_NODE_PREFIX_V27)) {
        val oldName = label.removePrefix(LEGACY_FILTER_NODE_PREFIX_V27)
        return CREATOR_FILTERS_V28.firstOrNull { it.name == oldName }?.id
    }
    return null
}

private fun filterLabelV28(preset: CreatorFilterPresetV28): String =
    "$FILTER_NODE_PREFIX_V28${preset.id} · ${preset.name}"

private fun inferIntensityV28(node: ColorNode, preset: CreatorFilterPresetV28): Float {
    if (preset.beautyEffects.isNotEmpty()) {
        val base = preset.beautyEffects.entries.firstOrNull { abs(it.value) > .0001f } ?: return 1f
        val current = node.effects.firstOrNull { it.name == base.key }?.amount ?: 0f
        return (current / base.value).coerceIn(0f, 1f)
    }
    val base = preset.corrections
    val current = node.corrections
    val pairs = listOf(
        current.contrast to base.contrast,
        current.saturation to base.saturation,
        current.temperature to base.temperature,
        current.exposure to base.exposure,
        current.highlights to base.highlights,
        current.shadows to base.shadows,
    )
    val pair = pairs.firstOrNull { (_, reference) -> abs(reference) > .0001f } ?: return 1f
    return (pair.first / pair.second).coerceIn(0f, 1f)
}

private fun NodeCorrections.scaledV28(amount: Float): NodeCorrections = copy(
    exposure = exposure * amount,
    contrast = contrast * amount,
    saturation = saturation * amount,
    temperature = temperature * amount,
    tint = tint * amount,
    highlights = highlights * amount,
    shadows = shadows * amount,
    hue = hue * amount,
    colorBoost = colorBoost * amount,
)

private fun ColorWheelValue.scaledV28(amount: Float): ColorWheelValue = copy(
    red = red * amount,
    green = green * amount,
    blue = blue * amount,
    luma = luma * amount,
    puckX = puckX * amount,
    puckY = puckY * amount,
)

private fun TimelineProject.withUpdatedClipV28(updated: TimelineClip): TimelineProject = copy(
    tracks = tracks.map { track ->
        if (track.clips.none { it.id == updated.id }) track
        else track.copy(clips = track.clips.map { clip -> if (clip.id == updated.id) updated else clip })
    },
)
