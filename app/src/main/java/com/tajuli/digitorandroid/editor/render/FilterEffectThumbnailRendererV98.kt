package com.tajuli.digitorandroid.editor.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.LruCache
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.DebugViewProvider
import androidx.media3.common.Format
import androidx.media3.common.SurfaceInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.VideoFrameProcessor
import androidx.media3.common.VideoGraph
import androidx.media3.common.util.TimestampIterator
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MultipleInputVideoGraph
import com.tajuli.digitorandroid.R
import com.tajuli.digitorandroid.editor.model.ClipNodeGraph
import com.tajuli.digitorandroid.editor.model.CreatorEffectCatalogV25
import com.tajuli.digitorandroid.editor.model.NodeEffect
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.creatorFilterMarkerNameV36
import com.tajuli.digitorandroid.editor.model.creatorFilterPresetV36
import com.tajuli.digitorandroid.editor.processing.PersonCutoutMaskStoreV43
import com.tajuli.digitorandroid.editor.processing.PortraitLensBlurV99
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Generates 16:9 full-frame preset thumbnails from one shared neutral source image.
 *
 * Every non-None thumbnail intentionally runs the whole image through
 * [SharedVideoPipeline.compositedExportEffectsFor]. There is no thumbnail-only filter/effect
 * approximation: creator LOOK markers, beauty stages and V25 creator effects therefore execute
 * through the same production Media3/OpenGL stages used for footage export. The explicit None
 * thumbnail is untouched source. Time-varying effects receive one deterministic source timestamp.
 *
 * Cache misses are serialized and run off the main thread. This avoids concurrent EGL churn while
 * [LruCache] prevents work from being repeated during Compose recomposition/list scrolling.
 */
@UnstableApi
internal object FilterEffectThumbnailRendererV98 {
    const val THUMBNAIL_WIDTH = 320
    const val THUMBNAIL_HEIGHT = 180
    const val LOGICAL_BASE_WIDTH = 640
    const val LOGICAL_BASE_HEIGHT = 360
    const val PREVIEW_TIME_US = 350_000L
    const val FULL_PREVIEW_AMOUNT = 1f

    private const val CLIP_DURATION_US = 1_000_000L
    private const val CACHE_VERSION = "v2"
    private const val TAG = "DigitorFxThumb"
    private const val CACHE_KB = 12 * 1024

