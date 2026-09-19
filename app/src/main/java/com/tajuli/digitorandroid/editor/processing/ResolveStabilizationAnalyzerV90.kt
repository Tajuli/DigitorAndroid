package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.tajuli.digitorandroid.editor.model.ClipStabilizationV90
import com.tajuli.digitorandroid.editor.model.PerspectiveQuadV102
import com.tajuli.digitorandroid.editor.model.StabilizationPathSampleV90
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineVisualMediaV21
import com.tajuli.digitorandroid.editor.model.computeCameraLockCoverScaleV100
import com.tajuli.digitorandroid.editor.model.computeCameraLockPerspectiveCoverScaleV102
import com.tajuli.digitorandroid.editor.model.solveHomographyV102
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
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
        val settings = base.normalized()
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
        val referenceAnchorsV100 = ArrayList<ReferenceAnchorV100>()
        var previousGray: IntArray? = null
        var segmentReferenceGray: IntArray? = null
        var framesSinceReferenceProbe = 0
        var analyzedWidth = 0
        var analyzedHeight = 0
        var pathX = 0f
        var pathY = 0f
        var pathRotation = 0f
        var pathLogScale = 0f
        var perspectivePathV102 = PerspectiveQuadV102.IDENTITY
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
            emitEveryFrame = true,
        ) { sourceTimeUs, bitmap ->
            try {
                val width = bitmap.width.coerceAtLeast(1)
                val height = bitmap.height.coerceAtLeast(1)
                analyzedWidth = width
                analyzedHeight = height
                val current = bitmap.toGrayV90()

                val previous = previousGray
                val confidence: Float
                val rotationScaleConfidence: Float
                if (previous == null || previous.size != current.size) {
                    confidence = 1f
                    rotationScaleConfidence = 1f
                    segmentReferenceGray = current.copyOf()
                    framesSinceReferenceProbe = 0
                } else {
                    val motion = estimateSimilarityV90(previous, current, width, height)
                    confidence = motion.confidence
                    rotationScaleConfidence = motion.rotationScaleConfidenceV99
                    if (motion.sceneCut) {
                        segmentV93++
                        pathX = 0f
                        pathY = 0f
                        pathRotation = 0f
                        pathLogScale = 0f
                        perspectivePathV102 = PerspectiveQuadV102.IDENTITY
                        segmentReferenceGray = current.copyOf()
                        framesSinceReferenceProbe = 0
                    } else {
                        if (motion.confidence >= MIN_ACCEPTED_CONFIDENCE) {
                            // V97 composes the incremental similarity transform instead of adding
                            // parameters independently.
                            val rsTrust = motion.rotationScaleConfidenceV99
                                .coerceIn(0f, 1f)
                            val trustedRotation = motion.rotationDegrees * rsTrust
                            val incrementalScale = exp(
                                (ln(motion.scale.coerceIn(MIN_STEP_SCALE, MAX_STEP_SCALE)) * rsTrust).toDouble(),
                            ).toFloat()
                            val radians = Math.toRadians(trustedRotation.toDouble())
                            val cosR = cos(radians).toFloat()
                            val sinR = sin(radians).toFloat()
                            val previousX = pathX
                            val previousY = pathY
                            val incX = motion.txPx / (width * .5f)
                            val incY = motion.tyPx / (height * .5f)
                            pathX = incrementalScale * (cosR * previousX - sinR * previousY) + incX
                            pathY = incrementalScale * (sinR * previousX + cosR * previousY) + incY
                            pathRotation += trustedRotation
                            pathLogScale += ln(incrementalScale)
                            motion.perspectiveDeltaV102?.let { incrementalPerspective ->
                                perspectivePathV102 = composePerspectivePathV102(
                                    previousPath = perspectivePathV102,
                                    incremental = incrementalPerspective,
                                )
                            }
                        }

                        // V97 reference re-lock: incremental tracking is excellent at high-frequency
                        // shake but slowly drifts. Periodically match the current frame directly to
                        // the first stable frame of this scene using a wider search. A confident
                        // direct pose replaces the accumulated pose and removes long-term drift.
                        framesSinceReferenceProbe++
                        val reference = segmentReferenceGray
                        if (
                            reference != null &&
                            reference.size == current.size &&
                            framesSinceReferenceProbe >= REFERENCE_RELOCK_INTERVAL_FRAMES_V97
                        ) {
                            val anchored = estimateSimilarityV90(
                                previous = reference,
                                current = current,
                                width = width,
                                height = height,
                                searchRadius = REFERENCE_SEARCH_RADIUS_V97,
                                coarseStep = REFERENCE_COARSE_STEP_V97,
                            )
                            if (
                                !anchored.sceneCut &&
                                anchored.confidence >= REFERENCE_RELOCK_MIN_CONFIDENCE_V97
                            ) {
                                // V100 never hard-snaps the live path to the direct reference pose.
                                // Store the reference observation and solve the drift correction
                                // offline after the whole clip is decoded. This removes the periodic
                                // jerk created by V97's every-N-frame re-lock snap.
                                referenceAnchorsV100 += ReferenceAnchorV100(
                                    sampleIndex = samples.size,
                                    segment = segmentV93,
                                    pathX = anchored.txPx / (width * .5f),
                                    pathY = anchored.tyPx / (height * .5f),
                                    rotationDegrees = anchored.rotationDegrees,
                                    logScale = ln(
                                        anchored.scale.coerceIn(
                                            REFERENCE_MIN_SCALE_V97,
                                            REFERENCE_MAX_SCALE_V97,
                                        ),
                                    ),
                                    rotationScaleConfidence =
                                        anchored.rotationScaleConfidenceV99.coerceIn(0f, 1f),
                                    perspectiveQuadV102 = anchored.perspectiveDeltaV102,
                                )
                            }
                            framesSinceReferenceProbe = 0
                        }
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
                    rotationScaleConfidenceV99 = rotationScaleConfidence,
                    perspectivePathV102 = perspectivePathV102,
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
        onProgress(.985f, "Solving tripod lock…")

        val tripodSamples = applyReferenceAnchorsV100(samples, referenceAnchorsV100)
        val cameraLockPerspective = applyPerspectiveReferenceAnchorsV102(
            samples = samples,
            anchors = referenceAnchorsV100,
        )
        val multiModeSamples = samples.indices.map { index ->
            val raw = samples[index]
            val tripod = tripodSamples[index]
            raw.copy(
                cameraLockPathXV101 = tripod.pathX,
                cameraLockPathYV101 = tripod.pathY,
                cameraLockRotationDegreesV101 = tripod.rotationDegrees,
                cameraLockLogScaleV101 = tripod.logScale,
                cameraLockPerspectivePathV102 = cameraLockPerspective[index],
            )
        }
        val solved = settings.copy(
            enabled = true,
            analyzedWidth = analyzedWidth,
            analyzedHeight = analyzedHeight,
            samples = multiModeSamples,
            analysisVersionV93 = 102,
            cameraLockCoverScaleV100 = 1f,
            cameraLockPerspectiveCoverScaleV102 = 1f,
        ).normalized()
        val constantCameraLockZoom = solved.computeCameraLockCoverScaleV100()
        val constantPerspectiveLockZoom = solved.computeCameraLockPerspectiveCoverScaleV102()

        onProgress(1f, "Finishing stabilization…")
        solved.copy(
            cameraLockCoverScaleV100 = constantCameraLockZoom,
            cameraLockPerspectiveCoverScaleV102 = constantPerspectiveLockZoom,
        )
    }

    private data class ReferenceAnchorV100(
        val sampleIndex: Int,
        val segment: Int,
        val pathX: Float,
        val pathY: Float,
        val rotationDegrees: Float,
        val logScale: Float,
        val rotationScaleConfidence: Float,
        val perspectiveQuadV102: PerspectiveQuadV102? = null,
    )

    private data class AnchorResidualV100(
        val sampleIndex: Int,
        val segment: Int,
        val x: Float,
        val y: Float,
        val rotation: Float,
        val logScale: Float,
    )

    /**
     * V100 tripod solve.
     *
     * Incremental tracking stays continuous and captures high-frequency shake. Fixed-reference
     * observations are converted into drift residuals, median-cleaned, then interpolated smoothly
     * across the timeline. Unlike the old hard re-lock, the solved path has no anchor discontinuity.
     */
    private fun applyReferenceAnchorsV100(
        rawSamples: List<StabilizationPathSampleV90>,
        anchors: List<ReferenceAnchorV100>,
    ): List<StabilizationPathSampleV90> {
        if (rawSamples.size < 2 || anchors.isEmpty()) return rawSamples

        val residualsBySegment = LinkedHashMap<Int, MutableList<AnchorResidualV100>>()
        val firstIndexBySegment = LinkedHashMap<Int, Int>()
        rawSamples.forEachIndexed { index, sample ->
            firstIndexBySegment.putIfAbsent(sample.segmentV93, index)
        }
        firstIndexBySegment.forEach { (segment, index) ->
            residualsBySegment.getOrPut(segment) { ArrayList() } += AnchorResidualV100(
                sampleIndex = index,
                segment = segment,
                x = 0f,
                y = 0f,
                rotation = 0f,
                logScale = 0f,
            )
        }

        for (anchor in anchors) {
            val raw = rawSamples.getOrNull(anchor.sampleIndex) ?: continue
            if (raw.segmentV93 != anchor.segment) continue
            val rsTrusted = anchor.rotationScaleConfidence >= REFERENCE_RS_RELOCK_MIN_TRUST_V99
            residualsBySegment.getOrPut(anchor.segment) { ArrayList() } += AnchorResidualV100(
                sampleIndex = anchor.sampleIndex,
                segment = anchor.segment,
                x = anchor.pathX - raw.pathX,
                y = anchor.pathY - raw.pathY,
                rotation = if (rsTrusted) anchor.rotationDegrees - raw.rotationDegrees else 0f,
                logScale = if (rsTrusted) anchor.logScale - raw.logScale else 0f,
            )
        }

        val cleanedBySegment = residualsBySegment.mapValues { (_, values) ->
            val sorted = values.distinctBy { it.sampleIndex }.sortedBy { it.sampleIndex }
            sorted.mapIndexed { index, value ->
                if (index == 0 || index == sorted.lastIndex || sorted.size < 3) {
                    value
                } else {
                    val neighborhood = sorted.subList(index - 1, index + 2)
                    value.copy(
                        x = medianV100(neighborhood.map { it.x }),
                        y = medianV100(neighborhood.map { it.y }),
                        rotation = medianV100(neighborhood.map { it.rotation }),
                        logScale = medianV100(neighborhood.map { it.logScale }),
                    )
                }
            }
        }

        return rawSamples.mapIndexed { index, sample ->
            val anchorsForSegment = cleanedBySegment[sample.segmentV93].orEmpty()
            if (anchorsForSegment.isEmpty()) return@mapIndexed sample

            var left = anchorsForSegment.first()
            var right = anchorsForSegment.last()
            for (candidate in anchorsForSegment) {
                if (candidate.sampleIndex <= index) left = candidate
                if (candidate.sampleIndex >= index) {
                    right = candidate
                    break
                }
            }

            val residual = if (left.sampleIndex == right.sampleIndex) {
                left
            } else {
                val t = ((index - left.sampleIndex).toFloat() /
                    (right.sampleIndex - left.sampleIndex).toFloat()).coerceIn(0f, 1f)
                val smoothT = t * t * (3f - 2f * t)
                AnchorResidualV100(
                    sampleIndex = index,
                    segment = sample.segmentV93,
                    x = lerpV100(left.x, right.x, smoothT),
                    y = lerpV100(left.y, right.y, smoothT),
                    rotation = lerpV100(left.rotation, right.rotation, smoothT),
                    logScale = lerpV100(left.logScale, right.logScale, smoothT),
                )
            }

            sample.copy(
                pathX = sample.pathX + residual.x,
                pathY = sample.pathY + residual.y,
                rotationDegrees = sample.rotationDegrees + residual.rotation,
                logScale = sample.logScale + residual.logScale,
            )
        }
    }

    private fun medianV100(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) * .5f
        }
    }

    private fun lerpV100(a: Float, b: Float, t: Float): Float = a + (b - a) * t

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
        val rotationScaleConfidenceV99: Float = 0f,
        val perspectiveDeltaV102: PerspectiveQuadV102? = null,
        val perspectiveConfidenceV102: Float = 0f,
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
        searchRadius: Int = SEARCH_RADIUS,
        coarseStep: Int = COARSE_STEP,
    ): SimilarityV90 {
        val margin = searchRadius + PATCH_RADIUS + 3
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
            matchPatchV90(
                previous,
                current,
                width,
                height,
                candidate.x,
                candidate.y,
                searchRadius,
                coarseStep,
            )
        }

        if (matches.size < MIN_FEATURES) {
            return SimilarityV90(
                confidence = (matches.size / MIN_FEATURES.toFloat() * .12f).coerceIn(0f, .12f),
                sceneCut = frameDifference >= SCENE_CUT_DIFFERENCE,
            )
        }

        val hypothesis = ransacSimilarityFitV93(matches, width, height)
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

        val fitted = fitSimilarityFitAllV93(inliers, width, height) ?: return SimilarityV90(confidence = .05f)
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
        val spatialCoverage = spatialCoverageV99(inliers, width, height)
        val spatialConfidence = (
            (spatialCoverage - MIN_SPATIAL_COVERAGE_V99) /
                (GOOD_SPATIAL_COVERAGE_V99 - MIN_SPATIAL_COVERAGE_V99)
            ).coerceIn(0f, 1f)
        val confidence = (
            featureConfidence * .22f +
                inlierConfidence * .23f +
                geometricConfidence * .22f +
                photometricConfidence * .10f +
                spatialConfidence * .23f
            ).coerceIn(0f, 1f)
        val rotationScaleConfidence = (
            spatialConfidence * .60f +
                geometricConfidence * .25f +
                inlierConfidence * .15f
            ).coerceIn(0f, 1f)

        val perspectiveConfidence = (
            spatialConfidence * .55f +
                geometricConfidence * .30f +
                inlierConfidence * .15f
            ).coerceIn(0f, 1f)
        val perspectiveDelta = estimatePerspectiveDeltaV102(
            inliers = inliers,
            fit = fitted,
            width = width,
            height = height,
            perspectiveTrust = perspectiveConfidence,
            searchRadius = searchRadius,
        )

        val hardCut = frameDifference >= SCENE_CUT_DIFFERENCE &&
            (inlierRatio < .42f || confidence < .24f)

        return fittedPublic.copy(
            confidence = if (hardCut) 0f else confidence,
            sceneCut = hardCut,
            rotationScaleConfidenceV99 = if (hardCut) 0f else rotationScaleConfidence,
            perspectiveDeltaV102 = if (hardCut) null else perspectiveDelta,
            perspectiveConfidenceV102 = if (hardCut) 0f else perspectiveConfidence,
        )
    }

    private fun estimatePerspectiveDeltaV102(
        inliers: List<MatchV90>,
        fit: SimilarityFitV93,
        width: Int,
        height: Int,
        perspectiveTrust: Float,
        searchRadius: Int,
    ): PerspectiveQuadV102 {
        val cornersPx = arrayOf(
            0f to 0f,
            (width - 1).toFloat() to 0f,
            (width - 1).toFloat() to (height - 1).toFloat(),
            0f to (height - 1).toFloat(),
        )
        val diagonal = sqrt((width * width + height * height).toFloat()).coerceAtLeast(1f)
        val localBlend = (perspectiveTrust * .78f).coerceIn(0f, .78f)
        val output = FloatArray(8)

        cornersPx.forEachIndexed { index, (cx, cy) ->
            val similarityX = fit.a * cx - fit.b * cy + fit.tx
            val similarityY = fit.b * cx + fit.a * cy + fit.ty

            var sumWeight = 0.0
            var dx = 0.0
            var dy = 0.0
            for (match in inliers) {
                val distanceX = match.px - cx
                val distanceY = match.py - cy
                val normalizedDistanceSq =
                    (distanceX * distanceX + distanceY * distanceY) /
                        (diagonal * diagonal)
                val weight = 1.0 / (0.035 + normalizedDistanceSq * 8.0)
                sumWeight += weight
                dx += (match.qx - match.px) * weight
                dy += (match.qy - match.py) * weight
            }

            val localX = if (sumWeight > 1e-8) cx + (dx / sumWeight).toFloat() else similarityX
            val localY = if (sumWeight > 1e-8) cy + (dy / sumWeight).toFloat() else similarityY

            val maxResidual = max(2f, searchRadius * .55f)
            val residualX = (localX - similarityX).coerceIn(-maxResidual, maxResidual)
            val residualY = (localY - similarityY).coerceIn(-maxResidual, maxResidual)
            val mappedX = similarityX + residualX * localBlend
            val mappedY = similarityY + residualY * localBlend

            output[index * 2] = (mappedX / (width - 1).coerceAtLeast(1) * 2f - 1f)
                .coerceIn(-1.75f, 1.75f)
            output[index * 2 + 1] = (1f - mappedY / (height - 1).coerceAtLeast(1) * 2f)
                .coerceIn(-1.75f, 1.75f)
        }

        return PerspectiveQuadV102(
            topLeftX = output[0],
            topLeftY = output[1],
            topRightX = output[2],
            topRightY = output[3],
            bottomRightX = output[4],
            bottomRightY = output[5],
            bottomLeftX = output[6],
            bottomLeftY = output[7],
        )
    }

    private fun composePerspectivePathV102(
        previousPath: PerspectiveQuadV102,
        incremental: PerspectiveQuadV102,
    ): PerspectiveQuadV102 {
        val matrix = solveHomographyV102(
            PerspectiveQuadV102.IDENTITY.asPoints(),
            incremental.asPoints(),
        ) ?: return previousPath
        val source = previousPath.asPoints()
        val mapped = FloatArray(8)
        for (index in 0 until 4) {
            val point = mapPerspectivePointV102(
                matrix,
                source[index * 2],
                source[index * 2 + 1],
            ) ?: return previousPath
            mapped[index * 2] = point.first
            mapped[index * 2 + 1] = point.second
        }
        return PerspectiveQuadV102(
            mapped[0], mapped[1],
            mapped[2], mapped[3],
            mapped[4], mapped[5],
            mapped[6], mapped[7],
        )
    }

    private fun applyPerspectiveReferenceAnchorsV102(
        samples: List<StabilizationPathSampleV90>,
        anchors: List<ReferenceAnchorV100>,
    ): List<PerspectiveQuadV102?> {
        if (samples.isEmpty()) return emptyList()
        val bySegment = anchors
            .filter { it.perspectiveQuadV102 != null }
            .groupBy { it.segment }
            .mapValues { (_, values) ->
                val sorted = values.sortedBy { it.sampleIndex }
                sorted.mapIndexed { index, anchor ->
                    val from = (index - 2).coerceAtLeast(0)
                    val to = (index + 2).coerceAtMost(sorted.lastIndex)
                    val neighborhood = sorted.subList(from, to + 1)
                    val points = Array(8) { point ->
                        neighborhood.map {
                            (it.perspectiveQuadV102 ?: PerspectiveQuadV102.IDENTITY)
                                .asPoints()[point]
                        }
                    }
                    anchor.copy(
                        perspectiveQuadV102 = PerspectiveQuadV102(
                            topLeftX = medianV100(points[0]),
                            topLeftY = medianV100(points[1]),
                            topRightX = medianV100(points[2]),
                            topRightY = medianV100(points[3]),
                            bottomRightX = medianV100(points[4]),
                            bottomRightY = medianV100(points[5]),
                            bottomLeftX = medianV100(points[6]),
                            bottomLeftY = medianV100(points[7]),
                        ),
                    )
                }
            }

        return samples.mapIndexed { index, sample ->
            val segmentAnchors = bySegment[sample.segmentV93].orEmpty()
            if (segmentAnchors.isEmpty()) {
                sample.perspectivePathV102
            } else {
                var left = segmentAnchors.first()
                var right = segmentAnchors.last()
                for (anchor in segmentAnchors) {
                    if (anchor.sampleIndex <= index) left = anchor
                    if (anchor.sampleIndex >= index) {
                        right = anchor
                        break
                    }
                }
                val leftQuad = left.perspectiveQuadV102 ?: PerspectiveQuadV102.IDENTITY
                val rightQuad = right.perspectiveQuadV102 ?: leftQuad
                if (left.sampleIndex == right.sampleIndex) {
                    leftQuad
                } else {
                    val t = ((index - left.sampleIndex).toFloat() /
                        (right.sampleIndex - left.sampleIndex).toFloat())
                        .coerceIn(0f, 1f)
                    val smoothT = t * t * (3f - 2f * t)
                    lerpPerspectiveQuadV102(leftQuad, rightQuad, smoothT)
                }
            }
        }
    }

    private fun lerpPerspectiveQuadV102(
        a: PerspectiveQuadV102,
        b: PerspectiveQuadV102,
        t: Float,
    ): PerspectiveQuadV102 {
        val ap = a.asPoints()
        val bp = b.asPoints()
        val out = FloatArray(8) { ap[it] + (bp[it] - ap[it]) * t }
        return PerspectiveQuadV102(
            out[0], out[1],
            out[2], out[3],
            out[4], out[5],
            out[6], out[7],
        )
    }

    private fun mapPerspectivePointV102(
        matrix: FloatArray,
        x: Float,
        y: Float,
    ): Pair<Float, Float>? {
        if (matrix.size < 9) return null
        val w = matrix[6] * x + matrix[7] * y + matrix[8]
        if (abs(w) < 1e-6f) return null
        return (
            (matrix[0] * x + matrix[1] * y + matrix[2]) / w
            ) to (
            (matrix[3] * x + matrix[4] * y + matrix[5]) / w
            )
    }

    private fun spatialCoverageV99(
        inliers: List<MatchV90>,
        width: Int,
        height: Int,
    ): Float {
        if (inliers.isEmpty() || width <= 0 || height <= 0) return 0f
        val occupied = BooleanArray(SPATIAL_GRID_COLUMNS_V99 * SPATIAL_GRID_ROWS_V99)
        for (match in inliers) {
            val gx = ((match.px / width.toFloat()) * SPATIAL_GRID_COLUMNS_V99)
                .toInt()
                .coerceIn(0, SPATIAL_GRID_COLUMNS_V99 - 1)
            val gy = ((match.py / height.toFloat()) * SPATIAL_GRID_ROWS_V99)
                .toInt()
                .coerceIn(0, SPATIAL_GRID_ROWS_V99 - 1)
            occupied[gy * SPATIAL_GRID_COLUMNS_V99 + gx] = true
        }
        return occupied.count { it }.toFloat() / occupied.size.toFloat()
    }

    private fun matchPatchV90(
        previous: IntArray,
        current: IntArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        searchRadius: Int,
        coarseStep: Int,
    ): MatchV90? {
        val forward = matchPatchOneWayV93(
            previous,
            current,
            width,
            height,
            x,
            y,
            searchRadius,
            coarseStep,
        ) ?: return null
        val reverse = matchPatchOneWayV93(
            current,
            previous,
            width,
            height,
            forward.qx.roundToInt(),
            forward.qy.roundToInt(),
            searchRadius,
            coarseStep,
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
        searchRadius: Int,
        coarseStep: Int,
    ): MatchV90? {
        val margin = searchRadius + PATCH_RADIUS + 1
        if (x !in margin until width - margin || y !in margin until height - margin) return null

        var bestDx = 0
        var bestDy = 0
        var best = Int.MAX_VALUE

        for (dy in -searchRadius..searchRadius step coarseStep) {
            for (dx in -searchRadius..searchRadius step coarseStep) {
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
                if (abs(dx) > searchRadius || abs(dy) > searchRadius) continue
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

    private fun ransacSimilarityFitV93(
        matches: List<MatchV90>,
        width: Int,
        height: Int,
    ): SimilarityFitV93? {
        var best: SimilarityFitV93? = null
        var bestInliers = -1
        var bestError = Float.POSITIVE_INFINITY
        var tested = 0

        loop@ for (i in 0 until matches.lastIndex) {
            for (j in i + 1 until matches.size) {
                if (tested++ >= MAX_RANSAC_HYPOTHESES) break@loop
                val candidate = fitSimilarityFromPairV93(
                    matches[i],
                    matches[j],
                    frameCenterX = (width - 1) * .5f,
                    frameCenterY = (height - 1) * .5f,
                ) ?: continue
                var inliers = 0
                var error = 0f
                for (match in matches) {
                    val e = reprojectionErrorV93(candidate, match)
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

    private fun fitSimilarityFromPairV93(
        first: MatchV90,
        second: MatchV90,
        frameCenterX: Float,
        frameCenterY: Float,
    ): SimilarityFitV93? {
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
        return fitFromCoefficientsV93(a, b, tx, ty, frameCenterX, frameCenterY)
    }

    private fun fitSimilarityFitAllV93(
        inliers: List<MatchV90>,
        width: Int,
        height: Int,
    ): SimilarityFitV93? {
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
        return fitFromCoefficientsV93(
            a,
            b,
            tx,
            ty,
            frameCenterX = (width - 1) * .5f,
            frameCenterY = (height - 1) * .5f,
        )
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
        const val ANALYSIS_LONG_SIDE = 320
        const val MAX_ANALYSIS_SAMPLES = 3600
        const val MIN_SAMPLE_INTERVAL_US = 33_333L
        const val PATCH_RADIUS = 3
        const val SEARCH_RADIUS = 18
        const val COARSE_STEP = 3
        const val GRID_COLUMNS = 8
        const val GRID_ROWS = 6
        const val MAX_FEATURES = 32
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
        const val REFERENCE_RELOCK_INTERVAL_FRAMES_V97 = 1
        const val REFERENCE_SEARCH_RADIUS_V97 = 56
        const val REFERENCE_COARSE_STEP_V97 = 6
        const val REFERENCE_RELOCK_MIN_CONFIDENCE_V97 = .38f
        const val REFERENCE_MIN_SCALE_V97 = .82f
        const val REFERENCE_MAX_SCALE_V97 = 1.18f
        const val REFERENCE_RS_RELOCK_MIN_TRUST_V99 = .62f
        const val SPATIAL_GRID_COLUMNS_V99 = 4
        const val SPATIAL_GRID_ROWS_V99 = 3
        const val MIN_SPATIAL_COVERAGE_V99 = .25f
        const val GOOD_SPATIAL_COVERAGE_V99 = .67f
    }
}

