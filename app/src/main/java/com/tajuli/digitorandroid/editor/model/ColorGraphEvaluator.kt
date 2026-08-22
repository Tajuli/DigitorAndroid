package com.tajuli.digitorandroid.editor.model

import java.util.ArrayDeque
import java.util.PriorityQueue

/**
 * Compiles a clip node graph into a reusable per-pixel execution plan.
 *
 * Serial/Parallel correctors consume the RGB output of their connected input. A Parallel Mixer
 * evaluates every incoming branch from the same nearest common upstream source and combines the
 * branch adjustments with equal priority:
 *
 *     mixed = commonInput + sum(branchOutput - commonInput)
 *
 * This preserves an existing grade when an untouched parallel branch is added, keeps sibling
 * qualifiers independent, and makes parallel branch ordering irrelevant.
 */
object ColorGraphEvaluator {
    fun compile(graph: ClipNodeGraph): ColorGraphPlan = ColorGraphPlan.compile(graph)
}

class ColorGraphPlan private constructor(
    private val operations: List<Operation>,
    private val outputSlot: Int,
) {
    private data class Operation(
        val node: ColorNode,
        val inputSlot: Int = -1,
        val mixerInputSlots: IntArray = IntArray(0),
        val mixerBaseSlot: Int = -1,
    )

    private val scratchLocal = object : ThreadLocal<FloatArray>() {
        override fun initialValue(): FloatArray = FloatArray(operations.size * 3)
    }

    fun apply(
        r: Float,
        g: Float,
        b: Float,
        nodeTransform: (ColorNode, Float, Float, Float) -> FloatArray,
    ): FloatArray {
        val sourceR = r.coerceIn(0f, 1f)
        val sourceG = g.coerceIn(0f, 1f)
        val sourceB = b.coerceIn(0f, 1f)
        if (operations.isEmpty() || outputSlot !in operations.indices) {
            return floatArrayOf(sourceR, sourceG, sourceB)
        }

        val scratch = scratchLocal.get()
        operations.forEachIndexed { slot, operation ->
            val offset = slot * 3
            when (operation.node.kind) {
                NodeKind.IMPORT -> {
                    scratch[offset] = sourceR
                    scratch[offset + 1] = sourceG
                    scratch[offset + 2] = sourceB
                }

                NodeKind.SERIAL, NodeKind.PARALLEL -> {
                    val input = operation.inputSlot.takeIf { it in 0 until slot }
                    val inputOffset = (input ?: -1) * 3
                    val inR = if (input == null) sourceR else scratch[inputOffset]
                    val inG = if (input == null) sourceG else scratch[inputOffset + 1]
                    val inB = if (input == null) sourceB else scratch[inputOffset + 2]
                    val result = nodeTransform(operation.node, inR, inG, inB)
                    scratch[offset] = result[0].coerceIn(0f, 1f)
                    scratch[offset + 1] = result[1].coerceIn(0f, 1f)
                    scratch[offset + 2] = result[2].coerceIn(0f, 1f)
                }

                NodeKind.MIX -> {
                    val base = operation.mixerBaseSlot.takeIf { it in 0 until slot }
                    val baseOffset = (base ?: -1) * 3
                    val baseR = if (base == null) sourceR else scratch[baseOffset]
                    val baseG = if (base == null) sourceG else scratch[baseOffset + 1]
                    val baseB = if (base == null) sourceB else scratch[baseOffset + 2]
                    var mixedR = baseR
                    var mixedG = baseG
                    var mixedB = baseB
                    var validBranches = 0
                    operation.mixerInputSlots.forEach { branchSlot ->
                        if (branchSlot in 0 until slot) {
                            val branchOffset = branchSlot * 3
                            mixedR += scratch[branchOffset] - baseR
                            mixedG += scratch[branchOffset + 1] - baseG
                            mixedB += scratch[branchOffset + 2] - baseB
                            validBranches++
                        }
                    }
                    if (validBranches == 0) {
                        mixedR = baseR
                        mixedG = baseG
                        mixedB = baseB
                    }
                    scratch[offset] = mixedR.coerceIn(0f, 1f)
                    scratch[offset + 1] = mixedG.coerceIn(0f, 1f)
                    scratch[offset + 2] = mixedB.coerceIn(0f, 1f)
                }

                NodeKind.OUTPUT -> {
                    val input = operation.inputSlot.takeIf { it in 0 until slot }
                    val inputOffset = (input ?: -1) * 3
                    scratch[offset] = if (input == null) sourceR else scratch[inputOffset]
                    scratch[offset + 1] = if (input == null) sourceG else scratch[inputOffset + 1]
                    scratch[offset + 2] = if (input == null) sourceB else scratch[inputOffset + 2]
                }
            }
        }

        val outputOffset = outputSlot * 3
        return floatArrayOf(
            scratch[outputOffset],
            scratch[outputOffset + 1],
            scratch[outputOffset + 2],
        )
    }

    companion object {
        internal fun compile(graph: ClipNodeGraph): ColorGraphPlan {
            if (graph.nodes.isEmpty()) return ColorGraphPlan(emptyList(), -1)

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

            // A valid editor graph is acyclic. For a malformed graph, append the remaining nodes
            // deterministically; forward-reference guards in apply() safely fall back to source.
            if (ordered.size != graph.nodes.size) {
                val emitted = ordered.mapTo(mutableSetOf()) { it.id }
                ordered += graph.nodes
                    .filterNot { it.id in emitted }
                    .sortedWith(compareBy<ColorNode> { it.position.x }.thenBy { it.position.y }.thenBy { it.id })
            }

            val slotById = ordered.mapIndexed { index, node -> node.id to index }.toMap()
            val operations = ordered.map { node ->
                val incoming = incomingById[node.id].orEmpty()
                if (node.kind == NodeKind.MIX) {
                    val branchIds = incoming.map { it.fromId }.distinct()
                    val commonBaseId = nearestCommonAncestor(branchIds, incomingById)
                    Operation(
                        node = node,
                        mixerInputSlots = branchIds.mapNotNull { slotById[it] }.toIntArray(),
                        mixerBaseSlot = commonBaseId?.let { slotById[it] } ?: -1,
                    )
                } else {
                    Operation(
                        node = node,
                        inputSlot = incoming.firstOrNull()?.fromId?.let { slotById[it] } ?: -1,
                    )
                }
            }

            val outputSlot = ordered.indexOfFirst { it.kind == NodeKind.OUTPUT }
                .takeIf { it >= 0 }
                ?: ordered.lastIndex
            return ColorGraphPlan(operations, outputSlot)
        }

        private fun nearestCommonAncestor(
            branchIds: List<String>,
            incomingById: Map<String, List<NodeEdge>>,
        ): String? {
            if (branchIds.isEmpty()) return null
            val distanceMaps = branchIds.map { ancestorDistances(it, incomingById) }
            var common = distanceMaps.first().keys.toMutableSet()
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
