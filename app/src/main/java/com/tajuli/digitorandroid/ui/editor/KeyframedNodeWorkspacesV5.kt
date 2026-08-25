package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.tajuli.digitorandroid.editor.model.NodeAnimationDomain
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.PreviewTransformClock
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
    Column(modifier) {
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
