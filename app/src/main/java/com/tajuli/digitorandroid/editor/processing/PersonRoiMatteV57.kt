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
private const val PERSON_ROI_SIDE_MARGIN_V57 = .12f
private const val PERSON_ROI_TOP_MARGIN_V57 = .10f
private const val PERSON_ROI_BOTTOM_MARGIN_V57 = .08f
private const val PERSON_ROI_BOOTSTRAP_EDGE_V59 = 256
private const val PERSON_ROI_BOOTSTRAP_ALPHA_V59 = 160

/**
 * Robust person ROI wrapper for PP-MattingV2 384.
 *
 * EfficientDet is the primary locator. If it misses, a recent matte-derived tracked box is reused.
 * If no recent box exists, one explicit full-frame PP-Matting pass is used only to bootstrap the
 * foreground bounding box; that bootstrap matte is NEVER stored as the final cutout. The detected
 * crop is then run through PP-MattingV2 384 again, so final output still comes from the dense ROI
 * inference. Every successful ROI matte refreshes the tracked box for following frames.
 */
internal class PersonRoiMatteV57(
    context: Context,
    private val portraitMatte: PortraitMatteBackendV50,
) : AutoCloseable {
    private val detector = FastPersonObjectDetectorV57(context.applicationContext)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var cachedPersonBounds: RectF? = null
    private var lastLocalizedTimeUs: Long = Long.MIN_VALUE
    private var lastSourceTimeUs: Long = Long.MIN_VALUE
    private var sourceWidth: Int = 0
    private var sourceHeight: Int = 0
    private var activeRoi: Rect? = null

    val detectorBackendLabel: String
        get() = detector.backendLabel + " + matte bootstrap/track"

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
        }

        val roi = tightRoi(cachedPersonBounds!!, source.width, source.height)
        activeRoi = roi
        lastSourceTimeUs = sourceTimeUs

        val crop = Bitmap.createBitmap(source, roi.left, roi.top, roi.width(), roi.height())
        val roiMatte = try {
            inferWithEdgeReplicatedSquare(crop)
        } finally {
            if (!crop.isRecycled) crop.recycle()
        }

        // Refresh tracking from the actual dense ROI matte. This keeps ROI alive even when the
        // external detector misses several consecutive frames, and it follows slow subject motion.
        dominantForegroundBounds(roiMatte)?.let { local ->
            val sourceBounds = RectF(
                roi.left + local.left,
                roi.top + local.top,
                roi.left + local.right,
                roi.top + local.bottom,
            )
            updateTrackedBounds(sourceBounds, sourceTimeUs)
        }

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
     * verified ROI so those refiners cannot reintroduce a chair, vase or other background outside
     * the detected person box.
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
            activeRoi = null
        }
        sourceWidth = source.width
        sourceHeight = source.height
    }

    private fun updateTrackedBounds(bounds: RectF, sourceTimeUs: Long) {
        val clipped = RectF(
            bounds.left.coerceIn(0f, sourceWidth.toFloat()),
            bounds.top.coerceIn(0f, sourceHeight.toFloat()),
            bounds.right.coerceIn(0f, sourceWidth.toFloat()),
            bounds.bottom.coerceIn(0f, sourceHeight.toFloat()),
        )
        if (clipped.width() < 2f || clipped.height() < 2f) return
        cachedPersonBounds = cachedPersonBounds?.let { previous ->
            smoothBounds(previous, clipped)
        } ?: clipped
        lastLocalizedTimeUs = sourceTimeUs
    }

    /**
     * Guaranteed bootstrap path for a visible subject: run PP-Matting full-frame once only to find
     * the dominant high-confidence foreground component, then discard that matte. The caller always
     * performs a second, subject-focused ROI inference for the final frame matte.
     */
    private fun bootstrapPersonBounds(source: Bitmap): RectF? {
        val bootstrap = portraitMatte.infer(source)
        return try {
            dominantForegroundBounds(bootstrap)
        } finally {
            bootstrap.recycle()
        }
    }

    /**
     * Find the dominant connected high-alpha component on a small working bitmap. Connected-component
     * selection prevents an isolated vase/flower false-positive from widening the bootstrap ROI.
     */
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
                foreground[i] = Color.red(pixels[i]) >= PERSON_ROI_BOOTSTRAP_ALPHA_V59
            }

            val visited = BooleanArray(count)
            val queue = IntArray(count)
            val minArea = max(20, (count * .0025f).roundToInt())
            val frameCx = (workW - 1) * .5f
            val frameCy = (workH - 1) * .5f

            var bestScore = -1f
            var bestLeft = 0
            var bestTop = 0
            var bestRight = 0
            var bestBottom = 0

            for (start in 0 until count) {
                if (visited[start]) continue
                visited[start] = true
                if (!foreground[start]) continue

                var head = 0
                var tail = 0
                queue[tail++] = start
                var area = 0
                var minX = workW
                var minY = workH
                var maxX = -1
                var maxY = -1
                var sumX = 0L
                var sumY = 0L

                while (head < tail) {
                    val index = queue[head++]
                    val y = index / workW
                    val x = index - y * workW
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
                    if (x + 1 < workW) offer(index + 1)
                    if (y > 0) offer(index - workW)
                    if (y + 1 < workH) offer(index + workW)
                }

                if (area < minArea || maxX < minX || maxY < minY) continue
                val boxW = maxX - minX + 1
                val boxH = maxY - minY + 1
                if (boxW < workW * .05f || boxH < workH * .10f) continue

                val cx = sumX.toFloat() / area.toFloat()
                val cy = sumY.toFloat() / area.toFloat()
                val dx = (cx - frameCx) / workW.coerceAtLeast(1).toFloat()
                val dy = (cy - frameCy) / workH.coerceAtLeast(1).toFloat()
                val centerBonus = 1f + .35f * (1f - min(1f, dx * dx + dy * dy))
                val verticalBonus = 1f + .15f * (boxH.toFloat() / workH.toFloat())
                val score = area.toFloat() * centerBonus * verticalBonus

                if (score > bestScore) {
                    bestScore = score
                    bestLeft = minX
                    bestTop = minY
                    bestRight = maxX + 1
                    bestBottom = maxY + 1
                }
            }

            if (bestScore <= 0f) return null
            val scaleX = matte.width.toFloat() / workW.toFloat()
            val scaleY = matte.height.toFloat() / workH.toFloat()
            return RectF(
                bestLeft * scaleX,
                bestTop * scaleY,
                bestRight * scaleX,
                bestBottom * scaleY,
            )
        } finally {
            if (work !== matte && !work.isRecycled) work.recycle()
        }
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
        val left = floor(person.left - personW * PERSON_ROI_SIDE_MARGIN_V57)
            .toInt().coerceIn(0, frameWidth - 1)
        val top = floor(person.top - personH * PERSON_ROI_TOP_MARGIN_V57)
            .toInt().coerceIn(0, frameHeight - 1)
        val right = ceil(person.right + personW * PERSON_ROI_SIDE_MARGIN_V57)
            .toInt().coerceIn(left + 1, frameWidth)
        val bottom = ceil(person.bottom + personH * PERSON_ROI_BOTTOM_MARGIN_V57)
            .toInt().coerceIn(top + 1, frameHeight)
        return Rect(left, top, right, bottom)
    }

    /**
     * PP-MattingV2 expects a square tensor. Preserve person geometry by padding the ROI to square,
     * but replicate the ROI edge pixels instead of adding a synthetic black border. The backend then
     * performs its normal fixed-384 resize on this already-subject-focused square.
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
