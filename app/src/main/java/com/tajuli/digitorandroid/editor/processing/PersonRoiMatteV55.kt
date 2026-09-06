package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val PERSON_ROI_REFRESH_US_V55 = 220_000L
private const val PERSON_ROI_STALE_US_V55 = 900_000L
private const val PERSON_ROI_SIDE_MARGIN_V55 = .18f
private const val PERSON_ROI_TOP_MARGIN_V55 = .18f
private const val PERSON_ROI_BOTTOM_MARGIN_V55 = .12f

/**
 * Detect-only person ROI wrapper for PP-MattingV2.
 *
 * The lightweight semantic model contributes exactly one thing: a tracked bounding box. Its pixels
 * are NEVER mixed into the final alpha. PP-MattingV2 256 runs on a square crop around that box and
 * its alpha is then placed back into full-frame coordinates. This keeps the 256 neural workload but
 * gives the matting model many more subject pixels and removes distant furniture/background from
 * its field of view.
 */
internal class PersonRoiMatteV55(
    context: Context,
    private val portraitMatte: PortraitMatteBackendV50,
) : AutoCloseable {
    private val localizer = FastPersonSemanticSegmenterV54(context.applicationContext)
    private val pastePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var cachedBounds: RectF? = null
    private var lastDetectionUs: Long = Long.MIN_VALUE
    private var lastSuccessfulDetectionUs: Long = Long.MIN_VALUE
    private var sourceWidth = 0
    private var sourceHeight = 0

    val localizerBackendLabel: String
        get() = if (localizer.usingGpuDelegate) "ROI detect GPU" else "ROI detect CPU"

    fun infer(source: Bitmap, sourceTimeUs: Long): Bitmap {
        val person = boundsForFrame(source, sourceTimeUs)
        val roi = person?.let { squareRoi(it, source.width, source.height) }
            ?: return portraitMatte.infer(source)

        // When ROI would effectively be the whole frame, avoid an unnecessary crop/paste cycle.
        if (roi.left == 0 && roi.top == 0 && roi.right == source.width && roi.bottom == source.height) {
            return portraitMatte.infer(source)
        }

        val crop = Bitmap.createBitmap(source, roi.left, roi.top, roi.width(), roi.height())
        val roiMatte = try {
            portraitMatte.infer(crop)
        } finally {
            if (crop !== source && !crop.isRecycled) crop.recycle()
        }

        return try {
            Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also { full ->
                val canvas = Canvas(full)
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(roiMatte, roi.left.toFloat(), roi.top.toFloat(), pastePaint)
            }
        } finally {
            if (!roiMatte.isRecycled) roiMatte.recycle()
        }
    }

    private fun boundsForFrame(source: Bitmap, sourceTimeUs: Long): RectF? {
        if (source.width != sourceWidth || source.height != sourceHeight || sourceTimeUs <= lastDetectionUs) {
            sourceWidth = source.width
            sourceHeight = source.height
            cachedBounds = null
            lastDetectionUs = Long.MIN_VALUE
            lastSuccessfulDetectionUs = Long.MIN_VALUE
        }

        val cached = cachedBounds
        val due = cached == null || lastDetectionUs == Long.MIN_VALUE || sourceTimeUs - lastDetectionUs >= PERSON_ROI_REFRESH_US_V55
        if (due) {
            val detected = runCatching { localizer.detectPersonBounds(source) }.getOrNull()
            lastDetectionUs = sourceTimeUs
            if (detected != null && isPlausible(detected, source.width, source.height)) {
                cachedBounds = cached?.let { smoothBounds(it, detected) } ?: RectF(detected)
                lastSuccessfulDetectionUs = sourceTimeUs
            }
        }

        val resolved = cachedBounds ?: return null
        if (lastSuccessfulDetectionUs != Long.MIN_VALUE && sourceTimeUs - lastSuccessfulDetectionUs > PERSON_ROI_STALE_US_V55) {
            cachedBounds = null
            return null
        }
        return RectF(resolved)
    }

    private fun isPlausible(bounds: RectF, width: Int, height: Int): Boolean {
        if (bounds.width() <= 0f || bounds.height() <= 0f) return false
        if (bounds.width() < width * .05f || bounds.height() < height * .12f) return false
        val areaFraction = bounds.width() * bounds.height() / (width.toFloat() * height.toFloat())
        return areaFraction >= .012f
    }

    private fun smoothBounds(previous: RectF, current: RectF): RectF {
        val iou = intersectionOverUnion(previous, current)
        if (iou < .20f) return RectF(current)
        // Favor fresh localization so motion does not lag, while damping one-frame bbox jitter.
        val fresh = .68f
        val old = 1f - fresh
        return RectF(
            previous.left * old + current.left * fresh,
            previous.top * old + current.top * fresh,
            previous.right * old + current.right * fresh,
            previous.bottom * old + current.bottom * fresh,
        )
    }

    private fun squareRoi(person: RectF, frameWidth: Int, frameHeight: Int): Rect {
        val personW = person.width().coerceAtLeast(1f)
        val personH = person.height().coerceAtLeast(1f)
        val expanded = RectF(
            person.left - personW * PERSON_ROI_SIDE_MARGIN_V55,
            person.top - personH * PERSON_ROI_TOP_MARGIN_V55,
            person.right + personW * PERSON_ROI_SIDE_MARGIN_V55,
            person.bottom + personH * PERSON_ROI_BOTTOM_MARGIN_V55,
        )

        val maxSquare = min(frameWidth, frameHeight).coerceAtLeast(1)
        // A square crop cannot safely contain a person wider than the short side of the frame.
        if (person.width() > maxSquare * .98f) return Rect(0, 0, frameWidth, frameHeight)

        val requestedSide = max(expanded.width(), expanded.height())
        val side = min(maxSquare.toFloat(), ceil(requestedSide)).roundToInt().coerceAtLeast(1)

        var cx = expanded.centerX()
        var cy = expanded.centerY()
        // Ensure the original detected person remains inside the capped square even when margins
        // must be reduced because a portrait subject nearly fills a landscape frame's height.
        val half = side * .5f
        cx = cx.coerceIn(person.right - half, person.left + half).coerceIn(half, frameWidth - half)
        cy = cy.coerceIn(person.bottom - half, person.top + half).coerceIn(half, frameHeight - half)

        var left = (cx - half).roundToInt().coerceIn(0, frameWidth - side)
        var top = (cy - half).roundToInt().coerceIn(0, frameHeight - side)
        // Re-shift if rounding put a detected edge outside the ROI.
        if (person.left < left) left = person.left.toInt().coerceIn(0, frameWidth - side)
        if (person.right > left + side) left = ceil(person.right - side).toInt().coerceIn(0, frameWidth - side)
        if (person.top < top) top = person.top.toInt().coerceIn(0, frameHeight - side)
        if (person.bottom > top + side) top = ceil(person.bottom - side).toInt().coerceIn(0, frameHeight - side)

        return Rect(left, top, left + side, top + side)
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
        runCatching { localizer.close() }
    }
}
