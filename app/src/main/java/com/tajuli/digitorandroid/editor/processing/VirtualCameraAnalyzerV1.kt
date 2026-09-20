package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineVisualMediaV21
import com.tajuli.digitorandroid.editor.model.VirtualCameraSampleV1
import com.tajuli.digitorandroid.editor.model.VirtualCameraStabilizationV1
import com.tajuli.digitorandroid.editor.model.requiredVirtualCameraCoverScaleV1
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class VirtualCameraAnalyzerV1(context: Context) {
    private val appContext = context.applicationContext

    suspend fun analyze(
        clip: TimelineClip,
        base: VirtualCameraStabilizationV1 = clip.virtualCameraStabilizationV1
            ?: VirtualCameraStabilizationV1(),
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): VirtualCameraStabilizationV1 = withContext(Dispatchers.Default) {
        require(clip.visualMediaV21 != TimelineVisualMediaV21.IMAGE) {
            "Stabilization requires a video clip"
        }

        val startUs = clip.sourceInUs.coerceAtLeast(0L)
        val endUs = clip.sourceOutUs.coerceAtLeast(startUs + 1L)
        val durationUs = endUs - startUs
        val raw = ArrayList<RawCameraSampleV1>()
        var previousGray: IntArray? = null
        var segmentReferenceGrayV3: IntArray? = null
        var persistentTripodTracksV3 = emptyList<PersistentTripodTrackV3>()
        var segment = 0
        var pathX = 0f
        var pathY = 0f
        var pathRotation = 0f
        var pathLogScale = 0f
        var lastTrustedIncX = 0f
        var lastTrustedIncY = 0f
        var lastTrustedIncRotation = 0f
        var lastTrustedIncLogScale = 0f
        var untrustedTranslationFrames = 0
        var untrustedRotationFrames = 0
        var untrustedScaleFrames = 0
        var decoded = 0

        onProgress(0f, "Preparing virtual camera...")
        val analysisContext = coroutineContext
        analysisContext.ensureActive()
        val decoder = GpuSequentialCutoutDecoderV47(
            context = appContext,
            analysisLongEdge = ANALYSIS_LONG_EDGE_V1,
        )
        decoder.decodeTargets(
            uri = Uri.parse(clip.uri),
            startUs = startUs,
            endUs = endUs,
            targetTimesUs = listOf(startUs, endUs - 1L),
            emitEveryFrame = true,
        ) { sourceTimeUs, bitmap ->
            try {
                analysisContext.ensureActive()
                val width = bitmap.width.coerceAtLeast(1)
                val height = bitmap.height.coerceAtLeast(1)
                val current = bitmap.toGrayV1()
                val previous = previousGray
                var confidence = 1f
                var translationConfidenceV4 = 1f
                var rotationConfidenceV4 = 1f
                var scaleConfidenceV4 = 1f
                var newReferenceFrame = previous == null

                if (previous != null && previous.size == current.size) {
                    val motion = estimatePairMotionV1(previous, current, width, height)
                    when {
                        motion.sceneCut -> {
                            segment++
                            pathX = 0f
                            pathY = 0f
                            pathRotation = 0f
                            pathLogScale = 0f
                            lastTrustedIncX = 0f
                            lastTrustedIncY = 0f
                            lastTrustedIncRotation = 0f
                            lastTrustedIncLogScale = 0f
                            untrustedTranslationFrames = 0
                            untrustedRotationFrames = 0
                            untrustedScaleFrames = 0
                            confidence = 1f
                            translationConfidenceV4 = 1f
                            rotationConfidenceV4 = 1f
                            scaleConfidenceV4 = 1f
                            newReferenceFrame = true
                        }
                        else -> {
                            confidence = motion.confidence.coerceIn(0f, 1f)
                            translationConfidenceV4 = motion.translationConfidenceV4.coerceIn(0f, 1f)
                            rotationConfidenceV4 = motion.rotationConfidenceV4.coerceIn(0f, 1f)
                            scaleConfidenceV4 = motion.scaleConfidenceV4.coerceIn(0f, 1f)

                            val measuredX = motion.centerDxPx / max(1f, width * .5f)
                            val measuredY = -motion.centerDyPx / max(1f, height * .5f)
                            val measuredRotation = -motion.rotationDegreesImage
                            val measuredLogScale = ln(motion.scale.coerceIn(.94f, 1.06f))

                            val incX: Float
                            val incY: Float
                            if (translationConfidenceV4 >= MIN_TRANSLATION_CONFIDENCE_V4) {
                                incX = measuredX
                                incY = measuredY
                                lastTrustedIncX = measuredX
                                lastTrustedIncY = measuredY
                                untrustedTranslationFrames = 0
                            } else {
                                untrustedTranslationFrames++
                                val decay = predictedMotionDecayV4(untrustedTranslationFrames)
                                incX = lastTrustedIncX * decay
                                incY = lastTrustedIncY * decay
                            }

                            val incRotation = if (
                                rotationConfidenceV4 >= MIN_ROTATION_CONFIDENCE_V4
                            ) {
                                lastTrustedIncRotation = measuredRotation
                                untrustedRotationFrames = 0
                                measuredRotation
                            } else {
                                untrustedRotationFrames++
                                lastTrustedIncRotation *
                                    predictedMotionDecayV4(untrustedRotationFrames)
                            }

                            val incLogScale = if (
                                scaleConfidenceV4 >= MIN_SCALE_CONFIDENCE_V4
                            ) {
                                lastTrustedIncLogScale = measuredLogScale
                                untrustedScaleFrames = 0
                                measuredLogScale
                            } else {
                                untrustedScaleFrames++
                                lastTrustedIncLogScale *
                                    predictedMotionDecayV4(untrustedScaleFrames)
                            }
                            val incScale = exp(incLogScale.toDouble()).toFloat()

                            val radians = Math.toRadians(incRotation.toDouble())
                            val cosR = cos(radians).toFloat()
                            val sinR = sin(radians).toFloat()
                            val oldX = pathX
                            val oldY = pathY
                            pathX = incScale * (cosR * oldX - sinR * oldY) + incX
                            pathY = incScale * (sinR * oldX + cosR * oldY) + incY
                            pathRotation += incRotation
                            pathLogScale += incLogScale
                        }
                    }
                }

                persistentTripodTracksV3 = when {
                    newReferenceFrame || previous == null || previous.size != current.size -> {
                        segmentReferenceGrayV3 = current.copyOf()
                        seedPersistentTripodTracksV3(current, width, height)
                    }
                    else -> {
                        var advanced = advancePersistentTripodTracksV3(
                            previous = previous,
                            current = current,
                            width = width,
                            height = height,
                            tracks = persistentTripodTracksV3,
                        )
                        val referenceFrame = segmentReferenceGrayV3
                        if (
                            referenceFrame != null &&
                            decoded % TRIPOD_REANCHOR_INTERVAL_FRAMES_V3 == 0
                        ) {
                            advanced = reanchorPersistentTripodTracksV3(
                                reference = referenceFrame,
                                current = current,
                                width = width,
                                height = height,
                                tracks = advanced,
                            )
                        }
                        advanced
                    }
                }

                raw += RawCameraSampleV1(
                    sourceTimeUs = sourceTimeUs,
                    segment = segment,
                    confidence = confidence,
                    translationConfidenceV4 = translationConfidenceV4,
                    rotationConfidenceV4 = rotationConfidenceV4,
                    scaleConfidenceV4 = scaleConfidenceV4,
                    x = pathX,
                    y = pathY,
                    rotation = pathRotation,
                    logScale = pathLogScale,
                    width = width,
                    height = height,
                    tripodObservationsV3 = tripodObservationsV3(persistentTripodTracksV3),
                )
                previousGray = current
                decoded++

                if (decoded % 3 == 0) {
                    val progress = ((sourceTimeUs - startUs).toDouble() / durationUs.toDouble())
                        .toFloat().coerceIn(0f, .97f)
                    onProgress(progress, "Tracking camera ${(progress * 100f).roundToInt()}%")
                }
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }

        require(raw.size >= 2) { "Could not decode enough frames for stabilization" }
        onProgress(.98f, "Solving virtual camera...")
        val samples = solveSegmentsV1(raw)
        val covers = computeCoverScalesV1(samples)
        onProgress(1f, "Stabilization ready")

        base.copy(
            enabled = true,
            samples = samples,
            translationCoverScale = covers.translation,
            similarityCoverScale = covers.similarity,
            tripodCoverScale = covers.tripod,
            analysisVersion = 4,
        ).normalized()
    }

    private fun predictedMotionDecayV4(untrustedFrames: Int): Float = when (untrustedFrames) {
        1 -> .72f
        2 -> .42f
        3 -> .18f
        else -> 0f
    }

    private data class RawCameraSampleV1(
        val sourceTimeUs: Long,
        val segment: Int,
        val confidence: Float,
        val translationConfidenceV4: Float,
        val rotationConfidenceV4: Float,
        val scaleConfidenceV4: Float,
        val x: Float,
        val y: Float,
        val rotation: Float,
        val logScale: Float,
        val width: Int,
        val height: Int,
        val tripodObservationsV3: List<TripodTrackObservationV3> = emptyList(),
    )

    private fun solveSegmentsV1(raw: List<RawCameraSampleV1>): List<VirtualCameraSampleV1> {
        val output = ArrayList<VirtualCameraSampleV1>(raw.size)
        val grouped = raw.groupBy { it.segment }

        for ((_, items) in grouped) {
            val n = items.size
            val confidence = FloatArray(n) { items[it].confidence.coerceIn(0f, 1f) }
            val translationConfidence = FloatArray(n) {
                items[it].translationConfidenceV4.coerceIn(0f, 1f)
            }
            val rotationConfidence = FloatArray(n) {
                items[it].rotationConfidenceV4.coerceIn(0f, 1f)
            }
            val scaleConfidence = FloatArray(n) {
                items[it].scaleConfidenceV4.coerceIn(0f, 1f)
            }
            val rawX = FloatArray(n) { items[it].x }
            val rawY = FloatArray(n) { items[it].y }
            val rawR = unwrapAnglesV1(FloatArray(n) { items[it].rotation })
            val rawS = FloatArray(n) { items[it].logScale }

            // V4 Translation: a global gimbal path that strongly removes alternating shake but
            // lowers regularization around sustained one-direction pan/tilt motion.
            var smoothX = solveAdaptiveVirtualCameraPathV4(
                raw = rawX,
                confidence = translationConfidence,
                baseLambda = TRANSLATION_LAMBDA_V4,
                intentVelocityThreshold = TRANSLATION_INTENT_VELOCITY_V4,
            )
            var smoothY = solveAdaptiveVirtualCameraPathV4(
                raw = rawY,
                confidence = translationConfidence,
                baseLambda = TRANSLATION_LAMBDA_V4,
                intentVelocityThreshold = TRANSLATION_INTENT_VELOCITY_V4,
            )
            smoothX = safeSolvedPathV4(rawX, smoothX, MIN_SOLVER_GAIN_TRANSLATION_V4)
            smoothY = safeSolvedPathV4(rawY, smoothY, MIN_SOLVER_GAIN_TRANSLATION_V4)
            smoothX = confidenceGatedTargetV4(
                rawX,
                smoothX,
                translationConfidence,
                MIN_TRANSLATION_TARGET_TRUST_V4,
            )
            smoothY = confidenceGatedTargetV4(
                rawY,
                smoothY,
                translationConfidence,
                MIN_TRANSLATION_TARGET_TRUST_V4,
            )
            smoothX = safeSolvedPathV4(rawX, smoothX, MIN_FINAL_GAIN_V4)
            smoothY = safeSolvedPathV4(rawY, smoothY, MIN_FINAL_GAIN_V4)

            // V4 Similarity: rotation and scale use independent, stricter trust and stronger
            // regularization. Scale is the most noise-sensitive DOF and therefore the smoothest.
            var smoothR = solveAdaptiveVirtualCameraPathV4(
                raw = rawR,
                confidence = rotationConfidence,
                baseLambda = ROTATION_LAMBDA_V4,
                intentVelocityThreshold = ROTATION_INTENT_VELOCITY_V4,
            )
            var smoothS = solveAdaptiveVirtualCameraPathV4(
                raw = rawS,
                confidence = scaleConfidence,
                baseLambda = SCALE_LAMBDA_V4,
                intentVelocityThreshold = SCALE_INTENT_VELOCITY_V4,
            )
            smoothR = safeSolvedPathV4(rawR, smoothR, MIN_SOLVER_GAIN_ROTATION_V4)
            smoothS = safeSolvedPathV4(rawS, smoothS, MIN_SOLVER_GAIN_SCALE_V4)
            smoothR = confidenceGatedTargetV4(
                rawR,
                smoothR,
                rotationConfidence,
                MIN_ROTATION_TARGET_TRUST_V4,
            )
            smoothS = confidenceGatedTargetV4(
                rawS,
                smoothS,
                scaleConfidence,
                MIN_SCALE_TARGET_TRUST_V4,
            )
            smoothR = safeSolvedPathV4(rawR, smoothR, MIN_FINAL_GAIN_V4)
            smoothS = safeSolvedPathV4(rawS, smoothS, MIN_FINAL_GAIN_V4)

            val tripodPathV3 = solvePersistentTripodPathV3(
                items = items,
                fallbackX = rawX,
                fallbackY = rawY,
                fallbackRotation = rawR,
                fallbackLogScale = rawS,
            )

            // V3 Tripod target is exactly the first-frame background geometry.
            // The raw Tripod pose comes from the same long-lived background points at every frame.
            val lockX = 0f
            val lockY = 0f
            val lockR = 0f
            val lockS = 0f

            for (i in 0 until n) {
                val item = items[i]
                output += VirtualCameraSampleV1(
                    sourceTimeUs = item.sourceTimeUs,
                    segment = item.segment,
                    confidence = item.confidence,
                    rawX = rawX[i],
                    rawY = rawY[i],
                    rawRotationDegrees = rawR[i],
                    rawLogScale = rawS[i],
                    tripodRawX = tripodPathV3.x[i],
                    tripodRawY = tripodPathV3.y[i],
                    tripodRawRotationDegrees = tripodPathV3.rotationDegrees[i],
                    tripodRawLogScale = tripodPathV3.logScale[i],
                    smoothX = smoothX[i],
                    smoothY = smoothY[i],
                    smoothRotationDegrees = smoothR[i],
                    smoothLogScale = smoothS[i],
                    lockX = lockX,
                    lockY = lockY,
                    lockRotationDegrees = lockR,
                    lockLogScale = lockS,
                )
            }
        }
        return output.sortedBy { it.sourceTimeUs }
    }

    private data class TripodPathV3(
        val x: FloatArray,
        val y: FloatArray,
        val rotationDegrees: FloatArray,
        val logScale: FloatArray,
    )

    private fun solvePersistentTripodPathV3(
        items: List<RawCameraSampleV1>,
        fallbackX: FloatArray,
        fallbackY: FloatArray,
        fallbackRotation: FloatArray,
        fallbackLogScale: FloatArray,
    ): TripodPathV3 {
        val n = items.size
        if (n == 0) {
            return TripodPathV3(
                fallbackX.copyOf(),
                fallbackY.copyOf(),
                fallbackRotation.copyOf(),
                fallbackLogScale.copyOf(),
            )
        }

        val survivorIds = survivingTripodTrackIdsV3(
            items.map { it.tripodObservationsV3 },
        )
        if (survivorIds.size < MIN_PERSISTENT_TRIPOD_TRACKS_V3) {
            return TripodPathV3(
                fallbackX.copyOf(),
                fallbackY.copyOf(),
                fallbackRotation.copyOf(),
                fallbackLogScale.copyOf(),
            )
        }

        // Pass 1: identify which final-survivor tracks behave like the dominant static background
        // for most of the shot. A moving foreground subject can survive in-frame, but it will fail
        // this consensus test and will not participate in the final fixed-reference solve.
        val firstPass = items.map { item ->
            estimateTripodReferencePoseV3(
                observations = item.tripodObservationsV3,
                survivorIds = survivorIds,
                width = item.width,
                height = item.height,
            )
        }
        val validFirstPass = firstPass.count { it != null }.coerceAtLeast(1)
        val inlierVotes = HashMap<Int, Int>()
        firstPass.forEach { pose ->
            pose?.inlierTrackIds?.forEach { id ->
                inlierVotes[id] = (inlierVotes[id] ?: 0) + 1
            }
        }
        val requiredVotes = (validFirstPass * BACKGROUND_INLIER_FRACTION_V3)
            .roundToInt()
            .coerceAtLeast(2)
        val backgroundIds = survivorIds.filterTo(linkedSetOf()) { id ->
            (inlierVotes[id] ?: 0) >= requiredVotes
        }
        val finalIds = if (backgroundIds.size >= MIN_PERSISTENT_TRIPOD_TRACKS_V3) {
            backgroundIds
        } else {
            survivorIds
        }

        val x = FloatArray(n) { Float.NaN }
        val y = FloatArray(n) { Float.NaN }
        val r = FloatArray(n) { Float.NaN }
        val s = FloatArray(n) { Float.NaN }

        // Reference frame is identity by construction.
        x[0] = 0f
        y[0] = 0f
        r[0] = 0f
        s[0] = 0f

        for (i in 1 until n) {
            val item = items[i]
            val pose = estimateTripodReferencePoseV3(
                observations = item.tripodObservationsV3,
                survivorIds = finalIds,
                width = item.width,
                height = item.height,
            ) ?: continue
            if (pose.confidence < MIN_PERSISTENT_TRIPOD_CONFIDENCE_V3) continue

            x[i] = pose.centerDxPx / max(1f, item.width * .5f)
            y[i] = -pose.centerDyPx / max(1f, item.height * .5f)
            r[i] = -pose.rotationDegreesImage
            s[i] = ln(pose.scale.coerceIn(.90f, 1.10f))
        }

        fillMissingTripodChannelV3(x, fallbackX)
        fillMissingTripodChannelV3(y, fallbackY)
        fillMissingTripodChannelV3(r, fallbackRotation)
        fillMissingTripodChannelV3(s, fallbackLogScale)

        return TripodPathV3(x, y, r, s)
    }

    private fun fillMissingTripodChannelV3(
        values: FloatArray,
        fallback: FloatArray,
    ) {
        val known = values.indices.filter { values[it].isFinite() }
        if (known.size < 2) {
            for (i in values.indices) values[i] = fallback[i]
            return
        }

        var left = known.first()
        for (right in known.drop(1)) {
            val span = (right - left).coerceAtLeast(1)
            for (i in left + 1 until right) {
                val t = (i - left).toFloat() / span.toFloat()
                values[i] = values[left] + (values[right] - values[left]) * t
            }
            left = right
        }

        for (i in 0 until known.first()) values[i] = values[known.first()]
        for (i in known.last() + 1 until values.size) {
            // Tail fallback only happens if the final persistent set temporarily fails model
            // confidence. Continue the last known fixed-reference pose rather than injecting a
            // pairwise-path jump into Tripod output.
            values[i] = values[known.last()]
        }
    }

    private data class CoverScalesV1(
        val translation: Float,
        val similarity: Float,
        val tripod: Float,
    )

    private fun computeCoverScalesV1(samples: List<VirtualCameraSampleV1>): CoverScalesV1 {
        var translation = 1f
        var similarity = 1f
        var tripod = 1f

        for (sample in samples) {
            translation = max(
                translation,
                requiredVirtualCameraCoverScaleV1(
                    sample.smoothX - sample.rawX,
                    sample.smoothY - sample.rawY,
                    0f,
                    1f,
                ),
            )

            val similarityCorrection = relativeCorrectionV1(
                sample.rawX,
                sample.rawY,
                sample.rawRotationDegrees,
                exp(sample.rawLogScale.toDouble()).toFloat(),
                sample.smoothX,
                sample.smoothY,
                sample.smoothRotationDegrees,
                exp(sample.smoothLogScale.toDouble()).toFloat(),
            )
            similarity = max(
                similarity,
                requiredVirtualCameraCoverScaleV1(
                    similarityCorrection.x,
                    similarityCorrection.y,
                    similarityCorrection.rotation,
                    similarityCorrection.scale,
                ),
            )

            val tripodCorrection = relativeCorrectionV1(
                sample.tripodRawX,
                sample.tripodRawY,
                sample.tripodRawRotationDegrees,
                exp(sample.tripodRawLogScale.toDouble()).toFloat(),
                sample.lockX,
                sample.lockY,
                sample.lockRotationDegrees,
                exp(sample.lockLogScale.toDouble()).toFloat(),
            )
            tripod = max(
                tripod,
                requiredVirtualCameraCoverScaleV1(
                    tripodCorrection.x,
                    tripodCorrection.y,
                    tripodCorrection.rotation,
                    tripodCorrection.scale,
                ),
            )
        }

        return CoverScalesV1(
            translation.coerceIn(1f, MAX_ANALYSIS_ZOOM_V1),
            similarity.coerceIn(1f, MAX_ANALYSIS_ZOOM_V1),
            tripod.coerceIn(1f, MAX_ANALYSIS_ZOOM_V1),
        )
    }

    private data class CorrectionV1(
        val x: Float,
        val y: Float,
        val rotation: Float,
        val scale: Float,
    )

    private fun relativeCorrectionV1(
        rawX: Float,
        rawY: Float,
        rawRotation: Float,
        rawScale: Float,
        targetX: Float,
        targetY: Float,
        targetRotation: Float,
        targetScale: Float,
    ): CorrectionV1 {
        val scale = (targetScale / rawScale.coerceAtLeast(.01f)).coerceIn(.70f, 1.40f)
        val rotation = targetRotation - rawRotation
        val radians = Math.toRadians(rotation.toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        val rotatedX = scale * (c * rawX - s * rawY)
        val rotatedY = scale * (s * rawX + c * rawY)
        return CorrectionV1(
            x = targetX - rotatedX,
            y = targetY - rotatedY,
            rotation = rotation,
            scale = scale,
        )
    }

    private fun Bitmap.toGrayV1(): IntArray {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val pixels = IntArray(w * h)
        getPixels(pixels, 0, w, 0, 0, w, h)
        return IntArray(pixels.size) { index ->
            val argb = pixels[index]
            val r = (argb ushr 16) and 0xff
            val g = (argb ushr 8) and 0xff
            val b = argb and 0xff
            (r * 77 + g * 150 + b * 29) ushr 8
        }
    }

    private fun weightedMeanV1(values: FloatArray, confidence: FloatArray): Float {
        var total = 0.0
        var weight = 0.0
        for (i in values.indices) {
            val w = (.05f + .95f * confidence[i].coerceIn(0f, 1f)).toDouble()
            total += values[i] * w
            weight += w
        }
        return if (weight <= 1e-8) values.firstOrNull() ?: 0f else (total / weight).toFloat()
    }

    private fun unwrapAnglesV1(values: FloatArray): FloatArray {
        if (values.isEmpty()) return values
        val output = values.copyOf()
        for (i in 1 until output.size) {
            while (output[i] - output[i - 1] > 180f) output[i] -= 360f
            while (output[i] - output[i - 1] < -180f) output[i] += 360f
        }
        return output
    }

    private companion object {
        const val ANALYSIS_LONG_EDGE_V1 = 384
        const val MIN_ACCEPTED_CONFIDENCE_V1 = .34f
        const val MIN_TRANSLATION_CONFIDENCE_V4 = .28f
        const val MIN_ROTATION_CONFIDENCE_V4 = .42f
        const val MIN_SCALE_CONFIDENCE_V4 = .48f
        const val TRANSLATION_LAMBDA_V4 = 175f
        const val ROTATION_LAMBDA_V4 = 235f
        const val SCALE_LAMBDA_V4 = 330f
        const val TRANSLATION_INTENT_VELOCITY_V4 = .0018f
        const val ROTATION_INTENT_VELOCITY_V4 = .025f
        const val SCALE_INTENT_VELOCITY_V4 = .00035f
        const val MIN_TRANSLATION_TARGET_TRUST_V4 = .24f
        const val MIN_ROTATION_TARGET_TRUST_V4 = .40f
        const val MIN_SCALE_TARGET_TRUST_V4 = .48f
        const val MIN_SOLVER_GAIN_TRANSLATION_V4 = .28f
        const val MIN_SOLVER_GAIN_ROTATION_V4 = .34f
        const val MIN_SOLVER_GAIN_SCALE_V4 = .42f
        const val MIN_FINAL_GAIN_V4 = .12f
        const val TRIPOD_REANCHOR_INTERVAL_FRAMES_V3 = 3
        const val MIN_PERSISTENT_TRIPOD_TRACKS_V3 = 6
        const val MIN_PERSISTENT_TRIPOD_CONFIDENCE_V3 = .46f
        const val BACKGROUND_INLIER_FRACTION_V3 = .65f
        const val MAX_ANALYSIS_ZOOM_V1 = 1.65f
    }
}
