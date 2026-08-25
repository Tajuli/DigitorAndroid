package com.tajuli.digitorandroid.editor.processing

import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.visibleEffects
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs

/** CPU reference/fallback implementation of Blur, Sharpen, Glow and Film Grain. */
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

        val nodes = clip.nodeGraph.nodes
            .asSequence()
            .filter { it.kind == NodeKind.SERIAL || it.kind == NodeKind.PARALLEL }
            .sortedWith(compareBy({ it.position.x }, { it.position.y }))
            .toList()

        nodes.forEach { baseNode ->
            val node = clip.nodeAnimations.evaluateNode(baseNode, sourceTimeUs)
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
            if (blur <= 0f && sharpen <= 0f && glow <= 0f && grain <= 0f) return@forEach

            val source = pixels.copyOf()
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

                            val blurred = FloatArray(3) { c ->
                                (centerRgb[c] * 4f + (n[c] + s[c] + e[c] + w[c]) * 2f +
                                    ne[c] + nw[c] + se[c] + sw[c]) / 16f
                            }
                            val mix = (blur * .92f).coerceIn(0f, .92f)
                            val out = FloatArray(3) { c -> centerRgb[c] + (blurred[c] - centerRgb[c]) * mix }

                            for (c in 0..2) {
                                val cross = (n[c] + s[c] + e[c] + w[c]) * .25f
                                out[c] += (centerRgb[c] - cross) * sharpen * 1.45f
                            }

                            val glowLuma = blurred[0] * .2126f + blurred[1] * .7152f + blurred[2] * .0722f
                            val glowMask = smoothstep(.48f, .88f, glowLuma)
                            for (c in 0..2) out[c] += blurred[c] * glowMask * glow * .72f

                            if (grain > 0f) {
                                val g = noise(px, py, frameSeed)
                                val luma = out[0] * .2126f + out[1] * .7152f + out[2] * .0722f
                                val weight = .55f + .45f * (1f - abs(luma * 2f - 1f))
                                val delta = g * grain * .10f * weight
                                for (c in 0..2) out[c] += delta
                            }

                            val a = (center ushr 24) and 0xFF
                            val r = (out[0].coerceIn(0f, 1f) * 255f + .5f).toInt()
                            val g = (out[1].coerceIn(0f, 1f) * 255f + .5f).toInt()
                            val b = (out[2].coerceIn(0f, 1f) * 255f + .5f).toInt()
                            pixels[py * width + px] = (a shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    }
                }
                y = endY
            }
            workers.invokeAll(jobs).forEach { it.get() }
        }
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
