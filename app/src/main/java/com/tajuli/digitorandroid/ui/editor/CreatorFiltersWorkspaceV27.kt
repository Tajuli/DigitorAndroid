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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.model.BEAUTY_HAIR_BROW_DARK_V28
import com.tajuli.digitorandroid.editor.model.BEAUTY_SKIN_BRIGHT_V28
import com.tajuli.digitorandroid.editor.model.BEAUTY_SKIN_SMOOTH_V28
import com.tajuli.digitorandroid.editor.model.CREATOR_FILTERS_V36
import com.tajuli.digitorandroid.editor.model.CreatorFilterGroupV36
import com.tajuli.digitorandroid.editor.model.CreatorFilterPresetV36
import com.tajuli.digitorandroid.editor.model.NodeEffect
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.appliedCreatorFiltersV41
import com.tajuli.digitorandroid.editor.model.creatorFilterMarkerNameV36
import com.tajuli.digitorandroid.editor.model.creatorFilterPresetIdV36
import com.tajuli.digitorandroid.editor.model.creatorFilterPresetV36
import com.tajuli.digitorandroid.editor.model.isLegacyCreatorFilterNodeV36
import com.tajuli.digitorandroid.editor.model.selectedCreatorFilterHostV41
import com.tajuli.digitorandroid.editor.processing.BeautyFaceAnalyzerV28
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Filter27Panel = Color(0xFF0B0B0F)
private val Filter27Raised = Color(0xFF17171C)
private val Filter27Divider = Color(0xFF292930)
private val Filter27Muted = Color(0xFF909098)
private val Filter27Accent = Color(0xFF30E0C3)