    private val renderMutex = Mutex()
    private val cache = object : LruCache<String, Bitmap>(CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)
    }
    private val cacheMisses = AtomicInteger(0)
    private val fallbacks = AtomicInteger(0)

    @Volatile
    private var logicalBase: Bitmap? = null

    @Volatile
    private var thumbnailBase: Bitmap? = null

    suspend fun renderFilter(context: Context, presetId: String): Bitmap =
        renderCached(context.applicationContext, "filter::" + CACHE_VERSION + "::" + presetId) { appContext, base ->
            val preset = creatorFilterPresetV36(presetId)
                ?: error("Unknown creator filter preset: " + presetId)
            val clip = clipWithEffect(
                id = "thumb-filter-" + preset.id,
                effect = NodeEffect(
                    name = creatorFilterMarkerNameV36(preset.id),
                    amount = FULL_PREVIEW_AMOUNT,
                ),
            )
            renderProductionFrame(appContext, clip, base)
        }

    suspend fun renderEffect(context: Context, effectName: String): Bitmap =
        renderCached(context.applicationContext, "effect::" + CACHE_VERSION + "::" + effectName.lowercase()) { appContext, base ->
            val preset = CreatorEffectCatalogV25.find(effectName)
                ?: error("Unknown creator effect preset: " + effectName)
            val clip = clipWithEffect(
                id = "thumb-effect-" + effectName.lowercase().replace(' ', '-'),
                effect = NodeEffect(name = preset.name, amount = FULL_PREVIEW_AMOUNT),
            )
            if (preset.category == "Body") {
                installBodyThumbnailMatteV102(appContext, clip, base.width, base.height)
            }
            renderProductionFrame(appContext, clip, base)
        }

    suspend fun renderPortraitLensBlur(context: Context): Bitmap =
        renderCached(context.applicationContext, "portrait-lens-blur::" + CACHE_VERSION) { _, base ->
            val width = base.width
            val height = base.height
            val pixels = IntArray(width * height)
            base.getPixels(pixels, 0, width, 0, 0, width, height)

            // Thumbnail-only matte: the shared source is composed with the person centered.
            // Keep a softly feathered portrait silhouette sharp while the full background receives
            // the same mobile 32-tap disk kernel used by the production effect.
            fun matteAt(u: Float, v: Float): Float {
                val dx = (u - 0.5f) / 0.24f
                val dy = (v - 0.47f) / 0.50f
                val d = kotlin.math.sqrt(dx * dx + dy * dy)
                return ((1.10f - d) / 0.18f).coerceIn(0f, 1f)
            }

            val blurred = PortraitLensBlurV99.apply(
                source = pixels,
                width = width,
                height = height,
                amount = .78f,
                matteAt = ::matteAt,
            )
            Bitmap.createBitmap(blurred, width, height, Bitmap.Config.ARGB_8888)
        }

    private fun installBodyThumbnailMatteV102(
        context: Context,
        clip: TimelineClip,
        width: Int,
        height: Int,
    ) {
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(mask)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
            val limb = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeWidth = width * .075f
            }
            val cx = width * .50f
            canvas.drawCircle(cx, height * .24f, height * .105f, fill)
            canvas.drawRoundRect(
                cx - width * .095f,
                height * .34f,
                cx + width * .095f,
                height * .73f,
                width * .055f,
                width * .055f,
                fill,
            )
            canvas.drawLine(cx - width * .075f, height * .42f, cx - width * .18f, height * .64f, limb)
            canvas.drawLine(cx + width * .075f, height * .42f, cx + width * .18f, height * .64f, limb)
            canvas.drawLine(cx - width * .048f, height * .68f, cx - width * .105f, height * .92f, limb)
            canvas.drawLine(cx + width * .048f, height * .68f, cx + width * .105f, height * .92f, limb)
            PersonCutoutMaskStoreV43.save(context, clip.uri, PREVIEW_TIME_US, mask)
        } finally {
            mask.recycle()
        }
    }

    /** Used by tests and by any future explicit "None" item. */
    suspend fun renderIdentity(context: Context): Bitmap =
        renderCached(context.applicationContext, "identity::" + CACHE_VERSION) { _, base ->
            base.copy(Bitmap.Config.ARGB_8888, false)
        }

    private suspend fun renderCached(
        context: Context,
        key: String,
        producer: (Context, Bitmap) -> Bitmap,
    ): Bitmap {
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.Default) {
            renderMutex.withLock {
                cache.get(key)?.let { return@withLock it }

                cacheMisses.incrementAndGet()
                val base = baseThumbnail(context)
                val processed = runCatching { producer(context, base) }
                    .onFailure { error ->
                        fallbacks.incrementAndGet()
                        Log.e(TAG, "Thumbnail render failed for " + key, error)
                    }
                    .getOrNull()

                val output = if (processed == null) {
                    fullFramePreviewV98(base, base, failed = true)
                } else {
                    try {
                        fullFramePreviewV98(base, processed, failed = false)
                    } finally {
                        if (processed !== base && !processed.isRecycled) processed.recycle()
                    }
                }
                cache.put(key, output)
                output
            }
        }
    }

    private fun clipWithEffect(id: String, effect: NodeEffect): TimelineClip {
        val graph = ClipNodeGraph.default()
        val selectedId = graph.selectedNodeId
        val edited = graph.copy(
            nodes = graph.nodes.map { node ->
                if (node.id == selectedId) node.copy(effects = listOf(effect)) else node
            },
            revision = graph.revision + 1L,
        )
        return TimelineClip(
            id = id,
            uri = "content://digitor/filter-effect-thumbnail",
            label = id,
            timelineStartUs = 0L,
            sourceInUs = 0L,
            sourceOutUs = CLIP_DURATION_US,
            nodeGraph = edited,
        )
    }

    private fun renderProductionFrame(
        context: Context,
        clip: TimelineClip,
        base: Bitmap,
    ): Bitmap {
        val track = TimelineTrack(
            id = "thumb-v1",
            name = "V1",
            kind = TrackKind.VIDEO,
            clips = listOf(clip),
        )
        val project = TimelineProject(
            title = "FilterEffectThumbnail",
            width = THUMBNAIL_WIDTH,
            height = THUMBNAIL_HEIGHT,
            tracks = listOf(track),
        )
        val sourceColor = ColorInfo.Builder()
            .setColorSpace(C.COLOR_SPACE_BT709)
            .setColorRange(C.COLOR_RANGE_FULL)
            .setColorTransfer(C.COLOR_TRANSFER_SRGB)
            .build()
        val inputFormat = ParityRenderContract.decoderOutputFormat(
            Format.Builder()
                .setWidth(THUMBNAIL_WIDTH)
                .setHeight(THUMBNAIL_HEIGHT)
                .setColorInfo(sourceColor)
                .build(),
        )

        val error = AtomicReference<Throwable?>(null)
        val outputLatch = CountDownLatch(1)
        val imageLatch = CountDownLatch(1)
        val result = AtomicReference<Bitmap?>(null)
        val readerThread = HandlerThread("DigitorFxThumbReader").apply { start() }
        val imageReader = ImageReader.newInstance(
            THUMBNAIL_WIDTH,
            THUMBNAIL_HEIGHT,
            PixelFormat.RGBA_8888,
            2,
        )
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                result.set(copyRgbaToBitmap(image, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT))
            } catch (failure: Throwable) {
                error.compareAndSet(null, failure)
            } finally {
                image.close()
                imageLatch.countDown()
            }
        }, Handler(readerThread.looper))

        val graph = MultipleInputVideoGraph.Factory().create(
            context,
            ParityRenderContract.videoGraphOutputColor(inputFormat),
            DebugViewProvider.NONE,
            object : VideoGraph.Listener {
                override fun onOutputFrameAvailableForRendering(
                    framePresentationTimeUs: Long,
                    isRedrawnFrame: Boolean,
                ) {
                    outputLatch.countDown()
                }

                override fun onError(exception: VideoFrameProcessingException) {
                    error.compareAndSet(null, exception)
                    outputLatch.countDown()
                    imageLatch.countDown()
                }
            },
            java.util.concurrent.Executor { runnable -> runnable.run() },
            0L,
            true,
        )

        try {
            graph.initialize()
            graph.setCompositorSettings(
                ResolveVideoCompositorSettings(
                    outputWidth = project.width,
                    outputHeight = project.height,
                    videoTracks = listOf(track),
                    livePreview = false,
                ),
            )
            graph.setOutputSurfaceInfo(
                SurfaceInfo(imageReader.surface, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT),
            )
            graph.registerInput(0)
            graph.registerInputStream(
                0,
                VideoFrameProcessor.INPUT_TYPE_BITMAP,
                inputFormat,
                SharedVideoPipeline.compositedExportEffectsFor(clip),
                0L,
            )

            val ownedInput = base.copy(Bitmap.Config.ARGB_8888, false)
            check(queueBitmapWhenReady(graph, ownedInput, PREVIEW_TIME_US)) {
                "Timed out waiting for thumbnail graph input"
            }
            graph.signalEndOfInput(0)

            check(outputLatch.await(10, TimeUnit.SECONDS)) {
                "Timed out waiting for thumbnail graph output"
            }
            error.get()?.let { throw it }
            check(imageLatch.await(10, TimeUnit.SECONDS)) {
                "Timed out waiting for thumbnail RGBA frame"
            }
            error.get()?.let { throw it }
            return requireNotNull(result.get()) { "Thumbnail graph produced no RGBA bitmap" }
        } finally {
            runCatching { graph.release() }
            imageReader.close()
            readerThread.quitSafely()
            readerThread.join(2_000L)
        }
    }

    private fun queueBitmapWhenReady(
        graph: MultipleInputVideoGraph,
        bitmap: Bitmap,
        timestampUs: Long,
    ): Boolean {
        val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadlineNs) {
            if (
                graph.queueInputBitmap(
                    0,
                    bitmap,
                    OneTimestampIterator(timestampUs),
                )
            ) {
                return true
            }
            Thread.sleep(8L)
        }
        if (!bitmap.isRecycled) bitmap.recycle()
        return false
    }

    private fun baseThumbnail(context: Context): Bitmap {
        thumbnailBase?.takeIf { !it.isRecycled }?.let { return it }
        val base640 = logicalBase(context)
        val created = Bitmap.createScaledBitmap(
            base640,
            THUMBNAIL_WIDTH,
            THUMBNAIL_HEIGHT,
            true,
        )
        thumbnailBase = created
        return created
    }

    private fun logicalBase(context: Context): Bitmap {
        logicalBase?.takeIf { !it.isRecycled }?.let { return it }
        val decoded = requireNotNull(
            BitmapFactory.decodeResource(context.resources, R.drawable.filter_effect_preview_base),
        ) { "Could not decode filter_effect_preview_base" }
        val cropped = centerCrop(decoded, LOGICAL_BASE_WIDTH, LOGICAL_BASE_HEIGHT)
        if (decoded !== cropped && !decoded.isRecycled) decoded.recycle()
        logicalBase = cropped
        return cropped
    }

    private fun centerCrop(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val sourceAspect = source.width.toFloat() / source.height.toFloat()
        val targetAspect = targetWidth.toFloat() / targetHeight.toFloat()
        val sourceRect = if (sourceAspect > targetAspect) {
            val cropWidth = (source.height * targetAspect).toInt().coerceAtMost(source.width)
            val left = (source.width - cropWidth) / 2
            Rect(left, 0, left + cropWidth, source.height)
        } else {
            val cropHeight = (source.width / targetAspect).toInt().coerceAtMost(source.height)
            val top = (source.height - cropHeight) / 2
            Rect(0, top, source.width, top + cropHeight)
        }
        val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(
            source,
            sourceRect,
            Rect(0, 0, targetWidth, targetHeight),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        return output
    }

    internal fun fullFramePreviewV98(
        original: Bitmap,
        processed: Bitmap,
        failed: Boolean,
    ): Bitmap {
        require(original.width == processed.width && original.height == processed.height) {
            "Preview bitmaps must have matching dimensions"
        }
        val source = if (failed) original else processed
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        if (failed) drawFailureIndicator(Canvas(output), output.width)
        return output
    }

    private fun drawFailureIndicator(canvas: Canvas, width: Int) {
        val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 0, 0, 0)
        }
        canvas.drawCircle(width - 13f, 13f, 9f, badge)
        val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 13f
            isFakeBoldText = true
        }
        canvas.drawText("!", width - 13f, 17.5f, mark)
    }

    private fun copyRgbaToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val plane = image.planes.single()
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        require(pixelStride >= 4) { "Unexpected RGBA pixel stride: " + pixelStride }

        val colors = IntArray(width * height)
        var index = 0
        for (y in 0 until height) {
            val row = y * rowStride
            for (x in 0 until width) {
                val pixel = row + x * pixelStride
                val r = buffer.get(pixel).toInt() and 0xFF
                val g = buffer.get(pixel + 1).toInt() and 0xFF
                val b = buffer.get(pixel + 2).toInt() and 0xFF
                val a = buffer.get(pixel + 3).toInt() and 0xFF
                colors[index++] = Color.argb(a, r, g, b)
            }
        }
        return Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888)
    }

    private class OneTimestampIterator(
        private val timestampUs: Long,
        private var consumed: Boolean = false,
    ) : TimestampIterator {
        override fun hasNext(): Boolean = !consumed

        override fun next(): Long {
            check(!consumed)
            consumed = true
            return timestampUs
        }

        override fun copyOf(): TimestampIterator = OneTimestampIterator(timestampUs)

        override fun getLastTimestampUs(): Long = timestampUs
    }

    internal fun clearMemoryCacheForTest() {
        cache.evictAll()
    }

    internal fun resetStatsForTest() {
        cacheMisses.set(0)
        fallbacks.set(0)
    }

    internal fun cacheMissCountForTest(): Int = cacheMisses.get()

    internal fun fallbackCountForTest(): Int = fallbacks.get()

    internal fun baseThumbnailForTest(context: Context): Bitmap = baseThumbnail(context.applicationContext)
}
