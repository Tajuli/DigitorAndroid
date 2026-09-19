package com.tajuli.digitorandroid.editor.processing

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class PairMotionV1(
    val centerDxPx: Float = 0f,
    val centerDyPx: Float = 0f,
    val rotationDegreesImage: Float = 0f,
    val scale: Float = 1f,
    val confidence: Float = 0f,
    val sceneCut: Boolean = false,
)

private data class CandidateV1(val x: Int, val y: Int, val score: Int)
private data class MatchV1(
    val px: Float,
    val py: Float,
    val qx: Float,
    val qy: Float,
    val error: Float,
)
private data class SimilarityFitV1(
    val a: Float,
    val b: Float,
    val tx: Float,
    val ty: Float,
)

internal fun estimatePairMotionV1(
    previous: IntArray,
    current: IntArray,
    width: Int,
    height: Int,
): PairMotionV1 {
    val frameDifference = frameDifferenceV1(previous, current)
    val margin = SEARCH_RADIUS_V1 + PATCH_RADIUS_V1 + 3
    if (width <= margin * 2 || height <= margin * 2) return PairMotionV1()

    val candidates = ArrayList<CandidateV1>()
    val usableW = width - margin * 2
    val usableH = height - margin * 2
    for (gy in 0 until GRID_ROWS_V1) {
        val top = margin + usableH * gy / GRID_ROWS_V1
        val bottom = margin + usableH * (gy + 1) / GRID_ROWS_V1
        for (gx in 0 until GRID_COLUMNS_V1) {
            val left = margin + usableW * gx / GRID_COLUMNS_V1
            val right = margin + usableW * (gx + 1) / GRID_COLUMNS_V1
            var best: CandidateV1? = null
            var y = top + FEATURE_SCAN_STEP_V1 / 2
            while (y < bottom) {
                var x = left + FEATURE_SCAN_STEP_V1 / 2
                while (x < right) {
                    if (x in 4 until width - 4 && y in 4 until height - 4) {
                        val score = textureScoreV1(previous, width, x, y)
                        if (best == null || score > best.score) best = CandidateV1(x, y, score)
                    }
                    x += FEATURE_SCAN_STEP_V1
                }
                y += FEATURE_SCAN_STEP_V1
            }
            if (best != null && best.score >= MIN_TEXTURE_V1) candidates += best
        }
    }

    val matches = candidates.mapNotNull { candidate ->
        matchPatchBidirectionalV1(previous, current, width, height, candidate.x, candidate.y)
    }
    if (matches.size < MIN_FEATURES_V1) {
        return PairMotionV1(
            confidence = .05f,
            sceneCut = frameDifference > SCENE_CUT_DIFFERENCE_V1,
        )
    }

    val hypothesis = ransacSimilarityV1(matches) ?: return PairMotionV1(
        confidence = .05f,
        sceneCut = frameDifference > SCENE_CUT_DIFFERENCE_V1,
    )
    val inliers = matches.filter {
        reprojectionErrorV1(hypothesis, it) <= RANSAC_INLIER_ERROR_V1
    }
    if (inliers.size < MIN_FEATURES_V1) {
        return PairMotionV1(
            confidence = .08f,
            sceneCut = frameDifference > SCENE_CUT_DIFFERENCE_V1,
        )
    }

    val fit = fitSimilarityAllV1(inliers) ?: return PairMotionV1(confidence = .05f)
    val scale = sqrt(fit.a * fit.a + fit.b * fit.b)
    val rotation = Math.toDegrees(atan2(fit.b.toDouble(), fit.a.toDouble())).toFloat()
    if (scale !in MIN_STEP_SCALE_V1..MAX_STEP_SCALE_V1 || abs(rotation) > MAX_STEP_ROTATION_V1) {
        return PairMotionV1(
            confidence = .04f,
            sceneCut = frameDifference > SCENE_CUT_DIFFERENCE_V1,
        )
    }

    val centerX = (width - 1) * .5f
    val centerY = (height - 1) * .5f
    val mappedCenterX = fit.a * centerX - fit.b * centerY + fit.tx
    val mappedCenterY = fit.b * centerX + fit.a * centerY + fit.ty
    val averageError = inliers.sumOf { reprojectionErrorV1(fit, it).toDouble() }
        .toFloat() / inliers.size
    val inlierRatio = inliers.size.toFloat() / matches.size.toFloat()
    val coverage = spatialCoverageV1(inliers, width, height)
    val countScore = (inliers.size / 22f).coerceIn(0f, 1f)
    val errorScore = (1f - averageError / RANSAC_INLIER_ERROR_V1).coerceIn(0f, 1f)
    val confidence = (
        countScore * .24f +
            inlierRatio * .28f +
            errorScore * .28f +
            coverage * .20f
        ).coerceIn(0f, 1f)
    val hardCut = frameDifference > SCENE_CUT_DIFFERENCE_V1 &&
        (inlierRatio < .40f || confidence < .25f)

    return PairMotionV1(
        centerDxPx = mappedCenterX - centerX,
        centerDyPx = mappedCenterY - centerY,
        rotationDegreesImage = rotation,
        scale = scale,
        confidence = if (hardCut) 0f else confidence,
        sceneCut = hardCut,
    )
}

