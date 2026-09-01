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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.model.AdvancedColorGrade
import com.tajuli.digitorandroid.editor.model.ClipNodeGraph
import com.tajuli.digitorandroid.editor.model.ColorNode
import com.tajuli.digitorandroid.editor.model.ColorWheelValue
import com.tajuli.digitorandroid.editor.model.LogWheels
import com.tajuli.digitorandroid.editor.model.NodeCorrections
import com.tajuli.digitorandroid.editor.model.NodeEdge
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.NodePosition
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import kotlin.math.abs

private val Filter27Panel = Color(0xFF0B0B0F)
private val Filter27Raised = Color(0xFF17171C)
private val Filter27Divider = Color(0xFF292930)
private val Filter27Muted = Color(0xFF909098)
private val Filter27Accent = Color(0xFF30E0C3)
private const val FILTER_NODE_PREFIX_V27 = "Filter · "

/**
 * Creator-facing filter look. These are Digitor-authored grading recipes inspired by the familiar
 * short-form looks requested by the creator; no third-party LUT/assets are bundled or copied.
 */
private data class CreatorFilterPresetV27(
    val id: String,
    val name: String,
    val description: String,
    val corrections: NodeCorrections,
    val shadows: ColorWheelValue = ColorWheelValue(),
    val midtones: ColorWheelValue = ColorWheelValue(),
    val highlights: ColorWheelValue = ColorWheelValue(),
    val global: ColorWheelValue = ColorWheelValue(),
    val swatchA: Color,
    val swatchB: Color,
)

