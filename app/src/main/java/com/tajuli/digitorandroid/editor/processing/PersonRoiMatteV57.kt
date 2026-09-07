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

private const val PERSON_ROI_STALE_US_V57 = 600_000L
private const val PERSON_ROI_SIDE_MARGIN_V57 = .12f
private const val PERSON_ROI_TOP_MARGIN_V57 = .10f
private const val PERSON_ROI_BOTTOM_MARGIN_V57 = .08f

/**
 * Verified person-detection ROI wrapper for PP-MattingV2 384.
 *
 * The object detector supplies only a person bounding box. We crop scene pixels BEFORE the
 * PP-MattingV2 resize, pad the rectangular crop to square with replicated edge pixels, run the
 * fixed 384 graph on that subject-focused square, crop the alpha back to ROI coordinates and paste
 * it into an otherwise-black full-frame matte.
 *
 * There is deliberately no silent full-frame fallback. If a real person box cannot be found and a
 * recent tracked box is unavailable, analysis fails rather than pretending ROI was used.
 */
internal class PersonRoiMatteV57(
    context: Context,
    private val portraitMatte: PortraitMatteBackendV50,
) : AutoCloseable {
    private val detector = FastPersonObjectDetectorV57(context.applicationContext)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var cachedPersonBounds: RectF? = null
    private var lastSuccessfulDetectionUs: Long = Long.MIN_VALUE
    private var lastSourceTimeUs: Long = Long.MIN_VALUE
    private var sourceWidth: Int = 0
    private var sourceHeight: Int = 0
    private var activeRoi: Rect? = null

    val detectorBackendLabel: String
        get() = detector.backendLabel

    fun infer(source: Bitmap, sourceTimeUs: Long): Bitmap {
        check(!source.isRecycled) { "Cannot run ROI matting on a recycled bitmap" }
        resetTrackingIfNeeded(source, sourceTimeUs)

        val people = runCatching { detector.detectPeople(source) }.getOrElse { emptyList() }
        val detected = choosePerson(
            detections = people,
            previous = cachedPersonBounds,
            frameWidth = source.width,
            frameHeight = source.height,
        )

        if (detected != null) {
            cachedPersonBounds = cachedPersonBounds?.let { previous ->
                smoothBounds(previous, detected.bounds)
            } ?: RectF(detected.bounds)
            lastSuccessfulDetectionUs = sourceTimeUs
        }

        val trackedBounds = cachedPersonBounds
        val recentEnough = trackedBounds != null &&
            lastSuccessfulDetectionUs != Long.MIN_VALUE &&
            sourceTimeUs - lastSuccessfulDetectionUs <= PERSON_ROI_STALE_US_V57
        check(recentEnough) {
            "Person ROI detector could not localize a person; refusing silent full-frame PP-Matting fallback"
        }

        val roi = tightRoi(trackedBounds!!, source.width, source.height)
        activeRoi = roi
        lastSourceTimeUs = sourceTimeUs

        val crop = Bitmap.createBitmap(source, roi.left, roi.top, roi.width(), roi.height())
        val roiMatte = try {
            inferWithEdgeReplicatedSquare(crop)
        } finally {
            if (!crop.isRecycled) crop.recycle()
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

    private fun resetTrackingIfNeeded(source: Bitmap, sourceTimeUs: Long) {
        val dimensionsChanged = source.width != sourceWidth || source.height != sourceHeight
        val nonMonotonic = lastSourceTimeUs != Long.MIN_VALUE && sourceTimeUs <= lastSourceTimeUs
        if (dimensionsChanged || nonMonotonic) {
            cachedPersonBounds = null
            lastSuccessfulDetectionUs = Long.MIN_VALUE
            activeRoi = null
        }
        sourceWidth = source.width
        sourceHeight = source.height
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
