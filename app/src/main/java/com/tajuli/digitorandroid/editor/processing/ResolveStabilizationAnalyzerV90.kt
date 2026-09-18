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
        var segmentV93 = 0

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
                    if (motion.sceneCut) {
                        segmentV93++
                        pathX = 0f
                        pathY = 0f
                        pathRotation = 0f
                        pathLogScale = 0f
                    } else if (motion.confidence >= MIN_ACCEPTED_CONFIDENCE) {
                        pathX += motion.txPx / (width * .5f)
                        pathY += motion.tyPx / (height * .5f)
                        pathRotation += motion.rotationDegrees
                        pathLogScale += ln(motion.scale.coerceIn(MIN_STEP_SCALE, MAX_STEP_SCALE))
                    }
                }

                samples += StabilizationPathSampleV90(
                    sourceTimeUs = sourceTimeUs,
                    pathX = pathX,
                    pathY = pathY,
                    rotationDegrees = pathRotation,
                    logScale = pathLogScale,
                    confidence = confidence,
                    segmentV93 = segmentV93,
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
        val sceneCut: Boolean = false,
    )

    /**
     * V93 robust global motion:
     *  - evenly spread high-texture features
     *  - forward/backward match validation
     *  - sub-pixel patch refinement
     *  - deterministic two-point RANSAC
     *  - least-squares similarity refit on inliers
     *
     * This is intentionally stricter than the old median-translation filter. Wrong foreground locks
     * are more damaging to stabilization than dropping a weak frame, so low-confidence frames keep
     * the last camera path instead of injecting a bad transform.
     */
    private fun estimateSimilarityV90(
        previous: IntArray,
        current: IntArray,
        width: Int,
        height: Int,
    ): SimilarityV90 {
        val margin = SEARCH_RADIUS + PATCH_RADIUS + 3
        if (width <= margin * 2 || height <= margin * 2) return SimilarityV90()

        val frameDifference = frameDifferenceV93(previous, current)
        val candidates = ArrayList<CandidateV90>()
        for (gy in 1..GRID_ROWS) {
            val y = margin + ((height - margin * 2) * gy / (GRID_ROWS + 1f)).roundToInt()
            for (gx in 1..GRID_COLUMNS) {
                val x = margin + ((width - margin * 2) * gx / (GRID_COLUMNS + 1f)).roundToInt()
                candidates += CandidateV90(x, y, textureScoreV90(previous, width, x, y))
            }
        }

        val chosen = candidates
            .sortedByDescending { it.texture }
            .take(MAX_FEATURES)

        val matches = chosen.mapNotNull { candidate ->
            matchPatchV90(previous, current, width, height, candidate.x, candidate.y)
        }

        if (matches.size < MIN_FEATURES) {
            return SimilarityV90(
                confidence = (matches.size / MIN_FEATURES.toFloat() * .12f).coerceIn(0f, .12f),
                sceneCut = frameDifference >= SCENE_CUT_DIFFERENCE,
            )
        }

        val hypothesis = ransacSimilarityFitV93(matches)
            ?: return SimilarityV90(
                confidence = .05f,
                sceneCut = frameDifference >= SCENE_CUT_DIFFERENCE,
            )

        val inliers = matches.filter {
            reprojectionErrorV93(hypothesis, it) <= RANSAC_INLIER_ERROR_PX
        }
        if (inliers.size < MIN_FEATURES) {
            return SimilarityV90(
                confidence = .08f,
                sceneCut = frameDifference >= SCENE_CUT_DIFFERENCE,
            )
        }

        val fitted = fitSimilarityFitAllV93(inliers) ?: return SimilarityV90(confidence = .05f)
        val fittedPublic = fitted.asSimilarityV90()
        val averageError = inliers.sumOf { reprojectionErrorV93(fitted, it).toDouble() }.toFloat() / inliers.size
        val averageSad = inliers.sumOf { it.sad.toDouble() }.toFloat() / inliers.size
        val inlierRatio = inliers.size.toFloat() / matches.size.toFloat()

        if (
            fittedPublic.scale !in MIN_STEP_SCALE..MAX_STEP_SCALE ||
            abs(fittedPublic.rotationDegrees) > MAX_STEP_ROTATION_DEGREES
        ) {
            return SimilarityV90(
                confidence = .04f,
                sceneCut = frameDifference >= SCENE_CUT_DIFFERENCE && inlierRatio < .45f,
            )
        }

        val featureConfidence = (inliers.size.toFloat() / MAX_FEATURES.toFloat()).coerceIn(0f, 1f)
        val inlierConfidence = ((inlierRatio - .25f) / .75f).coerceIn(0f, 1f)
        val geometricConfidence = (1f - averageError / RANSAC_INLIER_ERROR_PX).coerceIn(0f, 1f)
        val photometricConfidence = (1f - averageSad / REJECT_SAD).coerceIn(0f, 1f)
        val confidence = (
            featureConfidence * .30f +
                inlierConfidence * .30f +
                geometricConfidence * .25f +
                photometricConfidence * .15f
            ).coerceIn(0f, 1f)

        val hardCut = frameDifference >= SCENE_CUT_DIFFERENCE &&
            (inlierRatio < .42f || confidence < .24f)

        return fittedPublic.copy(
            confidence = if (hardCut) 0f else confidence,
            sceneCut = hardCut,
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
        val forward = matchPatchOneWayV93(previous, current, width, height, x, y) ?: return null
        val reverse = matchPatchOneWayV93(
            current,
            previous,
            width,
            height,
            forward.qx.roundToInt(),
            forward.qy.roundToInt(),
        ) ?: return null

        val backErrorX = reverse.qx - x.toFloat()
        val backErrorY = reverse.qy - y.toFloat()
        val backError = sqrt(backErrorX * backErrorX + backErrorY * backErrorY)
        if (backError > FORWARD_BACKWARD_ERROR_PX) return null

        return forward.copy(sad = max(forward.sad, reverse.sad))
    }

    private fun matchPatchOneWayV93(
        from: IntArray,
        to: IntArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
    ): MatchV90? {
        val margin = SEARCH_RADIUS + PATCH_RADIUS + 1
        if (x !in margin until width - margin || y !in margin until height - margin) return null

        var bestDx = 0
        var bestDy = 0
        var best = Int.MAX_VALUE

        for (dy in -SEARCH_RADIUS..SEARCH_RADIUS step COARSE_STEP) {
            for (dx in -SEARCH_RADIUS..SEARCH_RADIUS step COARSE_STEP) {
                val score = patchSadV90(from, to, width, height, x, y, dx, dy)
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
                val score = patchSadV90(from, to, width, height, x, y, dx, dy)
                if (score < best) {
                    best = score
                    bestDx = dx
                    bestDy = dy
                }
            }
        }

        val sampleCount = (PATCH_RADIUS * 2 + 1) * (PATCH_RADIUS * 2 + 1)
        val normalizedSad = best.toFloat() / sampleCount.toFloat()
        if (normalizedSad > REJECT_SAD) return null

        val left = patchSadV90(from, to, width, height, x, y, bestDx - 1, bestDy)
        val right = patchSadV90(from, to, width, height, x, y, bestDx + 1, bestDy)
        val up = patchSadV90(from, to, width, height, x, y, bestDx, bestDy - 1)
        val down = patchSadV90(from, to, width, height, x, y, bestDx, bestDy + 1)
        val subX = subpixelOffsetV93(left, best, right)
        val subY = subpixelOffsetV93(up, best, down)

        return MatchV90(
            px = x.toFloat(),
            py = y.toFloat(),
            qx = x + bestDx + subX,
            qy = y + bestDy + subY,
            sad = normalizedSad,
        )
    }

    private fun subpixelOffsetV93(left: Int, center: Int, right: Int): Float {
        if (left >= Int.MAX_VALUE / 8 || center >= Int.MAX_VALUE / 8 || right >= Int.MAX_VALUE / 8) return 0f
        val denominator = (left - 2f * center + right)
        if (abs(denominator) < 1f) return 0f
        return (.5f * (left - right) / denominator).coerceIn(-.5f, .5f)
    }

    private data class SimilarityFitV93(
        val a: Float,
        val b: Float,
        val tx: Float,
        val ty: Float,
        val txPx: Float,
        val tyPx: Float,
        val rotationDegrees: Float,
        val scale: Float,
        val confidence: Float = 0f,
        val sceneCut: Boolean = false,
    ) {
        fun asSimilarityV90(): SimilarityV90 = SimilarityV90(
            txPx = txPx,
            tyPx = tyPx,
            rotationDegrees = rotationDegrees,
            scale = scale,
            confidence = confidence,
            sceneCut = sceneCut,
        )
    }

    private fun ransacSimilarityFitV93(matches: List<MatchV90>): SimilarityFitV93? {
        var best: SimilarityFitV93? = null
        var bestInliers = -1
        var bestError = Float.POSITIVE_INFINITY
        var tested = 0

        loop@ for (i in 0 until matches.lastIndex) {
            for (j in i + 1 until matches.size) {
                if (tested++ >= MAX_RANSAC_HYPOTHESES) break@loop
                val candidate = fitSimilarityFromPairV93(matches[i], matches[j]) ?: continue
                var inliers = 0
                var error = 0f
                for (match in matches) {
                    val e = reprojectionErrorV93(candidate.asSimilarityV90(), match)
                    if (e <= RANSAC_INLIER_ERROR_PX) {
                        inliers++
                        error += e
                    }
                }
                if (inliers > bestInliers || (inliers == bestInliers && error < bestError)) {
                    best = candidate
                    bestInliers = inliers
                    bestError = error
                }
            }
        }
        return best
    }

    private fun fitSimilarityFromPairV93(first: MatchV90, second: MatchV90): SimilarityFitV93? {
        val dpx = second.px - first.px
        val dpy = second.py - first.py
        val dqx = second.qx - first.qx
        val dqy = second.qy - first.qy
        val denominator = dpx * dpx + dpy * dpy
        if (denominator < MIN_RANSAC_BASELINE_PX * MIN_RANSAC_BASELINE_PX) return null

        val a = (dpx * dqx + dpy * dqy) / denominator
        val b = (dpx * dqy - dpy * dqx) / denominator
        val scale = sqrt(a * a + b * b)
        if (scale !in .82f..1.18f) return null
        val tx = first.qx - (a * first.px - b * first.py)
        val ty = first.qy - (b * first.px + a * first.py)
        return fitFromCoefficientsV93(a, b, tx, ty, frameCenterX = .5f * ANALYSIS_LONG_SIDE, frameCenterY = .5f * ANALYSIS_LONG_SIDE)
    }

    private fun fitSimilarityFitAllV93(inliers: List<MatchV90>): SimilarityFitV93? {
        if (inliers.size < 2) return null
        val pCx = inliers.sumOf { it.px.toDouble() }.toFloat() / inliers.size
        val pCy = inliers.sumOf { it.py.toDouble() }.toFloat() / inliers.size
        val qCx = inliers.sumOf { it.qx.toDouble() }.toFloat() / inliers.size
        val qCy = inliers.sumOf { it.qy.toDouble() }.toFloat() / inliers.size

        var dot = 0.0
        var cross = 0.0
        var denominator = 0.0
        inliers.forEach { point ->
            val px = (point.px - pCx).toDouble()
            val py = (point.py - pCy).toDouble()
            val qx = (point.qx - qCx).toDouble()
            val qy = (point.qy - qCy).toDouble()
            dot += px * qx + py * qy
            cross += px * qy - py * qx
            denominator += px * px + py * py
        }
        if (denominator <= 1e-5) return null

        val a = (dot / denominator).toFloat()
        val b = (cross / denominator).toFloat()
        val tx = qCx - (a * pCx - b * pCy)
        val ty = qCy - (b * pCx + a * pCy)
        val centerX = inliers.sumOf { it.px.toDouble() }.toFloat() / inliers.size
        val centerY = inliers.sumOf { it.py.toDouble() }.toFloat() / inliers.size
        return fitFromCoefficientsV93(a, b, tx, ty, centerX, centerY)
    }

    private fun fitFromCoefficientsV93(
        a: Float,
        b: Float,
        tx: Float,
        ty: Float,
        frameCenterX: Float,
        frameCenterY: Float,
    ): SimilarityFitV93 {
        val scale = sqrt(a * a + b * b)
        val rotation = Math.toDegrees(atan2(b.toDouble(), a.toDouble())).toFloat()

        // Translation is evaluated at frame center, matching ClipTransformEffect's rotation pivot.
        val mappedCx = a * frameCenterX - b * frameCenterY + tx
        val mappedCy = b * frameCenterX + a * frameCenterY + ty
        return SimilarityFitV93(
            a = a,
            b = b,
            tx = tx,
            ty = ty,
            txPx = mappedCx - frameCenterX,
            tyPx = mappedCy - frameCenterY,
            rotationDegrees = rotation,
            scale = scale,
        )
    }

    private fun reprojectionErrorV93(transform: SimilarityFitV93, match: MatchV90): Float {
        val predictedX = transform.a * match.px - transform.b * match.py + transform.tx
        val predictedY = transform.b * match.px + transform.a * match.py + transform.ty
        val dx = predictedX - match.qx
        val dy = predictedY - match.qy
        return sqrt(dx * dx + dy * dy)
    }

    private fun frameDifferenceV93(previous: IntArray, current: IntArray): Float {
        if (previous.size != current.size || previous.isEmpty()) return 255f
        val stride = max(1, previous.size / 2048)
        var sum = 0L
        var count = 0
        var index = 0
        while (index < previous.size) {
            sum += abs(previous[index] - current[index]).toLong()
            count++
            index += stride
        }
        return if (count == 0) 0f else sum.toFloat() / count.toFloat()
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
        const val ANALYSIS_LONG_SIDE = 256
        const val MAX_ANALYSIS_SAMPLES = 1200
        const val MIN_SAMPLE_INTERVAL_US = 50_000L
        const val PATCH_RADIUS = 3
        const val SEARCH_RADIUS = 18
        const val COARSE_STEP = 3
        const val GRID_COLUMNS = 8
        const val GRID_ROWS = 6
        const val MAX_FEATURES = 28
        const val MIN_FEATURES = 7
        const val REJECT_SAD = 42f
        const val MIN_ACCEPTED_CONFIDENCE = .28f
        const val MAX_STEP_ROTATION_DEGREES = 8.5f
        const val MIN_STEP_SCALE = .90f
        const val MAX_STEP_SCALE = 1.10f
        const val FORWARD_BACKWARD_ERROR_PX = 1.75f
        const val RANSAC_INLIER_ERROR_PX = 3.25f
        const val MAX_RANSAC_HYPOTHESES = 180
        const val MIN_RANSAC_BASELINE_PX = 18f
        const val SCENE_CUT_DIFFERENCE = 52f
    }}
