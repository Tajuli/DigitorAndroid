package com.tajuli.digitorandroid.editor.processing

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val FLOW_LONG_EDGE_V45 = 192
private const val FLOW_BLOCK_SIZE_V45 = 12
private const val FLOW_PATCH_RADIUS_V45 = 3
private const val FLOW_SEARCH_RADIUS_V45 = 6
private const val FLOW_SCENE_CUT_MAD_V45 = 46f
private const val FLOW_SCENE_CUT_HIST_V45 = .42f
private const val FLOW_RESET_GAP_US_V45 = 1_200_000L

/**
 * Compact luminance frame used by the V45 local-motion estimator.
 * Keeping this representation independent from Android Bitmap makes the flow math deterministic
 * and unit-testable while the stabilizer itself remains Android-facing.
 */
internal data class LumaFrameV45(
    val width: Int,
    val height: Int,
    val values: IntArray,
) {
    init {
        require(width > 0 && height > 0)
        require(values.size == width * height)
    }

    operator fun get(x: Int, y: Int): Int = values[y * width + x]

    companion object {
        fun fromBitmap(bitmap: Bitmap): LumaFrameV45 {
            val longEdge = max(bitmap.width, bitmap.height).coerceAtLeast(1)
            val scale = if (longEdge <= FLOW_LONG_EDGE_V45) 1f
            else FLOW_LONG_EDGE_V45 / longEdge.toFloat()
            val width = (bitmap.width * scale).roundToInt().coerceAtLeast(16)
            val height = (bitmap.height * scale).roundToInt().coerceAtLeast(16)
            val scaled = if (width == bitmap.width && height == bitmap.height) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(bitmap, width, height, true)
            }
            try {
                val pixels = IntArray(width * height)
                scaled.getPixels(pixels, 0, width, 0, 0, width, height)
                val luma = IntArray(pixels.size)
                for (i in pixels.indices) {
                    val p = pixels[i]
                    // Integer BT.601-ish luma. It is intentionally cheap because analysis can run
                    // over hundreds of anchor frames on a phone.
                    luma[i] = (77 * Color.red(p) + 150 * Color.green(p) + 29 * Color.blue(p)) shr 8
                }
                return LumaFrameV45(width, height, luma)
            } finally {
                if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
            }
        }
    }
}

internal data class FlowSampleV45(
    val dx: Float,
    val dy: Float,
    val confidence: Float,
)

/**
 * A sparse block-motion grid with bilinear sampling. Sampling turns the local block vectors into a
 * dense per-pixel warp field for the matte. dx/dy point from a current pixel to the matching
 * location in the previous source frame.
 */
