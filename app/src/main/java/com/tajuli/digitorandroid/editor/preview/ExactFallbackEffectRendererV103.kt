package com.tajuli.digitorandroid.editor.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
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
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.render.ParityRenderContract
import com.tajuli.digitorandroid.editor.render.SharedVideoPipeline
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Exact-effect bridge for devices where MediaCodec -> realtime Media3 preview never yields a frame.
 *
 * SoftwarePreviewRenderer still owns source-frame decoding in that case, but the decoded Bitmap is
 * sent through the same production color/spatial effect chain as export. This avoids the old state
 * where the viewer visibly fell back to a plain CPU frame while the Effects UI showed RGB Split,
 * Digital Glitch, Lens, Motion or Body presets as selected.
 *
 * The fallback graph intentionally uses non-resident/static preview effects. One bitmap represents
 * one exact editor frame, so there is no benefit in keeping live no-op shaders resident between
 * calls. presentationTimeUs is reconstructed from source time so all V26 timed effects/keyframes
 * evaluate at the same source timestamp as normal preview/export.
 */
@UnstableApi
internal object ExactFallbackEffectRendererV103 {
    private const val GRAPH_TIMEOUT_SECONDS = 8L

    fun render(
        context: Context,
        clip: TimelineClip,
        source: Bitmap,
        sourceTimeUs: Long,
    ): Bitmap? {
        if (source.isRecycled || source.width <= 1 || source.height <= 1) return null

        val width = source.width
        val height = source.height
        val sourceColor = ColorInfo.Builder()
            .setColorSpace(C.COLOR_SPACE_BT709)
            .setColorRange(C.COLOR_RANGE_FULL)
            .setColorTransfer(C.COLOR_TRANSFER_SRGB)
            .build()
        val inputFormat = ParityRenderContract.decoderOutputFormat(
            Format.Builder()
                .setWidth(width)
                .setHeight(height)
                .setColorInfo(sourceColor)
                .build(),
        )
        val error = AtomicReference<Throwable?>(null)
        val outputLatch = CountDownLatch(1)
        val imageLatch = CountDownLatch(1)
        val result = AtomicReference<Bitmap?>(null)
        val readerThread = HandlerThread("DigitorFallbackFxReader").apply { start() }
        val imageReader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            2,
        )
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                result.set(copyRgbaToBitmap(image, width, height))
            } catch (failure: Throwable) {
                error.compareAndSet(null, failure)
            } finally {
                image.close()
                imageLatch.countDown()
            }
        }, Handler(readerThread.looper))

        val graph = MultipleInputVideoGraph.Factory().create(
            context.applicationContext,
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
            graph.setOutputSurfaceInfo(SurfaceInfo(imageReader.surface, width, height))
            graph.registerInput(0)
            graph.registerInputStream(
                0,
                VideoFrameProcessor.INPUT_TYPE_BITMAP,
                inputFormat,
                SharedVideoPipeline.compositedExactPreviewEffectsFor(clip),
                0L,
            )

            // Effects receive composition timestamps; reconstruct the timeline timestamp that maps
            // back to the requested source frame through ParityRenderContract.sourceTimeUs().
            val presentationTimeUs = (
                clip.timelineStartUs +
                    (sourceTimeUs - clip.sourceInUs)
                ).coerceAtLeast(0L)

            val ownedInput = source.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            if (!queueBitmapWhenReady(graph, ownedInput, presentationTimeUs)) return null
            graph.signalEndOfInput(0)

            if (!outputLatch.await(GRAPH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return null
            error.get()?.let { throw it }
            if (!imageLatch.await(GRAPH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return null
            error.get()?.let { throw it }
            return result.get()
        } catch (_: Throwable) {
            return null
        } finally {
            runCatching { graph.release() }
            imageReader.close()
            readerThread.quitSafely()
            runCatching { readerThread.join(2_000L) }
        }
    }

    private fun queueBitmapWhenReady(
        graph: VideoGraph,
        bitmap: Bitmap,
        timestampUs: Long,
    ): Boolean {
        val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (System.nanoTime() < deadlineNs) {
            if (graph.queueInputBitmap(0, bitmap, OneTimestampIterator(timestampUs))) {
                return true
            }
            Thread.sleep(8L)
        }
        if (!bitmap.isRecycled) bitmap.recycle()
        return false
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
                colors[index++] = (a shl 24) or (r shl 16) or (g shl 8) or b
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
}
