package com.tajuli.digitorandroid.editor.render

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tajuli.digitorandroid.editor.model.AnimatedFloat
import com.tajuli.digitorandroid.editor.model.ClipNodeGraph
import com.tajuli.digitorandroid.editor.model.ClipTransform
import com.tajuli.digitorandroid.editor.model.NodeCorrections
import com.tajuli.digitorandroid.editor.model.NodeEffect
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.preview.PreviewProjectRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-side proof of the render-stage parity contract.
 *
 * A synthetic RGBA frame is placed at the same composition timestamp through the two timestamp
 * conventions used in production:
 * - realtime preview: source timestamp + (timelineStart - sourceIn)
 * - Transformer export: clipped/local timestamp + timelineStart
 *
 * Preview uses one real compositor input. Export uses the real input plus the same zero-alpha
 * sentinel used by StableGpuExportCompositionBuilder. Both runs execute the production 33^3 LUT,
 * spatial shader, transform/opacity compositor and Transformer-equivalent SDR output color rule.
 * The resulting RGBA_8888 output buffers must be byte-for-byte identical.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PreviewExportPixelParityInstrumentedTest {

    @Test
    fun previewAndExportRenderStage_areByteIdentical() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val width = 64
        val height = 48
        val timelineUs = 500_000L
        val sourceUs = 750_000L

        val graph = ClipNodeGraph.default()
        val editedGraph = graph.copy(
            nodes = graph.nodes.map { node ->
                if (node.kind == NodeKind.SERIAL) {
                    node.copy(
                        corrections = NodeCorrections(
                            exposure = 0.18f,
                            contrast = 11f,
                            saturation = 16f,
                            temperature = 7f,
                        ),
                        effects = listOf(
                            NodeEffect(name = "Blur", amount = 0.22f),
                            NodeEffect(name = "Film Grain", amount = 0.09f),
                        ),
                    )
                } else {
                    node
                }
            },
            revision = graph.revision + 1L,
        )
        val clip = TimelineClip(
            id = "parity-real",
            uri = "content://synthetic/parity",
            label = "parity",
            timelineStartUs = 250_000L,
            sourceInUs = 500_000L,
            sourceOutUs = 1_500_000L,
            opacity = 0.67f,
            nodeGraph = editedGraph,
            transform = ClipTransform(
                positionX = AnimatedFloat(0.08f),
                positionY = AnimatedFloat(-0.05f),
                scaleX = AnimatedFloat(0.91f),
                scaleY = AnimatedFloat(0.86f),
                rotationDegrees = AnimatedFloat(7f),
            ),
        )
        val realTrack = TimelineTrack(
            id = "v1",
            name = "V1",
            kind = TrackKind.VIDEO,
            clips = listOf(clip),
        )
        val project = TimelineProject(width = width, height = height, tracks = listOf(realTrack))
        PreviewProjectRegistry.update(project)

        val sourceColor = ColorInfo.Builder()
            .setColorSpace(C.COLOR_SPACE_BT709)
            .setColorRange(C.COLOR_RANGE_FULL)
            .setColorTransfer(C.COLOR_TRANSFER_SRGB)
            .build()
        val decodedFormat = ParityRenderContract.decoderOutputFormat(
            Format.Builder()
                .setWidth(width)
                .setHeight(height)
                .setColorInfo(sourceColor)
                .build(),
        )
        val bitmap = patternedBitmap(width, height)

        val previewPixels = renderOneFrame(
            context = context,
            project = project,
            tracks = listOf(realTrack),
            clips = listOf(clip),
            inputFormat = decodedFormat,
            inputTimestampsUs = listOf(sourceUs),
            inputOffsetsUs = listOf(clip.timelineStartUs - clip.sourceInUs),
            effects = listOf(SharedVideoPipeline.compositedExactPreviewEffectsFor(clip)),
            livePreview = true,
            bitmap = bitmap,
        )

        val sentinelClip = clip.copy(
            id = "parity-sentinel",
            opacity = 0f,
            linkGroupId = null,
        )
        val sentinelTrack = realTrack.copy(
            id = "v1-sentinel",
            name = "V1 sentinel",
            clips = listOf(sentinelClip),
        )
        val localUs = sourceUs - clip.sourceInUs
        val exportPixels = renderOneFrame(
            context = context,
            project = project,
            tracks = listOf(realTrack, sentinelTrack),
            clips = listOf(clip, sentinelClip),
            inputFormat = decodedFormat,
            inputTimestampsUs = listOf(localUs, localUs),
            inputOffsetsUs = listOf(clip.timelineStartUs, clip.timelineStartUs),
            effects = listOf(
                SharedVideoPipeline.compositedExportEffectsFor(clip),
                SharedVideoPipeline.compositedExportEffectsFor(sentinelClip),
            ),
            livePreview = false,
            bitmap = bitmap,
        )

        if (!bitmap.isRecycled) bitmap.recycle()
        PreviewProjectRegistry.clear(project)

        // Both timestamp conventions must have landed on the same composition time; Film Grain is
        // source-time dependent, so this comparison also catches timestamp drift in spatial FX.
        assertTrue(ParityRenderContract.sourceTimeUs(clip, timelineUs) == sourceUs)
        assertArrayEquals(previewPixels, exportPixels)
    }

    private fun renderOneFrame(
        context: android.content.Context,
        project: TimelineProject,
        tracks: List<TimelineTrack>,
        clips: List<TimelineClip>,
        inputFormat: Format,
        inputTimestampsUs: List<Long>,
        inputOffsetsUs: List<Long>,
        effects: List<List<androidx.media3.common.Effect>>,
        livePreview: Boolean,
        bitmap: Bitmap,
    ): ByteArray {
        require(tracks.size == clips.size)
        require(tracks.size == inputTimestampsUs.size)
        require(tracks.size == inputOffsetsUs.size)
        require(tracks.size == effects.size)

        val error = AtomicReference<Throwable?>(null)
        val outputLatch = CountDownLatch(1)
        val imageLatch = CountDownLatch(1)
        val pixels = AtomicReference<ByteArray?>(null)
        val readerThread = HandlerThread("ParityImageReader").apply { start() }
        val imageReader = ImageReader.newInstance(
            project.width,
            project.height,
            PixelFormat.RGBA_8888,
            2,
        )
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                pixels.set(copyRgba(image, project.width, project.height))
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
                    Log.e(TAG, "Parity render graph failed", exception)
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
                    videoTracks = tracks,
                    livePreview = livePreview,
                ),
            )
            graph.setOutputSurfaceInfo(
                SurfaceInfo(imageReader.surface, project.width, project.height),
            )

            tracks.indices.forEach { index -> graph.registerInput(index) }
            tracks.indices.forEach { index ->
                graph.registerInputStream(
                    index,
                    VideoFrameProcessor.INPUT_TYPE_BITMAP,
                    inputFormat,
                    effects[index],
                    inputOffsetsUs[index],
                )
            }
            tracks.indices.forEach { index ->
                // Media3 owns queued bitmap inputs and may recycle them. Give every graph input its
                // own copy so preview/export runs and the export sentinel cannot invalidate a bitmap
                // still needed by another input.
                val inputBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                assertTrue(
                    "Bitmap input $index did not become ready",
                    queueBitmapWhenReady(
                        graph = graph,
                        inputIndex = index,
                        bitmap = inputBitmap,
                        timestampUs = inputTimestampsUs[index],
                    ),
                )
            }

            assertTrue("Timed out waiting for graph output", outputLatch.await(10, TimeUnit.SECONDS))
            throwIfGraphFailed(error.get())
            assertTrue("Timed out waiting for RGBA output", imageLatch.await(10, TimeUnit.SECONDS))
            throwIfGraphFailed(error.get())
            return requireNotNull(pixels.get()) { "No RGBA pixels captured" }
        } finally {
            runCatching { graph.release() }
            imageReader.close()
            readerThread.quitSafely()
            readerThread.join(2_000L)
        }
    }

    private fun throwIfGraphFailed(error: Throwable?) {
        if (error == null) return
        throw AssertionError("Render graph failed:\n${Log.getStackTraceString(error)}", error)
    }

    private fun queueBitmapWhenReady(
        graph: MultipleInputVideoGraph,
        inputIndex: Int,
        bitmap: Bitmap,
        timestampUs: Long,
    ): Boolean {
        val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadlineNs) {
            if (
                graph.queueInputBitmap(
                    inputIndex,
                    bitmap,
                    OneTimestampIterator(timestampUs),
                )
            ) {
                return true
            }
            Thread.sleep(10L)
        }
        return false
    }

    private fun patternedBitmap(width: Int, height: Int): Bitmap {
        val colors = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val r = (x * 255 / (width - 1)).coerceIn(0, 255)
                val g = (y * 255 / (height - 1)).coerceIn(0, 255)
                val b = ((x * 17 + y * 29) and 0xFF)
                colors[y * width + x] = Color.argb(255, r, g, b)
            }
        }
        return Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun copyRgba(image: Image, width: Int, height: Int): ByteArray {
        val plane = image.planes.single()
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        require(pixelStride >= 4) { "Unexpected RGBA pixel stride: $pixelStride" }
        val out = ByteArray(width * height * 4)
        var destination = 0
        for (y in 0 until height) {
            val row = y * rowStride
            for (x in 0 until width) {
                val pixel = row + x * pixelStride
                out[destination++] = buffer.get(pixel)
                out[destination++] = buffer.get(pixel + 1)
                out[destination++] = buffer.get(pixel + 2)
                out[destination++] = buffer.get(pixel + 3)
            }
        }
        return out
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

    private companion object {
        const val TAG = "PreviewExportParity"
    }
}
