package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.weight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tajuli.digitorandroid.editor.model.NodeAnimationDomain
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.TimelineClip

/** Keeps the same V4 grading panels while adding one clean keyframe lane per editing domain. */
@Composable
fun KeyframedCorrectionWorkspaceV5(
    clip: TimelineClip?,
    frameRate: Int,
    vm: EditorViewModelV4,
    modifier: Modifier = Modifier,
) {
    val node = clip?.nodeGraph?.selectedNode()
    Column(modifier) {
        if (clip != null && node != null && (node.kind == NodeKind.SERIAL || node.kind == NodeKind.PARALLEL)) {
            NodeDomainKeyframeBarV5(clip, node, NodeAnimationDomain.CORRECTION, frameRate)
        }
        Box(Modifier.weight(1f).fillMaxSize()) {
            CorrectionWorkspaceV4(clip, vm, Modifier.fillMaxSize())
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
    val node = clip?.nodeGraph?.selectedNode()
    Column(modifier) {
        if (clip != null && node != null && (node.kind == NodeKind.SERIAL || node.kind == NodeKind.PARALLEL)) {
            NodeDomainKeyframeBarV5(clip, node, NodeAnimationDomain.COLOR, frameRate)
        }
        Box(Modifier.weight(1f).fillMaxSize()) {
            ColorWorkspaceV4(clip, vm, Modifier.fillMaxSize())
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
    val node = clip?.nodeGraph?.selectedNode()
    Column(modifier) {
        if (clip != null && node != null && (node.kind == NodeKind.SERIAL || node.kind == NodeKind.PARALLEL)) {
            NodeDomainKeyframeBarV5(clip, node, NodeAnimationDomain.EFFECTS, frameRate)
        }
        Box(Modifier.weight(1f).fillMaxSize()) {
            EffectsWorkspaceV4(clip, vm, Modifier.fillMaxSize())
        }
    }
}
