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

private const val PERSON_ROI_REFRESH_US_V55 = 220_000L
private const val PERSON_ROI_STALE_US_V55 = 900_000L
private const val PERSON_ROI_SIDE_MARGIN_V55 = .10f
private const val PERSON_ROI_TOP_MARGIN_V55 = .10f
private const val PERSON_ROI_BOTTOM_MARGIN_V55 = .08f

/**
 * Tight tracked person ROI wrapper for PP-MattingV2 384.
 *
 * The localizer is never the final matte. It supplies a largest-person ROI and a generous coarse
 * human gate. PP-MattingV2 supplies all soft alpha/detail. The ROI stays rectangular so a portrait
 * subject does not force a wide scene crop; before neural inference it is letterboxed into a square
 * instead of stretched, preserving body geometry while keeping furniture outside the ROI invisible
 * to PP-MattingV2. After inference the coarse gate is only a background veto.
 */
internal class PersonRoiMatteV55(
    context: Context,
    private val portraitMatte: PortraitMatteBackendV50,
) : AutoCloseable {
    private val localizer = FastPersonSemanticSegmenterV54(context.applicationContext)
    private val pastePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var cachedBounds: RectF? = null
    private var cachedGate: Bitmap? = null
    private var lastDetectionUs: Long = Long.MIN_VALUE
    private var lastSuccessfulDetectionUs: Long = Long.MIN_VALUE
    private var sourceWidth = 0
    private var sourceHeight = 0

    val localizerBackendLabel: String
        get() = if (localizer.usingGpuDelegate) "ROI detect GPU" else "ROI detect CPU"

    fun infer(source: Bitmap, sourceTimeUs: Long): Bitmap {
        val region = regionForFrame(source, sourceTimeUs)
            ?: return portraitMatte.infer(source)
        val roi = tightRoi(region.bounds, source.width, source.height)

        val crop = Bitmap.createBitmap(source, roi.left, roi.top, roi.width(), roi.height())
        val roiMatte = try {
            inferLetterboxed(crop)
        } finally {
            if (!crop.isRecycled) crop.recycle()
        }

        val gatedRoi = try {
            applyBackgroundVeto(roiMatte, region.gate, roi)
        } finally {
            if (!roiMatte.isRecycled) roiMatte.recycle()
        }

        return try {
            Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also { full ->
                val canvas = Canvas(full)
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(gatedRoi, roi.left.toFloat(), roi.top.toFloat(), pastePaint)
            }
        } finally {
            if (!gatedRoi.isRecycled) gatedRoi.recycle()
        }
    }

    /**
     * Keep the tight rectangular ROI but do not geometrically stretch it into the model's square
     * tensor. Black letterbox padding contains no scene pixels, so the 384 tensor remains focused on
     * the subject and the returned alpha can be cropped back to the exact ROI coordinates.
     */
    private fun inferLetterboxed(crop: Bitmap): Bitmap {
        if (crop.width == crop.height) return portraitMatte.infer(crop)

        val side = max(crop.width, crop.height)
        val offsetX = (side - crop.width) / 2
        val offsetY = (side - crop.height) / 2
        val square = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        Canvas(square).apply {
            drawColor(Color.BLACK)
            drawBitmap(crop, offsetX.toFloat(), offsetY.toFloat(), pastePaint)
        }

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

    private data class CachedRegion(
        val bounds: RectF,
        val gate: Bitmap,
    )

    private fun regionForFrame(source: Bitmap, sourceTimeUs: Long): CachedRegion? {
        if (source.width != sourceWidth || source.height != sourceHeight || sourceTimeUs <= lastDetectionUs) {
            clearCachedRegion()
            sourceWidth = source.width
            sourceHeight = source.height
            lastDetectionUs = Long.MIN_VALUE
            lastSuccessfulDetectionUs = Long.MIN_VALUE
        }

        val due = cachedBounds == null || cachedGate == null || lastDetectionUs == Long.MIN_VALUE ||
            sourceTimeUs - lastDetectionUs >= PERSON_ROI_REFRESH_US_V55
        if (due) {
            val detected = runCatching { localizer.detectPersonRegion(source) }.getOrNull()
            lastDetectionUs = sourceTimeUs
            if (detected != null && isPlausible(detected.bounds, source.width, source.height)) {
                cachedBounds = cachedBounds?.let { smoothBounds(it, detected.bounds) } ?: RectF(detected.bounds)
                cachedGate?.let { if (!it.isRecycled) it.recycle() }
                cachedGate = detected.gate
                lastSuccessfulDetectionUs = sourceTimeUs
            } else {
                detected?.gate?.let { if (!it.isRecycled) it.recycle() }
            }
        }

        val bounds = cachedBounds ?: return null
        val gate = cachedGate ?: return null
        if (lastSuccessfulDetectionUs != Long.MIN_VALUE && sourceTimeUs - lastSuccessfulDetectionUs > PERSON_ROI_STALE_US_V55) {
            clearCachedRegion()
            return null
        }
        return CachedRegion(RectF(bounds), gate)
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
        val left = floor(person.left - personW * PERSON_ROI_SIDE_MARGIN_V55).toInt().coerceIn(0, frameWidth - 1)
        val top = floor(person.top - personH * PERSON_ROI_TOP_MARGIN_V55).toInt().coerceIn(0, frameHeight - 1)
        val right = ceil(person.right + personW * PERSON_ROI_SIDE_MARGIN_V55).toInt().coerceIn(left + 1, frameWidth)
        val bottom = ceil(person.bottom + personH * PERSON_ROI_BOTTOM_MARGIN_V55).toInt().coerceIn(top + 1, frameHeight)
        return Rect(left, top, right, bottom)
    }

    private fun applyBackgroundVeto(matte: Bitmap, fullGate: Bitmap, roi: Rect): Bitmap {
        check(matte.width == roi.width() && matte.height == roi.height()) {
            "PP-MattingV2 ROI matte size does not match source ROI"
        }
        check(fullGate.width >= roi.right && fullGate.height >= roi.bottom) {
            "Person gate does not match analyzed frame coordinates"
        }

        val count = matte.width * matte.height
        val mattePixels = IntArray(count)
        val gatePixels = IntArray(count)
        val out = IntArray(count)
        matte.getPixels(mattePixels, 0, matte.width, 0, 0, matte.width, matte.height)
        fullGate.getPixels(
            gatePixels,
            0,
            matte.width,
            roi.left,
            roi.top,
            matte.width,
            matte.height,
        )

        for (i in 0 until count) {
            val alpha = Color.red(mattePixels[i])
            val allow = Color.red(gatePixels[i])
            val value = (alpha * allow + 127) / 255
            out[i] = Color.argb(255, value, value, value)
        }
        return Bitmap.createBitmap(out, matte.width, matte.height, Bitmap.Config.ARGB_8888)
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

    private fun clearCachedRegion() {
        cachedBounds = null
        cachedGate?.let { if (!it.isRecycled) it.recycle() }
        cachedGate = null
    }

    override fun close() {
        clearCachedRegion()
        runCatching { localizer.close() }
    }
}