@Composable
fun CreatorFiltersWorkspace(
    clip: TimelineClip?,
    vm: EditorViewModel,
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
    var group by remember { mutableStateOf(CreatorFilterGroupV36.LOOKS) }
    var selectedPresetId by remember(clip.id, clip.nodeGraph.selectedNodeId) { mutableStateOf<String?>(null) }
    val host = clip.selectedCreatorFilterHostV41()
    val applied = host?.appliedCreatorFiltersV41() ?: linkedMapOf()
    val selectedPreset = CREATOR_FILTERS_V36.firstOrNull { it.id == selectedPresetId }
    val selectedIntensity = selectedPresetId?.let { applied[it] } ?: 0f
    val visiblePresets = CREATOR_FILTERS_V36.filter { it.group == group }
    val noneActive = visiblePresets.none { it.id in applied }

    fun refineBeautyInBackground(preset: CreatorFilterPresetV36) {
        val needsHairMask = preset.beautyWeights.containsKey(BEAUTY_HAIR_BROW_DARK_V28)
        val needsSkinMask = preset.beautyWeights.containsKey(BEAUTY_SKIN_SMOOTH_V28)
        val needsSkinColorSample = preset.beautyWeights.containsKey(BEAUTY_SKIN_BRIGHT_V28)
        val label = when {
            needsHairMask && needsSkinMask -> "skin + face + hair"
            needsHairMask -> "face + hair"
            needsSkinMask -> "skin + face"
            needsSkinColorSample -> "face color sample"
            else -> "face"
        }
        vm.setEditorStatusV19("${preset.name} active instantly · refining $label…")
        scope.launch {
            val analysisClip = vm.state.value.project.clip(clip.id) ?: clip
            val track = runCatching {
                withContext(Dispatchers.Default) {
                    BeautyFaceAnalyzerV28(context).analyzeAndStore(
                        analysisClip,
                        requireHairMask = needsHairMask,
                        requireSkinMask = needsSkinMask,
                    )
                }
            }.getOrElse { error ->
                vm.setEditorStatusV19("${preset.name} active · refinement unavailable: ${error.message ?: "analysis failed"}")
                return@launch
            }
            vm.setEditorStatusV19(
                if (track.samples.any { it.geometry != null }) "${preset.name} ready · refined $label"
                else "${preset.name} active · no clear face found; fallback remains active",
            )
        }
    }

    fun togglePreset(preset: CreatorFilterPresetV36) {
        if (host == null) {
            vm.setEditorStatusV19("Select a Serial or Parallel node before applying a filter")
            return
        }
        val liveHost = vm.state.value.project.clip(clip.id)?.selectedCreatorFilterHostV41()
        val wasApplied = liveHost?.appliedCreatorFiltersV41()?.containsKey(preset.id) == true
        if (wasApplied) {
            updateFilterMarkerV36(vm, clip.id, preset.id, 0f, coalesce = false)
            if (selectedPresetId == preset.id) selectedPresetId = null
            return
        }

        selectedPresetId = preset.id
        updateFilterMarkerV36(vm, clip.id, preset.id, preset.defaultIntensity, coalesce = false)
        if (preset.group == CreatorFilterGroupV36.BEAUTY) refineBeautyInBackground(preset)
    }

    Column(modifier.background(Filter27Panel)) {
        Row(
            Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Filters · ${clip.label}", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                if (host == null) "Select Serial/Parallel node" else "Node ${host.label} · ${applied.size} active",
                fontSize = 7.sp,
                color = if (host == null) Color(0xFFFFB86B) else Filter27Muted,
            )
            if (applied.isNotEmpty()) {
                TextButton(onClick = {
                    clearFilterMarkersV36(vm, clip.id)
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
            CreatorFilterGroupV36.entries.forEach { item ->
                val active = group == item
                Box(
                    Modifier.background(if (active) Filter27Accent.copy(alpha = .16f) else Filter27Raised, RoundedCornerShape(7.dp))
                        .clickable { group = item }
                        .padding(horizontal = 11.dp, vertical = 6.dp),
                ) {
                    Text(if (item == CreatorFilterGroupV36.LOOKS) "Looks" else "Beauty", fontSize = 8.sp, color = if (active) Filter27Accent else Color.White.copy(alpha = .75f))
                }
            }
        }
        LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            item(key = "none-" + group.name) {
                FilterNoneCardV98(
                    active = noneActive,
                    onClick = {
                        clearFilterGroupV36(vm, clip.id, group)
                        if (selectedPreset?.group == group) selectedPresetId = null
                    },
                )
            }
            items(visiblePresets, key = { it.id }) { preset ->
                FilterCardV36(
                    preset = preset,
                    applied = preset.id in applied,
                    selected = preset.id == selectedPresetId,
                    onClick = { togglePreset(preset) },
                )
            }
        }
        HorizontalDivider(color = Filter27Divider)
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(selectedPreset?.name ?: "Select a filter", fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (selectedPreset != null && selectedPreset.id in applied) {
                    Text("${(selectedIntensity * 100f).toInt()}%", fontSize = 9.sp, color = Filter27Accent)
                    TextButton(onClick = {
                        updateFilterMarkerV36(vm, clip.id, selectedPreset.id, 0f, coalesce = false)
                        selectedPresetId = null
                    }) { Text("Remove", fontSize = 7.sp, color = Color(0xFFFF7777)) }
                }
            }
            Slider(
                value = selectedIntensity.coerceIn(0f, 1f),
                onValueChange = { next ->
                    selectedPreset?.takeIf { it.id in applied }?.let { preset ->
                        updateFilterMarkerV36(vm, clip.id, preset.id, next, coalesce = true)
                    }
                },
                valueRange = 0f..1f,
                enabled = host != null && selectedPreset != null && selectedPreset.id in applied,
                modifier = Modifier.fillMaxWidth().height(30.dp),
            )
            Text(
                if (group == CreatorFilterGroupV36.LOOKS) {
                    "None keeps the image untouched. Preset thumbnails show the full filter at 100% preview strength. Tap an active filter again to remove it."
                } else {
                    "None keeps the image untouched. Beauty thumbnails show the full preset preview. Tap an active beauty preset again to remove it."
                },
                fontSize = 7.sp,
                color = Filter27Muted,
            )
        }
    }
}

@Composable
private fun FilterNoneCardV98(
    active: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier.width(170.dp)
            .background(Filter27Raised, RoundedCornerShape(9.dp))
            .border(
                if (active) 1.7.dp else .5.dp,
                if (active) Filter27Accent else Color.White.copy(alpha = .08f),
                RoundedCornerShape(9.dp),
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
        Text("None", fontSize = 8.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, textAlign = TextAlign.Center)
        Text("Original", fontSize = 6.sp, color = Filter27Muted, maxLines = 1, textAlign = TextAlign.Center)
    }
}

@Composable
private fun FilterCardV36(
    preset: CreatorFilterPresetV36,
    applied: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier.width(170.dp)
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
            Modifier.fillMaxWidth().height(90.dp)
                .clip(RoundedCornerShape(6.dp)),
        ) {
            FilterThumbnailV98(
                presetId = preset.id,
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
        Text(preset.name, fontSize = 8.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, textAlign = TextAlign.Center)
        Text(preset.description, fontSize = 6.sp, color = Filter27Muted, maxLines = 1, textAlign = TextAlign.Center)
    }
}

