package com.tajuli.digitorandroid.editor.model

import java.util.ArrayDeque
import java.util.PriorityQueue

/**
 * Reusable topology plan for spatial node effects.
 *
 * Unlike color, spatial effects operate on whole frame textures/pixel buffers. The plan therefore
 * exposes input slots and parallel-mixer branch/base slots so GPU and CPU compositors can execute
 * the same graph without flattening parallel branches into serial order.
 */
data class SpatialGraphOperation(
    val slot: Int,
    val node: ColorNode,
    val inputSlot: Int = -1,
    val mixerInputSlots: IntArray = IntArray(0),
    val mixerBaseSlot: Int = -1,
)

class SpatialNodeGraphPlan private constructor(
    val operations: List<SpatialGraphOperation>,
    val outputSlot: Int,
) {
    /** Maximum number of off-screen render targets needed during one frame. */
    val maximumScratchPasses: Int
        get() = operations.count { it.node.kind == NodeKind.SERIAL || it.node.kind == NodeKind.PARALLEL } +
            operations.filter { it.node.kind == NodeKind.MIX }.sumOf { it.mixerInputSlots.size }

    companion object {
        fun compile(graph: ClipNodeGraph): SpatialNodeGraphPlan {
            if (graph.nodes.isEmpty()) return SpatialNodeGraphPlan(emptyList(), -1)

            val byId = graph.nodes.associateBy { it.id }
            val validEdges = graph.edges.filter { it.fromId in byId && it.toId in byId }
            val incomingById = validEdges.groupBy { it.toId }
            val outgoingById = validEdges.groupBy { it.fromId }
            val indegree = graph.nodes.associate { node ->
                node.id to incomingById[node.id].orEmpty().size
            }.toMutableMap()

            val queue = PriorityQueue<ColorNode>(
                compareBy<ColorNode> { it.position.x }
                    .thenBy { it.position.y }
                    .thenBy { it.id },
            )
            graph.nodes.filter { indegree[it.id] == 0 }.forEach(queue::add)

            val ordered = mutableListOf<ColorNode>()
            while (queue.isNotEmpty()) {
                val node = queue.remove()
                ordered += node
                outgoingById[node.id].orEmpty().forEach { edge ->
                    val next = (indegree[edge.toId] ?: 0) - 1
                    indegree[edge.toId] = next
                    if (next == 0) byId[edge.toId]?.let(queue::add)
                }
            }

            // Malformed/cyclic graphs are still deterministic and safe: later forward references
            // simply fall back to the original frame in the compositor.
            if (ordered.size != graph.nodes.size) {
                val emitted = ordered.mapTo(mutableSetOf()) { it.id }
                ordered += graph.nodes
                    .filterNot { it.id in emitted }
                    .sortedWith(
                        compareBy<ColorNode> { it.position.x }
                            .thenBy { it.position.y }
                            .thenBy { it.id },
                    )
            }

            val slotById = ordered.mapIndexed { index, node -> node.id to index }.toMap()
            val operations = ordered.mapIndexed { slot, node ->
                val incoming = incomingById[node.id].orEmpty()
                if (node.kind == NodeKind.MIX) {
                    val branchIds = incoming.map { it.fromId }.distinct()
                    val commonBaseId = nearestCommonAncestor(branchIds, incomingById)
                    SpatialGraphOperation(
                        slot = slot,
                        node = node,
                        mixerInputSlots = branchIds.mapNotNull { slotById[it] }.toIntArray(),
                        mixerBaseSlot = commonBaseId?.let { slotById[it] } ?: -1,
                    )
                } else {
                    SpatialGraphOperation(
                        slot = slot,
                        node = node,
                        inputSlot = incoming.firstOrNull()?.fromId?.let { slotById[it] } ?: -1,
                    )
                }
            }

            val outputSlot = ordered.indexOfFirst { it.kind == NodeKind.OUTPUT }
                .takeIf { it >= 0 }
                ?: ordered.lastIndex
            return SpatialNodeGraphPlan(operations, outputSlot)
        }

        private fun nearestCommonAncestor(
            branchIds: List<String>,
            incomingById: Map<String, List<NodeEdge>>,
        ): String? {
            if (branchIds.isEmpty()) return null
            val distanceMaps = branchIds.map { ancestorDistances(it, incomingById) }
            val common = distanceMaps.first().keys.toMutableSet()
            distanceMaps.drop(1).forEach { distances -> common.retainAll(distances.keys) }
            if (common.isEmpty()) return null

            return common.minWithOrNull(
                compareBy<String> { candidate ->
                    distanceMaps.maxOf { distances -> distances[candidate] ?: Int.MAX_VALUE / 4 }
                }.thenBy { candidate ->
                    distanceMaps.sumOf { distances -> distances[candidate] ?: Int.MAX_VALUE / 4 }
                },
            )
        }

        private fun ancestorDistances(
            startId: String,
            incomingById: Map<String, List<NodeEdge>>,
        ): Map<String, Int> {
            val distances = mutableMapOf(startId to 0)
            val queue = ArrayDeque<String>()
            queue.add(startId)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                val nextDistance = (distances[current] ?: 0) + 1
                incomingById[current].orEmpty().forEach { edge ->
                    val previous = distances[edge.fromId]
                    if (previous == null || nextDistance < previous) {
                        distances[edge.fromId] = nextDistance
                        queue.addLast(edge.fromId)
                    }
                }
            }
            return distances
        }
    }
}