private fun ransacSimilarityV1(matches: List<MatchV1>): SimilarityFitV1? {
    var best: SimilarityFitV1? = null
    var bestInliers = -1
    var bestError = Float.POSITIVE_INFINITY
    var tested = 0
    outer@ for (i in 0 until matches.lastIndex) {
        for (j in i + 1 until matches.size) {
            if (tested++ >= MAX_RANSAC_HYPOTHESES_V1) break@outer
            val fit = fitPairV1(matches[i], matches[j]) ?: continue
            var count = 0
            var error = 0f
            for (match in matches) {
                val e = reprojectionErrorV1(fit, match)
                if (e <= RANSAC_INLIER_ERROR_V1) {
                    count++
                    error += e
                }
            }
            if (count > bestInliers || (count == bestInliers && error < bestError)) {
                best = fit
                bestInliers = count
                bestError = error
            }
        }
    }
    return best
}

private fun fitPairV1(a: MatchV1, b: MatchV1): SimilarityFitV1? {
    val dpx = b.px - a.px
    val dpy = b.py - a.py
    val dqx = b.qx - a.qx
    val dqy = b.qy - a.qy
    val denominator = dpx * dpx + dpy * dpy
    if (denominator < MIN_RANSAC_BASELINE_V1 * MIN_RANSAC_BASELINE_V1) return null
    val aa = (dpx * dqx + dpy * dqy) / denominator
    val bb = (dpx * dqy - dpy * dqx) / denominator
    val tx = a.qx - (aa * a.px - bb * a.py)
    val ty = a.qy - (bb * a.px + aa * a.py)
    return SimilarityFitV1(aa, bb, tx, ty)
}

private fun fitSimilarityAllV1(matches: List<MatchV1>): SimilarityFitV1? {
    if (matches.size < 2) return null
    val pCx = matches.sumOf { it.px.toDouble() }.toFloat() / matches.size
    val pCy = matches.sumOf { it.py.toDouble() }.toFloat() / matches.size
    val qCx = matches.sumOf { it.qx.toDouble() }.toFloat() / matches.size
    val qCy = matches.sumOf { it.qy.toDouble() }.toFloat() / matches.size
    var dot = 0.0
    var cross = 0.0
    var denominator = 0.0
    for (m in matches) {
        val px = (m.px - pCx).toDouble()
        val py = (m.py - pCy).toDouble()
        val qx = (m.qx - qCx).toDouble()
        val qy = (m.qy - qCy).toDouble()
        dot += px * qx + py * qy
        cross += px * qy - py * qx
        denominator += px * px + py * py
    }
    if (denominator < 1e-6) return null
    val a = (dot / denominator).toFloat()
    val b = (cross / denominator).toFloat()
    return SimilarityFitV1(
        a,
        b,
        qCx - (a * pCx - b * pCy),
        qCy - (b * pCx + a * pCy),
    )
}

