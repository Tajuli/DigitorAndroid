package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val PERSON_ROI_STALE_US_V57 = 1_500_000L
private const val PERSON_ROI_SIDE_MARGIN_V60 = .08f
private const val PERSON_ROI_TOP_MARGIN_V60 = .07f
private const val PERSON_ROI_BOTTOM_MARGIN_V60 = .06f
private const val PERSON_ROI_BOOTSTRAP_EDGE_V59 = 256
private const val PERSON_ROI_BOOTSTRAP_ALPHA_V60 = 190
private const val PERSON_ROI_GATE_EDGE_V60 = 192
private const val PERSON_ROI_GATE_CORE_ALPHA_V60 = 200
private const val PERSON_ROI_GATE_FEATHER_V60 = 4

/**
 * Robust person ROI wrapper for PP-MattingV2 384.
 *
 * The important invariant is crop-before-resize: a real source-frame person box is cropped first,
 * then that crop is padded to square and only then PP-MattingV2 performs its fixed 384 resize.
 * This concentrates the model resolution on the subject instead of the whole 16:9 scene.
 *
 * A rectangular ROI alone cannot remove a chair that is physically behind the person but still
 * inside the rectangle. To suppress that failure mode without replacing PP-MattingV2 with a coarse
 * segmentation mask, the dense PP-Matting result is additionally constrained by a generous
 * high-confidence connected foreground envelope. PP-MattingV2 still supplies all final soft alpha
 * and edge detail inside that envelope.
 */