private val CREATOR_FILTERS_V27 = listOf(
    CreatorFilterPresetV27(
        id = "fresh_lime",
        name = "Fresh Lime",
        description = "Clean, fresh greens",
        corrections = NodeCorrections(exposure = .08f, contrast = 7f, saturation = 13f, temperature = -4f, tint = -3f, highlights = -5f, shadows = 7f),
        shadows = ColorWheelValue(red = -.015f, green = .035f, blue = .005f),
        highlights = ColorWheelValue(red = .012f, green = .030f, blue = -.012f),
        swatchA = Color(0xFF84DDA4), swatchB = Color(0xFFEAF8B4),
    ),
    CreatorFilterPresetV27(
        id = "vivid_verse",
        name = "Vivid Verse",
        description = "Punchy social color",
        corrections = NodeCorrections(exposure = .04f, contrast = 16f, saturation = 23f, temperature = 3f, tint = 2f, highlights = -6f, shadows = -3f),
        shadows = ColorWheelValue(red = -.020f, green = .000f, blue = .025f),
        highlights = ColorWheelValue(red = .025f, green = .006f, blue = -.014f),
        swatchA = Color(0xFF865DFF), swatchB = Color(0xFFFF7E70),
    ),
    CreatorFilterPresetV27(
        id = "soft_light",
        name = "Soft Light",
        description = "Airy soft highlights",
        corrections = NodeCorrections(exposure = .16f, contrast = -12f, saturation = -4f, temperature = 5f, tint = 2f, highlights = -18f, shadows = 15f),
        midtones = ColorWheelValue(luma = .025f),
        highlights = ColorWheelValue(red = .018f, green = .010f, blue = .004f, luma = .020f),
        swatchA = Color(0xFFF8D9D3), swatchB = Color(0xFFF5F0E8),
    ),
    CreatorFilterPresetV27(
        id = "vhs",
        name = "VHS",
        description = "Muted retro camcorder",
        corrections = NodeCorrections(exposure = -.03f, contrast = -7f, saturation = -17f, temperature = 7f, tint = 7f, highlights = -12f, shadows = 11f, hue = -2f),
        shadows = ColorWheelValue(red = -.020f, green = .002f, blue = .028f),
        highlights = ColorWheelValue(red = .030f, green = -.005f, blue = -.020f),
        swatchA = Color(0xFF6B7AA8), swatchB = Color(0xFFD88793),
    ),
    CreatorFilterPresetV27(
        id = "teal_orange",
        name = "Teal & Orange",
        description = "Cool shadows, warm skin",
        corrections = NodeCorrections(exposure = .01f, contrast = 14f, saturation = 9f, temperature = 2f, highlights = -8f, shadows = -7f),
        shadows = ColorWheelValue(red = -.075f, green = .025f, blue = .090f),
        midtones = ColorWheelValue(red = .018f, green = .003f, blue = -.014f),
        highlights = ColorWheelValue(red = .085f, green = .022f, blue = -.065f),
        swatchA = Color(0xFF238B91), swatchB = Color(0xFFE79B63),
    ),
    CreatorFilterPresetV27(
        id = "warm_film",
        name = "Warm Film",
        description = "Warm cinematic film",
        corrections = NodeCorrections(exposure = .03f, contrast = -4f, saturation = -5f, temperature = 17f, tint = 2f, highlights = -10f, shadows = 9f),
        shadows = ColorWheelValue(red = .018f, green = .006f, blue = -.020f),
        highlights = ColorWheelValue(red = .060f, green = .020f, blue = -.045f),
        swatchA = Color(0xFF8B694F), swatchB = Color(0xFFE6B47E),
    ),
    CreatorFilterPresetV27(
        id = "golden_hour",
        name = "Golden Hour",
        description = "Sunset warmth and glow",
        corrections = NodeCorrections(exposure = .10f, contrast = 6f, saturation = 14f, temperature = 29f, tint = 4f, highlights = -7f, shadows = 7f),
        midtones = ColorWheelValue(red = .035f, green = .010f, blue = -.030f),
        highlights = ColorWheelValue(red = .090f, green = .035f, blue = -.070f, luma = .012f),
        swatchA = Color(0xFFD78845), swatchB = Color(0xFFFFD982),
    ),
    CreatorFilterPresetV27(
        id = "moody_cinema",
        name = "Moody Cinema",
        description = "Deep cinematic contrast",
        corrections = NodeCorrections(exposure = -.16f, contrast = 21f, saturation = -12f, temperature = -5f, tint = 1f, highlights = -23f, shadows = -13f),
        shadows = ColorWheelValue(red = -.035f, green = .006f, blue = .060f, luma = -.025f),
        highlights = ColorWheelValue(red = .030f, green = .006f, blue = -.022f),
        swatchA = Color(0xFF243541), swatchB = Color(0xFF8A6B59),
    ),
    CreatorFilterPresetV27(
        id = "natural_portrait",
        name = "Natural Portrait",
        description = "Gentle natural skin",
        corrections = NodeCorrections(exposure = .08f, contrast = -4f, saturation = 5f, temperature = 8f, tint = 3f, highlights = -9f, shadows = 11f),
        midtones = ColorWheelValue(red = .022f, green = .006f, blue = -.012f, luma = .010f),
        highlights = ColorWheelValue(red = .025f, green = .009f, blue = -.014f),
        swatchA = Color(0xFFC68E78), swatchB = Color(0xFFF0C9B4),
    ),
    CreatorFilterPresetV27(
        id = "fade_film",
        name = "Fade Film",
        description = "Lifted blacks, matte film",
        corrections = NodeCorrections(exposure = .03f, contrast = -19f, saturation = -10f, temperature = 6f, tint = 1f, highlights = -11f, shadows = 22f),
        shadows = ColorWheelValue(red = .012f, green = .006f, blue = .000f, luma = .080f),
        highlights = ColorWheelValue(red = .025f, green = .008f, blue = -.016f, luma = -.010f),
        swatchA = Color(0xFF77736B), swatchB = Color(0xFFC9B99B),
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
            Text("Select a video clip to use Filters", fontSize = 10.sp, color = Filter27Muted)
        }
        return
    }

    val filterNode = clip.nodeGraph.filterNodeV27()
    val selectedPreset = filterNode?.let { node -> presetFromFilterNodeV27(node) }
    val intensity = if (filterNode != null && selectedPreset != null) inferIntensityV27(filterNode, selectedPreset) else 1f

    Column(modifier.background(Filter27Panel)) {
        Row(
            Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Filters · ${clip.label}", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("10 looks · dedicated final color node", fontSize = 7.sp, color = Filter27Muted)
        }
        HorizontalDivider(color = Filter27Divider)

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            FilterCardV27(
                name = "None",
                description = "Remove filter",
                selected = selectedPreset == null,
                swatchA = Color(0xFF55555B),
                swatchB = Color(0xFF99999F),
                onClick = { removeFilterV27(vm, clip.id) },
            )
            CREATOR_FILTERS_V27.forEach { preset ->
                FilterCardV27(
                    name = preset.name,
                    description = preset.description,
                    selected = preset.id == selectedPreset?.id,
                    swatchA = preset.swatchA,
                    swatchB = preset.swatchB,
                    onClick = { applyFilterV27(vm, clip.id, preset, 1f, coalesce = false) },
                )
            }
        }

        HorizontalDivider(color = Filter27Divider)
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(selectedPreset?.name ?: "No filter", fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(if (selectedPreset == null) "—" else "${(intensity * 100f).toInt()}%", fontSize = 9.sp, color = Filter27Accent)
            }
            Slider(
                value = if (selectedPreset == null) 0f else intensity,
                onValueChange = { next ->
                    selectedPreset?.let { preset -> applyFilterV27(vm, clip.id, preset, next, coalesce = true) }
                },
                valueRange = 0f..1f,
                enabled = selectedPreset != null,
                modifier = Modifier.fillMaxWidth().height(34.dp),
            )
            Text(
                "Filter lives on its own final serial color node, so your Correction/Color nodes stay untouched. Preview and export use the same shared grading pipeline.",
                fontSize = 7.sp,
                color = Filter27Muted,
            )
        }
    }
}