private fun reprojectionErrorV1(fit: SimilarityFitV1, m: MatchV1): Float {
    val x = fit.a * m.px - fit.b * m.py + fit.tx
    val y = fit.b * m.px + fit.a * m.py + fit.ty
    val dx = x - m.qx
    val dy = y - m.qy
    return sqrt(dx * dx + dy * dy)
}

private fun matchPatchBidirectionalV1(
    previous: IntArray,
    current: IntArray,
    width: Int,
    height: Int,
    x: Int,
    y: Int,
): MatchV1? {
    val forward = matchPatchOneWayV1(previous, current, width, height, x, y) ?: return null
    val reverse = matchPatchOneWayV1(
        current,
        previous,
        width,
        height,
        forward.qx.roundToInt(),
        forward.qy.roundToInt(),
    ) ?: return null
    val dx = reverse.qx - x
    val dy = reverse.qy - y
    if (sqrt(dx * dx + dy * dy) > FORWARD_BACKWARD_ERROR_V1) return null
    return forward.copy(error = max(forward.error, reverse.error))
}

private fun matchPatchOneWayV1(
    from: IntArray,
    to: IntArray,
    width: Int,
    height: Int,
    x: Int,
    y: Int,
): MatchV1? {
    val margin = SEARCH_RADIUS_V1 + PATCH_RADIUS_V1 + 2
    if (x !in margin until width - margin || y !in margin until height - margin) return null
    var bestDx = 0
    var bestDy = 0
    var best = Int.MAX_VALUE

    for (dy in -SEARCH_RADIUS_V1..SEARCH_RADIUS_V1 step COARSE_STEP_V1) {
        for (dx in -SEARCH_RADIUS_V1..SEARCH_RADIUS_V1 step COARSE_STEP_V1) {
            val score = patchSadV1(from, to, width, x, y, dx, dy)
            if (score < best) {
                best = score
                bestDx = dx
                bestDy = dy
            }
        }
    }
    for (dy in bestDy - 2..bestDy + 2) {
        for (dx in bestDx - 2..bestDx + 2) {
            if (abs(dx) > SEARCH_RADIUS_V1 || abs(dy) > SEARCH_RADIUS_V1) continue
            val score = patchSadV1(from, to, width, x, y, dx, dy)
            if (score < best) {
                best = score
                bestDx = dx
                bestDy = dy
            }
        }
    }

    val count = (PATCH_RADIUS_V1 * 2 + 1) * (PATCH_RADIUS_V1 * 2 + 1)
    val error = best.toFloat() / count.toFloat()
    if (error > MAX_PATCH_SAD_V1) return null

    // V3: quadratic sub-pixel refinement removes the integer-pixel quantization that otherwise
    // becomes visible as tripod micro-jitter after the inverse transform is rendered.
    val centerCost = patchSadV1(from, to, width, x, y, bestDx, bestDy).toFloat()
    val leftCost = if (bestDx > -SEARCH_RADIUS_V1) {
        patchSadV1(from, to, width, x, y, bestDx - 1, bestDy).toFloat()
    } else centerCost
    val rightCost = if (bestDx < SEARCH_RADIUS_V1) {
        patchSadV1(from, to, width, x, y, bestDx + 1, bestDy).toFloat()
    } else centerCost
    val upCost = if (bestDy > -SEARCH_RADIUS_V1) {
        patchSadV1(from, to, width, x, y, bestDx, bestDy - 1).toFloat()
    } else centerCost
    val downCost = if (bestDy < SEARCH_RADIUS_V1) {
        patchSadV1(from, to, width, x, y, bestDx, bestDy + 1).toFloat()
    } else centerCost
    val subX = quadraticOffsetV3(leftCost, centerCost, rightCost)
    val subY = quadraticOffsetV3(upCost, centerCost, downCost)

    return MatchV1(
        x.toFloat(),
        y.toFloat(),
        x + bestDx.toFloat() + subX,
        y + bestDy.toFloat() + subY,
        error,
    )
}