internal class SpatialMotionFieldV45 private constructor(
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    private val blockSize: Int,
    private val cols: Int,
    private val rows: Int,
    private val dx: FloatArray,
    private val dy: FloatArray,
    private val confidence: FloatArray,
) {
    fun sample(x: Float, y: Float): FlowSampleV45 {
        if (cols == 1 && rows == 1) {
            return FlowSampleV45(dx[0], dy[0], confidence[0])
        }
        val gx = ((x - blockSize * .5f) / blockSize.toFloat()).coerceIn(0f, (cols - 1).toFloat())
        val gy = ((y - blockSize * .5f) / blockSize.toFloat()).coerceIn(0f, (rows - 1).toFloat())
        val x0 = floor(gx).toInt().coerceIn(0, cols - 1)
        val y0 = floor(gy).toInt().coerceIn(0, rows - 1)
        val x1 = min(x0 + 1, cols - 1)
        val y1 = min(y0 + 1, rows - 1)
        val tx = gx - x0
        val ty = gy - y0

        fun bilerp(values: FloatArray): Float {
            val a = values[y0 * cols + x0]
            val b = values[y0 * cols + x1]
            val c = values[y1 * cols + x0]
            val d = values[y1 * cols + x1]
            val top = a + (b - a) * tx
            val bottom = c + (d - c) * tx
            return top + (bottom - top) * ty
        }
        return FlowSampleV45(
            dx = bilerp(dx),
            dy = bilerp(dy),
            confidence = bilerp(confidence).coerceIn(0f, 1f),
        )
    }

    companion object {
        fun estimate(current: LumaFrameV45, previous: LumaFrameV45): SpatialMotionFieldV45 {
            require(current.width == previous.width && current.height == previous.height)
            val block = FLOW_BLOCK_SIZE_V45
            val cols = max(1, (current.width + block - 1) / block)
            val rows = max(1, (current.height + block - 1) / block)
            val dx = FloatArray(cols * rows)
            val dy = FloatArray(cols * rows)
            val confidence = FloatArray(cols * rows)

            for (gy in 0 until rows) {
                for (gx in 0 until cols) {
                    val cx = (gx * block + block / 2).coerceIn(
                        FLOW_PATCH_RADIUS_V45,
                        max(FLOW_PATCH_RADIUS_V45, current.width - FLOW_PATCH_RADIUS_V45 - 1),
                    )
                    val cy = (gy * block + block / 2).coerceIn(
                        FLOW_PATCH_RADIUS_V45,
                        max(FLOW_PATCH_RADIUS_V45, current.height - FLOW_PATCH_RADIUS_V45 - 1),
                    )
                    val match = bestMatch(current, previous, cx, cy)
                    val index = gy * cols + gx
                    dx[index] = match.dx.toFloat()
                    dy[index] = match.dy.toFloat()
                    confidence[index] = match.confidence
                }
            }

            // Robust spatial regularization suppresses isolated bad vectors without forcing the
            // whole portrait to one global motion. This is important for independently moving hands,
            // shoulders and loose hair/hijab edges.
            val smoothDx = FloatArray(dx.size)
            val smoothDy = FloatArray(dy.size)
            val smoothConfidence = FloatArray(confidence.size)
            for (gy in 0 until rows) {
                for (gx in 0 until cols) {
                    val index = gy * cols + gx
                    val baseDx = dx[index]
                    val baseDy = dy[index]
                    var sumX = baseDx * (confidence[index] * 2f + .05f)
                    var sumY = baseDy * (confidence[index] * 2f + .05f)
                    var sumWeight = confidence[index] * 2f + .05f
                    var confWeight = confidence[index]
                    var confCount = 1f
                    for (ny in max(0, gy - 1)..min(rows - 1, gy + 1)) {
                        for (nx in max(0, gx - 1)..min(cols - 1, gx + 1)) {
                            if (nx == gx && ny == gy) continue
                            val n = ny * cols + nx
                            val distance = abs(dx[n] - baseDx) + abs(dy[n] - baseDy)
                            val robust = 1f / (1f + .45f * distance)
                            val w = confidence[n] * robust
                            sumX += dx[n] * w
                            sumY += dy[n] * w
                            sumWeight += w
                            confWeight += confidence[n] * robust
                            confCount += robust
                        }
                    }
                    smoothDx[index] = sumX / sumWeight.coerceAtLeast(.001f)
                    smoothDy[index] = sumY / sumWeight.coerceAtLeast(.001f)
                    smoothConfidence[index] = (confWeight / confCount.coerceAtLeast(.001f)).coerceIn(0f, 1f)
                }
            }
            return SpatialMotionFieldV45(
                sourceWidth = current.width,
                sourceHeight = current.height,
                blockSize = block,
                cols = cols,
                rows = rows,
                dx = smoothDx,
                dy = smoothDy,
                confidence = smoothConfidence,
            )
        }

        private data class BlockMatch(
            val dx: Int,
            val dy: Int,
            val confidence: Float,
        )

        private fun bestMatch(
            current: LumaFrameV45,
            previous: LumaFrameV45,
            cx: Int,
            cy: Int,
        ): BlockMatch {
            var best = Float.MAX_VALUE
            var second = Float.MAX_VALUE
            var bestDx = 0
            var bestDy = 0
            for (dy in -FLOW_SEARCH_RADIUS_V45..FLOW_SEARCH_RADIUS_V45) {
                val py = cy + dy
                if (py - FLOW_PATCH_RADIUS_V45 < 0 || py + FLOW_PATCH_RADIUS_V45 >= previous.height) continue
                for (dx in -FLOW_SEARCH_RADIUS_V45..FLOW_SEARCH_RADIUS_V45) {
                    val px = cx + dx
                    if (px - FLOW_PATCH_RADIUS_V45 < 0 || px + FLOW_PATCH_RADIUS_V45 >= previous.width) continue
                    val sad = patchSad(current, previous, cx, cy, px, py)
                    if (sad < best) {
                        second = best
                        best = sad
                        bestDx = dx
                        bestDy = dy
                    } else if (sad < second) {
                        second = sad
                    }
                }
            }
            if (!best.isFinite()) return BlockMatch(0, 0, 0f)

            val texture = patchTexture(current, cx, cy)
            val photoConfidence = (1f - best / 52f).coerceIn(0f, 1f)
            val uniqueness = if (!second.isFinite()) 0f
            else (((second - best) / (second + 1f)) * 4f).coerceIn(0f, 1f)
            val textureConfidence = (texture / 18f).coerceIn(0f, 1f)
            val confidence = (
                photoConfidence * (.28f + .42f * uniqueness + .30f * textureConfidence)
            ).coerceIn(0f, 1f)
            return BlockMatch(bestDx, bestDy, confidence)
        }

        private fun patchSad(
            current: LumaFrameV45,
            previous: LumaFrameV45,
            cx: Int,
            cy: Int,
            px: Int,
            py: Int,
        ): Float {
            var sum = 0
            var count = 0
            for (oy in -FLOW_PATCH_RADIUS_V45..FLOW_PATCH_RADIUS_V45) {
                for (ox in -FLOW_PATCH_RADIUS_V45..FLOW_PATCH_RADIUS_V45) {
                    sum += abs(current[cx + ox, cy + oy] - previous[px + ox, py + oy])
                    count++
                }
            }
            return sum.toFloat() / count.coerceAtLeast(1)
        }

        private fun patchTexture(frame: LumaFrameV45, cx: Int, cy: Int): Float {
            var sum = 0
            var count = 0
            val radius = FLOW_PATCH_RADIUS_V45
            for (oy in -radius until radius) {
                for (ox in -radius until radius) {
                    val x = (cx + ox).coerceIn(0, frame.width - 2)
                    val y = (cy + oy).coerceIn(0, frame.height - 2)
                    val here = frame[x, y]
                    sum += abs(here - frame[x + 1, y])
                    sum += abs(here - frame[x, y + 1])
                    count += 2
                }
            }
            return sum.toFloat() / count.coerceAtLeast(1)
        }
    }
}

