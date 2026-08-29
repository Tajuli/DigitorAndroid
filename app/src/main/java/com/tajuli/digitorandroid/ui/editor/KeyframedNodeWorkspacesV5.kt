package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.tajuli.digitorandroid.editor.model.AdvancedColorGrade
import com.tajuli.digitorandroid.editor.model.ColorNode
import com.tajuli.digitorandroid.editor.model.ColorWheelValue
import com.tajuli.digitorandroid.editor.model.HslQualifier
import com.tajuli.digitorandroid.editor.model.LogWheels
import com.tajuli.digitorandroid.editor.model.NodeAnimationDomain
import com.tajuli.digitorandroid.editor.model.NodeCorrections
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.PreviewTransformClock
import com.tajuli.digitorandroid.editor.model.PrimaryWheels
import com.tajuli.digitorandroid.editor.model.RgbCurves
import com.tajuli.digitorandroid.editor.model.TimelineClip
import kotlin.math.roundToLong

private data class EvaluatedNodeClip(
    val clip: TimelineClip?,
    val sourceTimeUs: Long?,
)

/**
 * Builds a display-only clip whose node graph is evaluated at the current frame. The immutable
 * timeline model remains untouched; NodeAnimations is shared so keyframe add/delete remains live.
 */
@Composable
private fun evaluatedNodeClip(clip: TimelineClip?, frameRate: Int): EvaluatedNodeClip {
    val clock by PreviewTransformClock.flow.collectAsState()
    if (clip == null) return EvaluatedNodeClip(null, null)

    val rawLocal = if (clock.clipId == clip.id) clock.localUs else 0L
    val frameUs = (1_000_000.0 / frameRate.coerceAtLeast(1)).roundToLong().coerceAtLeast(1L)
    val localUs = ((rawLocal.toDouble() / frameUs).roundToLong() * frameUs)
        .coerceIn(0L, clip.durationUs)
    val sourceUs = (clip.sourceInUs + localUs).coerceIn(clip.sourceInUs, clip.sourceOutUs)
    val evaluatedGraph = clip.nodeAnimations.evaluateGraph(clip.nodeGraph, sourceUs)
    return EvaluatedNodeClip(clip.copy(nodeGraph = evaluatedGraph), sourceUs)
}

@Composable
private fun AutoKeyCorrectionV5(
    clip: TimelineClip?,
    baseNode: ColorNode?,
    evaluatedNode: ColorNode?,
    sourceTimeUs: Long?,
) {
    if (clip == null || baseNode == null || evaluatedNode == null || sourceTimeUs == null) return
    var previous by remember(clip.id, baseNode.id) { mutableStateOf(baseNode.corrections) }
    val current = baseNode.corrections

    LaunchedEffect(current, sourceTimeUs, clip.id, baseNode.id) {
        if (current != previous && clip.nodeAnimations.hasAnimation(baseNode.id, NodeAnimationDomain.CORRECTION)) {
            val merged = mergeCorrections(previous, current, evaluatedNode.corrections)
            clip.nodeAnimations.upsertIfAnimated(
                evaluatedNode.copy(corrections = merged),
                NodeAnimationDomain.CORRECTION,
                sourceTimeUs,
            )
        }
        previous = current
    }
}

@Composable
private fun AutoKeyColorV5(
    clip: TimelineClip?,
    baseNode: ColorNode?,
    evaluatedNode: ColorNode?,
    sourceTimeUs: Long?,
) {
    if (clip == null || baseNode == null || evaluatedNode == null || sourceTimeUs == null) return
    var previous by remember(clip.id, baseNode.id) { mutableStateOf(baseNode.advancedColor) }
    val current = baseNode.advancedColor

    LaunchedEffect(current, sourceTimeUs, clip.id, baseNode.id) {
        if (current != previous && clip.nodeAnimations.hasAnimation(baseNode.id, NodeAnimationDomain.COLOR)) {
            val merged = mergeAdvancedColor(previous, current, evaluatedNode.advancedColor)
            clip.nodeAnimations.upsertIfAnimated(
                evaluatedNode.copy(advancedColor = merged),
                NodeAnimationDomain.COLOR,
                sourceTimeUs,
            )
        }
        previous = current
    }
}