private fun patchSadV1(
    a: IntArray,
    b: IntArray,
    width: Int,
    x: Int,
    y: Int,
    dx: Int,
    dy: Int,
): Int {
    val qx = x + dx
    val qy = y + dy
    var sum = 0
    for (py in -PATCH_RADIUS_V1..PATCH_RADIUS_V1) {
        val rowA = (y + py) * width
        val rowB = (qy + py) * width
        for (px in -PATCH_RADIUS_V1..PATCH_RADIUS_V1) {
            sum += abs(a[rowA + x + px] - b[rowB + qx + px])
        }
    }
    return sum
}

private fun spatialCoverageV1(matches: List<MatchV1>, width: Int, height: Int): Float {
    val occupied = BooleanArray(12)
    for (m in matches) {
        val gx = ((m.px / width.toFloat()) * 4f).toInt().coerceIn(0, 3)
        val gy = ((m.py / height.toFloat()) * 3f).toInt().coerceIn(0, 2)
        occupied[gy * 4 + gx] = true
    }
    return occupied.count { it }.toFloat() / occupied.size.toFloat()
}

private fun textureScoreV1(frame: IntArray, width: Int, x: Int, y: Int): Int {
    var score = 0
    for (dy in -3..3) {
        val row = (y + dy) * width
        for (dx in -3..3) {
            val center = frame[row + x + dx]
            score += abs(center - frame[row + x + dx + 1])
            score += abs(center - frame[row + width + x + dx])
        }
    }
    return score
}

private fun frameDifferenceV1(a: IntArray, b: IntArray): Float {
    if (a.size != b.size || a.isEmpty()) return 255f
    val stride = max(1, a.size / 2048)
    var sum = 0L
    var count = 0
    var i = 0
    while (i < a.size) {
        sum += abs(a[i] - b[i]).toLong()
        count++
        i += stride
    }
    return if (count == 0) 0f else sum.toFloat() / count
}

private const val PATCH_RADIUS_V1 = 4
private const val SEARCH_RADIUS_V1 = 20
private const val COARSE_STEP_V1 = 4
private const val GRID_COLUMNS_V1 = 8
private const val GRID_ROWS_V1 = 6
private const val FEATURE_SCAN_STEP_V1 = 5
private const val MIN_TEXTURE_V1 = 95
private const val MIN_FEATURES_V1 = 8
private const val MAX_PATCH_SAD_V1 = 38f
private const val FORWARD_BACKWARD_ERROR_V1 = 1.2f
private const val RANSAC_INLIER_ERROR_V1 = 2.6f
private const val MAX_RANSAC_HYPOTHESES_V1 = 160
private const val MIN_RANSAC_BASELINE_V1 = 20f
private const val MIN_STEP_SCALE_V1 = .94f
private const val MAX_STEP_SCALE_V1 = 1.06f
private const val MAX_STEP_ROTATION_V1 = 5f
private const val SCENE_CUT_DIFFERENCE_V1 = 50f


/**
 * V3 Tripod tracker.
 *
 * Tracks are born only on the first frame of a scene segment. They are never reseeded. A track is
 * removed permanently if it leaves the safe image area or fails forward/backward validation.
 * Therefore IDs still alive on the final frame are exactly the reference points that stayed inside
 * the frame for the whole segment.
 */
internal data class PersistentTripodTrackV3(
    val id: Int,
    val referenceX: Float,
    val referenceY: Float,
    val currentX: Float,
    val currentY: Float,
    val ageFrames: Int = 1,
    val meanPatchError: Float = 0f,
)

internal data class TripodTrackObservationV3(
    val id: Int,
    val referenceX: Float,
    val referenceY: Float,
    val currentX: Float,
    val currentY: Float,
    val ageFrames: Int,
    val meanPatchError: Float,
)

