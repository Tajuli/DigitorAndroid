package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.tajuli.digitorandroid.editor.model.ClipStabilizationV90
import com.tajuli.digitorandroid.editor.model.StabilizationPathSampleV90
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineVisualMediaV21
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Offline, gyro-free camera-motion analyzer for V90 stabilization.
 *
 * V91 deliberately reuses the editor's sequential MediaCodec/OES decoder instead of issuing one
 * MediaMetadataRetriever seek per sample. A trimmed clip is decoded once from left to right, selected
 * frames are read back at a small analysis resolution, textured grid patches are matched coarse-to-
 * fine, outliers are rejected, and a robust 2D similarity camera path is accumulated in absolute
 * source time. Rendering performs smoothing, so changing stabilization controls stays instant.
 */
class ResolveStabilizationAnalyzerV90(context: Context) {
    private val appContext = context.applicationContext

    suspend fun analyze(
        clip: TimelineClip,
        base: ClipStabilizationV90 = clip.stabilizationV90 ?: ClipStabilizationV90(),
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): ClipStabilizationV90 = withContext(Dispatchers.Default) {
        require(clip.visualMediaV21 != TimelineVisualMediaV21.IMAGE) {
            "Stabilization requires a moving-video clip"
        }

        val startUs = clip.sourceInUs.coerceAtLeast(0L)
        val endUs = clip.sourceOutUs.coerceAtLeast(startUs + 1L)
        val durationUs = endUs - startUs
        val intervalUs = max(
            MIN_SAMPLE_INTERVAL_US,
            ceil(durationUs.toDouble() / MAX_ANALYSIS_SAMPLES).toLong(),
        )
        val targetTimesUs = buildList {
            var t = startUs
            while (t < endUs) {
                add(t)
                t += intervalUs
            }
            if (isEmpty() || last() != endUs - 1L) add(endUs - 1L)
        }
        require(targetTimesUs.size >= 2) { "Clip is too short to stabilize" }

        onProgress(0f, "Preparing stabilization decoder…")

        val samples = ArrayList<StabilizationPathSampleV90>(targetTimesUs.size)
        var previousGray: IntArray? = null
        var analyzedWidth = 0
        var analyzedHeight = 0
        var pathX = 0f
        var pathY = 0f
        var pathRotation = 0f
        var pathLogScale = 0f

        val decoder = GpuSequentialCutoutDecoderV47(
            context = appContext,
            analysisLongEdge = ANALYSIS_LONG_SIDE,
        )
        decoder.decodeTargets(
            uri = Uri.parse(clip.uri),
            startUs = startUs,
            endUs = endUs,
            targetTimesUs = targetTimesUs,
            emitEveryFrame = false,
        ) { sourceTimeUs, bitmap ->
            try {
                val width = bitmap.width.coerceAtLeast(1)
                val height = bitmap.height.coerceAtLeast(1)
                analyzedWidth = width
                analyzedHeight = height
                val current = bitmap.toGrayV90()

                val previous = previousGray
                val confidence: Float
                if (previous == null || previous.size != current.size) {
                    confidence = 1f
                } else {
                    val motion = estimateSimilarityV90(previous, current, width, height)
                    confidence = motion.confidence
                    if (motion.confidence >= MIN_ACCEPTED_CONFIDENCE) {
                        pathX += motion.txPx / (width * .5f)
                        pathY += motion.tyPx / (height * .5f)
                        pathRotation += motion.rotationDegrees
                        pathLogScale += ln(motion.scale.coerceIn(.92f, 1.08f))
                    }
                }

                samples += StabilizationPathSampleV90(
                    sourceTimeUs = sourceTimeUs,
                    pathX = pathX,
                    pathY = pathY,
                    rotationDegrees = pathRotation,
                    logScale = pathLogScale,
                    confidence = confidence,
                )
                previousGray = current

                val progress = ((sourceTimeUs - startUs).toDouble() / durationUs.toDouble())
                    .toFloat()
                    .coerceIn(0f, 1f)
                onProgress(
                    progress,
                    "Analyzing camera motion · ${(progress * 100f).roundToInt()}%",
                )
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }

        require(samples.size >= 2) {
            "Stabilization decoder could not read enough frames"
        }
        onProgress(1f, "Finishing stabilization…")

        base.copy(
            enabled = true,
            analyzedWidth = analyzedWidth,
            analyzedHeight = analyzedHeight,
            samples = samples,
        ).normalized()
    }

    private fun Bitmap.toGrayV90(): IntArray {
        val width = width.coerceAtLeast(1)
        val height = height.coerceAtLeast(1)
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return IntArray(pixels.size) { i ->
            val argb = pixels[i]
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF
            (r * 77 + g * 150 + b * 29) ushr 8
        }
    }

    private data class CandidateV90(val x: Int, val y: Int, val texture: Int)
    private data class MatchV90(
        val px: Float,
        val py: Float,
        val qx: Float,
        val qy: Float,
        val sad: Float,
    )
    private data class SimilarityV90(
        val txPx: Float = 0f,
        val tyPx: Float = 0f,
        val rotationDegrees: Float = 0f,
        val scale: Float = 1f,
        val confidence: Float = 0f,
    )

    private fun estimateSimilarityV90(
        previous: IntArray,
        current: IntArray,
        width: Int,
        height: Int,
    ): SimilarityV90 {
        val margin = SEARCH_RADIUS + PATCH_RADIUS + 2
        if (width <= margin * 2 || height <= margin * 2) return SimilarityV90()

        val candidates = ArrayList<CandidateV90>()
        for (gy in 1..5) {
            val y = margin + ((height - margin * 2) * gy / 6f).roundToInt()
            for (gx in 1..7) {
                val x = margin + ((width - margin * 2) * gx / 8f).roundToInt()
                candidates += CandidateV90(x, y, textureScoreV90(previous, width, x, y))
            }
        }

        val chosen = candidates.sortedByDescending { it.texture }.take(MAX_FEATURES)
        val matches = chosen.mapNotNull { candidate ->
            matchPatchV90(previous, current, width, height, candidate.x, candidate.y)
        }
        if (matches.size < MIN_FEATURES) {
            return SimilarityV90(confidence = matches.size / MIN_FEATURES.toFloat() * .15f)
        }

        val medianDx = medianV90(matches.map { it.qx - it.px })
        val medianDy = medianV90(matches.map { it.qy - it.py })
        val robust = matches.filter {
            abs((it.qx - it.px) - medianDx) <= OUTLIER_RADIUS &&
                abs((it.qy - it.py) - medianDy) <= OUTLIER_RADIUS
        }
        if (robust.size < MIN_FEATURES) {
            return SimilarityV90(
                txPx = medianDx,
                tyPx = medianDy,
                confidence = (robust.size.toFloat() / MAX_FEATURES.toFloat()).coerceIn(0f, .28f),
            )
        }

        val pCx = robust.sumOf { it.px.toDouble() }.toFloat() / robust.size
        val pCy = robust.sumOf { it.py.toDouble() }.toFloat() / robust.size
        val qCx = robust.sumOf { it.qx.toDouble() }.toFloat() / robust.size
        val qCy = robust.sumOf { it.qy.toDouble() }.toFloat() / robust.size

        var dot = 0.0
        var cross = 0.0
        var denom = 0.0
        robust.forEach { point ->
            val px = (point.px - pCx).toDouble()
            val py = (point.py - pCy).toDouble()
            val qx = (point.qx - qCx).toDouble()
            val qy = (point.qy - qCy).toDouble()
            dot += px * qx + py * qy
            cross += px * qy - py * qx
            denom += px * px + py * py
        }

        if (denom <= 1e-5) {
            return SimilarityV90(txPx = medianDx, tyPx = medianDy, confidence = .25f)
        }

        val a = dot / denom
        val b = cross / denom
        val scale = sqrt(a * a + b * b).toFloat()
        val rotation = Math.toDegrees(atan2(b, a)).toFloat()
        val tx = qCx - (a * pCx - b * pCy).toFloat()
        val ty = qCy - (b * pCx + a * pCy).toFloat()

        // The render transform rotates around frame center. Convert the fitted top-left-origin
        // transform to motion of that same center so rotation is not double-counted as translation.
        val frameCx = (width - 1) * .5f
        val frameCy = (height - 1) * .5f
        val mappedCx = (a * frameCx - b * frameCy).toFloat() + tx
        val mappedCy = (b * frameCx + a * frameCy).toFloat() + ty
        val centerDx = mappedCx - frameCx
        val centerDy = mappedCy - frameCy
        val averageSad = robust.sumOf { it.sad.toDouble() }.toFloat() / robust.size

        if (scale !in .92f..1.08f || abs(rotation) > MAX_STEP_ROTATION_DEGREES) {
            return SimilarityV90(confidence = .05f)
        }

        val featureConfidence = (robust.size.toFloat() / MAX_FEATURES.toFloat()).coerceIn(0f, 1f)
        val photometricConfidence = (1f - averageSad / REJECT_SAD).coerceIn(0f, 1f)
        return SimilarityV90(
            txPx = centerDx.coerceIn(-SEARCH_RADIUS.toFloat(), SEARCH_RADIUS.toFloat()),
            tyPx = centerDy.coerceIn(-SEARCH_RADIUS.toFloat(), SEARCH_RADIUS.toFloat()),
            rotationDegrees = rotation,
            scale = scale,
            confidence = (featureConfidence * .7f + photometricConfidence * .3f).coerceIn(0f, 1f),
        )
    }

    private fun matchPatchV90(
        previous: IntArray,
        current: IntArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
    ): MatchV90? {
        var bestDx = 0
        var bestDy = 0
        var best = Int.MAX_VALUE

        for (dy in -SEARCH_RADIUS..SEARCH_RADIUS step COARSE_STEP) {
            for (dx in -SEARCH_RADIUS..SEARCH_RADIUS step COARSE_STEP) {
                val score = patchSadV90(previous, current, width, height, x, y, dx, dy)
                if (score < best) {
                    best = score
                    bestDx = dx
                    bestDy = dy
                }
            }
        }

        val coarseX = bestDx
        val coarseY = bestDy
        for (dy in (coarseY - 2)..(coarseY + 2)) {
            for (dx in (coarseX - 2)..(coarseX + 2)) {
                if (abs(dx) > SEARCH_RADIUS || abs(dy) > SEARCH_RADIUS) continue
                val score = patchSadV90(previous, current, width, height, x, y, dx, dy)
                if (score < best) {
                    best = score
                    bestDx = dx
                    bestDy = dy
                }
            }
        }

        val samples = (PATCH_RADIUS * 2 + 1) * (PATCH_RADIUS * 2 + 1)
        val normalizedSad = best.toFloat() / samples.toFloat()
        if (normalizedSad > REJECT_SAD) return null
        return MatchV90(
            px = x.toFloat(),
            py = y.toFloat(),
            qx = (x + bestDx).toFloat(),
            qy = (y + bestDy).toFloat(),
            sad = normalizedSad,
        )
    }

    private fun patchSadV90(
        previous: IntArray,
        current: IntArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        dx: Int,
        dy: Int,
    ): Int {
        val qx = x + dx
        val qy = y + dy
        if (qx - PATCH_RADIUS < 0 || qx + PATCH_RADIUS >= width ||
            qy - PATCH_RADIUS < 0 || qy + PATCH_RADIUS >= height
        ) return Int.MAX_VALUE / 4

        var sad = 0
        for (py in -PATCH_RADIUS..PATCH_RADIUS) {
            val pRow = (y + py) * width
            val qRow = (qy + py) * width
            for (px in -PATCH_RADIUS..PATCH_RADIUS) {
                sad += abs(previous[pRow + x + px] - current[qRow + qx + px])
            }
        }
        return sad
    }

    private fun textureScoreV90(frame: IntArray, width: Int, x: Int, y: Int): Int {
        var score = 0
        for (dy in -4..4 step 2) {
            val row = (y + dy) * width
            for (dx in -4..4 step 2) {
                val center = frame[row + x + dx]
                score += abs(center - frame[row + x + dx + 1])
                score += abs(center - frame[row + width + x + dx])
            }
        }
        return score
    }

    private fun medianV90(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
        else (sorted[middle - 1] + sorted[middle]) * .5f
    }

    private companion object {
        const val ANALYSIS_LONG_SIDE = 192
        const val MAX_ANALYSIS_SAMPLES = 900
        const val MIN_SAMPLE_INTERVAL_US = 100_000L
        const val PATCH_RADIUS = 2
        const val SEARCH_RADIUS = 12
        const val COARSE_STEP = 2
        const val MAX_FEATURES = 16
        const val MIN_FEATURES = 5
        const val OUTLIER_RADIUS = 7f
        const val REJECT_SAD = 44f
        const val MIN_ACCEPTED_CONFIDENCE = .22f
        const val MAX_STEP_ROTATION_DEGREES = 7.5f
    }
}
