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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.TransitionStyleV22
import com.tajuli.digitorandroid.editor.model.US_PER_SECOND

private val CCT23Accent = Color(0xFF30E0C3)
private val CCT23Muted = Color(0xFF909098)
private val CCT23Panel = Color(0xFF101014)
private const val CCT23_DEFAULT_DURATION_US = 700_000L

data class TransitionCutTargetV23(
    val trackId: String,
    val outgoing: TimelineClip,
    val incoming: TimelineClip,
) {
    val cutUs: Long get() = incoming.timelineStartUs
}

internal fun TimelineTrack.capCutTransitionCutsV23(): List<TransitionCutTargetV23> {
    if (kind != TrackKind.VIDEO || muted) return emptyList()
    return sortedClips().zipWithNext().mapNotNull { (outgoing, incoming) ->
        if (outgoing.timelineEndUs != incoming.timelineStartUs) return@mapNotNull null
        TransitionCutTargetV23(id, outgoing, incoming)
    }
}

private fun TransitionCutTargetV23.maxDurationUsV23(): Long = minOf(
    incoming.durationUs / 2L,
    outgoing.durationUs,
    3_000_000L,
).coerceAtLeast(100_000L)

/** Pure cut mutation used by the UI and tests so a transition tap always changes project metadata. */
internal fun TimelineProject.withTransitionForCutV23(
    incomingClipId: String,
    style: TransitionStyleV22,
    durationUs: Long,
    presetIdV24: String? = null,
): TimelineProject? {
    val incoming = clip(incomingClipId) ?: return null
    val track = trackContaining(incomingClipId)?.takeIf { it.kind == TrackKind.VIDEO } ?: return null
    val ordered = track.sortedClips()
    val index = ordered.indexOfFirst { it.id == incomingClipId }
    if (index < 0) return null
    val outgoing = ordered.getOrNull(index - 1)
    if (style != TransitionStyleV22.NONE && (outgoing == null || outgoing.timelineEndUs != incoming.timelineStartUs)) {
        return null
    }

    val safeDuration = if (style == TransitionStyleV22.NONE) {
        0L
    } else {
        minOf(
            durationUs.coerceAtLeast(100_000L),
            incoming.durationUs / 2L,
            outgoing?.durationUs ?: incoming.durationUs,
            3_000_000L,
        ).coerceAtLeast(1L)
    }
    val nextTransition = incoming.transition.copy(
        styleV22 = style.takeUnless { it == TransitionStyleV22.NONE },
        durationUsV22 = safeDuration,
        presetIdV24 = presetIdV24.takeUnless { style == TransitionStyleV22.NONE },
    )
    if (nextTransition == incoming.transition) return this

    return copy(
        tracks = tracks.map { candidate ->
            if (candidate.id != track.id) candidate
            else candidate.copy(
                clips = candidate.clips.map { item ->
                    if (item.id == incoming.id) item.copy(transition = nextTransition) else item
                },
            )
        },
    )
}

fun EditorViewModelV4.setTransitionForCutV23(
    incomingClipId: String,
    style: TransitionStyleV22,
    durationUs: Long,
    presetIdV24: String? = null,
    displayLabelV24: String? = null,
) {
    val activeVm = ActiveEditorVmRegistryV14.current()
    if (activeVm != null && activeVm !== this) {
        activeVm.setTransitionForCutV23(incomingClipId, style, durationUs, presetIdV24, displayLabelV24)
        return
    }

    val snapshot = state.value
    val nextProject = snapshot.project.withTransitionForCutV23(incomingClipId, style, durationUs, presetIdV24)
    if (nextProject == null) {
        setEditorStatusV19("Transition needs two clips touching at the cut")
        return
    }
    val updatedClip = nextProject.clip(incomingClipId) ?: return
    val safeDuration = updatedClip.transition.resolvedDurationUsV22
    val label = displayLabelV24 ?: style.label
    if (nextProject == snapshot.project) {
        setEditorStatusV19(
            if (style == TransitionStyleV22.NONE) "Transition removed" else "$label · ${safeDuration / 1000L} ms",
        )
        return
    }
    commitProjectV19(
        label = "transition-v24-cut",
        project = nextProject,
        status = if (style == TransitionStyleV22.NONE) {
            "Transition removed"
        } else {
            "$label · ${safeDuration / 1000L} ms"
        },
        coalesce = true,
    )
}

