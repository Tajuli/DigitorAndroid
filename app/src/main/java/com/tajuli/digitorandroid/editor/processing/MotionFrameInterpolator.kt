package com.tajuli.digitorandroid.editor.processing

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/** Bounded block-motion estimation with bidirectional warping. No model download or proprietary code. */
internal class MotionFrameInterpolator(private val workingEdge: Int = 160) {
    class Pair internal constructor(
        private val first: IntArray, private val second: IntArray,
        private val width: Int, private val height: Int,
        private val cols: Int, private val rows: Int,
        private val dx: FloatArray, private val dy: FloatArray,
        private val cut: Boolean,
    ) {
        fun render(amount: Float, out: IntArray, cancelled: () -> Boolean = { false }): IntArray {
            require(out.size == width * height)
            val t = amount.coerceIn(0f, 1f)
            if (t == 0f || t == 1f || cut) {
                (if (t < .5f) first else second).copyInto(out)
                return out
            }
            for (y in 0 until height) {
                checkCancelled(cancelled)
                for (x in 0 until width) {
                    val gx = ((x + .5f) / width * cols - .5f).coerceIn(0f, (cols - 1).toFloat())
                    val gy = ((y + .5f) / height * rows - .5f).coerceIn(0f, (rows - 1).toFloat())
                    val vx = vector(dx, cols, rows, gx, gy)
                    val vy = vector(dy, cols, rows, gx, gy)
                    val a = pixel(first, width, height, x - t * vx, y - t * vy)
                    val b = pixel(second, width, height, x + (1 - t) * vx, y + (1 - t) * vy)
                    // Unmatched/occluded pixels use the nearer source, avoiding a double silhouette.
                    val mismatch = abs(luma(a) - luma(b))
                    out[y * width + x] = if (mismatch > 48) { if (t < .5f) a else b } else mix(a, b, t)
                }
            }
            return out
        }
    }

    fun prepare(first: IntArray, second: IntArray, width: Int, height: Int,
                cancelled: () -> Boolean = { false }): Pair {
        require(width > 0 && height > 0 && first.size == width * height && second.size == first.size)
        val scale = min(1.0, workingEdge.coerceIn(48, 256).toDouble() / maxOf(width, height))
        val w = (width * scale).roundToInt().coerceAtLeast(1)
        val h = (height * scale).roundToInt().coerceAtLeast(1)
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        for (y in 0 until h) {
            checkCancelled(cancelled)
            for (x in 0 until w) {
                val index = (y * height / h) * width + x * width / w
                a[y * w + x] = luma(first[index]); b[y * w + x] = luma(second[index])
            }
        }
        fun error(cx: Int, cy: Int, vx: Int, vy: Int, radius: Int, step: Int): Float {
            var sum = 0L; var count = 0
            for (y in maxOf(0, cy - radius)..minOf(h - 1, cy + radius) step step) {
                for (x in maxOf(0, cx - radius)..minOf(w - 1, cx + radius) step step) {
                    if (x + vx !in 0 until w || y + vy !in 0 until h) continue
                    sum += abs(a[y * w + x] - b[(y + vy) * w + x + vx]); count++
                }
            }
            return if (count < 4) Float.MAX_VALUE else sum.toFloat() / count
        }
        var globalX = 0; var globalY = 0
        var globalError = error(w / 2, h / 2, 0, 0, maxOf(w, h), 4)
        for (vy in -12..12 step 2) {
            checkCancelled(cancelled)
            for (vx in -12..12 step 2) {
                val cost = error(w / 2, h / 2, vx, vy, maxOf(w, h), 4) + .015f * (abs(vx) + abs(vy))
                if (cost < globalError) { globalError = cost; globalX = vx; globalY = vy }
            }
        }
        val cols = ceil(w / 8.0).toInt(); val rows = ceil(h / 8.0).toInt()
        val dx = FloatArray(cols * rows); val dy = FloatArray(dx.size)
        for (gy in 0 until rows) {
            checkCancelled(cancelled)
            for (gx in 0 until cols) {
                val cx = minOf(w - 1, gx * 8 + 4); val cy = minOf(h - 1, gy * 8 + 4)
                var bx = globalX; var by = globalY
                var best = error(cx, cy, bx, by, 6, 2)
                for (vy in globalY - 3..globalY + 3) for (vx in globalX - 3..globalX + 3) {
                    val score = error(cx, cy, vx, vy, 6, 2) + .08f * (abs(vx - globalX) + abs(vy - globalY))
                    if (score < best) { best = score; bx = vx; by = vy }
                }
                dx[gy * cols + gx] = bx * width.toFloat() / w
                dy[gy * cols + gx] = by * height.toFloat() / h
            }
        }
        return Pair(first, second, width, height, cols, rows, dx, dy, globalError > 55f)
    }

    private companion object {
        fun checkCancelled(cancelled: () -> Boolean) {
            if (cancelled() || Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
        }
        fun luma(c: Int): Int = (((c ushr 16 and 255) * 77 + (c ushr 8 and 255) * 150 + (c and 255) * 29) ushr 8)
        fun mix(a: Int, b: Int, t: Float): Int {
            var result = 0
            for (shift in 0..24 step 8) {
                val value = ((a ushr shift and 255) * (1 - t) + (b ushr shift and 255) * t).roundToInt()
                result = result or (value.coerceIn(0, 255) shl shift)
            }
            return result
        }
        fun vector(values: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
            val ix = x.toInt(); val iy = y.toInt(); val nx = minOf(w - 1, ix + 1); val ny = minOf(h - 1, iy + 1)
            val fx = x - ix; val fy = y - iy
            return (values[iy * w + ix] * (1 - fx) + values[iy * w + nx] * fx) * (1 - fy) +
                (values[ny * w + ix] * (1 - fx) + values[ny * w + nx] * fx) * fy
        }
        fun pixel(values: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
            val px = x.coerceIn(0f, (w - 1).toFloat()); val py = y.coerceIn(0f, (h - 1).toFloat())
            val ix = px.toInt(); val iy = py.toInt(); val nx = minOf(w - 1, ix + 1); val ny = minOf(h - 1, iy + 1)
            return mix(mix(values[iy * w + ix], values[iy * w + nx], px - ix),
                mix(values[ny * w + ix], values[ny * w + nx], px - ix), py - iy)
        }
    }
}
