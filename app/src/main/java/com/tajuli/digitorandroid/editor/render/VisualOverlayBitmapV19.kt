package com.tajuli.digitorandroid.editor.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.util.LruCache
import com.tajuli.digitorandroid.editor.model.ClipNodeGraph
import com.tajuli.digitorandroid.editor.model.ShapePresetV19
import com.tajuli.digitorandroid.editor.model.StickerPresetV19
import com.tajuli.digitorandroid.editor.model.VisualOverlayClipV19
import com.tajuli.digitorandroid.editor.model.VisualOverlayKindV19
import com.tajuli.digitorandroid.editor.processing.CpuColorProcessor
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Shared raster source for realtime Compose preview and Media3 export overlays. */
internal object VisualOverlayBitmapCacheV19 {
    private val memory = object : LruCache<String, Bitmap>(24) {}

    fun get(context: Context, spec: VisualOverlayClipV19, maxEdge: Int = 1536): Bitmap {
        val key = cacheKey(spec, maxEdge)
        memory.get(key)?.takeIf { !it.isRecycled }?.let { return it }
        val source = when (spec.kind) {
            VisualOverlayKindV19.IMAGE -> decodeImage(context, spec.imageUri, maxEdge)
            VisualOverlayKindV19.STICKER -> drawSticker(spec.stickerPreset ?: StickerPresetV19.STAR, spec.colorArgb)
            VisualOverlayKindV19.SHAPE -> drawShape(spec.shapePreset ?: ShapePresetV19.RECTANGLE, spec.colorArgb)
        }
        val bitmap = if (spec.kind == VisualOverlayKindV19.IMAGE && spec.imageNodeGraphV20 != null) {
            applyImageNodeGraphV20(source, spec.imageNodeGraphV20)
        } else {
            source
        }
        memory.put(key, bitmap)
        return bitmap
    }

    private fun cacheKey(spec: VisualOverlayClipV19, maxEdge: Int): String = buildString {
        append(spec.kind.name).append('|')
        append(spec.imageUri.orEmpty()).append('|')
        append(spec.stickerPreset?.name.orEmpty()).append('|')
        append(spec.shapePreset?.name.orEmpty()).append('|')
        append(spec.colorArgb.toString(16).lowercase(Locale.US)).append('|')
        // Image grade edits must invalidate both realtime preview and export bitmap caches.
        append(spec.imageNodeGraphV20?.hashCode() ?: 0).append('|')
        append(maxEdge)
    }