private fun mergeCorrections(
    previous: NodeCorrections,
    current: NodeCorrections,
    evaluated: NodeCorrections,
): NodeCorrections = NodeCorrections(
    exposure = changed(previous.exposure, current.exposure, evaluated.exposure),
    contrast = changed(previous.contrast, current.contrast, evaluated.contrast),
    saturation = changed(previous.saturation, current.saturation, evaluated.saturation),
    temperature = changed(previous.temperature, current.temperature, evaluated.temperature),
    tint = changed(previous.tint, current.tint, evaluated.tint),
    highlights = changed(previous.highlights, current.highlights, evaluated.highlights),
    shadows = changed(previous.shadows, current.shadows, evaluated.shadows),
    hue = changed(previous.hue, current.hue, evaluated.hue),
    colorBoost = changed(previous.colorBoost, current.colorBoost, evaluated.colorBoost),
)

private fun mergeAdvancedColor(
    previous: AdvancedColorGrade,
    current: AdvancedColorGrade,
    evaluated: AdvancedColorGrade,
): AdvancedColorGrade = AdvancedColorGrade(
    primary = mergePrimary(previous.primary, current.primary, evaluated.primary),
    log = mergeLog(previous.log, current.log, evaluated.log),
    curves = mergeCurves(previous.curves, current.curves, evaluated.curves),
    qualifier = mergeQualifier(previous.qualifier, current.qualifier, evaluated.qualifier),
)

private fun mergePrimary(
    previous: PrimaryWheels,
    current: PrimaryWheels,
    evaluated: PrimaryWheels,
): PrimaryWheels = PrimaryWheels(
    lift = mergeWheel(previous.lift, current.lift, evaluated.lift),
    gamma = mergeWheel(previous.gamma, current.gamma, evaluated.gamma),
    gain = mergeWheel(previous.gain, current.gain, evaluated.gain),
    offset = mergeWheel(previous.offset, current.offset, evaluated.offset),
)

private fun mergeLog(
    previous: LogWheels,
    current: LogWheels,
    evaluated: LogWheels,
): LogWheels = LogWheels(
    shadows = mergeWheel(previous.shadows, current.shadows, evaluated.shadows),
    midtones = mergeWheel(previous.midtones, current.midtones, evaluated.midtones),
    highlights = mergeWheel(previous.highlights, current.highlights, evaluated.highlights),
    global = mergeWheel(previous.global, current.global, evaluated.global),
    shadowRange = changed(previous.shadowRange, current.shadowRange, evaluated.shadowRange),
    highlightRange = changed(previous.highlightRange, current.highlightRange, evaluated.highlightRange),
)

private fun mergeWheel(
    previous: ColorWheelValue,
    current: ColorWheelValue,
    evaluated: ColorWheelValue,
): ColorWheelValue = ColorWheelValue(
    red = changed(previous.red, current.red, evaluated.red),
    green = changed(previous.green, current.green, evaluated.green),
    blue = changed(previous.blue, current.blue, evaluated.blue),
    luma = changed(previous.luma, current.luma, evaluated.luma),
    puckX = changed(previous.puckX, current.puckX, evaluated.puckX),
    puckY = changed(previous.puckY, current.puckY, evaluated.puckY),
)

private fun mergeCurves(previous: RgbCurves, current: RgbCurves, evaluated: RgbCurves): RgbCurves = RgbCurves(
    master = if (current.master != previous.master) current.master else evaluated.master,
    red = if (current.red != previous.red) current.red else evaluated.red,
    green = if (current.green != previous.green) current.green else evaluated.green,
    blue = if (current.blue != previous.blue) current.blue else evaluated.blue,
)

