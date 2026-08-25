package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.model.ColorNode
import com.tajuli.digitorandroid.editor.model.NodeAnimationDomain
import com.tajuli.digitorandroid.editor.model.PreviewTransformClock
import com.tajuli.digitorandroid.editor.model.TimelineClip
import kotlin.math.abs
import kotlin.math.roundToLong

private val KFPanel = Color(0xFF101014)
private val KFRaised = Color(0xFF18181E)
private val KFTrack = Color(0xFF32323A)
private val KFMuted = Color(0xFF9696A0)
private val KFAccent = Color(0xFF30E0C3)
private val KFDanger = Color(0xFFFF7474)

@Composable
private fun nodeKeyframeSourceTimeUs(clip: TimelineClip, frameRate: Int): Long {
    val clock by PreviewTransformClock.flow.collectAsState()
    val rawLocal = if (clock.clipId == clip.id) clock.localUs else 0L
    val frameUs = (1_000_000.0 / frameRate.coerceAtLeast(1)).roundToLong().coerceAtLeast(1L)
    val snappedLocal = ((rawLocal.toDouble() / frameUs).roundToLong() * frameUs)
        .coerceIn(0L, clip.durationUs)
    return (clip.sourceInUs + snappedLocal).coerceIn(clip.sourceInUs, clip.sourceOutUs)
}

@Composable
fun NodeDomainKeyframeBarV5(
    clip: TimelineClip,
    node: ColorNode,
    domain: NodeAnimationDomain,
    frameRate: Int,
    modifier: Modifier = Modifier,
) {
    var localRevision by remember(clip.id, node.id, domain) { mutableLongStateOf(0L) }
    var selectedSourceUs by remember(clip.id, node.id, domain) { mutableStateOf<Long?>(null) }
    val sourceUs = nodeKeyframeSourceTimeUs(clip, frameRate)
    val track = clip.nodeAnimations.track(node.id, domain)
    val keys = track.keyframes.sortedBy { it.sourceTimeUs }
    val active = track.hasKeyframeAt(sourceUs)
    @Suppress("UNUSED_VARIABLE") val revisionRead = localRevision + clip.nodeAnimations.revision

    if (active && selectedSourceUs != sourceUs) selectedSourceUs = sourceUs
    if (selectedSourceUs != null && keys.none { it.sourceTimeUs == selectedSourceUs }) selectedSourceUs = null

    Column(modifier.fillMaxWidth().background(KFPanel).padding(horizontal = 8.dp, vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(domainTitle(domain), fontSize = 8.sp, color = Color.White.copy(alpha = .82f))
            Spacer(Modifier.weight(1f))
            if (keys.isNotEmpty()) {
                Text("${keys.size} key${if (keys.size == 1) "" else "s"}", fontSize = 7.sp, color = KFMuted)
                Spacer(Modifier.width(7.dp))
            }
            Text(
                if (active) "◆ Keyframe" else "◇ Keyframe",
                fontSize = 8.sp,
                color = if (active) KFAccent else KFMuted,
                modifier = Modifier
                    .background(KFRaised, RoundedCornerShape(5.dp))
                    .clickable {
                        if (active) {
                            clip.nodeAnimations.toggle(node, domain, sourceUs)
                            selectedSourceUs = null
                        } else {
                            clip.nodeAnimations.toggle(node, domain, sourceUs)
                            // toggle() seeds the continuity value; overwrite the new key with the
                            // actual editor value so a user can edit, then press diamond to capture.
                            clip.nodeAnimations.upsertIfAnimated(node, domain, sourceUs)
                            selectedSourceUs = sourceUs
                        }
                        localRevision++
                    }
                    .padding(horizontal = 9.dp, vertical = 6.dp),
            )
        }

        if (keys.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            NodeKeyframeStripV5(
                clip = clip,
                keys = keys.map { it.sourceTimeUs },
                selectedSourceUs = selectedSourceUs,
                modifier = Modifier.fillMaxWidth().height(20.dp),
                onSelect = { selectedSourceUs = it },
            )
        }

        selectedSourceUs?.let { selected ->
            Spacer(Modifier.height(3.dp))
            Row(
                Modifier.fillMaxWidth()
                    .background(KFRaised, RoundedCornerShape(5.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Selected ◆ ${formatNodeKeyframeTime((selected - clip.sourceInUs).coerceAtLeast(0L))}",
                    fontSize = 7.sp,
                    color = KFAccent,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Delete keyframe",
                    fontSize = 8.sp,
                    color = KFDanger,
                    modifier = Modifier
                        .background(KFDanger.copy(alpha = .12f), RoundedCornerShape(5.dp))
                        .clickable {
                            clip.nodeAnimations.remove(node.id, domain, selected)
                            selectedSourceUs = null
                            localRevision++
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun NodeKeyframeStripV5(
    clip: TimelineClip,
    keys: List<Long>,
    selectedSourceUs: Long?,
    modifier: Modifier,
    onSelect: (Long) -> Unit,
) {
    Canvas(
        modifier.pointerInput(keys, clip.sourceInUs, clip.durationUs) {
            detectTapGestures { tap ->
                if (keys.isEmpty() || size.width <= 0) return@detectTapGestures
                val width = size.width.toFloat()
                val nearest = keys.minByOrNull { sourceUs ->
                    val local = (sourceUs - clip.sourceInUs).coerceIn(0L, clip.durationUs)
                    val x = local.toFloat() / clip.durationUs.coerceAtLeast(1L).toFloat() * width
                    abs(x - tap.x)
                } ?: return@detectTapGestures
                val local = (nearest - clip.sourceInUs).coerceIn(0L, clip.durationUs)
                val x = local.toFloat() / clip.durationUs.coerceAtLeast(1L).toFloat() * width
                if (abs(x - tap.x) <= maxOf(18f, width * .03f)) onSelect(nearest)
            }
        },
    ) {
        drawLine(KFTrack, Offset(0f, size.height * .5f), Offset(size.width, size.height * .5f), strokeWidth = 2f)
        keys.forEach { sourceUs ->
            val local = (sourceUs - clip.sourceInUs).coerceIn(0L, clip.durationUs)
            val x = local.toFloat() / clip.durationUs.coerceAtLeast(1L).toFloat() * size.width
            val selected = sourceUs == selectedSourceUs
            drawCircle(if (selected) Color.White else KFAccent, radius = if (selected) 5f else 3.2f, center = Offset(x, size.height * .5f))
        }
    }
}

private fun domainTitle(domain: NodeAnimationDomain): String = when (domain) {
    NodeAnimationDomain.CORRECTION -> "Correction animation"
    NodeAnimationDomain.COLOR -> "Color animation"
    NodeAnimationDomain.EFFECTS -> "Effects animation"
}

private fun formatNodeKeyframeTime(us: Long): String {
    val totalMs = us / 1000L
    val minutes = totalMs / 60_000L
    val seconds = (totalMs % 60_000L) / 1000L
    val millis = totalMs % 1000L
    return "%02d:%02d.%03d".format(minutes, seconds, millis)
}
