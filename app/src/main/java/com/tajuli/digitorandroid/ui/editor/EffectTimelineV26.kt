package com.tajuli.digitorandroid.ui.editor

import com.tajuli.digitorandroid.editor.model.EFFECT_MIN_DURATION_US_V26
import com.tajuli.digitorandroid.editor.model.NodeEffect
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.normalizedForClipV26
import com.tajuli.digitorandroid.editor.model.resolvedSourceEndUsV26
import com.tajuli.digitorandroid.editor.model.resolvedSourceStartUsV26
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Stable identity for one creator-effect bar on the timeline. */
data class EffectTimelineSelectionV26(
    val clipId: String,
    val nodeId: String,
    val effectId: String,
)

object EffectTimelineSelectionBusV26 {
    private val _selection = MutableStateFlow<EffectTimelineSelectionV26?>(null)
    val selection: StateFlow<EffectTimelineSelectionV26?> = _selection.asStateFlow()

    fun select(clipId: String, nodeId: String, effectId: String) {
        _selection.value = EffectTimelineSelectionV26(clipId, nodeId, effectId)
    }

    fun clear() {
        _selection.value = null
    }
}

/**
 * TimelineEditorV4 historically resolves an un-keyed EditorViewModelV4 while MainActivity owns a
 * keyed editor-session ViewModel. Always route effect-bar commits to that active keyed instance,
 * exactly like the V14 trim/resize fix. Otherwise drag preview works locally but releasing the
 * pointer mutates an invisible ViewModel and the bar snaps back on the next recomposition.
 */
private fun EditorViewModelV4.activeEffectEditorV26(): EditorViewModelV4 =
    ActiveEditorVmRegistryV14.current() ?: this

fun EditorViewModelV4.deleteEffectTimelineV26(selection: EffectTimelineSelectionV26) {
    val target = activeEffectEditorV26()
    target.updateEffectTimelineV26(selection, "delete-effect", "Effect deleted") { _, _ -> null }
    if (EffectTimelineSelectionBusV26.selection.value == selection) EffectTimelineSelectionBusV26.clear()
}

fun EditorViewModelV4.moveEffectTimelineV26(selection: EffectTimelineSelectionV26, deltaUs: Long) {
    if (deltaUs == 0L) return
    val target = activeEffectEditorV26()
    target.updateEffectTimelineV26(selection, "move-effect", "Effect moved", coalesce = true) { clip, effect ->
        val normalized = effect.normalizedForClipV26(clip)
        val start = normalized.resolvedSourceStartUsV26(clip)
        val end = normalized.resolvedSourceEndUsV26(clip)
        val duration = (end - start).coerceAtLeast(1L)
        val maxStart = (clip.sourceOutUs - duration).coerceAtLeast(clip.sourceInUs)
        val nextStart = (start + deltaUs).coerceIn(clip.sourceInUs, maxStart)
        normalized.copy(sourceStartUsV26 = nextStart, sourceEndUsV26 = nextStart + duration)
    }
}

fun EditorViewModelV4.resizeEffectStartV26(selection: EffectTimelineSelectionV26, targetSourceUs: Long) {
    val target = activeEffectEditorV26()
    target.updateEffectTimelineV26(selection, "resize-effect-start", "Effect duration updated", coalesce = true) { clip, effect ->
        val normalized = effect.normalizedForClipV26(clip)
        val end = normalized.resolvedSourceEndUsV26(clip)
        val minDuration = minOf(EFFECT_MIN_DURATION_US_V26, clip.durationUs).coerceAtLeast(1L)
        val start = targetSourceUs.coerceIn(clip.sourceInUs, (end - minDuration).coerceAtLeast(clip.sourceInUs))
        normalized.copy(sourceStartUsV26 = start, sourceEndUsV26 = end)
    }
}

fun EditorViewModelV4.resizeEffectEndV26(selection: EffectTimelineSelectionV26, targetSourceUs: Long) {
    val target = activeEffectEditorV26()
    target.updateEffectTimelineV26(selection, "resize-effect-end", "Effect duration updated", coalesce = true) { clip, effect ->
        val normalized = effect.normalizedForClipV26(clip)
        val start = normalized.resolvedSourceStartUsV26(clip)
        val minDuration = minOf(EFFECT_MIN_DURATION_US_V26, clip.durationUs).coerceAtLeast(1L)
        val end = targetSourceUs.coerceIn((start + minDuration).coerceAtMost(clip.sourceOutUs), clip.sourceOutUs)
        normalized.copy(sourceStartUsV26 = start, sourceEndUsV26 = end)
    }
}

private fun EditorViewModelV4.updateEffectTimelineV26(
    selection: EffectTimelineSelectionV26,
    historyLabel: String,
    status: String,
    coalesce: Boolean = false,
    transform: (TimelineClip, NodeEffect) -> NodeEffect?,
) {
    val current = state.value.project
    var changed = false
    val next = current.copy(
        tracks = current.tracks.map { track ->
            track.copy(
                clips = track.clips.map { clip ->
                    if (clip.id != selection.clipId) return@map clip
                    val nextNodes = clip.nodeGraph.nodes.map { node ->
                        if (node.id != selection.nodeId) return@map node
                        val nextEffects = buildList {
                            node.effects.forEach { effect ->
                                if (effect.id != selection.effectId) {
                                    add(effect)
                                } else {
                                    val replacement = transform(clip, effect)
                                    if (replacement != null) add(replacement)
                                    changed = true
                                }
                            }
                        }
                        node.copy(effects = nextEffects)
                    }
                    clip.copy(nodeGraph = clip.nodeGraph.copy(nodes = nextNodes, revision = clip.nodeGraph.revision + 1L))
                },
            )
        },
    )
    if (!changed || next == current) return
    commitProjectV19(historyLabel, next, status, coalesce)
}

fun TimelineProject.effectSelectionExistsV26(selection: EffectTimelineSelectionV26?): Boolean {
    selection ?: return false
    val clip = clip(selection.clipId) ?: return false
    val node = clip.nodeGraph.nodes.firstOrNull { it.id == selection.nodeId } ?: return false
    return node.effects.any { it.id == selection.effectId }
}
