package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val PERSON_ROI_SIDE_MARGIN_V62 = .055f
private const val PERSON_ROI_TOP_MARGIN_V62 = .045f
private const val PERSON_ROI_BOTTOM_MARGIN_V62 = .035f
private const val PERSON_ROI_MAX_FRAME_FRACTION_V62 = .46f
private const val PERSON_ROI_BOOTSTRAP_MAX_FRAME_FRACTION_V62 = .44f
private const val PERSON_ROI_BOOTSTRAP_EDGE_V62 = 256
private const val PERSON_ROI_BOOTSTRAP_ALPHA_V62 = 200
private const val PERSON_ROI_GATE_EDGE_V62 = 192
private const val PERSON_ROI_GATE_CORE_ALPHA_V62 = 205
private const val PERSON_ROI_GATE_FEATHER_V62 = 4
private const val PERSON_ROI_CENTER_TRACK_GAIN_V62 = .25f
private const val PERSON_ROI_CENTER_TRACK_MAX_SHIFT_V62 = .04f

/**
 * Detector-authoritative person ROI wrapper for PP-MattingV2 384.
 *
 * The bbox size comes only from SelfieMulticlass confidence localization or EfficientDet. A dense
 * PP-Matting result is allowed to move the bbox center between detector hits, but it can NEVER grow
 * or shrink bbox width/height. This removes the feedback loop where a leaked chair widened the
 * matte-derived bbox and then remained inside every later crop.
 *
 * The source-frame crop is taken before the fixed 384 resize. PP-MattingV2 remains the final soft
 * alpha source; a generous high-confidence PP-Matting envelope only vetoes obvious in-box leakage.
 */
