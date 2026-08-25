package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.model.AnimatedFloat
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.TransformProperty
import kotlin.math.roundToInt

private val X5Panel = Color(0xFF0B0B0F)
private val X5Raised = Color(0xFF141419)
private val X5Divider = Color(0xFF292930)
private val X5Muted = Color(0xFF909098)
private val X5Accent = Color(0xFF30E0C3)

private enum class EditPageV5 { TIMELINE, TRANSFORM }

/**
 * Edit keeps timeline and geometry under one subsystem. Transform is deliberately not a top-level
 * workspace: users select a clip in Timeline, then open Transform without leaving Edit.
 */
@Composable
fun EditWorkspaceV5(
    project: TimelineProject,
    selectedTrackId: String?,
    selectedClipIds: Set<String>,
    selectedClip: TimelineClip?,
    cursorUs: Long,
    vm: EditorViewModelV4,
    onSeek: (Long) -> Unit,
    onSelectTrack: (String) -> Unit,
    onSelectClip: (String) -> Unit,
    onMoveClip: (String, String, Long) -> Unit,
    onMoveClipToTrack: (String, String) -> Unit,
    onAddVideoTrack: () -> Unit,
    onAddAudioTrack: () -> Unit,
    onSplit: () -> Unit,
    onDelete: () -> Unit,
    onUnlink: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var page by remember { mutableStateOf(EditPageV5.TIMELINE) }
    val canTransform = selectedClip != null && project.trackContaining(selectedClip.id)?.kind == TrackKind.VIDEO

    LaunchedEffect(canTransform) {
        if (!canTransform && page == EditPageV5.TRANSFORM) page = EditPageV5.TIMELINE
    }

    Column(modifier.background(X5Panel)) {
        Row(
            Modifier.fillMaxWidth().height(34.dp).background(Color(0xFF101014)).padding(horizontal = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            TextButton(onClick = { page = EditPageV5.TIMELINE }) {
                Text("Timeline", fontSize = 8.sp, color = if (page == EditPageV5.TIMELINE) X5Accent else X5Muted)
            }
            TextButton(onClick = { page = EditPageV5.TRANSFORM }, enabled = canTransform) {
                Text("Transform", fontSize = 8.sp, color = if (page == EditPageV5.TRANSFORM) X5Accent else X5Muted)
            }
            Spacer(Modifier.weight(1f))
            if (page == EditPageV5.TRANSFORM) {
                Text("◆ keyframe at playhead", fontSize = 7.sp, color = X5Muted)
            }
        }
        HorizontalDivider(color = X5Divider)

        when (page) {
            EditPageV5.TIMELINE -> TimelineEditorV4(
                project = project,
                selectedTrackId = selectedTrackId,
                selectedClipIds = selectedClipIds,
                cursorUs = cursorUs,
                onSeek = onSeek,
                onSelectTrack = onSelectTrack,
                onSelectClip = onSelectClip,
                onMoveClip = onMoveClip,
                onMoveClipToTrack = onMoveClipToTrack,
                onAddVideoTrack = onAddVideoTrack,
                onAddAudioTrack = onAddAudioTrack,
                onSplit = onSplit,
                onDelete = onDelete,
                onUnlink = onUnlink,
                onImport = onImport,
                modifier = Modifier.fillMaxSize(),
            )
            EditPageV5.TRANSFORM -> {
                if (selectedClip != null && canTransform) {
                    TransformWorkspaceV5(selectedClip, cursorUs, project.frameRate, vm, onSeek, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun TransformWorkspaceV5(
    clip: TimelineClip,
    cursorUs: Long,
    frameRate: Int,
    vm: EditorViewModelV4,
    onSeek: (Long) -> Unit,
    modifier: Modifier,
) {
    val rawLocalUs = (cursorUs - clip.timelineStartUs).coerceIn(0L, clip.durationUs)
    val keyframeLocalUs = vm.transformKeyframeLocalUs(clip, cursorUs)
    val duration = clip.durationUs.coerceAtLeast(1L)
    val fraction = (rawLocalUs.toDouble() / duration.toDouble()).toFloat().coerceIn(0f, 1f)
    val evaluated = clip.transform.evaluate(rawLocalUs)

    Column(modifier.background(X5Panel).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Transform · ${clip.label}", fontSize = 9.sp, color = Color.White)
                Text(
                    "${formatTransformTime(rawLocalUs)} / ${formatTransformTime(duration)} · ${frameRate} fps",
                    fontSize = 7.sp,
                    color = X5Muted,
                )
            }
            TextButton(onClick = { vm.resetTransformAt(cursorUs) }) { Text("Reset", fontSize = 7.sp) }
            TextButton(onClick = { vm.toggleAllTransformKeyframes(cursorUs) }) {
                Text("◆ All", fontSize = 7.sp, color = X5Accent)
            }
        }

        Slider(
            value = fraction,
            onValueChange = { amount ->
                onSeek(clip.timelineStartUs + (duration * amount.coerceIn(0f, 1f)).toLong())
            },
            modifier = Modifier.fillMaxWidth().height(24.dp),
        )

        TransformRowV5(
            label = "Position X",
            property = TransformProperty.POSITION_X,
            value = evaluated.positionX,
            range = -1f..1f,
            display = { "${(it * 100f).roundToInt()}%" },
            channel = clip.transform.positionX,
            durationUs = duration,
            keyframeLocalUs = keyframeLocalUs,
            onValue = { vm.setTransformProperty(TransformProperty.POSITION_X, it, cursorUs) },
            onKeyframe = { vm.toggleTransformKeyframe(TransformProperty.POSITION_X, cursorUs) },
        )
        TransformRowV5(
            label = "Position Y",
            property = TransformProperty.POSITION_Y,
            value = evaluated.positionY,
            range = -1f..1f,
            display = { "${(it * 100f).roundToInt()}%" },
            channel = clip.transform.positionY,
            durationUs = duration,
            keyframeLocalUs = keyframeLocalUs,
            onValue = { vm.setTransformProperty(TransformProperty.POSITION_Y, it, cursorUs) },
            onKeyframe = { vm.toggleTransformKeyframe(TransformProperty.POSITION_Y, cursorUs) },
        )
        TransformRowV5(
            label = "Scale",
            property = TransformProperty.SCALE,
            value = evaluated.scale,
            range = .1f..4f,
            display = { "${(it * 100f).roundToInt()}%" },
            channel = clip.transform.scale,
            durationUs = duration,
            keyframeLocalUs = keyframeLocalUs,
            onValue = { vm.setTransformProperty(TransformProperty.SCALE, it, cursorUs) },
            onKeyframe = { vm.toggleTransformKeyframe(TransformProperty.SCALE, cursorUs) },
        )
        TransformRowV5(
            label = "Rotation",
            property = TransformProperty.ROTATION,
            value = evaluated.rotationDegrees.coerceIn(-180f, 180f),
            range = -180f..180f,
            display = { "${it.roundToInt()}°" },
            channel = clip.transform.rotationDegrees,
            durationUs = duration,
            keyframeLocalUs = keyframeLocalUs,
            onValue = { vm.setTransformProperty(TransformProperty.ROTATION, it, cursorUs) },
            onKeyframe = { vm.toggleTransformKeyframe(TransformProperty.ROTATION, cursorUs) },
        )
    }
}

@Composable
private fun TransformRowV5(
    label: String,
    property: TransformProperty,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String,
    channel: AnimatedFloat,
    durationUs: Long,
    keyframeLocalUs: Long,
    onValue: (Float) -> Unit,
    onKeyframe: () -> Unit,
) {
    val activeKeyframe = channel.hasKeyframeAt(keyframeLocalUs)
    Column(Modifier.fillMaxWidth().background(X5Raised).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.width(70.dp), fontSize = 7.sp, color = Color.White.copy(alpha = .76f))
            Slider(
                value = value.coerceIn(range.start, range.endInclusive),
                onValueChange = onValue,
                valueRange = range,
                modifier = Modifier.weight(1f).height(24.dp),
            )
            Text(display(value), Modifier.width(45.dp), fontSize = 7.sp, color = X5Muted)
            TextButton(onClick = onKeyframe, modifier = Modifier.width(34.dp)) {
                Text(if (activeKeyframe) "◆" else "◇", fontSize = 14.sp, color = if (activeKeyframe) X5Accent else X5Muted)
            }
        }
        if (channel.keyframes.isNotEmpty()) {
            KeyframeStripV5(channel, durationUs, Modifier.fillMaxWidth().height(5.dp))
        }
    }
    Spacer(Modifier.height(3.dp))
}

@Composable
private fun KeyframeStripV5(channel: AnimatedFloat, durationUs: Long, modifier: Modifier) {
    Canvas(modifier) {
        drawLine(X5Divider, start = androidx.compose.ui.geometry.Offset(0f, size.height * .5f), end = androidx.compose.ui.geometry.Offset(size.width, size.height * .5f), strokeWidth = 1f)
        channel.keyframes.forEach { keyframe ->
            val x = (keyframe.timeUs.toDouble() / durationUs.coerceAtLeast(1L).toDouble()).toFloat()
                .coerceIn(0f, 1f) * size.width
            drawCircle(X5Accent, radius = 2.2f, center = androidx.compose.ui.geometry.Offset(x, size.height * .5f))
        }
    }
}

private fun formatTransformTime(us: Long): String {
    val totalMs = us.coerceAtLeast(0L) / 1000L
    val minutes = totalMs / 60_000L
    val seconds = (totalMs % 60_000L) / 1000L
    val millis = totalMs % 1000L
    return "%02d:%02d.%03d".format(minutes, seconds, millis)
}
