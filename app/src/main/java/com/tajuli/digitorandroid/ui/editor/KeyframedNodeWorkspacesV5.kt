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
import com.tajuli.digitorandroid.editor.model.ColorNode
import com.tajuli.digitorandroid.editor.model.NodeAnimationDomain
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.PreviewTransformClock
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.visibleEffects
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

/**
 * Watches only the immutable editor/base node. Playback changes the evaluated display node but not
 * this fingerprint, so moving the playhead never creates keyframes by itself. Once a domain has at
 * least one keyframe, an actual control edit updates the base node and is automatically captured at
 * the current frame, matching Transform auto-key behavior.
 *
 * Effects use a more precise inline auto-key path in EffectsWorkspaceV4 so changing one amount
 * starts from the evaluated effect state and does not overwrite another effect's interpolated amount.
 */
@Composable
private fun AutoKeyNodeDomainV5(
    clip: TimelineClip?,
    node: ColorNode?,
    domain: NodeAnimationDomain,
    sourceTimeUs: Long?,
) {
    if (clip == null || node == null || sourceTimeUs == null) return
    val fingerprint: Any = when (domain) {
        NodeAnimationDomain.CORRECTION -> node.corrections
        NodeAnimationDomain.COLOR -> node.advancedColor
        NodeAnimationDomain.EFFECTS -> node.visibleEffects()
    }
    var previous by remember(clip.id, node.id, domain) { mutableStateOf(fingerprint) }

    LaunchedEffect(fingerprint, sourceTimeUs, clip.id, node.id, domain) {
        if (fingerprint != previous && clip.nodeAnimations.hasAnimation(node.id, domain)) {
            clip.nodeAnimations.upsertIfAnimated(node, domain, sourceTimeUs)
        }
        previous = fingerprint
    }
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
    AutoKeyNodeDomainV5(clip, baseNode, NodeAnimationDomain.CORRECTION, evaluated.sourceTimeUs)
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
    AutoKeyNodeDomainV5(clip, baseNode, NodeAnimationDomain.COLOR, evaluated.sourceTimeUs)
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
