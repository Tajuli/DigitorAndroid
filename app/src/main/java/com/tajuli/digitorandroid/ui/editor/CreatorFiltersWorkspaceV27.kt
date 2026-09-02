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
import com.tajuli.digitorandroid.editor.model.appliedCreatorFiltersV36
import com.tajuli.digitorandroid.editor.model.creatorFilterHostNodeV36
import com.tajuli.digitorandroid.editor.model.creatorFilterMarkerNameV36
import com.tajuli.digitorandroid.editor.model.creatorFilterPresetIdV36
import com.tajuli.digitorandroid.editor.model.creatorFilterPresetV36
import com.tajuli.digitorandroid.editor.model.isLegacyCreatorFilterNodeV36
import com.tajuli.digitorandroid.editor.processing.BeautyFaceAnalyzerV28
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Filter27Panel = Color(0xFF0B0B0F)
private val Filter27Raised = Color(0xFF17171C)
private val Filter27Divider = Color(0xFF292930)
private val Filter27Muted = Color(0xFF909098)
private val Filter27Accent = Color(0xFF30E0C3)

private data class FilterSwatchV36(val a: Color, val b: Color)

private fun swatchV36(id: String): FilterSwatchV36 = when (id) {
    "fresh_lime" -> FilterSwatchV36(Color(0xFF84DDA4), Color(0xFFEAF8B4))
    "vivid_verse" -> FilterSwatchV36(Color(0xFF865DFF), Color(0xFFFF7E70))
    "soft_light" -> FilterSwatchV36(Color(0xFFF8D9D3), Color(0xFFF5F0E8))
    "vhs" -> FilterSwatchV36(Color(0xFF6B7AA8), Color(0xFFD88793))
    "teal_orange" -> FilterSwatchV36(Color(0xFF238B91), Color(0xFFE79B63))
    "warm_film" -> FilterSwatchV36(Color(0xFF8B694F), Color(0xFFE6B47E))
    "golden_hour" -> FilterSwatchV36(Color(0xFFD78845), Color(0xFFFFD982))
    "moody_cinema" -> FilterSwatchV36(Color(0xFF243541), Color(0xFF8A6B59))
    "natural_portrait" -> FilterSwatchV36(Color(0xFFC68E78), Color(0xFFF0C9B4))
    "fade_film" -> FilterSwatchV36(Color(0xFF77736B), Color(0xFFC9B99B))
    "skin_bright" -> FilterSwatchV36(Color(0xFFD6A98F), Color(0xFFFFE3CE))
    "skin_smooth" -> FilterSwatchV36(Color(0xFFC89582), Color(0xFFF1C6B7))
    "pink_lips" -> FilterSwatchV36(Color(0xFF9B4E61), Color(0xFFF08FA8))
    "hair_brows" -> FilterSwatchV36(Color(0xFF111115), Color(0xFF4E4240))
    "eye_pop" -> FilterSwatchV36(Color(0xFF394D65), Color(0xFFD9E7F2))
    else -> FilterSwatchV36(Color(0xFFB66F72), Color(0xFFF3C7A9))
}