private fun updateFilterMarkerV36(
    vm: EditorViewModel,
    clipId: String,
    presetId: String,
    intensity: Float,
    coalesce: Boolean,
) {
    val state = vm.state.value
    val liveClip = state.project.clip(clipId) ?: return
    val preset = creatorFilterPresetV36(presetId)
    var graph = liveClip.nodeGraph
    graph.nodes.filter { it.isLegacyCreatorFilterNodeV36() }.map { it.id }.forEach { id ->
        graph = graph.deleteEditableNodeV4(id)
    }
    val migratedClip = liveClip.copy(nodeGraph = graph)
    val host = migratedClip.selectedCreatorFilterHostV41()
    if (host == null) {
        vm.setEditorStatusV19("Select a Serial or Parallel node before applying a filter")
        return
    }

    val remembered = host.appliedCreatorFiltersV41().toMutableMap()
    if (intensity > .001f) {
        if (preset?.group == CreatorFilterGroupV36.LOOKS) {
            remembered.keys.filter { creatorFilterPresetV36(it)?.group == CreatorFilterGroupV36.LOOKS }
                .toList().forEach(remembered::remove)
        }
        remembered.remove(presetId)
        remembered[presetId] = intensity.coerceIn(0f, 1f)
    } else {
        remembered.remove(presetId)
    }

    val preservedEffects = host.effects.filter { it.creatorFilterPresetIdV36() == null }
    val markerEffects = remembered.entries.map { (id, amount) ->
        NodeEffect(name = creatorFilterMarkerNameV36(id), amount = amount.coerceIn(0f, 1f))
    }
    val updatedHost = host.copy(effects = preservedEffects + markerEffects)
    graph = graph.copy(
        nodes = graph.nodes.map { if (it.id == host.id) updatedHost else it },
        revision = graph.revision + 1L,
    )
    vm.commitProjectV19(
        label = "filter-marker-v41",
        project = state.project.withUpdatedClipV36(liveClip.copy(nodeGraph = graph)),
        status = if (intensity > .001f) {
            "${preset?.name ?: presetId} · ${(intensity * 100f).toInt()}% · Node ${host.label}"
        } else {
            "${preset?.name ?: presetId} removed from Node ${host.label}"
        },
        coalesce = coalesce,
    )
}

private fun clearFilterGroupV36(
    vm: EditorViewModel,
    clipId: String,
    group: CreatorFilterGroupV36,
) {
    val state = vm.state.value
    val liveClip = state.project.clip(clipId) ?: return
    var graph = liveClip.nodeGraph
    graph.nodes.filter { it.isLegacyCreatorFilterNodeV36() }.map { it.id }.forEach { id ->
        graph = graph.deleteEditableNodeV4(id)
    }
    val migratedClip = liveClip.copy(nodeGraph = graph)
    val host = migratedClip.selectedCreatorFilterHostV41() ?: return
    val updatedHost = host.copy(
        effects = host.effects.filter { effect ->
            val presetId = effect.creatorFilterPresetIdV36() ?: return@filter true
            creatorFilterPresetV36(presetId)?.group != group
        },
    )
    graph = graph.copy(
        nodes = graph.nodes.map { if (it.id == host.id) updatedHost else it },
        revision = graph.revision + 1L,
    )
    vm.commitProjectV19(
        label = "filter-none-v98",
        project = state.project.withUpdatedClipV36(liveClip.copy(nodeGraph = graph)),
        status = if (group == CreatorFilterGroupV36.LOOKS) "Looks cleared" else "Beauty cleared",
    )
}

private fun clearFilterMarkersV36(vm: EditorViewModel, clipId: String) {
    val state = vm.state.value
    val liveClip = state.project.clip(clipId) ?: return
    var graph = liveClip.nodeGraph
    graph.nodes.filter { it.isLegacyCreatorFilterNodeV36() }.map { it.id }.forEach { id ->
        graph = graph.deleteEditableNodeV4(id)
    }
    graph = graph.copy(
        nodes = graph.nodes.map { node ->
            if (node.kind != NodeKind.SERIAL && node.kind != NodeKind.PARALLEL) node
            else node.copy(effects = node.effects.filter { it.creatorFilterPresetIdV36() == null })
        },
        revision = graph.revision + 1L,
    )
    vm.commitProjectV19(
        label = "filter-marker-v41-clear",
        project = state.project.withUpdatedClipV36(liveClip.copy(nodeGraph = graph)),
        status = "All filters removed",
    )
}

private fun TimelineProject.withUpdatedClipV36(updated: TimelineClip): TimelineProject = copy(
    tracks = tracks.map { track ->
        if (track.clips.none { it.id == updated.id }) track
        else track.copy(clips = track.clips.map { if (it.id == updated.id) updated else it })
    },
)