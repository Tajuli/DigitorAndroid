package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TransitionStyleV22
import com.tajuli.digitorandroid.editor.model.US_PER_SECOND

private val T22Muted = Color(0xFF909098)
private val T22Accent = Color(0xFF30E0C3)

@Composable
internal fun TransitionPickerV22(
    project: TimelineProject,
    clip: TimelineClip,
    vm: EditorViewModelV4,
) {
    val track = project.trackContaining(clip.id)
    val ordered = track?.sortedClips().orEmpty()
    val index = ordered.indexOfFirst { it.id == clip.id }
    val previous = ordered.getOrNull(index - 1)
    val hasCut = previous != null && previous.timelineEndUs == clip.timelineStartUs
    val maxDurationUs = if (hasCut) {
        minOf(clip.durationUs / 2L, previous!!.durationUs, 3_000_000L).coerceAtLeast(100_000L)
    } else {
        100_000L
    }
    val style = clip.transition.resolvedStyleV22
    val durationUs = clip.transition.resolvedDurationUsV22
        .takeIf { it > 0L }
        ?.coerceAtMost(maxDurationUs)
        ?: minOf(700_000L, maxDurationUs)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            if (hasCut) "Transition into this clip" else "Place/select a clip directly after another clip",
            fontSize = 7.sp,
            color = T22Muted,
        )

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            TransitionStyleV22.entries.filterNot { it == TransitionStyleV22.NONE }.forEach { item ->
                FilledTonalButton(
                    enabled = hasCut,
                    onClick = { vm.setSelectedTransitionV22(item, durationUs) },
                    modifier = Modifier.height(30.dp),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        if (style == item) "✓ ${item.label}" else item.label,
                        fontSize = 7.sp,
                        color = if (style == item) T22Accent else Color.White.copy(alpha = .72f),
                    )
                }
            }
        }

        if (style != TransitionStyleV22.NONE && hasCut) {
            Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Duration", fontSize = 7.sp, color = T22Muted, modifier = Modifier.width(58.dp))
                Slider(
                    value = (durationUs.toFloat() / maxDurationUs.toFloat()).coerceIn(0f, 1f),
                    onValueChange = { amount ->
                        val value = (amount * maxDurationUs).toLong().coerceAtLeast(100_000L)
                        vm.setSelectedTransitionV22(style, value)
                    },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    String.format("%.1fs", durationUs / US_PER_SECOND.toFloat()),
                    fontSize = 7.sp,
                    color = T22Muted,
                    modifier = Modifier.width(38.dp),
                )
            }
            TextButton(onClick = { vm.setSelectedTransitionV22(TransitionStyleV22.NONE, 0L) }) {
                Text("Remove transition", fontSize = 8.sp)
            }
        }
    }
}