private fun mergeQualifier(
    previous: HslQualifier,
    current: HslQualifier,
    evaluated: HslQualifier,
): HslQualifier = HslQualifier(
    enabled = if (current.enabled != previous.enabled) current.enabled else evaluated.enabled,
    hueCenterDegrees = changed(previous.hueCenterDegrees, current.hueCenterDegrees, evaluated.hueCenterDegrees),
    hueWidthDegrees = changed(previous.hueWidthDegrees, current.hueWidthDegrees, evaluated.hueWidthDegrees),
    saturationMin = changed(previous.saturationMin, current.saturationMin, evaluated.saturationMin),
    saturationMax = changed(previous.saturationMax, current.saturationMax, evaluated.saturationMax),
    luminanceMin = changed(previous.luminanceMin, current.luminanceMin, evaluated.luminanceMin),
    luminanceMax = changed(previous.luminanceMax, current.luminanceMax, evaluated.luminanceMax),
    softness = changed(previous.softness, current.softness, evaluated.softness),
    hueShiftDegrees = changed(previous.hueShiftDegrees, current.hueShiftDegrees, evaluated.hueShiftDegrees),
    saturationShift = changed(previous.saturationShift, current.saturationShift, evaluated.saturationShift),
    luminanceShift = changed(previous.luminanceShift, current.luminanceShift, evaluated.luminanceShift),
    pickedRed = changedNullable(previous.pickedRed, current.pickedRed, evaluated.pickedRed),
    pickedGreen = changedNullable(previous.pickedGreen, current.pickedGreen, evaluated.pickedGreen),
    pickedBlue = changedNullable(previous.pickedBlue, current.pickedBlue, evaluated.pickedBlue),
)

private fun changed(previous: Float, current: Float, evaluated: Float): Float =
    if (current != previous) current else evaluated

private fun changedNullable(previous: Float?, current: Float?, evaluated: Float?): Float? =
    if (current != previous) current else evaluated

/** Keeps the V4 grading panels while adding one clean keyframe lane per editing domain. */
@Composable
fun KeyframedCorrectionWorkspaceV5(
    clip: TimelineClip?,
    frameRate: Int,
    vm: EditorViewModelV4,
    modifier: Modifier = Modifier,
) {
    val baseNode = clip?.nodeGraph?.selectedNode()
    val evaluated = evaluatedNodeClip(clip, frameRate)
    val evaluatedNode = evaluated.clip?.nodeGraph?.selectedNode()
    AutoKeyCorrectionV5(clip, baseNode, evaluatedNode, evaluated.sourceTimeUs)
    Column(modifier) {
        if (clip != null && baseNode != null && (baseNode.kind == NodeKind.SERIAL || baseNode.kind == NodeKind.PARALLEL)) {
            NodeDomainKeyframeBarV5(clip, baseNode, NodeAnimationDomain.CORRECTION, frameRate)
        }
        Box(Modifier.weight(1f).fillMaxSize()) {
            CorrectionWorkspaceV4(evaluated.clip, vm, Modifier.fillMaxSize())
        }
    }
}

@Composable
fun KeyframedColorWorkspaceV5(
    clip: TimelineClip?,
    frameRate: Int,
    vm: EditorViewModelV4,
    modifier: Modifier = Modifier,
) {
    val baseNode = clip?.nodeGraph?.selectedNode()
    val evaluated = evaluatedNodeClip(clip, frameRate)
    val evaluatedNode = evaluated.clip?.nodeGraph?.selectedNode()
    AutoKeyColorV5(clip, baseNode, evaluatedNode, evaluated.sourceTimeUs)
    Column(modifier) {
        InputColorProfileBarV1(clip, vm)
        if (clip != null && baseNode != null && (baseNode.kind == NodeKind.SERIAL || baseNode.kind == NodeKind.PARALLEL)) {
            NodeDomainKeyframeBarV5(clip, baseNode, NodeAnimationDomain.COLOR, frameRate)
        }
        Box(Modifier.weight(1f).fillMaxSize()) {
            ColorWorkspaceV4(evaluated.clip, vm, Modifier.fillMaxSize())
        }
    }
}

@Composable
fun KeyframedEffectsWorkspaceV5(
    clip: TimelineClip?,
    frameRate: Int,
    vm: EditorViewModelV4,
    modifier: Modifier = Modifier,
) {
    val baseNode = clip?.nodeGraph?.selectedNode()
    val evaluated = evaluatedNodeClip(clip, frameRate)
    Column(modifier) {
        if (clip != null && baseNode != null && (baseNode.kind == NodeKind.SERIAL || baseNode.kind == NodeKind.PARALLEL)) {
            NodeDomainKeyframeBarV5(clip, baseNode, NodeAnimationDomain.EFFECTS, frameRate)
        }
        Box(Modifier.weight(1f).fillMaxSize()) {
            EffectsWorkspaceV4(
                clip = evaluated.clip,
                vm = vm,
                modifier = Modifier.fillMaxSize(),
                animationSourceTimeUs = evaluated.sourceTimeUs,
            )
        }
    }
}