internal class PersonRoiMatteV57(
    context: Context,
    private val portraitMatte: PortraitMatteBackendV50,
) : AutoCloseable {
    private val detector = FastPersonObjectDetectorV57(context.applicationContext)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var cachedPersonBounds: RectF? = null
    private var lastLocalizedTimeUs: Long = Long.MIN_VALUE
    private var lastLocalizationSource: String = "none"
    private var sourceWidth: Int = 0
    private var sourceHeight: Int = 0
    private var activeRoi: Rect? = null

    val detectorBackendLabel: String
        get() = detector.backendLabel + " + matte bootstrap/track + core envelope"

    fun infer(source: Bitmap, sourceTimeUs: Long): Bitmap {
        check(!source.isRecycled) { "Cannot run ROI matting on a recycled bitmap" }
        resetTrackingIfNeeded(source)

        val people = runCatching { detector.detectPeople(source) }.getOrElse { emptyList() }
        val detected = choosePerson(
            detections = people,
            previous = cachedPersonBounds,
            frameWidth = source.width,
            frameHeight = source.height,
        )

        if (detected != null) {
            updateTrackedBounds(detected.bounds, sourceTimeUs)
            lastLocalizationSource = detected.source
        }

        val tracked = cachedPersonBounds
        val trackedIsRecent = tracked != null && lastLocalizedTimeUs != Long.MIN_VALUE &&
            abs(sourceTimeUs - lastLocalizedTimeUs) <= PERSON_ROI_STALE_US_V57

        if (!trackedIsRecent) {
            val bootstrapBounds = bootstrapPersonBounds(source)
            check(bootstrapBounds != null) {
                "Person ROI localization failed: detector/semantic locator missed and PP-Matting bootstrap found no foreground"
            }
            updateTrackedBounds(bootstrapBounds, sourceTimeUs)
            lastLocalizationSource = "PP-Matting bootstrap"
        }

        val roi = tightRoi(cachedPersonBounds!!, source.width, source.height)
        activeRoi = roi

        val crop = Bitmap.createBitmap(source, roi.left, roi.top, roi.width(), roi.height())
        val rawRoiMatte = try {
            inferWithEdgeReplicatedSquare(crop)
        } finally {
            if (!crop.isRecycled) crop.recycle()
        }

        val gatedRoiMatte = buildForegroundEnvelopeGatedMatte(rawRoiMatte)
        val roiMatte = gatedRoiMatte ?: rawRoiMatte
        val gateApplied = gatedRoiMatte != null
        if (roiMatte !== rawRoiMatte && !rawRoiMatte.isRecycled) rawRoiMatte.recycle()

        // Refresh tracking from the actual dense ROI matte, but do not allow a leaked chair/vase to
        // suddenly expand the tracked box. External detector/semantic localization can still move
        // the box freely on the next frame.
        dominantForegroundBounds(roiMatte)?.let { local ->
            val sourceBounds = RectF(
                roi.left + local.left,
                roi.top + local.top,
                roi.left + local.right,
                roi.top + local.bottom,
            )
            updateTrackedBoundsFromMatte(sourceBounds, sourceTimeUs)
            if (detected == null) lastLocalizationSource = "Dense matte track"
        }

        publishRoiProof(
            roi = roi,
            frameWidth = source.width,
            frameHeight = source.height,
            source = lastLocalizationSource,
            gateApplied = gateApplied,
        )

        return try {
            Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also { full ->
                val canvas = Canvas(full)
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(roiMatte, roi.left.toFloat(), roi.top.toFloat(), paint)
            }
        } finally {
            if (!roiMatte.isRecycled) roiMatte.recycle()
        }
    }

    /**
     * Hair/temporal refinement works in full-frame coordinates. Clamp the final matte back to the
     * active ROI so those refiners cannot reintroduce background outside the crop.
     */
    fun clampToActiveRoi(matte: Bitmap): Bitmap {
        val roi = activeRoi ?: error("No verified person ROI is active for this matte")
        check(matte.width == sourceWidth && matte.height == sourceHeight) {
            "Final matte dimensions do not match the ROI analysis frame"
        }

        return Bitmap.createBitmap(matte.width, matte.height, Bitmap.Config.ARGB_8888).also { output ->
            val canvas = Canvas(output)
            canvas.drawColor(Color.BLACK)
            val save = canvas.save()
            canvas.clipRect(roi)
            canvas.drawBitmap(matte, 0f, 0f, paint)
            canvas.restoreToCount(save)
        }
    }

    private fun resetTrackingIfNeeded(source: Bitmap) {
        val dimensionsChanged = source.width != sourceWidth || source.height != sourceHeight
        if (dimensionsChanged) {
            cachedPersonBounds = null
            lastLocalizedTimeUs = Long.MIN_VALUE
            lastLocalizationSource = "none"
            activeRoi = null
            PersonRoiRuntimeStatusV60.update(null)
        }
        sourceWidth = source.width
        sourceHeight = source.height
    }

    private fun updateTrackedBounds(bounds: RectF, sourceTimeUs: Long) {
        val clipped = clipBounds(bounds)
        if (clipped.width() < 2f || clipped.height() < 2f) return
        cachedPersonBounds = cachedPersonBounds?.let { previous ->
            smoothBounds(previous, clipped)
        } ?: clipped
        lastLocalizedTimeUs = sourceTimeUs
    }

    private fun updateTrackedBoundsFromMatte(bounds: RectF, sourceTimeUs: Long) {
        val candidate = clipBounds(bounds)
        if (candidate.width() < 2f || candidate.height() < 2f) return
        val previous = cachedPersonBounds
        if (previous == null) {
            cachedPersonBounds = candidate
            lastLocalizedTimeUs = sourceTimeUs
            return
        }

        val maxExpandX = previous.width() * .06f
        val maxExpandY = previous.height() * .06f
        val constrained = RectF(
            max(candidate.left, previous.left - maxExpandX),
            max(candidate.top, previous.top - maxExpandY),
            min(candidate.right, previous.right + maxExpandX),
            min(candidate.bottom, previous.bottom + maxExpandY),
        )
        if (constrained.width() < 2f || constrained.height() < 2f) return
        cachedPersonBounds = smoothBounds(previous, constrained)
        lastLocalizedTimeUs = sourceTimeUs
    }

    private fun clipBounds(bounds: RectF): RectF = RectF(
        bounds.left.coerceIn(0f, sourceWidth.toFloat()),
        bounds.top.coerceIn(0f, sourceHeight.toFloat()),
        bounds.right.coerceIn(0f, sourceWidth.toFloat()),
        bounds.bottom.coerceIn(0f, sourceHeight.toFloat()),
    )

    /**
     * Run PP-Matting full-frame only to bootstrap a person box when both cheap locators fail. This
     * matte is discarded; final output still comes from a second PP-Matting pass on the tight ROI.
     */
    private fun bootstrapPersonBounds(source: Bitmap): RectF? {
        val bootstrap = portraitMatte.infer(source)
        return try {
            dominantForegroundBounds(bootstrap)
        } finally {
            bootstrap.recycle()
        }
    }

    /** Find the dominant high-confidence connected foreground component. */
    private fun dominantForegroundBounds(matte: Bitmap): RectF? {
        val longEdge = max(matte.width, matte.height).coerceAtLeast(1)
        val scale = min(1f, PERSON_ROI_BOOTSTRAP_EDGE_V59 / longEdge.toFloat())
        val workW = (matte.width * scale).roundToInt().coerceAtLeast(1)
        val workH = (matte.height * scale).roundToInt().coerceAtLeast(1)
        val work = if (workW == matte.width && workH == matte.height) {
            matte
        } else {
            Bitmap.createScaledBitmap(matte, workW, workH, true)
        }

        try {
            val count = workW * workH
            val pixels = IntArray(count)
            work.getPixels(pixels, 0, workW, 0, 0, workW, workH)
            val foreground = BooleanArray(count)
            for (i in 0 until count) {
                foreground[i] = Color.red(pixels[i]) >= PERSON_ROI_BOOTSTRAP_ALPHA_V60
            }

            val component = findBestForegroundComponent(foreground, workW, workH) ?: return null
            val scaleX = matte.width.toFloat() / workW.toFloat()
            val scaleY = matte.height.toFloat() / workH.toFloat()
            return RectF(
                component.left * scaleX,
                component.top * scaleY,
                component.right * scaleX,
                component.bottom * scaleY,
            )
        } finally {
            if (work !== matte && !work.isRecycled) work.recycle()
        }
    }

    /**
     * Suppress background leakage that sits inside the rectangular bbox (for example a chair behind
     * the shoulders). We seed from only very confident PP-Matting foreground, keep the dominant
     * centered component, then dilate/feather it generously. The original PP alpha is multiplied by
     * this envelope, so edge quality is still PP-MattingV2 rather than a hard segmentation mask.
     */
    private fun buildForegroundEnvelopeGatedMatte(matte: Bitmap): Bitmap? {
        val longEdge = max(matte.width, matte.height).coerceAtLeast(1)
        val scale = min(1f, PERSON_ROI_GATE_EDGE_V60 / longEdge.toFloat())
        val workW = (matte.width * scale).roundToInt().coerceAtLeast(1)
        val workH = (matte.height * scale).roundToInt().coerceAtLeast(1)
        val work = if (workW == matte.width && workH == matte.height) {
            matte
        } else {
            Bitmap.createScaledBitmap(matte, workW, workH, true)
        }

        val gateSmall = try {
            val count = workW * workH
            val pixels = IntArray(count)
            work.getPixels(pixels, 0, workW, 0, 0, workW, workH)
            val foreground = BooleanArray(count)
            for (i in 0 until count) {
                foreground[i] = Color.red(pixels[i]) >= PERSON_ROI_GATE_CORE_ALPHA_V60
            }

            val component = findBestForegroundComponent(foreground, workW, workH) ?: return null
            buildDilatedComponentGate(component.pixels, workW, workH)
        } finally {
            if (work !== matte && !work.isRecycled) work.recycle()
        }

        val gate = if (gateSmall.width == matte.width && gateSmall.height == matte.height) {
            gateSmall
        } else {
            Bitmap.createScaledBitmap(gateSmall, matte.width, matte.height, true).also {
                gateSmall.recycle()
            }
        }

        return try {
            val count = matte.width * matte.height
            val mattePixels = IntArray(count)
            val gatePixels = IntArray(count)
            val outputPixels = IntArray(count)
            matte.getPixels(mattePixels, 0, matte.width, 0, 0, matte.width, matte.height)
            gate.getPixels(gatePixels, 0, matte.width, 0, 0, matte.width, matte.height)
            for (i in 0 until count) {
                val a = Color.red(mattePixels[i])
                val g = Color.red(gatePixels[i])
                val v = (a * g + 127) / 255
                outputPixels[i] = Color.argb(255, v, v, v)
            }
            Bitmap.createBitmap(outputPixels, matte.width, matte.height, Bitmap.Config.ARGB_8888)
        } finally {
            if (!gate.isRecycled) gate.recycle()
        }
    }

    private data class ForegroundComponentV60(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val pixels: IntArray,
    )

    private fun findBestForegroundComponent(
        foreground: BooleanArray,
        width: Int,
        height: Int,
    ): ForegroundComponentV60? {
        val count = width * height
        val visited = BooleanArray(count)
        val queue = IntArray(count)
        val minArea = max(20, (count * .0025f).roundToInt())
        val frameCx = (width - 1) * .5f
        val frameCy = (height - 1) * .5f

        var bestScore = -1f
        var best: ForegroundComponentV60? = null

        for (start in 0 until count) {
            if (visited[start]) continue
            visited[start] = true
            if (!foreground[start]) continue

            var head = 0
            var tail = 0
            queue[tail++] = start
            var area = 0
            var minX = width
            var minY = height
            var maxX = -1
            var maxY = -1
            var sumX = 0L
            var sumY = 0L

            while (head < tail) {
                val index = queue[head++]
                val y = index / width
                val x = index - y * width
                area++
                sumX += x
                sumY += y
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y

                fun offer(next: Int) {
                    if (visited[next]) return
                    visited[next] = true
                    if (!foreground[next]) return
                    queue[tail++] = next
                }

                if (x > 0) offer(index - 1)
                if (x + 1 < width) offer(index + 1)
                if (y > 0) offer(index - width)
                if (y + 1 < height) offer(index + width)
            }

            if (area < minArea || maxX < minX || maxY < minY) continue
            val boxW = maxX - minX + 1
            val boxH = maxY - minY + 1
            if (boxW < width * .05f || boxH < height * .10f) continue

            val cx = sumX.toFloat() / area.toFloat()
            val cy = sumY.toFloat() / area.toFloat()
            val dx = (cx - frameCx) / width.coerceAtLeast(1).toFloat()
            val dy = (cy - frameCy) / height.coerceAtLeast(1).toFloat()
            val centerBonus = 1f + .35f * (1f - min(1f, dx * dx + dy * dy))
            val verticalBonus = 1f + .15f * (boxH.toFloat() / height.toFloat())
            val score = area.toFloat() * centerBonus * verticalBonus

            if (score > bestScore) {
                bestScore = score
                best = ForegroundComponentV60(
                    left = minX,
                    top = minY,
                    right = maxX + 1,
                    bottom = maxY + 1,
                    pixels = queue.copyOf(tail),
                )
            }
        }
        return best
    }

    private fun buildDilatedComponentGate(component: IntArray, width: Int, height: Int): Bitmap {
        val count = width * height
        val radius = max(5, min(width, height) / 18)
        val maxDistance = radius + PERSON_ROI_GATE_FEATHER_V60
        val distance = IntArray(count) { Int.MAX_VALUE }
        val queue = IntArray(count)
        var head = 0
        var tail = 0

        for (index in component) {
            if (index !in 0 until count || distance[index] == 0) continue
            distance[index] = 0
            queue[tail++] = index
        }

        while (head < tail) {
            val index = queue[head++]
            val current = distance[index]
            if (current >= maxDistance) continue
            val y = index / width
            val x = index - y * width
            val nextDistance = current + 1

            fun offer(next: Int) {
                if (distance[next] <= nextDistance) return
                distance[next] = nextDistance
                queue[tail++] = next
            }

            if (x > 0) offer(index - 1)
            if (x + 1 < width) offer(index + 1)
            if (y > 0) offer(index - width)
            if (y + 1 < height) offer(index + width)
            if (x > 0 && y > 0) offer(index - width - 1)
            if (x + 1 < width && y > 0) offer(index - width + 1)
            if (x > 0 && y + 1 < height) offer(index + width - 1)
            if (x + 1 < width && y + 1 < height) offer(index + width + 1)
        }

        val pixels = IntArray(count)
        for (i in 0 until count) {
            val d = distance[i]
            val value = when {
                d <= radius -> 255
                d > maxDistance -> 0
                else -> {
                    val featherStep = d - radius
                    (255f * (1f - featherStep / (PERSON_ROI_GATE_FEATHER_V60 + 1f)))
                        .roundToInt()
                        .coerceIn(0, 255)
                }
            }
            pixels[i] = Color.argb(255, value, value, value)
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun choosePerson(
        detections: List<PersonDetectionV57>,
        previous: RectF?,
        frameWidth: Int,
        frameHeight: Int,
    ): PersonDetectionV57? {
        if (detections.isEmpty()) return null
        val frameArea = (frameWidth.toFloat() * frameHeight.toFloat()).coerceAtLeast(1f)
        val frameCx = frameWidth * .5f
        val frameCy = frameHeight * .5f
        val safeWidth = frameWidth.coerceAtLeast(1).toFloat()
        val safeHeight = frameHeight.coerceAtLeast(1).toFloat()

        return detections.maxByOrNull { detection ->
            val box = detection.bounds
            val areaFraction = (box.width() * box.height() / frameArea).coerceIn(0f, 1f)
            val cx = box.centerX()
            val cy = box.centerY()
            val dx = (cx - frameCx) / safeWidth
            val dy = (cy - frameCy) / safeHeight
            val centerScore = (1f - min(1f, dx * dx + dy * dy)).coerceIn(0f, 1f)
            val continuity = previous?.let { intersectionOverUnion(it, box) } ?: 0f

            if (previous == null) {
                detection.score * 2.0f + areaFraction * 1.2f + centerScore * .25f
            } else {
                continuity * 3.0f + detection.score * 1.5f + areaFraction * .35f
            }
        }
    }

    private fun smoothBounds(previous: RectF, current: RectF): RectF {
        val iou = intersectionOverUnion(previous, current)
        if (iou < .15f) return RectF(current)
        val fresh = .72f
        val old = 1f - fresh
        return RectF(
            previous.left * old + current.left * fresh,
            previous.top * old + current.top * fresh,
            previous.right * old + current.right * fresh,
            previous.bottom * old + current.bottom * fresh,
        )
    }

    private fun tightRoi(person: RectF, frameWidth: Int, frameHeight: Int): Rect {
        val personW = person.width().coerceAtLeast(1f)
        val personH = person.height().coerceAtLeast(1f)
        val left = floor(person.left - personW * PERSON_ROI_SIDE_MARGIN_V60)
            .toInt().coerceIn(0, frameWidth - 1)
        val top = floor(person.top - personH * PERSON_ROI_TOP_MARGIN_V60)
            .toInt().coerceIn(0, frameHeight - 1)
        val right = ceil(person.right + personW * PERSON_ROI_SIDE_MARGIN_V60)
            .toInt().coerceIn(left + 1, frameWidth)
        val bottom = ceil(person.bottom + personH * PERSON_ROI_BOTTOM_MARGIN_V60)
            .toInt().coerceIn(top + 1, frameHeight)
        return Rect(left, top, right, bottom)
    }

    /**
     * Preserve person geometry by square-padding the ROI with replicated edge pixels before the
     * backend performs its fixed 384 resize.
     */
    private fun inferWithEdgeReplicatedSquare(crop: Bitmap): Bitmap {
        if (crop.width == crop.height) return portraitMatte.infer(crop)

        val side = max(crop.width, crop.height)
        val offsetX = (side - crop.width) / 2
        val offsetY = (side - crop.height) / 2
        val square = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(square)

        if (crop.height >= crop.width) {
            if (offsetX > 0) {
                canvas.drawBitmap(
                    crop,
                    Rect(0, 0, 1, crop.height),
                    Rect(0, 0, offsetX, side),
                    paint,
                )
            }
            val rightStart = offsetX + crop.width
            if (rightStart < side) {
                canvas.drawBitmap(
                    crop,
                    Rect(crop.width - 1, 0, crop.width, crop.height),
                    Rect(rightStart, 0, side, side),
                    paint,
                )
            }
        } else {
            if (offsetY > 0) {
                canvas.drawBitmap(
                    crop,
                    Rect(0, 0, crop.width, 1),
                    Rect(0, 0, side, offsetY),
                    paint,
                )
            }
            val bottomStart = offsetY + crop.height
            if (bottomStart < side) {
                canvas.drawBitmap(
                    crop,
                    Rect(0, crop.height - 1, crop.width, crop.height),
                    Rect(0, bottomStart, side, side),
                    paint,
                )
            }
        }
        canvas.drawBitmap(crop, offsetX.toFloat(), offsetY.toFloat(), paint)

        val squareMatte = try {
            portraitMatte.infer(square)
        } finally {
            square.recycle()
        }
        return try {
            Bitmap.createBitmap(squareMatte, offsetX, offsetY, crop.width, crop.height)
        } finally {
            squareMatte.recycle()
        }
    }

    private fun publishRoiProof(
        roi: Rect,
        frameWidth: Int,
        frameHeight: Int,
        source: String,
        gateApplied: Boolean,
    ) {
        val frameArea = (frameWidth.toLong() * frameHeight.toLong()).coerceAtLeast(1L)
        val roiArea = roi.width().toLong() * roi.height().toLong()
        val coverage = roiArea * 100.0 / frameArea.toDouble()
        val density = frameArea.toDouble() / roiArea.coerceAtLeast(1L).toDouble()
        PersonRoiRuntimeStatusV60.update(
            buildString {
                append("ROI DEBUG: ACTIVE")
                append(" · source=").append(source)
                append(" · box=").append(roi.left).append(',').append(roi.top)
                append(" ").append(roi.width()).append('x').append(roi.height())
                append(" of ").append(frameWidth).append('x').append(frameHeight)
                append(" · frame=").append("%.1f".format(coverage)).append('%')
                append(" · pixel-density≈").append("%.2f".format(density)).append('x')
                append(" · crop-before-384=YES")
                append(" · outside-ROI alpha=0")
                append(" · core-envelope=").append(if (gateApplied) "ON" else "BYPASS")
            },
        )
    }

    private fun intersectionOverUnion(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    override fun close() {
        detector.close()
    }
}