/**
 * Filters V38 keeps the public V27 function name so the editor screen and saved UI contract stay
 * source-compatible. Filters remain lightweight marker effects on one existing editable node.
 *
 * LOOKS now have CapCut-style single-selection semantics: tapping a look removes other LOOK markers
 * while leaving BEAUTY markers independent. This also fixes a V37 bug where re-tapping an already
 * applied look did not move its marker and `activeCreatorLookV37()` could keep rendering an older
 * look. BEAUTY remains stackable.
 */
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
    var group by remember { mutableStateOf(CreatorFilterGroupV36.LOOKS) }
    var selectedPresetId by remember(clip.id) { mutableStateOf<String?>(null) }

    val applied = clip.appliedCreatorFiltersV36()
    val selectedPreset = CREATOR_FILTERS_V36.firstOrNull { it.id == selectedPresetId }
    val selectedIntensity = selectedPresetId?.let { applied[it] } ?: 0f
    val visiblePresets = CREATOR_FILTERS_V36.filter { it.group == group }

    fun refineBeautyInBackground(preset: CreatorFilterPresetV36) {
        val needsHairMask = preset.beautyWeights.containsKey(BEAUTY_HAIR_BROW_DARK_V28)
        // V38 Skin Bright does not need a semantic skin mask. Face geometry is only an automatic
        // eyedropper source for the global color qualifier. Skin Smooth still benefits from masks.
        val needsSkinMask = preset.beautyWeights.containsKey(BEAUTY_SKIN_SMOOTH_V28)
        val needsSkinColorSample = preset.beautyWeights.containsKey(BEAUTY_SKIN_BRIGHT_V28)
        val analysisLabel = when {
            needsHairMask && needsSkinMask -> "skin + face + hair"
            needsHairMask -> "face + hair"
            needsSkinMask -> "skin + face"
            needsSkinColorSample -> "face color sample"
            else -> "face"
        }
        vm.setEditorStatusV19("${preset.name} active instantly · refining $analysisLabel…")
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
            if (track.samples.any { it.geometry != null }) {
                vm.setEditorStatusV19("${preset.name} ready · refined $analysisLabel")
            } else {
                vm.setEditorStatusV19("${preset.name} active · no clear face found; global color fallback remains active")
            }
        }
    }

    fun applyPreset(preset: CreatorFilterPresetV36) {
        selectedPresetId = preset.id
        val wasApplied = preset.id in applied
        // LOOK taps always write the marker: V38 uses the write to enforce single-look selection and
        // make the tapped look the deterministic active look even if it already existed in the map.
        if (!wasApplied || preset.group == CreatorFilterGroupV36.LOOKS) {
            updateFilterMarkerV36(vm, clip.id, preset.id, preset.defaultIntensity, coalesce = false)
        }
        if (!wasApplied && preset.group == CreatorFilterGroupV36.BEAUTY) {
            refineBeautyInBackground(preset)
        }
    }

    Column(modifier.background(Filter27Panel)) {
        Row(
            Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Filters · ${clip.label}", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("${applied.size} active · instant", fontSize = 7.sp, color = Filter27Muted)
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
                val label = if (item == CreatorFilterGroupV36.LOOKS) "Looks" else "Beauty"
                Box(
                    Modifier.background(if (active) Filter27Accent.copy(alpha = .16f) else Filter27Raised, RoundedCornerShape(7.dp))
                        .clickable { group = item }
                        .padding(horizontal = 11.dp, vertical = 6.dp),
                ) {
                    Text(label, fontSize = 8.sp, color = if (active) Filter27Accent else Color.White.copy(alpha = .75f))
                }
            }
            if (group == CreatorFilterGroupV36.BEAUTY) {
                Text("Skin Bright = global color qualifier", fontSize = 7.sp, color = Filter27Muted)
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            visiblePresets.forEach { preset ->
                val swatch = swatchV36(preset.id)
                FilterCardV36(
                    name = preset.name,
                    description = preset.description,
                    applied = preset.id in applied,
                    selected = preset.id == selectedPresetId,
                    swatchA = swatch.a,
                    swatchB = swatch.b,
                    onClick = { applyPreset(preset) },
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
                enabled = selectedPreset != null && selectedPreset.id in applied,
                modifier = Modifier.fillMaxWidth().height(30.dp),
            )
            Text(
                if (group == CreatorFilterGroupV36.BEAUTY) {
                    "V38 Skin Bright uses face geometry only to auto-pick representative skin color. The brightness qualifier is then applied everywhere that color matches, with soft chroma/luma falloff and no face ellipse or segmentation boundary."
                } else {
                    "Looks are single-select full-frame transforms. Cinematic/Moody Cinema also receives a small global color-qualifier relight after the LUT; no spatial face mask is used."
                },
                fontSize = 7.sp,
                color = Filter27Muted,
            )
        }
    }
}