@Composable
internal fun CapCutTransitionCutButtonV23(
    target: TransitionCutTargetV23,
    pps: Float,
    onClick: () -> Unit,
) {
    val density = LocalDensity.current
    val ppsDp = with(density) { pps.toDp().value }
    val x = (target.cutUs / US_PER_SECOND.toFloat() * ppsDp).dp
    val active = target.incoming.transition.resolvedStyleV22 != TransitionStyleV22.NONE

    Box(
        Modifier
            .offset(x = x - 10.dp, y = 8.dp)
            .size(20.dp)
            .background(
                if (active) CCT23Accent else Color(0xFFEEEEF2),
                RoundedCornerShape(5.dp),
            )
            .border(
                1.dp,
                if (active) Color.White.copy(alpha = .65f) else Color.Black.copy(alpha = .22f),
                RoundedCornerShape(5.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (active) "◆" else "◇",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = if (active) Color.Black else Color(0xFF222228),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CapCutTransitionSheetV23(
    project: TimelineProject,
    targetClipId: String,
    vm: EditorViewModelV4,
    onSeek: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val incoming = project.clip(targetClipId)
    val track = incoming?.let { project.trackContaining(it.id) }
    val ordered = track?.sortedClips().orEmpty()
    val index = incoming?.let { clip -> ordered.indexOfFirst { it.id == clip.id } } ?: -1
    val outgoing = ordered.getOrNull(index - 1)
    if (incoming == null || track == null || track.kind != TrackKind.VIDEO || outgoing == null || outgoing.timelineEndUs != incoming.timelineStartUs) {
        onDismiss()
        return
    }
    val target = TransitionCutTargetV23(track.id, outgoing, incoming)
    val maxDurationUs = target.maxDurationUsV23()
    val projectStyle = incoming.transition.resolvedStyleV22
    val persistedPresetId = incoming.transition.presetIdV24
    val projectDurationUs = incoming.transition.resolvedDurationUsV22
        .takeIf { it > 0L }
        ?.coerceAtMost(maxDurationUs)
        ?: minOf(CCT23_DEFAULT_DURATION_US, maxDurationUs)
    var category by remember(targetClipId) { mutableStateOf(CapCutTransitionCategoryV24.BASIC) }
    var selectedStyle by remember(targetClipId) { mutableStateOf(projectStyle) }
    var selectedPresetId by remember(targetClipId) {
        mutableStateOf(persistedPresetId ?: defaultPresetForStyleV24(projectStyle)?.id)
    }
    var selectedDurationUs by remember(targetClipId) { mutableStateOf(projectDurationUs) }
    var appliedNotice by remember(targetClipId) { mutableStateOf(projectStyle != TransitionStyleV22.NONE) }

    LaunchedEffect(projectStyle, persistedPresetId, projectDurationUs, targetClipId) {
        selectedStyle = projectStyle
        selectedPresetId = persistedPresetId ?: defaultPresetForStyleV24(projectStyle)?.id
        selectedDurationUs = projectDurationUs
        appliedNotice = projectStyle != TransitionStyleV22.NONE
    }

    fun previewAt(duration: Long) {
        val midpoint = (target.cutUs + duration.coerceAtLeast(100_000L) / 2L)
            .coerceAtMost(project.durationUs.coerceAtLeast(0L))
        onSeek(midpoint)
    }

    fun applyPreset(preset: CapCutTransitionPresetV24, duration: Long) {
        val safeDuration = duration.coerceIn(100_000L, maxDurationUs)
        selectedStyle = preset.engineStyle
        selectedPresetId = preset.id
        selectedDurationUs = safeDuration
        appliedNotice = true
        vm.setTransitionForCutV23(
            incomingClipId = incoming.id,
            style = preset.engineStyle,
            durationUs = safeDuration,
            presetIdV24 = preset.id,
            displayLabelV24 = preset.label,
        )
        previewAt(safeDuration)
    }

    fun removeTransition() {
        selectedStyle = TransitionStyleV22.NONE
        selectedPresetId = null
        appliedNotice = false
        vm.setTransitionForCutV23(incoming.id, TransitionStyleV22.NONE, selectedDurationUs)
        onSeek(target.cutUs)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = CCT23Panel,
        dragHandle = null,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Transition", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${outgoing.label}  ◇  ${incoming.label}",
                        color = CCT23Muted,
                        fontSize = 9.sp,
                    )
                }
                if (appliedNotice) {
                    Text(
                        "✓ Applied",
                        color = CCT23Accent,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
                TextButton(onClick = onDismiss) { Text("Done", color = CCT23Accent) }
            }

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CapCutTransitionCategoryV24.entries.forEach { item ->
                    val active = category == item
                    Text(
                        item.label,
                        modifier = Modifier
                            .background(
                                if (active) CCT23Accent.copy(alpha = .18f) else Color.White.copy(alpha = .06f),
                                RoundedCornerShape(16.dp),
                            )
                            .clickable { category = item }
                            .padding(horizontal = 13.dp, vertical = 7.dp),
                        color = if (active) CCT23Accent else Color.White.copy(alpha = .68f),
                        fontSize = 9.sp,
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                presetsForCategoryV24(category).forEach { item ->
                    val active = selectedPresetId == item.id
                    Column(
                        Modifier
                            .width(112.dp)
                            .height(72.dp)
                            .background(
                                if (active) CCT23Accent.copy(alpha = .20f) else Color.White.copy(alpha = .07f),
                                RoundedCornerShape(10.dp),
                            )
                            .border(
                                1.dp,
                                if (active) CCT23Accent else Color.White.copy(alpha = .10f),
                                RoundedCornerShape(10.dp),
                            )
                            .clickable { applyPreset(item, selectedDurationUs) }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            if (active) "✓" else "◇",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (active) CCT23Accent else Color.White.copy(alpha = .78f),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            item.label,
                            fontSize = 9.sp,
                            lineHeight = 11.sp,
                            maxLines = 2,
                            textAlign = TextAlign.Center,
                            color = Color.White,
                        )
                    }
                }
            }

            if (selectedStyle != TransitionStyleV22.NONE) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Duration", color = CCT23Muted, fontSize = 9.sp, modifier = Modifier.width(62.dp))
                    Slider(
                        value = (selectedDurationUs.toFloat() / maxDurationUs.toFloat()).coerceIn(0f, 1f),
                        onValueChange = { amount ->
                            val value = (amount * maxDurationUs).toLong().coerceAtLeast(100_000L)
                            selectedDurationUs = value
                            val preset = presetForIdV24(selectedPresetId)
                            vm.setTransitionForCutV23(
                                incomingClipId = incoming.id,
                                style = selectedStyle,
                                durationUs = value,
                                presetIdV24 = selectedPresetId,
                                displayLabelV24 = preset?.label,
                            )
                            previewAt(value)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        String.format("%.1fs", selectedDurationUs / US_PER_SECOND.toFloat()),
                        color = Color.White.copy(alpha = .75f),
                        fontSize = 9.sp,
                        modifier = Modifier.width(42.dp),
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "50 presets · V24 motion + shader rendering",
                        color = CCT23Muted,
                        fontSize = 8.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = ::removeTransition) {
                        Text("Remove", color = Color(0xFFFF7474))
                    }
                }
            } else {
                Spacer(Modifier.height(3.dp))
                Text(
                    "Tap any transition card to apply it instantly.",
                    color = CCT23Muted,
                    fontSize = 8.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
