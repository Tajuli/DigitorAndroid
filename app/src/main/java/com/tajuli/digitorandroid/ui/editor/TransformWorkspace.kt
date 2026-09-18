package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.collectAsState
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
import com.tajuli.digitorandroid.editor.model.ClipStabilizationV90
import com.tajuli.digitorandroid.editor.model.StabilizationModeV90
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.TransformProperty
import com.tajuli.digitorandroid.editor.model.evaluate
import kotlin.math.abs
import kotlin.math.roundToInt

private val X5Panel = Color(0xFF0B0B0F)
private val X5Raised = Color(0xFF141419)
private val X5Divider = Color(0xFF292930)
private val X5Muted = Color(0xFF909098)
private val X5Accent = Color(0xFF30E0C3)
private val X5Danger = Color(0xFFFF7474)

private enum class EditPageV5 { TIMELINE, TRANSFORM, STABILIZE, RETIME, CUTOUT }

/** Timeline, transform, stabilization, retime and cutout live under Edit; transitions stay on the timeline. */
@Composable
fun EditWorkspace(
    project: TimelineProject,
    selectedTrackId: String?,
    selectedClipIds: Set<String>,
    selectedClip: TimelineClip?,
    cursorUs: Long,
    vm: EditorViewModel,
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
    val canEditVideo = selectedClip != null && project.trackContaining(selectedClip.id)?.kind == TrackKind.VIDEO

    LaunchedEffect(canEditVideo) {
        if (!canEditVideo && page != EditPageV5.TIMELINE) page = EditPageV5.TIMELINE
    }

    Column(modifier.background(X5Panel)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .horizontalScroll(rememberScrollState())
                .background(Color(0xFF101014))
                .padding(horizontal = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            TextButton(onClick = { page = EditPageV5.TIMELINE }) {
                Text("Timeline", fontSize = 8.sp, color = if (page == EditPageV5.TIMELINE) X5Accent else X5Muted)
            }
            TextButton(onClick = { page = EditPageV5.TRANSFORM }, enabled = canEditVideo) {
                Text("Transform", fontSize = 8.sp, color = if (page == EditPageV5.TRANSFORM) X5Accent else X5Muted)
            }
            TextButton(onClick = { page = EditPageV5.STABILIZE }, enabled = canEditVideo) {
                Text("Stabilize", fontSize = 8.sp, color = if (page == EditPageV5.STABILIZE) X5Accent else X5Muted)
            }
            TextButton(onClick = { page = EditPageV5.RETIME }, enabled = canEditVideo) {
                Text("Retime", fontSize = 8.sp, color = if (page == EditPageV5.RETIME) X5Accent else X5Muted)
            }
            TextButton(onClick = { page = EditPageV5.CUTOUT }, enabled = canEditVideo) {
                Text("Cutout", fontSize = 8.sp, color = if (page == EditPageV5.CUTOUT) X5Accent else X5Muted)
            }
            if (page == EditPageV5.TRANSFORM) {
                Text("◆ keyframe", fontSize = 7.sp, color = X5Muted, modifier = Modifier.padding(horizontal = 6.dp))
            }
        }
        HorizontalDivider(color = X5Divider)

        when (page) {
            EditPageV5.TIMELINE -> TimelineEditor(
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
                onSelectText = vm::selectTextOverlay,
                onMoveText = vm::moveTextOverlayV10,
                onMoveTextToTrack = vm::moveTextOverlayToVideoTrackV10,
                onDeleteText = vm::deleteSelectedText,
                modifier = Modifier.fillMaxSize(),
            )

            EditPageV5.TRANSFORM -> {
                if (selectedClip != null && canEditVideo) {
                    TransformWorkspaceV5(selectedClip, cursorUs, project.frameRate, vm, onSeek, Modifier.fillMaxSize())
                }
            }

            EditPageV5.STABILIZE -> {
                if (selectedClip != null && canEditVideo) {
                    StabilizationWorkspaceV90(selectedClip, cursorUs, vm, Modifier.fillMaxSize())
                }
            }

            EditPageV5.RETIME -> {
                if (selectedClip != null && canEditVideo) {
                    RetimeWorkspaceV5(selectedClip, cursorUs, vm, Modifier.fillMaxSize())
                }
            }

            EditPageV5.CUTOUT -> {
                if (selectedClip != null && canEditVideo) {
                    CutoutWorkspace(vm = vm, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun StabilizationWorkspaceV90(
    clip: TimelineClip,
    cursorUs: Long,
    vm: EditorViewModel,
    modifier: Modifier,
) {
    val stabilization = clip.stabilizationV90 ?: ClipStabilizationV90(enabled = false)
    val localUs = (cursorUs - clip.timelineStartUs).coerceIn(0L, clip.durationUs)
    val stabilizationAtPlayhead = stabilization.evaluate(clip.sourceInUs + localUs)
    val uiState by vm.state.collectAsState()
    val analyzing = uiState.busyOperation == "Stabilization"

    Column(
        modifier
            .background(X5Panel)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text("Stabilization · ${clip.label}", fontSize = 10.sp, color = Color.White)
        Text(
            "Resolve-style offline camera analysis. Translation removes hand-shake, Similarity also corrects rotation/zoom, and Camera Lock pins the shot to the analyzed reference.",
            fontSize = 7.sp,
            color = X5Muted,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                when {
                    analyzing -> "Analyzing…"
                    stabilization.hasAnalysis -> "Re-analyze"
                    else -> "Analyze"
                },
                fontSize = 9.sp,
                color = if (analyzing) X5Muted else Color.White,
                modifier = Modifier
                    .background(
                        if (analyzing) X5Raised else X5Accent.copy(alpha = .18f),
                        RoundedCornerShape(6.dp),
                    )
                    .clickable(enabled = !analyzing) { vm.analyzeSelectedStabilizationV90() }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
            if (stabilization.hasAnalysis) {
                Text(
                    if (stabilization.enabled) "Enabled" else "Disabled",
                    fontSize = 9.sp,
                    color = if (stabilization.enabled) X5Accent else X5Muted,
                    modifier = Modifier
                        .background(X5Raised, RoundedCornerShape(6.dp))
                        .clickable { vm.setSelectedStabilizationEnabledV90(!stabilization.enabled) }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                )
                Text(
                    "Clear",
                    fontSize = 9.sp,
                    color = X5Danger,
                    modifier = Modifier
                        .background(X5Raised, RoundedCornerShape(6.dp))
                        .clickable { vm.clearSelectedStabilizationV90() }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }

        if (analyzing) {
            Text(
                uiState.status,
                fontSize = 9.sp,
                color = X5Accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(X5Raised, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 9.dp),
            )
        }

        if (!stabilization.hasAnalysis) {
            Text(
                if (analyzing) {
                    "Keep this screen open while the clip is decoded sequentially. Controls appear as soon as analysis finishes."
                } else {
                    "Analyze once, then Strength, Smooth and Crop update instantly without decoding the clip again."
                },
                fontSize = 8.sp,
                color = X5Muted,
            )
            return@Column
        }

        Text(
            when {
                stabilization.analysisVersionV93 >= 96 ->
                    "V96 Resolve Translation · ${stabilization.samples.size} frame samples · ${stabilization.analyzedWidth}×${stabilization.analyzedHeight}"
                stabilization.analysisVersionV93 >= 93 ->
                    "V93 analysis · Re-analyze for V96 full-frame tracking"
                else ->
                    "Legacy analysis · Re-analyze for V96 full-frame tracking"
            },
            fontSize = 7.sp,
            color = if (stabilization.analysisVersionV93 >= 93) X5Muted else X5Accent,
        )

        Text("Mode", fontSize = 8.sp, color = X5Muted)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                StabilizationModeV90.TRANSLATION to "Translation",
                StabilizationModeV90.SIMILARITY to "Similarity",
                StabilizationModeV90.CAMERA_LOCK to "Camera Lock",
            ).forEach { (mode, label) ->
                Text(
                    label,
                    fontSize = 8.sp,
                    color = if (stabilization.mode == mode) X5Accent else Color.White,
                    modifier = Modifier
                        .background(
                            if (stabilization.mode == mode) X5Accent.copy(alpha = .14f) else X5Raised,
                            RoundedCornerShape(6.dp),
                        )
                        .clickable { vm.setSelectedStabilizationModeV90(mode) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        StabilizationSliderV90(
            "Strength",
            stabilization.strength,
            0f..1f,
            { "${(it * 100f).roundToInt()}%" },
            vm::setSelectedStabilizationStrengthV90,
        )
        val smoothSeconds = stabilization.smoothRadiusUs / 1_000_000f
        StabilizationSliderV90(
            "Smooth",
            smoothSeconds,
            .08f..3f,
            { String.format("%.2fs", it) },
        ) { vm.setSelectedStabilizationSmoothV90((it * 1_000_000L).toLong()) }
        StabilizationSliderV90(
            "Crop / Auto Zoom",
            stabilization.crop,
            0f..1f,
            { "${(it * 100f).roundToInt()}%" },
            vm::setSelectedStabilizationCropV90,
        )

        Text(
            "Auto zoom at playhead: ${(stabilizationAtPlayhead.scale * 100f).roundToInt()}% · Crop 100% guarantees frame coverage for the calculated stabilization transform.",
            fontSize = 8.sp,
            color = X5Accent,
        )
        Text(
            "For strong handheld shake: Similarity, Strength 85–100%, Smooth 0.6–1.2s, Crop/Auto Zoom 100%. Camera Lock is strongest but can require much more zoom.",
            fontSize = 7.sp,
            color = X5Muted,
        )
    }
}

@Composable
private fun StabilizationSliderV90(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String,
    onValue: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(X5Raised, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.width(62.dp), fontSize = 8.sp, color = Color.White.copy(alpha = .78f))
            Slider(
                value = value.coerceIn(range.start, range.endInclusive),
                onValueChange = onValue,
                valueRange = range,
                modifier = Modifier.weight(1f),
            )
            Text(display(value), Modifier.width(50.dp), fontSize = 7.sp, color = X5Muted)
        }
    }
}

@Composable
private fun RetimeWorkspaceV5(
    clip: TimelineClip,
    cursorUs: Long,
    vm: EditorViewModel,
    modifier: Modifier,
) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Retime · ${clip.label}", fontSize = 10.sp, color = Color.White)
        Text("Speed", fontSize = 8.sp, color = X5Muted)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(.5f, .75f, 1.25f, 1.5f, 2f, 3f).forEach { speed ->
                Text(
                    "${speed}x",
                    fontSize = 9.sp,
                    color = Color.White,
                    modifier = Modifier
                        .background(X5Raised, RoundedCornerShape(6.dp))
                        .clickable { vm.bakeSelectedSpeed(speed) }
                        .padding(horizontal = 13.dp, vertical = 8.dp),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Reverse",
                fontSize = 9.sp,
                color = Color.White,
                modifier = Modifier
                    .background(X5Raised, RoundedCornerShape(6.dp))
                    .clickable { vm.reverseSelectedVideo() }
                    .padding(horizontal = 15.dp, vertical = 9.dp),
            )
            Text(
                "Freeze 2s",
                fontSize = 9.sp,
                color = Color.White,
                modifier = Modifier
                    .background(X5Raised, RoundedCornerShape(6.dp))
                    .clickable { vm.freezeSelectedAt(cursorUs, 2_000_000L) }
                    .padding(horizontal = 15.dp, vertical = 9.dp),
            )
        }
        Text("Transitions are applied from the ◇ icon between clips.", fontSize = 8.sp, color = X5Muted)
    }
}

@Composable
private fun TransformWorkspaceV5(
    clip: TimelineClip,
    cursorUs: Long,
    frameRate: Int,
    vm: EditorViewModel,
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
            KeyframeStripV5(
                channel = channel,
                durationUs = durationUs,
                activeKeyframeUs = keyframeLocalUs.takeIf { activeKeyframe },
                onKeyframeTap = onSeekKeyframe,
                modifier = Modifier.fillMaxWidth().height(18.dp),
            )
            if (activeKeyframe) {
                Row(
                    Modifier.fillMaxWidth().height(28.dp).padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Selected ◆ ${formatTransformTime(keyframeLocalUs)}",
                        modifier = Modifier.weight(1f),
                        fontSize = 7.sp,
                        color = X5Accent,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Delete keyframe",
                        fontSize = 8.sp,
                        color = X5Danger,
                        modifier = Modifier
                            .background(X5Danger.copy(alpha = .12f), RoundedCornerShape(5.dp))
                            .clickable(onClick = onKeyframe)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
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
                val hitRadius = maxOf(14f, width * .025f)
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