    private fun applyImageNodeGraphV20(source: Bitmap, graph: ClipNodeGraph): Bitmap {
        val bitmap = Bitmap.createBitmap(source.width.coerceAtLeast(1), source.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawBitmap(source, 0f, 0f, null)
        if (source !== bitmap && !source.isRecycled) source.recycle()

        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        CpuColorProcessor().use { processor ->
            processor.processNodeGraphArgb8888(pixels, bitmap.width, bitmap.height, graph)
        }
        bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return bitmap
    }

    private fun decodeImage(context: Context, uriString: String?, maxEdge: Int): Bitmap {
        val uri = uriString?.takeIf { it.isNotBlank() }?.let(Uri::parse)
            ?: return errorBitmap()
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            resolver.openInputStream(uri)?.use { input -> BitmapFactory.decodeStream(input, null, bounds) }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return errorBitmap()
        val safeMax = maxEdge.coerceIn(256, 4096)
        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > safeMax * 2) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample.coerceAtLeast(1)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = runCatching {
            resolver.openInputStream(uri)?.use { input -> BitmapFactory.decodeStream(input, null, options) }
        }.getOrNull() ?: return errorBitmap()
        if (max(decoded.width, decoded.height) <= safeMax) return decoded
        val scale = safeMax.toFloat() / max(decoded.width, decoded.height).toFloat()
        val width = (decoded.width * scale).toInt().coerceAtLeast(1)
        val height = (decoded.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(decoded, width, height, true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun drawShape(preset: ShapePresetV19, argb: Long): Bitmap {
        val width = if (preset == ShapePresetV19.RECTANGLE || preset == ShapePresetV19.ARROW) 768 else 512
        val height = 512
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = fillPaint(argb)
        val pad = 42f
        when (preset) {
            ShapePresetV19.RECTANGLE -> canvas.drawRoundRect(
                RectF(pad, 100f, width - pad, height - 100f), 38f, 38f, paint,
            )
            ShapePresetV19.CIRCLE -> canvas.drawCircle(width / 2f, height / 2f, height * .39f, paint)
            ShapePresetV19.TRIANGLE -> {
                val path = Path().apply {
                    moveTo(width / 2f, 42f)
                    lineTo(width - 42f, height - 42f)
                    lineTo(42f, height - 42f)
                    close()
                }
                canvas.drawPath(path, paint)
            }
            ShapePresetV19.ARROW -> canvas.drawPath(arrowPath(width.toFloat(), height.toFloat(), 36f), paint)
        }
        return bitmap
    }

    private fun drawSticker(preset: StickerPresetV19, argb: Long): Bitmap {
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fill = fillPaint(argb)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb.toInt()
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 58f
        }
        when (preset) {
            StickerPresetV19.HEART -> {
                val p = Path().apply {
                    moveTo(256f, 450f)
                    cubicTo(220f, 405f, 70f, 310f, 70f, 178f)
                    cubicTo(70f, 78f, 190f, 42f, 256f, 132f)
                    cubicTo(322f, 42f, 442f, 78f, 442f, 178f)
                    cubicTo(442f, 310f, 292f, 405f, 256f, 450f)
                    close()
                }
                canvas.drawPath(p, fill)
            }
            StickerPresetV19.STAR -> canvas.drawPath(starPath(256f, 256f, 210f, 88f), fill)
            StickerPresetV19.LIGHTNING -> {
                val p = Path().apply {
                    moveTo(284f, 32f); lineTo(112f, 282f); lineTo(232f, 282f)
                    lineTo(194f, 480f); lineTo(402f, 204f); lineTo(278f, 204f); close()
                }
                canvas.drawPath(p, fill)
            }
            StickerPresetV19.CHECK -> {
                val p = Path().apply { moveTo(78f, 270f); lineTo(205f, 394f); lineTo(438f, 126f) }
                canvas.drawPath(p, stroke)
            }
            StickerPresetV19.ARROW -> canvas.drawPath(arrowPath(512f, 512f, 54f), fill)
            StickerPresetV19.SMILE -> {
                canvas.drawCircle(256f, 256f, 210f, fill)
                val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.TRANSPARENT
                    xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(185f, 215f, 26f, cut)
                canvas.drawCircle(327f, 215f, 26f, cut)
                val mouth = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.TRANSPARENT
                    xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
                    style = Paint.Style.STROKE
                    strokeWidth = 34f
                    strokeCap = Paint.Cap.ROUND
                }
                canvas.drawArc(RectF(150f, 218f, 362f, 370f), 15f, 150f, false, mouth)
            }
        }
        return bitmap
    }

    private fun fillPaint(argb: Long): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = argb.toInt()
        style = Paint.Style.FILL
    }

    private fun starPath(cx: Float, cy: Float, outer: Float, inner: Float): Path {
        val path = Path()
        for (point in 0 until 10) {
            val radius = if (point % 2 == 0) outer else inner
            val angle = -PI / 2.0 + point * PI / 5.0
            val x = cx + cos(angle).toFloat() * radius
            val y = cy + sin(angle).toFloat() * radius
            if (point == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    private fun arrowPath(width: Float, height: Float, pad: Float): Path {
        val mid = height / 2f
        val shaftHalf = height * .12f
        val headStart = width * .55f
        return Path().apply {
            moveTo(pad, mid - shaftHalf)
            lineTo(headStart, mid - shaftHalf)
            lineTo(headStart, pad)
            lineTo(width - pad, mid)
            lineTo(headStart, height - pad)
            lineTo(headStart, mid + shaftHalf)
            lineTo(pad, mid + shaftHalf)
            close()
        }
    }

    private fun errorBitmap(): Bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF; style = Paint.Style.STROKE; strokeWidth = 5f }
        canvas.drawRect(6f, 6f, 58f, 58f, paint)
        canvas.drawLine(12f, 12f, 52f, 52f, paint)
    }
}