@Composable
private fun FilterCardV27(
    name: String,
    description: String,
    selected: Boolean,
    swatchA: Color,
    swatchB: Color,
    onClick: () -> Unit,
) {
    Column(
        Modifier.width(92.dp)
            .background(Filter27Raised, RoundedCornerShape(9.dp))
            .border(if (selected) 1.5.dp else .5.dp, if (selected) Filter27Accent else Color.White.copy(alpha = .08f), RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().height(48.dp)
                .background(Brush.linearGradient(listOf(swatchA, swatchB)), RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.height(4.dp))
        Text(name, fontSize = 8.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, textAlign = TextAlign.Center)
        Text(description, fontSize = 6.sp, color = Filter27Muted, maxLines = 1, textAlign = TextAlign.Center)
    }
}

private fun applyFilterV27(
    vm: EditorViewModelV4,
    clipId: String,
    preset: CreatorFilterPresetV27,
    intensity: Float,
    coalesce: Boolean,
) {
    val state = vm.state.value
    val liveClip = state.project.clip(clipId) ?: return
    val safeIntensity = intensity.coerceIn(0f, 1f)
    var graph = liveClip.nodeGraph.ensureFilterNodeV27()
    val filterNode = graph.filterNodeV27() ?: return
    val nextNode = filterNode.copy(
        label = FILTER_NODE_PREFIX_V27 + preset.name,
        corrections = preset.corrections.scaledV27(safeIntensity),
        advancedColor = AdvancedColorGrade(
            log = LogWheels(
                shadows = preset.shadows.scaledV27(safeIntensity),
                midtones = preset.midtones.scaledV27(safeIntensity),
                highlights = preset.highlights.scaledV27(safeIntensity),
                global = preset.global.scaledV27(safeIntensity),
            ),
        ),
    )
    graph = graph.copy(
        nodes = graph.nodes.map { node -> if (node.id == nextNode.id) nextNode else node },
        revision = graph.revision + 1L,
    )
    val updatedClip = liveClip.copy(nodeGraph = graph)
    val project = state.project.withUpdatedClipV27(updatedClip)
    vm.commitProjectV19(
        label = "filter-v27",
        project = project,
        status = "${preset.name} filter · ${(safeIntensity * 100f).toInt()}%",
        coalesce = coalesce,
    )
}

private fun removeFilterV27(vm: EditorViewModelV4, clipId: String) {
    val state = vm.state.value
    val liveClip = state.project.clip(clipId) ?: return
    val filterNode = liveClip.nodeGraph.filterNodeV27() ?: return
    val graph = liveClip.nodeGraph.deleteEditableNodeV4(filterNode.id).let { deleted ->
        deleted.copy(revision = deleted.revision + 1L)
    }
    vm.commitProjectV19(
        label = "filter-v27-remove",
        project = state.project.withUpdatedClipV27(liveClip.copy(nodeGraph = graph)),
        status = "Filter removed",
    )
}

private fun ClipNodeGraph.ensureFilterNodeV27(): ClipNodeGraph {
    if (filterNodeV27() != null) return this
    val output = nodes.firstOrNull { it.kind == NodeKind.OUTPUT } ?: return this
    val incoming = edges.firstOrNull { it.toId == output.id } ?: return this
    val filter = ColorNode(
        kind = NodeKind.SERIAL,
        label = FILTER_NODE_PREFIX_V27 + "None",
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

private fun ClipNodeGraph.filterNodeV27(): ColorNode? =
    nodes.firstOrNull { node -> node.kind == NodeKind.SERIAL && node.label.startsWith(FILTER_NODE_PREFIX_V27) }

private fun presetFromFilterNodeV27(node: ColorNode): CreatorFilterPresetV27? {
    val name = node.label.removePrefix(FILTER_NODE_PREFIX_V27)
    return CREATOR_FILTERS_V27.firstOrNull { it.name == name }
}

private fun inferIntensityV27(node: ColorNode, preset: CreatorFilterPresetV27): Float {
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

private fun NodeCorrections.scaledV27(amount: Float): NodeCorrections = copy(
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

private fun ColorWheelValue.scaledV27(amount: Float): ColorWheelValue = copy(
    red = red * amount,
    green = green * amount,
    blue = blue * amount,
    luma = luma * amount,
    puckX = puckX * amount,
    puckY = puckY * amount,
)

private fun TimelineProject.withUpdatedClipV27(updated: TimelineClip): TimelineProject = copy(
    tracks = tracks.map { track ->
        if (track.clips.none { it.id == updated.id }) track
        else track.copy(clips = track.clips.map { clip -> if (clip.id == updated.id) updated else clip })
    },
)
