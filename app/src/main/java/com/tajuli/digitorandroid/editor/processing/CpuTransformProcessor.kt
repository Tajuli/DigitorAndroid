package com.tajuli.digitorandroid.editor.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.tajuli.digitorandroid.editor.model.ClipTransform
import com.tajuli.digitorandroid.editor.model.TimelineClip

/** Software geometry path used only by the CPU export fallback. */
object CpuTransformProcessor {
    fun render(
        source: Bitmap,
        outputWidth: Int,
        outputHeight: Int,
        clip: TimelineClip,
        clipLocalUs: Long,
    ): Bitmap {
        val value = clip.transform.evaluate(clipLocalUs)
        val identity = clip.transform.isStaticIdentity
        if (identity && source.width == outputWidth && source.height == outputHeight) return source
        if (identity) return Bitmap.createScaledBitmap(source, outputWidth, outputHeight, true)

        val output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val cx = outputWidth * .5f
        val cy = outputHeight * .5f
        val translateX = ClipTransform.normalizedPositionPixels(value.positionX, outputWidth)
        val translateY = ClipTransform.normalizedPositionPixels(value.positionY, outputHeight)

        canvas.save()
        canvas.translate(cx + translateX, cy + translateY)
        canvas.rotate(value.rotationDegrees)
        canvas.scale(value.scaleX, value.scaleY)
        canvas.translate(-cx, -cy)
        canvas.drawBitmap(
            source,
            null,
            RectF(0f, 0f, outputWidth.toFloat(), outputHeight.toFloat()),
            paint,
        )
        canvas.restore()
        return output
    }
}
