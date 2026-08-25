package com.tajuli.digitorandroid.editor.processing

import com.tajuli.digitorandroid.editor.model.ColorNode
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.SpatialNodeGraphPlan
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.visibleEffects
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * CPU reference/fallback implementation of Blur, Sharpen, Glow and Film Grain.
 *
 * Spatial nodes are executed from the real graph topology. Parallel branches receive the same
 * common-base pixels and Mix uses the same adjustment rule as the color graph:
 *
 *     mixed = base + sum(branch - base)
 *
 * This keeps CPU fallback behavior aligned with the GPU spatial graph compositor.
 */
class CpuNodeEffectsProcessor(
    workerCount: Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 8),
) : AutoCloseable {
    private val workerCount = workerCount.coerceIn(1, 16)
    private val workers = Executors.newFixedThreadPool(this.workerCount)

    fun processClipArgb8888(
        pixels: IntArray,
        width: Int,
        height: Int,
        clip: TimelineClip,
        sourceTimeUs: Long,
    ) {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return

        val plan = SpatialNodeGraphPlan.compile(clip.nodeGraph)
        if (plan.operations.isEmpty() || plan.outputSlot !in plan.operations.indices) return
        val slots = arrayOfNulls<IntArray>(plan.operations.size)

        plan.operations.forEach { operation ->
            when (operation.node.kind) {
                NodeKind.IMPORT -> slots[operation.slot] = pixels

                NodeKind.SERIAL, NodeKind.PARALLEL -> {
                    val input = slotOrSource(slots, operation.inputSlot, operation.slot, pixels)
                    val node = clip.nodeAnimations.evaluateNode(operation.node, sourceTimeUs)
                    if (!hasActiveEffects(node)) {
                        slots[operation.slot] = input
                    } else {
                        val output = IntArray(width * height)
                        renderNode(input, output, width, height, node, sourceTimeUs)
                        slots[operation.slot] = output
                    }
                }

                NodeKind.MIX -> {
                    val base = slotOrSource(slots, operation.mixerBaseSlot, operation.slot, pixels)
                    val branches = operation.mixerInputSlots
                        .filter { it in 0 until operation.slot }
                        .map { slots[it] ?: pixels }
                    if (branches.isEmpty() || branches.all { it === base }) {
                        slots[operation.slot] = base
                    } else {
                        val output = IntArray(width * height)
                        mixParallel(base, branches, output, width, height)
                        slots[operation.slot] = output
                    }
                }

                NodeKind.OUTPUT -> {
                    slots[operation.slot] = slotOrSource(
                        slots,
                        operation.inputSlot,
                        operation.slot,
                        pixels,
                    )
                }
            }
        }

        val output = slots[plan.outputSlot] ?: pixels
        if (output !== pixels) output.copyInto(pixels, endIndex = width * height)
    }

    private fun slotOrSource(
        slots: Array<IntArray?>,
        slot: Int,
        currentSlot: Int,
        source: IntArray,
    ): IntArray = if (slot in 0 until currentSlot) slots[slot] ?: source else source

    private fun hasActiveEffects(node: ColorNode): Boolean =
        node.visibleEffects().any { it.enabled && it.amount > 0f }

    private fun renderNode(
        source: IntArray,
        output: IntArray,
        width: Int,
        height: Int,
        node: ColorNode,
        sourceTimeUs: Long,
    ) {
        val effects = node.visibleEffects()
        fun amount(name: String): Float = effects
            .firstOrNull { it.name.equals(name, ignoreCase = true) && it.enabled }
            ?.amount
            ?.coerceIn(0f, 1f)
            ?: 0f

        val blur = amount("Blur")
        val sharpen = amount("Sharpen")
        val glow = amount("Glow")
        val grain = amount("Film Grain")
        val radius = (1f + blur * 4f + glow * 2f).toInt().coerceIn(1, 7)
        val frameSeed = ((sourceTimeUs / 33_333L).toInt() * 31) xor node.id.hashCode()
        val stripe = (height / workerCount).coerceAtLeast(1)
        val jobs = mutableListOf<Callable<Unit>>()
        var y = 0
        while (y < height) {
            val startY = y
            val endY = (y + stripe).coerceAtMost(height)
            jobs += Callable {
                for (py in startY until endY) {
                    for (px in 0 until width) {
                        val center = source[py * width + px]
                        val centerRgb = rgb(center)
                        val n = rgb(sample(source, width, height, px, py - radius))
                        val s = rgb(sample(source, width, height, px, py + radius))
                        val e = rgb(sample(source, width, height, px + radius, py))
                        val w = rgb(sample(source, width, height, px - radius, py))
                        val ne = rgb(sample(source, width, height, px + radius, py - radius))
                        val nw = rgb(sample(source, width, height, px - radius, py - radius))
                        val se = rgb(sample(source, width, height, px + radius, py + radius))
                        val sw = rgb(sample(source, width, height, px - radius, py + radius))

                        val blurred = FloatArray(3) { channel ->
                            (centerRgb[channel] * 4f +
                                (n[channel] + s[channel] + e[channel] + w[channel]) * 2f +
                                ne[channel] + nw[channel] + se[channel] + sw[channel]) / 16f
                        }
                        val blurMix = (blur * .92f).coerceIn(0f, .92f)
                        val out = FloatArray(3) { channel ->
                            centerRgb[channel] + (blurred[channel] - centerRgb[channel]) * blurMix
                        }

                        for (channel in 0..2) {
                            val cross = (n[channel] + s[channel] + e[channel] + w[channel]) * .25f
                            out[channel] += (centerRgb[channel] - cross) * sharpen * 1.45f
                        }

                        val glowLuma = blurred[0] * .2126f + blurred[1] * .7152f + blurred[2] * .0722f
                        val glowMask = smoothstep(.48f, .88f, glowLuma)
                        for (channel in 0..2) out[channel] += blurred[channel] * glowMask * glow * .72f

                        if (grain > 0f) {
                            val noise = noise(px, py, frameSeed)
                            val luma = out[0] * .2126f + out[1] * .7152f + out[2] * .0722f
                            val weight = .55f + .45f * (1f - abs(luma * 2f - 1f))
                            val delta = noise * grain * .10f * weight
                            for (channel in 0..2) out[channel] += delta
                        }

                        val a = (center ushr 24) and 0xFF
                        val r = (out[0].coerceIn(0f, 1f) * 255f + .5f).toInt()
                        val g = (out[1].coerceIn(0f, 1f) * 255f + .5f).toInt()
                        val b = (out[2].coerceIn(0f, 1f) * 255f + .5f).toInt()
                        output[py * width + px] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
            }
            y = endY
        }
        workers.invokeAll(jobs).forEach { it.get() }
    }

    private fun mixParallel(
        base: IntArray,
        branches: List<IntArray>,
        output: IntArray,
        width: Int,
        height: Int,
    ) {
        val stripe = (height / workerCount).coerceAtLeast(1)
        val jobs = mutableListOf<Callable<Unit>>()
        var y = 0
        while (y < height) {
            val startY = y
            val endY = (y + stripe).coerceAtMost(height)
            jobs += Callable {
                var index = startY * width
                val end = endY * width
                while (index < end) {
                    val baseArgb = base[index]
                    val baseR = (baseArgb ushr 16) and 0xFF
                    val baseG = (baseArgb ushr 8) and 0xFF
                    val baseB = baseArgb and 0xFF
                    var mixedR = baseR.toFloat()
                    var mixedG = baseG.toFloat()
                    var mixedB = baseB.toFloat()
                    branches.forEach { branch ->
                        if (branch !== base) {
                            val argb = branch[index]
                            mixedR += ((argb ushr 16) and 0xFF) - baseR
                            mixedG += ((argb ushr 8) and 0xFF) - baseG
                            mixedB += (argb and 0xFF) - baseB
                        }
                    }
                    val a = (baseArgb ushr 24) and 0xFF
                    val r = (mixedR + .5f).toInt().coerceIn(0, 255)
                    val g = (mixedG + .5f).toInt().coerceIn(0, 255)
                    val b = (mixedB + .5f).toInt().coerceIn(0, 255)
                    output[index] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    index++
                }
            }
            y = endY
        }
        workers.invokeAll(jobs).forEach { it.get() }
    }

    private fun sample(pixels: IntArray, width: Int, height: Int, x: Int, y: Int): Int {
        val sx = x.coerceIn(0, width - 1)
        val sy = y.coerceIn(0, height - 1)
        return pixels[sy * width + sx]
    }

    private fun rgb(argb: Int): FloatArray = floatArrayOf(
        ((argb ushr 16) and 0xFF) / 255f,
        ((argb ushr 8) and 0xFF) / 255f,
        (argb and 0xFF) / 255f,
    )

    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a).coerceAtLeast(.000001f)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun noise(x: Int, y: Int, seed: Int): Float {
        var h = x * 374_761_393 + y * 668_265_263 + seed * 362_437
        h = (h xor (h ushr 13)) * 1_274_126_177
        h = h xor (h ushr 16)
        return ((h ushr 8) and 0xFFFF) / 32767.5f - 1f
    }

    override fun close() {
        workers.shutdown()
    }
}
