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

/** Selected editable node first; legacy fallback keeps older projects usable. */
fun TimelineClip.selectedCreatorFilterHostV41(): ColorNode? {
    val selected = nodeGraph.selectedNode()
    if (selected != null &&
        (selected.kind == NodeKind.SERIAL || selected.kind == NodeKind.PARALLEL) &&
        !selected.isLegacyCreatorFilterNodeV36()
    ) {
        return selected
    }
    return creatorFilterHostNodeV36()
}