internal data class TripodReferencePoseV3(
    val centerDxPx: Float,
    val centerDyPx: Float,
    val rotationDegreesImage: Float,
    val scale: Float,
    val confidence: Float,
    val inlierTrackIds: Set<Int>,
)

internal fun seedPersistentTripodTracksV3(
    frame: IntArray,
    width: Int,
    height: Int,
): List<PersistentTripodTrackV3> {
    val margin = TRIPOD_SAFE_MARGIN_V3
    if (width <= margin * 2 || height <= margin * 2) return emptyList()

    val candidates = ArrayList<CandidateV1>()
    val usableW = width - margin * 2
    val usableH = height - margin * 2
    for (gy in 0 until GRID_ROWS_V1) {
        val top = margin + usableH * gy / GRID_ROWS_V1
        val bottom = margin + usableH * (gy + 1) / GRID_ROWS_V1
        for (gx in 0 until GRID_COLUMNS_V1) {
            val left = margin + usableW * gx / GRID_COLUMNS_V1
            val right = margin + usableW * (gx + 1) / GRID_COLUMNS_V1
            var best: CandidateV1? = null
            var y = top + FEATURE_SCAN_STEP_V1 / 2
            while (y < bottom) {
                var x = left + FEATURE_SCAN_STEP_V1 / 2
                while (x < right) {
                    if (x in 4 until width - 4 && y in 4 until height - 4) {
                        val score = textureScoreV1(frame, width, x, y)
                        if (best == null || score > best.score) best = CandidateV1(x, y, score)
                    }
                    x += FEATURE_SCAN_STEP_V1
                }
                y += FEATURE_SCAN_STEP_V1
            }
            if (best != null && best.score >= MIN_TEXTURE_V1) candidates += best
        }
    }

    return candidates.mapIndexed { index, point ->
        PersistentTripodTrackV3(
            id = index + 1,
            referenceX = point.x.toFloat(),
            referenceY = point.y.toFloat(),
            currentX = point.x.toFloat(),
            currentY = point.y.toFloat(),
        )
    }
}

internal fun advancePersistentTripodTracksV3(
    previous: IntArray,
    current: IntArray,
    width: Int,
    height: Int,
    tracks: List<PersistentTripodTrackV3>,
): List<PersistentTripodTrackV3> {
    if (tracks.isEmpty()) return emptyList()
    val safe = TRIPOD_SAFE_MARGIN_V3.toFloat()
    val output = ArrayList<PersistentTripodTrackV3>(tracks.size)

    for (track in tracks) {
        val x = track.currentX.roundToInt()
        val y = track.currentY.roundToInt()
        if (
            track.currentX < safe ||
            track.currentX > width - 1f - safe ||
            track.currentY < safe ||
            track.currentY > height - 1f - safe
        ) continue

        val match = matchPatchBidirectionalV1(previous, current, width, height, x, y)
            ?: continue
        if (
            match.qx < safe ||
            match.qx > width - 1f - safe ||
            match.qy < safe ||
            match.qy > height - 1f - safe
        ) continue

        val age = track.ageFrames + 1
        val previousErrorSamples = (track.ageFrames - 1).coerceAtLeast(0)
        val errorSamples = previousErrorSamples + 1
        val meanError = (
            track.meanPatchError * previousErrorSamples.toFloat() + match.error
            ) / errorSamples.toFloat()
        if (meanError > TRIPOD_MAX_MEAN_PATCH_ERROR_V3) continue

        output += track.copy(
            currentX = match.qx,
            currentY = match.qy,
            ageFrames = age,
            meanPatchError = meanError,
        )
    }
    return output
}

internal fun tripodObservationsV3(
    tracks: List<PersistentTripodTrackV3>,
): List<TripodTrackObservationV3> = tracks.map { track ->
    TripodTrackObservationV3(
        id = track.id,
        referenceX = track.referenceX,
        referenceY = track.referenceY,
        currentX = track.currentX,
        currentY = track.currentY,
        ageFrames = track.ageFrames,
        meanPatchError = track.meanPatchError,
    )
}