internal class PersonRoiMatteV57(
    context: Context,
    private val portraitMatte: PortraitMatteBackendV50,
) : AutoCloseable {
    private val detector = FastPersonObjectDetectorV57(context.applicationContext)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var detectorAnchor: RectF? = null
    private var lastDetectorTimeUs: Long = Long.MIN_VALUE
    private var lastSizeAuthoritySource: String = "none"
    private var sourceWidth: Int = 0
    private var sourceHeight: Int = 0
    private var activeRoi: Rect? = null

    val detectorBackendLabel: String
        get() = detector.backendLabel +
            " + detector-only bbox size + matte center-only tracking + core envelope"

    fun infer(source: Bitmap, sourceTimeUs: Long): Bitmap {
        check(!source.isRecycled) { "Cannot run ROI matting on a recycled bitmap" }
        resetTrackingIfNeeded(source)

        val detections = runCatching { detector.detectPeople(source) }.getOrElse { emptyList() }
        val detected = choosePerson(
            detections = detections,
            previous = detectorAnchor,
            frameWidth = source.width,
            frameHeight = source.height,
        )

        if (detected != null) {
            updateDetectorAnchor(detected.bounds, sourceTimeUs, detected.source)
        } else if (detectorAnchor == null) {
            val bootstrapBounds = bootstrapPersonBounds(source)
            check(bootstrapBounds != null) {
                "Person ROI localization failed: Selfie/EfficientDet missed and PP-Matting bootstrap found no foreground"
            }
            detectorAnchor = capBootstrapSeed(bootstrapBounds)
            lastSizeAuthoritySource = "PP-Matting bootstrap seed"
        }

        val anchor = detectorAnchor ?: error("Person ROI anchor is unavailable")
        val roi = tightRoi(anchor, source.width, source.height)
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

        var centerTrackApplied = false
        if (detected == null) {
            dominantForegroundBounds(roiMatte)?.let { local ->
                val sourceBounds = RectF(
                    roi.left + local.left,
                    roi.top + local.top,
                    roi.left + local.right,
                    roi.top + local.bottom,
                )
                centerTrackApplied = shiftAnchorCenterOnly(sourceBounds)
            }
        }

        publishRoiProof(
            roi = roi,
            frameWidth = source.width,
            frameHeight = source.height,
            sourceTimeUs = sourceTimeUs,
            sizeAuthoritySource = lastSizeAuthoritySource,
            centerTrackApplied = centerTrackApplied,
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
     * active ROI so those refiners cannot reintroduce background outside the verified crop.
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
            detectorAnchor = null
            lastDetectorTimeUs = Long.MIN_VALUE
            lastSizeAuthoritySource = "none"
            activeRoi = null
            PersonRoiRuntimeStatusV60.update(null)
        }
        sourceWidth = source.width
        sourceHeight = source.height
    }

    /** Detector/Selfie is the only normal authority allowed to change bbox width and height. */
    private fun updateDetectorAnchor(bounds: RectF, sourceTimeUs: Long, source: String) {
        val clipped = clipBounds(bounds)
        if (clipped.width() < 2f || clipped.height() < 2f) return
        val previous = detectorAnchor
        detectorAnchor = if (
            previous == null ||
            lastSizeAuthoritySource.startsWith("PP-Matting bootstrap") ||
            intersectionOverUnion(previous, clipped) < .08f
        ) {
            clipped
        } else {
            smoothDetectorBounds(previous, clipped, source)
        }
        lastDetectorTimeUs = sourceTimeUs
        lastSizeAuthoritySource = source
    }

    /**
     * Dense matte tracking may move only the center. Width and height are copied byte-for-byte from
     * the detector-owned anchor, so chair/vase leakage can never inflate the next ROI.
     */
    private fun shiftAnchorCenterOnly(bounds: RectF): Boolean {
        val anchor = detectorAnchor ?: return false
        val candidate = clipBounds(bounds)
        if (candidate.width() < 2f || candidate.height() < 2f) return false

        val width = anchor.width()
        val height = anchor.height()
        val maxShiftX = width * PERSON_ROI_CENTER_TRACK_MAX_SHIFT_V62
        val maxShiftY = height * PERSON_ROI_CENTER_TRACK_MAX_SHIFT_V62
        val desiredDx = (candidate.centerX() - anchor.centerX()) * PERSON_ROI_CENTER_TRACK_GAIN_V62
        val desiredDy = (candidate.centerY() - anchor.centerY()) * PERSON_ROI_CENTER_TRACK_GAIN_V62
        val dx = desiredDx.coerceIn(-maxShiftX, maxShiftX)
        val dy = desiredDy.coerceIn(-maxShiftY, maxShiftY)
        if (kotlin.math.abs(dx) < .5f && kotlin.math.abs(dy) < .5f) return false

        val halfW = width * .5f
        val halfH = height * .5f
        val cx = (anchor.centerX() + dx).coerceIn(halfW, sourceWidth - halfW)
        val cy = (anchor.centerY() + dy).coerceIn(halfH, sourceHeight - halfH)
        detectorAnchor = RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        return true
    }

    private fun smoothDetectorBounds(previous: RectF, current: RectF, source: String): RectF {
        val freshCenter = if (source.startsWith("SelfieMulticlass")) .82f else .68f
        val freshSize = if (source.startsWith("SelfieMulticlass")) .72f else .55f
        val cx = previous.centerX() * (1f - freshCenter) + current.centerX() * freshCenter
        val cy = previous.centerY() * (1f - freshCenter) + current.centerY() * freshCenter
        val width = previous.width() * (1f - freshSize) + current.width() * freshSize
        val height = previous.height() * (1f - freshSize) + current.height() * freshSize
        return clipCenteredBox(cx, cy, width, height)
    }

    private fun clipCenteredBox(cx: Float, cy: Float, width: Float, height: Float): RectF {
        val safeW = width.coerceIn(2f, sourceWidth.toFloat())
        val safeH = height.coerceIn(2f, sourceHeight.toFloat())
        val halfW = safeW * .5f
        val halfH = safeH * .5f
        val safeCx = cx.coerceIn(halfW, sourceWidth - halfW)
        val safeCy = cy.coerceIn(halfH, sourceHeight - halfH)
        return RectF(safeCx - halfW, safeCy - halfH, safeCx + halfW, safeCy + halfH)
    }

    private fun clipBounds(bounds: RectF): RectF = RectF(
        bounds.left.coerceIn(0f, sourceWidth.toFloat()),
        bounds.top.coerceIn(0f, sourceHeight.toFloat()),
        bounds.right.coerceIn(0f, sourceWidth.toFloat()),
        bounds.bottom.coerceIn(0f, sourceHeight.toFloat()),
    )

    /** Full-frame PP-Matting is only an emergency first-frame seed; it never becomes final alpha. */
    private fun bootstrapPersonBounds(source: Bitmap): RectF? {
        val bootstrap = portraitMatte.infer(source)
        return try {
            dominantForegroundBounds(bootstrap)
        } finally {
            bootstrap.recycle()
        }
    }

    /** Keep a bootstrap seed from becoming another near-full-frame bbox. */
    private fun capBootstrapSeed(bounds: RectF): RectF {
        val clipped = clipBounds(bounds)
        val frameArea = (sourceWidth.toFloat() * sourceHeight.toFloat()).coerceAtLeast(1f)
        val area = clipped.width() * clipped.height()
        val maxArea = frameArea * PERSON_ROI_BOOTSTRAP_MAX_FRAME_FRACTION_V62
        if (area <= maxArea || clipped.height() <= 1f) return clipped

        val targetWidth = (maxArea / clipped.height()).coerceAtLeast(2f)
        if (targetWidth >= clipped.width()) return clipped
        return clipCenteredBox(clipped.centerX(), clipped.centerY(), targetWidth, clipped.height())
    }

    /** Find the dominant high-confidence connected foreground component. */
    private fun dominantForegroundBounds(matte: Bitmap): RectF? {
        val longEdge = max(matte.width, matte.height).coerceAtLeast(1)
        val scale = min(1f, PERSON_ROI_BOOTSTRAP_EDGE_V62 / longEdge.toFloat())
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
                foreground[i] = Color.red(pixels[i]) >= PERSON_ROI_BOOTSTRAP_ALPHA_V62
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
     * Suppress background leakage that remains inside the rectangular ROI. PP-MattingV2 still owns
     * the final soft alpha; this envelope only vetoes pixels far from its strongest human component.
     */
    private fun buildForegroundEnvelopeGatedMatte(matte: Bitmap): Bitmap? {
        val longEdge = max(matte.width, matte.height).coerceAtLeast(1)
        val scale = min(1f, PERSON_ROI_GATE_EDGE_V62 / longEdge.toFloat())
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
                foreground[i] = Color.red(pixels[i]) >= PERSON_ROI_GATE_CORE_ALPHA_V62
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

    private data class ForegroundComponentV62(
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
    ): ForegroundComponentV62? {
        val count = width * height
        val visited = BooleanArray(count)
        val queue = IntArray(count)
        val minArea = max(20, (count * .0025f).roundToInt())
        val frameCx = (width - 1) * .5f
        val frameCy = (height - 1) * .5f

        var bestScore = -1f
        var best: ForegroundComponentV62? = null

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
                best = ForegroundComponentV62(
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
        val maxDistance = radius + PERSON_ROI_GATE_FEATHER_V62
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
                    (255f * (1f - featherStep / (PERSON_ROI_GATE_FEATHER_V62 + 1f)))
                        .roundToInt()
                        .coerceIn(0, 255)
                }
            }
            pixels[i] = Color.argb(255, value, value, value)
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /** Prefer the semantic human-pixel bbox over the generic object detector rectangle. */
    private fun choosePerson(
        detections: List<PersonDetectionV57>,
        previous: RectF?,
        frameWidth: Int,
        frameHeight: Int,
    ): PersonDetectionV57? {
        if (detections.isEmpty()) return null
        detections
            .filter { it.source.startsWith("SelfieMulticlass") }
            .maxByOrNull { it.score }
            ?.let { return it }

        val frameArea = (frameWidth.toFloat() * frameHeight.toFloat()).coerceAtLeast(1f)
        val frameCx = frameWidth * .5f
        val frameCy = frameHeight * .5f
        val safeWidth = frameWidth.coerceAtLeast(1).toFloat()
        val safeHeight = frameHeight.coerceAtLeast(1).toFloat()

        return detections.maxByOrNull { detection ->
            val box = detection.bounds
            val areaFraction = (box.width() * box.height() / frameArea).coerceIn(0f, 1f)
            val dx = (box.centerX() - frameCx) / safeWidth
            val dy = (box.centerY() - frameCy) / safeHeight
            val centerScore = (1f - min(1f, dx * dx + dy * dy)).coerceIn(0f, 1f)
            val continuity = previous?.let { intersectionOverUnion(it, box) } ?: 0f
            continuity * 2.5f + detection.score * 1.5f + areaFraction * .25f + centerScore * .20f
        }
    }

    /**
     * Add small safety margins, but if margins alone push a detector-owned ROI above 46% of the
     * frame, shrink the margins (never the detector bbox itself) until the ROI is at or below target.
     */
    private fun tightRoi(person: RectF, frameWidth: Int, frameHeight: Int): Rect {
        val raw = RectF(
            person.left.coerceIn(0f, frameWidth.toFloat()),
            person.top.coerceIn(0f, frameHeight.toFloat()),
            person.right.coerceIn(0f, frameWidth.toFloat()),
            person.bottom.coerceIn(0f, frameHeight.toFloat()),
        )

        fun expanded(scale: Float): Rect {
            val personW = raw.width().coerceAtLeast(1f)
            val personH = raw.height().coerceAtLeast(1f)
            val left = floor(raw.left - personW * PERSON_ROI_SIDE_MARGIN_V62 * scale)
                .toInt().coerceIn(0, frameWidth - 1)
            val top = floor(raw.top - personH * PERSON_ROI_TOP_MARGIN_V62 * scale)
                .toInt().coerceIn(0, frameHeight - 1)
            val right = ceil(raw.right + personW * PERSON_ROI_SIDE_MARGIN_V62 * scale)
                .toInt().coerceIn(left + 1, frameWidth)
            val bottom = ceil(raw.bottom + personH * PERSON_ROI_BOTTOM_MARGIN_V62 * scale)
                .toInt().coerceIn(top + 1, frameHeight)
            return Rect(left, top, right, bottom)
        }

        val frameArea = (frameWidth.toLong() * frameHeight.toLong()).coerceAtLeast(1L)
        val rawAreaFraction = raw.width() * raw.height() / frameArea.toFloat()
        var roi = expanded(1f)
        fun fraction(rect: Rect): Float =
            (rect.width().toLong() * rect.height().toLong()).toFloat() / frameArea.toFloat()

        if (fraction(roi) > PERSON_ROI_MAX_FRAME_FRACTION_V62 &&
            rawAreaFraction < PERSON_ROI_MAX_FRAME_FRACTION_V62
        ) {
            var low = 0f
            var high = 1f
            repeat(10) {
                val mid = (low + high) * .5f
                if (fraction(expanded(mid)) <= PERSON_ROI_MAX_FRAME_FRACTION_V62) low = mid
                else high = mid
            }
            roi = expanded(low)
        }
        return roi
    }

    /** Preserve person geometry by square-padding the ROI before the fixed 384 resize. */
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
        sourceTimeUs: Long,
        sizeAuthoritySource: String,
        centerTrackApplied: Boolean,
        gateApplied: Boolean,
    ) {
        val frameArea = (frameWidth.toLong() * frameHeight.toLong()).coerceAtLeast(1L)
        val roiArea = (roi.width().toLong() * roi.height().toLong()).coerceAtLeast(1L)
        val coverage = roiArea * 100.0 / frameArea.toDouble()
        val densityVs384 = frameArea.toDouble() / roiArea.toDouble()
        val densityVs512 = densityVs384 * (384.0 * 384.0) / (512.0 * 512.0)
        val detectorAgeMs = if (lastDetectorTimeUs == Long.MIN_VALUE) null
        else ((sourceTimeUs - lastDetectorTimeUs).coerceAtLeast(0L) / 1000L)
        val authority = if (sizeAuthoritySource.startsWith("PP-Matting bootstrap")) {
            "BOOTSTRAP_SEED_LOCKED"
        } else {
            "DETECTOR_ONLY"
        }

        PersonRoiRuntimeStatusV60.update(
            buildString {
                append("ROI DEBUG: ACTIVE")
                append(" · source=").append(sizeAuthoritySource)
                append(" · size-authority=").append(authority)
                append(" · matte-track=center-only")
                append(" · center-shift=").append(if (centerTrackApplied) "ON" else "OFF")
                detectorAgeMs?.let { append(" · detector-age=").append(it).append("ms") }
                append(" · box=").append(roi.left).append(',').append(roi.top)
                append(" ").append(roi.width()).append('x').append(roi.height())
                append(" of ").append(frameWidth).append('x').append(frameHeight)
                append(" · frame=").append("%.1f".format(coverage)).append('%')
                append(" · density-vs-384≈").append("%.2f".format(densityVs384)).append('x')
                append(" · density-vs-512≈").append("%.2f".format(densityVs512)).append('x')
                append(" · target<=").append((PERSON_ROI_MAX_FRAME_FRACTION_V62 * 100).roundToInt()).append('%')
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
