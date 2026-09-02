package com.tajuli.digitorandroid.editor.model

/**
 * V41 node-binding contract for creator filters.
 *
 * Filter cards now edit the currently selected Serial/Parallel node instead of silently falling
 * back to the first editable node in the clip. This keeps filter ownership consistent with the
 * Color workspace and makes node ordering/parallel branches meaningful for creator looks.
 */
fun ColorNode.appliedCreatorFiltersV41(): LinkedHashMap<String, Float> {
    val result = linkedMapOf<String, Float>()
    effects.asSequence()
        .filter { it.enabled && it.amount > 0f }
        .forEach { effect ->
            effect.creatorFilterPresetIdV36()?.let { id ->
                result[id] = effect.amount.coerceIn(0f, 1f)
            }
        }
    return result
}

/**
 * Returns only the actual selected editable node. Import/Output/Mixer selections are not silently
 * redirected to another node. A null legacy selection may still fall back to the first editable
 * node so old saved projects remain usable until the user explicitly selects a node.
 */
fun TimelineClip.selectedCreatorFilterHostV41(): ColorNode? {
    val selectedId = nodeGraph.selectedNodeId
    val selected = nodeGraph.selectedNode()
    if (selectedId != null) {
        return selected?.takeIf { node ->
            (node.kind == NodeKind.SERIAL || node.kind == NodeKind.PARALLEL) &&
                !node.isLegacyCreatorFilterNodeV36()
        }
    }
    return creatorFilterHostNodeV36()
}
