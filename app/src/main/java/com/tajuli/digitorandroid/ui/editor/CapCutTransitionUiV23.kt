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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
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

fun EditorViewModelV4.setTransitionForCutV23(
    incomingClipId: String,
    style: TransitionStyleV22,
    durationUs: Long,
) {
    val snapshot = state.value
    val incoming = snapshot.project.clip(incomingClipId) ?: return
    val track = snapshot.project.trackContaining(incomingClipId)?.takeIf { it.kind == TrackKind.VIDEO } ?: return
    val ordered = track.sortedClips()
    val index = ordered.indexOfFirst { it.id == incomingClipId }
    val outgoing = ordered.getOrNull(index - 1)
    if (style != TransitionStyleV22.NONE && (outgoing == null || outgoing.timelineEndUs != incoming.timelineStartUs)) {
        setEditorStatusV19("Transition needs two clips touching at the cut")
        return
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
    )
    if (nextTransition == incoming.transition) return

    val nextProject = snapshot.project.copy(
        tracks = snapshot.project.tracks.map { candidate ->
            if (candidate.id != track.id) candidate
            else candidate.copy(
                clips = candidate.clips.map { clip ->
                    if (clip.id == incoming.id) clip.copy(transition = nextTransition) else clip
                },
            )
        },
    )
    commitProjectV19(
        label = "transition-v23-cut",
        project = nextProject,
        status = if (style == TransitionStyleV22.NONE) {
            "Transition removed"
        } else {
            "${style.label} · ${safeDuration / 1000L} ms"
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

private enum class CapCutTransitionCategoryV23(val label: String) {
    BASIC("Basic"),
    CAMERA("Camera"),
    EFFECT("Effect"),
}

private fun stylesForCategoryV23(category: CapCutTransitionCategoryV23): List<TransitionStyleV22> = when (category) {
    CapCutTransitionCategoryV23.BASIC -> listOf(
        TransitionStyleV22.CROSS_DISSOLVE,
        TransitionStyleV22.FADE,
        TransitionStyleV22.SMOOTH_CUT,
        TransitionStyleV22.DIP_TO_BLACK,
        TransitionStyleV22.DIP_TO_WHITE,
    )
    CapCutTransitionCategoryV23.CAMERA -> listOf(
        TransitionStyleV22.PUSH_LEFT,
        TransitionStyleV22.PUSH_RIGHT,
        TransitionStyleV22.PUSH_UP,
        TransitionStyleV22.PUSH_DOWN,
        TransitionStyleV22.SLIDE,
        TransitionStyleV22.ZOOM_IN,
        TransitionStyleV22.ZOOM_OUT,
        TransitionStyleV22.WHIP,
        TransitionStyleV22.SPIN,
    )
    CapCutTransitionCategoryV23.EFFECT -> listOf(
        TransitionStyleV22.BLUR,
        TransitionStyleV22.FLASH,
        TransitionStyleV22.LIGHT_LEAK,
        TransitionStyleV22.MASK_WIPE,
        TransitionStyleV22.CIRCLE_WIPE,
        TransitionStyleV22.SPLIT,
    )
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
    val style = incoming.transition.resolvedStyleV22
    val durationUs = incoming.transition.resolvedDurationUsV22
        .takeIf { it > 0L }
        ?.coerceAtMost(maxDurationUs)
        ?: minOf(CCT23_DEFAULT_DURATION_US, maxDurationUs)
    var category by remember(targetClipId) { mutableStateOf(CapCutTransitionCategoryV23.BASIC) }

    fun previewAt(duration: Long) {
        val midpoint = (target.cutUs + duration.coerceAtLeast(100_000L) / 2L)
            .coerceAtMost(project.durationUs.coerceAtLeast(0L))
        onSeek(midpoint)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = CCT23Panel,
        dragHandle = null,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
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
                TextButton(onClick = onDismiss) { Text("Done", color = CCT23Accent) }
            }

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CapCutTransitionCategoryV23.entries.forEach { item ->
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
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                stylesForCategoryV23(category).forEach { item ->
                    FilledTonalButton(
                        onClick = {
                            vm.setTransitionForCutV23(incoming.id, item, durationUs)
                            previewAt(durationUs)
                        },
                        modifier = Modifier.height(42.dp),
                        shape = RoundedCornerShape(7.dp),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (style == item) "◆" else "◇", fontSize = 11.sp, color = if (style == item) CCT23Accent else Color.White)
                            Text(item.label, fontSize = 7.sp, color = Color.White.copy(alpha = .78f))
                        }
                    }
                }
            }

            if (style != TransitionStyleV22.NONE) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Duration", color = CCT23Muted, fontSize = 9.sp, modifier = Modifier.width(62.dp))
                    Slider(
                        value = (durationUs.toFloat() / maxDurationUs.toFloat()).coerceIn(0f, 1f),
                        onValueChange = { amount ->
                            val value = (amount * maxDurationUs).toLong().coerceAtLeast(100_000L)
                            vm.setTransitionForCutV23(incoming.id, style, value)
                            previewAt(value)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        String.format("%.1fs", durationUs / US_PER_SECOND.toFloat()),
                        color = Color.White.copy(alpha = .75f),
                        fontSize = 9.sp,
                        modifier = Modifier.width(42.dp),
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Tap another transition to replace it",
                        color = CCT23Muted,
                        fontSize = 8.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        vm.setTransitionForCutV23(incoming.id, TransitionStyleV22.NONE, 0L)
                        onSeek(target.cutUs)
                    }) {
                        Text("Remove", color = Color(0xFFFF7474))
                    }
                }
            } else {
                Spacer(Modifier.height(3.dp))
                Text(
                    "Choose a transition. The playhead jumps to its preview point automatically.",
                    color = CCT23Muted,
                    fontSize = 8.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
