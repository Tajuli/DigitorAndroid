package com.tajuli.digitorandroid.editor.processing

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * V53 portrait-topology cleanup for the GPU-generated soft matte.
 *
 * SelfieMulticlass can occasionally give a chair/headrest or another nearby background object a
 * weak person score. Temporal flow can then preserve that false island for several frames. This
 * pass intentionally runs on a small (<=384 px long edge) matte and is therefore tiny compared with
 * neural inference. It builds a high-confidence person core, erodes one pixel to break weak bridges,
 * keeps the dominant portrait component, then grows a short support margin so soft hijab/scarf,
 * fingers and anti-aliased edges are not clipped. Finally it fills only tiny, strongly-supported
 * pinholes; it never performs a broad grow that could pull the chair back in.
 */
internal fun cleanupPersonMatteTopologyV53(
    matte: Bitmap,
    targetLongEdge: Int = 384,
): Bitmap {
    check(!matte.isRecycled) { "Cannot clean a recycled person matte" }

    val sourceLongEdge = max(matte.width, matte.height).coerceAtLeast(1)
    val working = if (sourceLongEdge > targetLongEdge) {
        val scale = targetLongEdge.toFloat() / sourceLongEdge.toFloat()
        Bitmap.createScaledBitmap(
            matte,
            (matte.width * scale).roundToInt().coerceAtLeast(1),
            (matte.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    } else {
        matte.copy(Bitmap.Config.ARGB_8888, false)
            ?: error("Could not copy person matte for topology cleanup")
    }

    val width = working.width
    val height = working.height
    val size = width * height
    val pixels = IntArray(size)
    working.getPixels(pixels, 0, width, 0, 0, width, height)

    val alpha = IntArray(size)
    val strong = BooleanArray(size)
    for (i in 0 until size) {
        val a = Color.red(pixels[i]).coerceIn(0, 255)
        alpha[i] = a
        strong[i] = a >= 72
    }

    val eroded = BooleanArray(size)
    for (y in 1 until height - 1) {
        val row = y * width
        for (x in 1 until width - 1) {
            val center = row + x
            if (!strong[center]) continue
            var supported = true
            loop@ for (dy in -1..1) {
                val base = center + dy * width
                for (dx in -1..1) {
                    if (!strong[base + dx]) {
                        supported = false
                        break@loop
                    }
                }
            }
            eroded[center] = supported
        }
    }

    val labels = IntArray(size)
    val queue = IntArray(size)
    var nextLabel = 0
    var bestLabel = 0
    var bestArea = 0

    fun flood(seed: Int, label: Int): Int {
        var head = 0
        var tail = 0
        queue[tail++] = seed
        labels[seed] = label
        var area = 0
        while (head < tail) {
            val index = queue[head++]
            area++
            val y = index / width
            val x = index - y * width
            val y0 = maxOf(0, y - 1)
            val y1 = minOf(height - 1, y + 1)
            val x0 = maxOf(0, x - 1)
            val x1 = minOf(width - 1, x + 1)
            for (ny in y0..y1) {
                val base = ny * width
                for (nx in x0..x1) {
                    val ni = base + nx
                    if (ni == index || !eroded[ni] || labels[ni] != 0) continue
                    labels[ni] = label
                    queue[tail++] = ni
                }
            }
        }
        return area
    }

    for (i in 0 until size) {
        if (!eroded[i] || labels[i] != 0) continue
        nextLabel++
        val area = flood(i, nextLabel)
        if (area > bestArea) {
            bestArea = area
            bestLabel = nextLabel
        }
    }

    val minimumUsefulCore = max(24, size / 500)
    if (bestLabel == 0 || bestArea < minimumUsefulCore) return working

    val distance = ByteArray(size) { 127.toByte() }
    var head = 0
    var tail = 0
    for (i in 0 until size) {
        if (labels[i] == bestLabel) {
            distance[i] = 0
            queue[tail++] = i
        }
    }

    // 6 px at 384 is the same relative margin as the previous 5 px at 320, while the higher working
    // resolution better preserves scarf/hijab curvature and matches the 384 px semantic-hair stage.
    val supportRadius = 6
    while (head < tail) {
        val index = queue[head++]
        val d = distance[index].toInt()
        if (d >= supportRadius) continue
        val y = index / width
        val x = index - y * width
        fun offer(ni: Int) {
            if (distance[ni].toInt() <= d + 1) return
            distance[ni] = (d + 1).toByte()
            queue[tail++] = ni
        }
        if (x > 0) offer(index - 1)
        if (x + 1 < width) offer(index + 1)
        if (y > 0) offer(index - width)
        if (y + 1 < height) offer(index + width)
    }

    val filtered = IntArray(size)
    for (i in 0 until size) {
        filtered[i] = if (distance[i].toInt() <= supportRadius) alpha[i] else 0
    }

    val repaired = IntArray(size)
    for (y in 0 until height) {
        val upperPortrait = y < (height * 0.72f).toInt()
        val requiredNeighbours = if (upperPortrait) 4 else 5
        val cap = if (upperPortrait) 24 else 16
        for (x in 0 until width) {
            val index = y * width + x
            if (distance[index].toInt() > supportRadius) {
                repaired[index] = 0
                continue
            }
            val current = filtered[index]
            if (current >= 245) {
                repaired[index] = current
                continue
            }
            var strongNeighbours = 0
            var neighbourMax = current
            val y0 = maxOf(0, y - 1)
            val y1 = minOf(height - 1, y + 1)
            val x0 = maxOf(0, x - 1)
            val x1 = minOf(width - 1, x + 1)
            for (ny in y0..y1) {
                val base = ny * width
                for (nx in x0..x1) {
                    if (nx == x && ny == y) continue
                    val value = filtered[base + nx]
                    if (value >= 96) strongNeighbours++
                    if (value > neighbourMax) neighbourMax = value
                }
            }
            repaired[index] = if (strongNeighbours >= requiredNeighbours) {
                maxOf(current, minOf(neighbourMax, current + cap))
            } else {
                current
            }
        }
    }

    for (i in 0 until size) {
        val v = repaired[i].coerceIn(0, 255)
        pixels[i] = Color.argb(255, v, v, v)
    }
    working.setPixels(pixels, 0, width, 0, 0, width, height)
    return working
}