@Composable
private fun FilterCardV36(
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
            if (applied) Text("✓", fontSize = 10.sp, color = Color.White, modifier = Modifier.padding(4.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(name, fontSize = 8.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, textAlign = TextAlign.Center)
        Text(description, fontSize = 6.sp, color = Filter27Muted, maxLines = 1, textAlign = TextAlign.Center)
    }
}

/**
 * Writes/updates one filter marker on a stable existing node. Legacy V28 filter nodes are migrated
 * lazily on first edit so projects made with PR #47 keep their visible filter stack.
 *
 * V38 LOOKS are exclusive. When a LOOK is selected, every other LOOK marker is removed before the
 * selected marker is appended. BEAUTY markers remain stackable and untouched.
 */
private fun updateFilterMarkerV36(
    vm: EditorViewModelV4,
    clipId: String,
    presetId: String,
    intensity: Float,
    coalesce: Boolean,
) {
    val state = vm.state.value
    val liveClip = state.project.clip(clipId) ?: return
    val preset = creatorFilterPresetV36(presetId)
    val remembered = liveClip.appliedCreatorFiltersV36().toMutableMap()

    if (intensity > .001f) {
        if (preset?.group == CreatorFilterGroupV36.LOOKS) {
            val oldLookIds = remembered.keys.filter { id ->
                creatorFilterPresetV36(id)?.group == CreatorFilterGroupV36.LOOKS
            }.toList()
            oldLookIds.forEach(remembered::remove)
        }
        // Remove + append makes this tap deterministic even for an already-existing legacy marker.
        remembered.remove(presetId)
        remembered[presetId] = intensity.coerceIn(0f, 1f)
    } else {
        remembered.remove(presetId)
    }

    var graph = liveClip.nodeGraph
    graph.nodes.filter { it.isLegacyCreatorFilterNodeV36() }.map { it.id }.forEach { id ->
        graph = graph.deleteEditableNodeV4(id)
    }

    val migratedClip = liveClip.copy(nodeGraph = graph)
    val host = migratedClip.creatorFilterHostNodeV36()
    if (host == null) {
        vm.setEditorStatusV19("Filter unavailable · no editable color node")
        return
    }

    val preservedEffects = host.effects.filter { effect -> effect.creatorFilterPresetIdV36() == null }
    val markerEffects = remembered.entries.map { (id, amount) ->
        NodeEffect(name = creatorFilterMarkerNameV36(id), amount = amount.coerceIn(0f, 1f))
    }
    val updatedHost = host.copy(effects = preservedEffects + markerEffects)
    graph = graph.copy(
        nodes = graph.nodes.map { node -> if (node.id == host.id) updatedHost else node },
        revision = graph.revision + 1L,
    )

    vm.commitProjectV19(
        label = "filter-marker-v38",
        project = state.project.withUpdatedClipV36(liveClip.copy(nodeGraph = graph)),
        status = if (intensity > .001f) {
            val suffix = if (preset?.group == CreatorFilterGroupV36.LOOKS) " · single global look" else " · instant"
            "${preset?.name ?: presetId} · ${(intensity * 100f).toInt()}%$suffix"
        } else {
            "${preset?.name ?: presetId} removed"
        },
        coalesce = coalesce,
    )
}

private fun clearFilterMarkersV36(vm: EditorViewModelV4, clipId: String) {
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
        label = "filter-marker-v38-clear",
        project = state.project.withUpdatedClipV36(liveClip.copy(nodeGraph = graph)),
        status = "All filters removed",
    )
}

private fun TimelineProject.withUpdatedClipV36(updated: TimelineClip): TimelineProject = copy(
    tracks = tracks.map { track ->
        if (track.clips.none { it.id == updated.id }) track
        else track.copy(clips = track.clips.map { clip -> if (clip.id == updated.id) updated else clip })
    },
)
