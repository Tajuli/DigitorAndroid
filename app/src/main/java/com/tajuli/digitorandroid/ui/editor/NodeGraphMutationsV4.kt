package com.tajuli.digitorandroid.ui.editor

import com.tajuli.digitorandroid.editor.model.ClipNodeGraph
import com.tajuli.digitorandroid.editor.model.NodeEdge
import com.tajuli.digitorandroid.editor.model.NodeKind

/**
 * Deletes an editable color node while preserving a valid graph.
 *
 * Serial nodes are spliced out of their current path (previous -> next).
 * Parallel nodes that feed a two-branch Mix collapse the remaining branch back to Serial and
 * remove the now-unnecessary Mix node. Multi-branch mixes stay intact until only one branch is
 * left.
 */
fun ClipNodeGraph.deleteEditableNodeV4(nodeId: String): ClipNodeGraph {
    val target = nodes.firstOrNull { it.id == nodeId } ?: return this
    if (target.kind != NodeKind.SERIAL && target.kind != NodeKind.PARALLEL) return this

    val incoming = edges.firstOrNull { it.toId == target.id }
    val outgoing = edges.firstOrNull { it.fromId == target.id }

    if (target.kind == NodeKind.PARALLEL) {
        val mix = outgoing
            ?.let { edge -> nodes.firstOrNull { it.id == edge.toId } }
            ?.takeIf { it.kind == NodeKind.MIX }
        if (mix != null) {
            val remainingMixInputs = edges.filter { it.toId == mix.id && it.fromId != target.id }
            if (remainingMixInputs.size == 1) {
                val survivorId = remainingMixInputs.single().fromId
                val downstreamId = edges.firstOrNull { it.fromId == mix.id }?.toId
                val rebuiltNodes = nodes
                    .filterNot { it.id == target.id || it.id == mix.id }
                    .map { node ->
                        if (node.id == survivorId && node.kind == NodeKind.PARALLEL) {
                            node.copy(
                                kind = NodeKind.SERIAL,
                                label = node.label.removePrefix("P").padStart(2, '0'),
                            )
                        } else {
                            node
                        }
                    }
                val rebuiltEdges = edges
                    .filterNot { edge ->
                        edge.fromId == target.id || edge.toId == target.id ||
                            edge.fromId == mix.id || edge.toId == mix.id
                    }
                    .toMutableList()
                if (downstreamId != null && survivorId != downstreamId &&
                    rebuiltEdges.none { it.fromId == survivorId && it.toId == downstreamId }
                ) {
                    rebuiltEdges += NodeEdge(survivorId, downstreamId)
                }
                return copy(
                    nodes = rebuiltNodes,
                    edges = rebuiltEdges,
                    selectedNodeId = nextSelectionAfterDelete(
                        deletedId = target.id,
                        preferredId = survivorId,
                        remainingNodes = rebuiltNodes.map { it.id }.toSet(),
                    ),
                )
            }

            if (remainingMixInputs.isNotEmpty()) {
                val rebuiltNodes = nodes.filterNot { it.id == target.id }
                val rebuiltEdges = edges.filterNot { it.fromId == target.id || it.toId == target.id }
                return copy(
                    nodes = rebuiltNodes,
                    edges = rebuiltEdges,
                    selectedNodeId = nextSelectionAfterDelete(
                        deletedId = target.id,
                        preferredId = remainingMixInputs.first().fromId,
                        remainingNodes = rebuiltNodes.map { it.id }.toSet(),
                    ),
                )
            }
        }
    }

    // Normal serial-path splice. This is also the safe fallback for a malformed/partial graph.
    val rebuiltNodes = nodes.filterNot { it.id == target.id }
    val rebuiltEdges = edges
        .filterNot { it.fromId == target.id || it.toId == target.id }
        .toMutableList()
    val previousId = incoming?.fromId
    val nextId = outgoing?.toId
    if (previousId != null && nextId != null && previousId != nextId &&
        rebuiltEdges.none { it.fromId == previousId && it.toId == nextId }
    ) {
        rebuiltEdges += NodeEdge(previousId, nextId)
    }
    val preferred = previousId
        ?.takeIf { id -> rebuiltNodes.any { it.id == id && (it.kind == NodeKind.SERIAL || it.kind == NodeKind.PARALLEL) } }
        ?: nextId?.takeIf { id -> rebuiltNodes.any { it.id == id && (it.kind == NodeKind.SERIAL || it.kind == NodeKind.PARALLEL) } }
        ?: rebuiltNodes.firstOrNull { it.kind == NodeKind.SERIAL || it.kind == NodeKind.PARALLEL }?.id

    return copy(
        nodes = rebuiltNodes,
        edges = rebuiltEdges,
        selectedNodeId = nextSelectionAfterDelete(
            deletedId = target.id,
            preferredId = preferred,
            remainingNodes = rebuiltNodes.map { it.id }.toSet(),
        ),
    )
}

private fun ClipNodeGraph.nextSelectionAfterDelete(
    deletedId: String,
    preferredId: String?,
    remainingNodes: Set<String>,
): String? {
    if (selectedNodeId != deletedId && selectedNodeId in remainingNodes) return selectedNodeId
    return preferredId?.takeIf { it in remainingNodes }
}
