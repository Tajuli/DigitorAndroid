package com.tajuli.digitorandroid.editor.processing

import android.graphics.Bitmap
import com.tajuli.digitorandroid.editor.model.CutoutModeV43
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.resolvedCutoutV43
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * CPU parity for the V46 fabric-aware GPU realism pass.
 *
 * Input is the already-cut-out ARGB frame. RGB still contains the source foreground/background
 * colours while alpha contains the portrait matte, so a compact joint-bilateral correction can
 * align soft alpha with visible cloth edges without re-running segmentation. Dehalo correction
 * preserves luminance much more strongly than chroma to keep hijab/fabric folds from looking
 * painted or plastic.
 *
 * The hot loop deliberately operates on packed ARGB ints. CPU fallback can touch millions of pixels
 * per frame, so allocating temporary RGB arrays per sample would create unacceptable GC pressure.
 */
object CpuFabricAwareCutoutRefineV46 {
    fun refine(source: Bitmap, clip: TimelineClip): Bitmap {
        val settings = clip.resolvedCutoutV43()
        if (settings.mode != CutoutModeV43.PERSON || source.width <= 1 || source.height <= 1) return source

        val width = source.width
        val height = source.height
        val input = IntArray(width * height)
        source.getPixels(input, 0, width, 0, 0, width, height)
        val out = input.copyOf()

        fun pixelAt(x0: Int, y0: Int): Int {
            val x = x0.coerceIn(0, width - 1)
            val y = y0.coerceIn(0, height - 1)
            return input[y * width + x]
        }

        fun alphaOf(pixel: Int): Float = ((pixel ushr 24) and 0xFF) / 255f
        fun redOf(pixel: Int): Float = ((pixel ushr 16) and 0xFF) / 255f
        fun greenOf(pixel: Int): Float = ((pixel ushr 8) and 0xFF) / 255f
        fun blueOf(pixel: Int): Float = (pixel and 0xFF) / 255f

        fun colorDistance2(a: Int, b: Int): Float {
            val dr = redOf(a) - redOf(b)
            val dg = greenOf(a) - greenOf(b)
            val db = blueOf(a) - blueOf(b)
            return dr * dr + dg * dg + db * db
        }

        fun colorDistance(a: Int, b: Int): Float = sqrt(colorDistance2(a, b))

        val offsets = arrayOf(
            intArrayOf(-1, 0, 82), intArrayOf(1, 0, 82),
            intArrayOf(0, -1, 82), intArrayOf(0, 1, 82),
            intArrayOf(-1, -1, 58), intArrayOf(1, -1, 58),
            intArrayOf(-1, 1, 58), intArrayOf(1, 1, 58),
        )

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val centerPixel = input[index]
                val alpha = alphaOf(centerPixel)
                if (alpha <= .002f || alpha >= .998f) continue

                var alphaSum = alpha
                var weightSum = 1f
                for (offset in offsets) {
                    val sample = pixelAt(x + offset[0], y + offset[1])
                    val spatial = offset[2] / 100f
                    val colorWeight = spatial / (1f + 22f * colorDistance2(centerPixel, sample))
                    alphaSum += alphaOf(sample) * colorWeight
                    weightSum += colorWeight
                }
                val guidedAlpha = alphaSum / weightSum.coerceAtLeast(.0001f)

                val left = pixelAt(x - 1, y)
                val right = pixelAt(x + 1, y)
                val down = pixelAt(x, y - 1)
                val up = pixelAt(x, y + 1)
                val sourceEdge = max(colorDistance(left, right), colorDistance(down, up))
                val visibleEdge = smoothstep(.018f, .16f, sourceEdge)
                val uncertainty = (4f * alpha * (1f - alpha)).coerceIn(0f, 1f)
                var refineStrength = (.24f + .34f * visibleEdge) * uncertainty
                refineStrength *= .88f - .18f * settings.edgeCleanV44.coerceIn(0f, 1f)
                val limitedGuided = guidedAlpha.coerceIn(alpha - .17f, alpha + .17f)
                val refinedAlpha = lerp(alpha, limitedGuided, refineStrength.coerceIn(0f, .58f))
                    .coerceIn(0f, 1f)

                var r = redOf(centerPixel)
                var g = greenOf(centerPixel)
                var b = blueOf(centerPixel)
                val outer = 1f - smoothstep(.56f, .88f, refinedAlpha)
                val edge = (4f * refinedAlpha * (1f - refinedAlpha)).coerceIn(0f, 1f) * outer
                if (edge > .001f && settings.dehaloV44 > .001f) {
                    val gx = alphaOf(pixelAt(x + 2, y)) - alphaOf(pixelAt(x - 2, y))
                    val gy = alphaOf(pixelAt(x, y + 2)) - alphaOf(pixelAt(x, y - 2))
                    val gradLength = sqrt(gx * gx + gy * gy)
                    if (gradLength > .001f) {
                        val reach = 2f + 1.5f * settings.dehaloV44
                        val ix = (x + gx / gradLength * reach).roundToInt().coerceIn(0, width - 1)
                        val iy = (y + gy / gradLength * reach).roundToInt().coerceIn(0, height - 1)
                        val interior = pixelAt(ix, iy)
                        val contamination = smoothstep(.025f, .20f, colorDistance(centerPixel, interior))
                        val w = (settings.dehaloV44 * edge * contamination * .54f).coerceIn(0f, 1f)
                        if (w > .0001f) {
                            val ir = redOf(interior)
                            val ig = greenOf(interior)
                            val ib = blueOf(interior)
                            val sourceY = .2126f * r + .7152f * g + .0722f * b
                            val interiorY = .2126f * ir + .7152f * ig + .0722f * ib
                            val sourceCb = b - sourceY
                            val sourceCr = r - sourceY
                            val interiorCb = ib - interiorY
                            val interiorCr = ir - interiorY
                            val outY = lerp(sourceY, interiorY, w * .16f)
                            val outCb = lerp(sourceCb, interiorCb, w * .68f)
                            val outCr = lerp(sourceCr, interiorCr, w * .68f)
                            r = (outY + outCr).coerceIn(0f, 1f)
                            b = (outY + outCb).coerceIn(0f, 1f)
                            g = ((outY - .2126f * r - .0722f * b) / .7152f).coerceIn(0f, 1f)
                        }
                    }
                }

                val aa = (refinedAlpha * 255f + .5f).toInt().coerceIn(0, 255)
                val rr = (r * 255f + .5f).toInt().coerceIn(0, 255)
                val gg = (g * 255f + .5f).toInt().coerceIn(0, 255)
                val bb = (b * 255f + .5f).toInt().coerceIn(0, 255)
                out[index] = (aa shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
        }

        return Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0).coerceAtLeast(.0001f)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)
}
