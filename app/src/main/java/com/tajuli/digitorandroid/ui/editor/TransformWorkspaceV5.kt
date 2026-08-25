package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.model.AnimatedFloat
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.TransformProperty
import kotlin.math.abs
import kotlin.math.roundToInt

private val X5Panel = Color(0xFF0B0B0F)
private val X5Raised = Color(0xFF141419)
private val X5Divider = Color(0xFF292930)
private val X5Muted = Color(0xFF909098)
private val X5Accent = Color(0xFF30E0C3)
private val X5Danger = Color(0xFFFF7474)

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
    val scrollState = rememberScrollState()
    val seekKeyframe: (Long) -> Unit = { localUs ->
        onSeek(clip.timelineStartUs + localUs.coerceIn(0L, duration))
    }

    Column(
        modifier
            .background(X5Panel)
            .verticalScroll(scrollState)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Transform · ${clip.label}", fontSize = 9.sp, color = Color.White)
                Text(
                    "${formatTransformTime(rawLocalUs)} / ${formatTransformTime(duration)} · ${frameRate} fps",
                    fontSize = 7.sp,
                    color = X5Muted,
                )
            }
            Text(
                "Reset",
                fontSize = 8.sp,
                color = X5Muted,
                modifier = Modifier
                    .background(X5Raised, RoundedCornerShape(5.dp))
                    .clickable { vm.resetTransformAt(cursorUs) }
                    .padding(horizontal = 9.dp, vertical = 6.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                "◆ All",
                fontSize = 8.sp,
                color = X5Accent,
                modifier = Modifier
                    .background(X5Raised, RoundedCornerShape(5.dp))
                    .clickable { vm.toggleAllTransformKeyframes(cursorUs) }
                    .padding(horizontal = 9.dp, vertical = 6.dp),
            )
        }

        Slider(
            value = fraction,
            onValueChange = { amount ->
                onSeek(clip.timelineStartUs + (duration * amount.coerceIn(0f, 1f)).toLong())
            },
            modifier = Modifier.fillMaxWidth().height(28.dp),
        )

        TransformRowV5(
            label = "Position X",
            value = evaluated.positionX,
            range = -1f..1f,
            display = { "${(it * 100f).roundToInt()}%" },
            channel = clip.transform.positionX,
            durationUs = duration,
            keyframeLocalUs = keyframeLocalUs,
            onValue = { vm.setTransformProperty(TransformProperty.POSITION_X, it, cursorUs) },
            onKeyframe = { vm.toggleTransformKeyframe(TransformProperty.POSITION_X, cursorUs) },
            onSeekKeyframe = seekKeyframe,
        )
        TransformRowV5(
            label = "Position Y",
            value = evaluated.positionY,
            range = -1f..1f,
            display = { "${(it * 100f).roundToInt()}%" },
            channel = clip.transform.positionY,
            durationUs = duration,
            keyframeLocalUs = keyframeLocalUs,
            onValue = { vm.setTransformProperty(TransformProperty.POSITION_Y, it, cursorUs) },
            onKeyframe = { vm.toggleTransformKeyframe(TransformProperty.POSITION_Y, cursorUs) },
            onSeekKeyframe = seekKeyframe,
        )
        TransformRowV5(
            label = "Scale X",
            value = evaluated.scaleX,
            range = .1f..4f,
            display = { "${(it * 100f).roundToInt()}%" },
            channel = clip.transform.scaleX,
            durationUs = duration,
            keyframeLocalUs = keyframeLocalUs,
            onValue = { vm.setTransformProperty(TransformProperty.SCALE_X, it, cursorUs) },
            onKeyframe = { vm.toggleTransformKeyframe(TransformProperty.SCALE_X, cursorUs) },
            onSeekKeyframe = seekKeyframe,
        )
        TransformRowV5(
            label = "Scale Y",
            value = evaluated.scaleY,
            range = .1f..4f,
            display = { "${(it * 100f).roundToInt()}%" },
            channel = clip.transform.scaleY,
            durationUs = duration,
            keyframeLocalUs = keyframeLocalUs,
            onValue = { vm.setTransformProperty(TransformProperty.SCALE_Y, it, cursorUs) },
            onKeyframe = { vm.toggleTransformKeyframe(TransformProperty.SCALE_Y, cursorUs) },
            onSeekKeyframe = seekKeyframe,
        )
        TransformRowV5(
            label = "Rotation",
            value = evaluated.rotationDegrees.coerceIn(-180f, 180f),
            range = -180f..180f,
            display = { "${it.roundToInt()}°" },
            channel = clip.transform.rotationDegrees,
            durationUs = duration,
            keyframeLocalUs = keyframeLocalUs,
            onValue = { vm.setTransformProperty(TransformProperty.ROTATION, it, cursorUs) },
            onKeyframe = { vm.toggleTransformKeyframe(TransformProperty.ROTATION, cursorUs) },
            onSeekKeyframe = seekKeyframe,
        )

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun TransformRowV5(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String,
    channel: AnimatedFloat,
    durationUs: Long,
    keyframeLocalUs: Long,
    onValue: (Float) -> Unit,
    onKeyframe: () -> Unit,
    onSeekKeyframe: (Long) -> Unit,
) {
    val activeKeyframe = channel.hasKeyframeAt(keyframeLocalUs)
    Column(Modifier.fillMaxWidth().background(X5Raised).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.width(70.dp), fontSize = 7.sp, color = Color.White.copy(alpha = .76f))
            Slider(
                value = value.coerceIn(range.start, range.endInclusive),
                onValueChange = onValue,
                valueRange = range,
                modifier = Modifier.weight(1f).height(28.dp),
            )
            Text(display(value), Modifier.width(45.dp), fontSize = 7.sp, color = X5Muted)
            Text(
                if (activeKeyframe) "◆" else "◇",
                fontSize = 14.sp,
                color = if (activeKeyframe) X5Accent else X5Muted,
                modifier = Modifier
                    .clickable(onClick = onKeyframe)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }
        if (channel.keyframes.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().height(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KeyframeStripV5(
                    channel = channel,
                    durationUs = durationUs,
                    activeKeyframeUs = keyframeLocalUs.takeIf { activeKeyframe },
                    onKeyframeTap = onSeekKeyframe,
                    modifier = Modifier.weight(1f).height(18.dp),
                )
                if (activeKeyframe) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "◆ ${formatTransformTime(keyframeLocalUs)}",
                        fontSize = 6.sp,
                        color = X5Accent,
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "Delete",
                        fontSize = 7.sp,
                        color = X5Danger,
                        modifier = Modifier
                            .background(X5Danger.copy(alpha = .08f), RoundedCornerShape(4.dp))
                            .clickable(onClick = onKeyframe)
                            .padding(horizontal = 7.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun KeyframeStripV5(
    channel: AnimatedFloat,
    durationUs: Long,
    activeKeyframeUs: Long?,
    onKeyframeTap: (Long) -> Unit,
    modifier: Modifier,
) {
    val keys = channel.keyframes.sortedBy { it.timeUs }
    Canvas(
        modifier.pointerInput(keys, durationUs) {
            detectTapGestures { tap ->
                if (keys.isEmpty() || size.width <= 0) return@detectTapGestures
                val width = size.width.toFloat()
                val nearest = keys.minByOrNull { keyframe ->
                    val x = (keyframe.timeUs.toDouble() / durationUs.coerceAtLeast(1L).toDouble())
                        .toFloat().coerceIn(0f, 1f) * width
                    abs(x - tap.x)
                } ?: return@detectTapGestures
                val nearestX = (nearest.timeUs.toDouble() / durationUs.coerceAtLeast(1L).toDouble())
                    .toFloat().coerceIn(0f, 1f) * width
                val hitRadius = maxOf(18f, width * .035f)
                if (abs(nearestX - tap.x) <= hitRadius) onKeyframeTap(nearest.timeUs)
            }
        },
    ) {
        drawLine(
            X5Divider,
            start = androidx.compose.ui.geometry.Offset(0f, size.height * .5f),
            end = androidx.compose.ui.geometry.Offset(size.width, size.height * .5f),
            strokeWidth = 1f,
        )
        keys.forEach { keyframe ->
            val x = (keyframe.timeUs.toDouble() / durationUs.coerceAtLeast(1L).toDouble()).toFloat()
                .coerceIn(0f, 1f) * size.width
            val active = activeKeyframeUs == keyframe.timeUs
            drawCircle(
                if (active) Color.White else X5Accent,
                radius = if (active) 4.2f else 2.8f,
                center = androidx.compose.ui.geometry.Offset(x, size.height * .5f),
            )
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