/**
 * V45 temporal stabilizer: a local optical-flow-style block field is estimated from source luma,
 * interpolated into a dense warp, then used only where the current MODNet alpha is uncertain.
 * Strong foreground/background pixels always remain authoritative. Flow confidence, alpha
 * disagreement and scene-cut detection reject occlusions and bad matches rather than smearing them.
 */
internal class SpatialFlowTemporalMatteStabilizerV45 : AutoCloseable {
    private var previousMatte: Bitmap? = null
    private var previousLuma: LumaFrameV45? = null
    private var previousTimeUs: Long = Long.MIN_VALUE

    fun stabilize(
        source: Bitmap,
        current: Bitmap,
        sourceTimeUs: Long,
        strength: Float,
    ): Bitmap {
        val nowLuma = LumaFrameV45.fromBitmap(source)
        val oldMatte = previousMatte
        val oldLuma = previousLuma
        if (
            oldMatte == null || oldLuma == null ||
            oldMatte.width != current.width || oldMatte.height != current.height ||
            oldLuma.width != nowLuma.width || oldLuma.height != nowLuma.height ||
            sourceTimeUs <= previousTimeUs || sourceTimeUs - previousTimeUs > FLOW_RESET_GAP_US_V45 ||
            isSceneCut(oldLuma, nowLuma)
        ) {
            replacePrevious(current, nowLuma, sourceTimeUs)
            return current.copy(Bitmap.Config.ARGB_8888, false)
                ?: error("Could not copy portrait matte")
        }

        val s = strength.coerceIn(0f, .92f)
        if (s <= .001f) {
            replacePrevious(current, nowLuma, sourceTimeUs)
            return current.copy(Bitmap.Config.ARGB_8888, false)
                ?: error("Could not copy portrait matte")
        }

        val flow = SpatialMotionFieldV45.estimate(nowLuma, oldLuma)
        val currentPixels = IntArray(current.width * current.height)
        val previousPixels = IntArray(oldMatte.width * oldMatte.height)
        current.getPixels(currentPixels, 0, current.width, 0, 0, current.width, current.height)
        oldMatte.getPixels(previousPixels, 0, oldMatte.width, 0, 0, oldMatte.width, oldMatte.height)
        val out = IntArray(currentPixels.size)

        val matteToFlowX = (nowLuma.width - 1).toFloat() / max(1, current.width - 1).toFloat()
        val matteToFlowY = (nowLuma.height - 1).toFloat() / max(1, current.height - 1).toFloat()
        val flowToMatteX = max(1, current.width - 1).toFloat() / max(1, nowLuma.width - 1).toFloat()
        val flowToMatteY = max(1, current.height - 1).toFloat() / max(1, nowLuma.height - 1).toFloat()

        for (y in 0 until current.height) {
            val ly = y * matteToFlowY
            for (x in 0 until current.width) {
                val index = y * current.width + x
                val curr = Color.red(currentPixels[index]) / 255f
                // Do not temporally soften pixels MODNet is already certain about.
                if (curr <= .015f || curr >= .985f) {
                    out[index] = currentPixels[index]
                    continue
                }

                val lx = x * matteToFlowX
                val vector = flow.sample(lx, ly)
                if (vector.confidence < .08f) {
                    out[index] = currentPixels[index]
                    continue
                }
                val previousX = x + vector.dx * flowToMatteX
                val previousY = y + vector.dy * flowToMatteY
                val prev = sampleMatte(previousPixels, current.width, current.height, previousX, previousY)
                val disagreement = abs(curr - prev)
                val uncertainty = (4f * curr * (1f - curr)).coerceIn(0f, 1f)
                val agreement = (1f - disagreement / .34f).coerceIn(0f, 1f)
                // Occlusion/disocclusion gate: a large alpha disagreement means the current matte
                // must win even if the photometric flow looked plausible.
                val occlusionGate = when {
                    disagreement >= .55f -> 0f
                    disagreement <= .20f -> 1f
                    else -> (1f - (disagreement - .20f) / .35f).coerceIn(0f, 1f)
                }
                val temporalWeight = (
                    s * .78f * uncertainty * vector.confidence * (.22f + .78f * agreement) * occlusionGate
                ).coerceIn(0f, .78f)
                val alpha = (curr + (prev - curr) * temporalWeight).coerceIn(0f, 1f)
                val v = (alpha * 255f).roundToInt().coerceIn(0, 255)
                out[index] = Color.argb(255, v, v, v)
            }
        }

        val result = Bitmap.createBitmap(current.width, current.height, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, current.width, 0, 0, current.width, current.height)
        replacePrevious(result, nowLuma, sourceTimeUs)
        return result
    }