internal fun survivingTripodTrackIdsV3(
    frames: List<List<TripodTrackObservationV3>>,
): Set<Int> = frames.lastOrNull().orEmpty().mapTo(linkedSetOf()) { it.id }

internal fun estimateTripodReferencePoseV3(
    observations: List<TripodTrackObservationV3>,
    survivorIds: Set<Int>,
    width: Int,
    height: Int,
): TripodReferencePoseV3? {
    val eligible = observations
        .asSequence()
        .filter { it.id in survivorIds }
        .filter { it.meanPatchError <= TRIPOD_MAX_MEAN_PATCH_ERROR_V3 }
        .map {
            MatchV1(
                px = it.referenceX,
                py = it.referenceY,
                qx = it.currentX,
                qy = it.currentY,
                error = it.meanPatchError,
            )
        }
        .toList()

    if (eligible.size < TRIPOD_MIN_SURVIVORS_V3) return null
    val hypothesis = ransacSimilarityV1(eligible) ?: return null
    val inliers = eligible.filter {
        reprojectionErrorV1(hypothesis, it) <= TRIPOD_REFERENCE_INLIER_PX_V3
    }
    if (inliers.size < TRIPOD_MIN_SURVIVORS_V3) return null

    val refined = fitSimilarityAllV1(inliers) ?: return null
    val scale = sqrt(refined.a * refined.a + refined.b * refined.b)
    val rotation = Math.toDegrees(atan2(refined.b.toDouble(), refined.a.toDouble())).toFloat()
    if (scale !in TRIPOD_REFERENCE_MIN_SCALE_V3..TRIPOD_REFERENCE_MAX_SCALE_V3) return null

    val centerX = (width - 1) * .5f
    val centerY = (height - 1) * .5f
    val mappedCenterX = refined.a * centerX - refined.b * centerY + refined.tx
    val mappedCenterY = refined.b * centerX + refined.a * centerY + refined.ty
    val averageError = inliers.sumOf { reprojectionErrorV1(refined, it).toDouble() }
        .toFloat() / inliers.size.toFloat()
    val inlierRatio = inliers.size.toFloat() / eligible.size.toFloat()
    val coverage = spatialCoverageV1(inliers, width, height)
    val confidence = (
        inlierRatio * .40f +
            coverage * .35f +
            (1f - averageError / TRIPOD_REFERENCE_INLIER_PX_V3).coerceIn(0f, 1f) * .25f
        ).coerceIn(0f, 1f)

    val inlierIds = inliers.mapNotNull { match ->
        observations.firstOrNull {
            it.id in survivorIds &&
                abs(it.referenceX - match.px) < .01f &&
                abs(it.referenceY - match.py) < .01f
        }?.id
    }.toSet()

    return TripodReferencePoseV3(
        centerDxPx = mappedCenterX - centerX,
        centerDyPx = mappedCenterY - centerY,
        rotationDegreesImage = rotation,
        scale = scale,
        confidence = confidence,
        inlierTrackIds = inlierIds,
    )
}

private const val TRIPOD_SAFE_MARGIN_V3 = 30
private const val TRIPOD_MIN_SURVIVORS_V3 = 6
private const val TRIPOD_MAX_MEAN_PATCH_ERROR_V3 = 26f
private const val TRIPOD_REFERENCE_INLIER_PX_V3 = 2.2f
private const val TRIPOD_REFERENCE_MIN_SCALE_V3 = .90f
private const val TRIPOD_REFERENCE_MAX_SCALE_V3 = 1.10f


private fun quadraticOffsetV3(minus: Float, center: Float, plus: Float): Float {
    val denominator = minus - 2f * center + plus
    if (abs(denominator) < 1e-4f) return 0f
    return (.5f * (minus - plus) / denominator).coerceIn(-.75f, .75f)
}