    private fun sampleMatte(
        pixels: IntArray,
        width: Int,
        height: Int,
        x: Float,
        y: Float,
    ): Float {
        val fx = x.coerceIn(0f, (width - 1).toFloat())
        val fy = y.coerceIn(0f, (height - 1).toFloat())
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val tx = fx - x0
        val ty = fy - y0
        fun alpha(px: Int, py: Int): Float = Color.red(pixels[py * width + px]) / 255f
        val top = alpha(x0, y0) + (alpha(x1, y0) - alpha(x0, y0)) * tx
        val bottom = alpha(x0, y1) + (alpha(x1, y1) - alpha(x0, y1)) * tx
        return (top + (bottom - top) * ty).coerceIn(0f, 1f)
    }

    private fun isSceneCut(previous: LumaFrameV45, current: LumaFrameV45): Boolean {
        if (previous.width != current.width || previous.height != current.height) return true
        val previousHistogram = IntArray(32)
        val currentHistogram = IntArray(32)
        var absDifference = 0L
        var count = 0
        val step = 2
        var y = 0
        while (y < current.height) {
            var x = 0
            while (x < current.width) {
                val old = previous[x, y]
                val now = current[x, y]
                previousHistogram[(old shr 3).coerceIn(0, 31)]++
                currentHistogram[(now shr 3).coerceIn(0, 31)]++
                absDifference += abs(now - old)
                count++
                x += step
            }
            y += step
        }
        val mad = absDifference.toFloat() / count.coerceAtLeast(1).toFloat()
        var histDistance = 0
        for (i in previousHistogram.indices) {
            histDistance += abs(previousHistogram[i] - currentHistogram[i])
        }
        val normalizedHistogramDistance = histDistance.toFloat() / (2f * count.coerceAtLeast(1))
        return mad >= FLOW_SCENE_CUT_MAD_V45 && normalizedHistogramDistance >= FLOW_SCENE_CUT_HIST_V45
    }

    private fun replacePrevious(bitmap: Bitmap, luma: LumaFrameV45, sourceTimeUs: Long) {
        previousMatte?.recycle()
        previousMatte = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            ?: error("Could not retain previous portrait matte")
        previousLuma = luma
        previousTimeUs = sourceTimeUs
    }

    override fun close() {
        previousMatte?.recycle()
        previousMatte = null
        previousLuma = null
    }
}
