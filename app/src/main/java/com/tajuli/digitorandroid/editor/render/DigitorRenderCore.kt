package com.tajuli.digitorandroid.editor.render

import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import android.view.Surface
import androidx.media3.common.ColorInfo
import androidx.media3.common.DebugViewProvider
import androidx.media3.common.Format
import androidx.media3.common.SurfaceInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.VideoFrameProcessor
import androidx.media3.common.VideoGraph
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MultipleInputVideoGraph
import androidx.media3.transformer.TransformerUtil
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import java.io.Closeable
import java.util.concurrent.Executor

/**
 * The one realtime video render graph used by Digitor preview.
 *
 * This deliberately does not own playback, seeking or decoding. MediaCodec producers feed decoded
 * frames into its input Surfaces. The graph applies the exact same 33^3 color LUT, spatial node
 * effects, transform/opacity rules and multilayer compositor that export uses.
 *
 * Source metadata is resolved independently from the decoder transport. Color metadata is then
 * normalized with the exact same Transformer rule used by export: a null or incomplete DataSpace
 * falls back to SDR BT.709 limited, while complete source ColorInfo is preserved. This is required
 * both for preview/export parity and because DefaultVideoFrameProcessor rejects invalid DataSpace.
 */
@UnstableApi
internal class DigitorRenderCore(
    context: Context,
    private val project: TimelineProject,
    private val layers: List<Layer>,
    listenerExecutor: Executor,
    private val listener: Listener,
) : Closeable {

    data class Layer(
        val track: TimelineTrack,
        val clip: TimelineClip,
        val format: Format,
    )

    interface Listener {
        fun onFrameRendered(timelineUs: Long)
        fun onError(error: Throwable)
    }

    private val appContext = context.applicationContext

    // TransformerUtil.getValidColor() is the export-side contract. Apply it to the Format passed to
    // the preview GL graph too, not just to the graph output ColorInfo. DefaultVideoFrameProcessor
    // requires both input and output ColorInfo to contain a valid DataSpace.
    private val renderFormats: List<Format> = layers.map { layer ->
        val sourceFormat = resolveSourceVideoFormat(appContext, layer.clip)
        sourceFormat.buildUpon()
            .setColorInfo(TransformerUtil.getValidColor(sourceFormat.colorInfo))
            .build()
    }

    private fun normalizedColorInfo(format: Format): ColorInfo =
        TransformerUtil.getValidColor(format.colorInfo)

    private val outputColorInfo: ColorInfo =
        renderFormats.firstOrNull()?.let(::normalizedColorInfo) ?: ColorInfo.SDR_BT709_LIMITED

    private val graph: MultipleInputVideoGraph = MultipleInputVideoGraph.Factory().create(
        appContext,
        outputColorInfo,
        DebugViewProvider.NONE,
        object : VideoGraph.Listener {
            override fun onOutputFrameAvailableForRendering(
                framePresentationTimeUs: Long,
                isRedrawnFrame: Boolean,
            ) {
                listener.onFrameRendered(framePresentationTimeUs)
            }

            override fun onError(exception: VideoFrameProcessingException) {
                listener.onError(exception)
            }
        },
        listenerExecutor,
        0L,
        true,
    )

    private var outputSurface: Surface? = null

    init {
        require(!ColorInfo.isTransferHdr(outputColorInfo)) {
            "Pixel-parity realtime compositor currently supports SDR input only"
        }
        require(renderFormats.all { normalizedColorInfo(it) == outputColorInfo }) {
            "Pixel-parity compositing requires all simultaneous layers to use identical SDR ColorInfo"
        }

        graph.initialize()

        // Input order stays in project track order. ResolveVideoCompositorSettings therefore owns
        // the exact same z-order/geometry semantics in preview and Transformer export.
        graph.setCompositorSettings(
            ResolveVideoCompositorSettings(
                outputWidth = project.width,
                outputHeight = project.height,
                videoTracks = layers.map { it.track },
                livePreview = true,
            ),
        )

        // MultipleInputVideoGraph requires all input slots to exist before any frame is rendered.
        layers.indices.forEach { index -> graph.registerInput(index) }
        layers.forEachIndexed { index, layer ->
            graph.registerInputStream(
                index,
                VideoFrameProcessor.INPUT_TYPE_SURFACE,
                renderFormats[index],
                SharedVideoPipeline.compositedExactPreviewEffectsFor(layer.clip),
                layer.clip.timelineStartUs - layer.clip.sourceInUs,
            )
        }
    }

    fun setOutputSurface(surface: Surface?) {
        if (outputSurface === surface) return
        outputSurface = surface
        graph.setOutputSurfaceInfo(
            surface?.takeIf { it.isValid }?.let {
                SurfaceInfo(it, project.width.coerceAtLeast(1), project.height.coerceAtLeast(1))
            },
        )
    }

    fun inputSurface(inputIndex: Int): Surface = graph.getInputSurface(inputIndex)

    fun registerInputFrame(inputIndex: Int): Boolean = graph.registerInputFrame(inputIndex)

    fun pendingInputFrames(inputIndex: Int): Int = graph.getPendingInputFrameCount(inputIndex)

    fun redraw() {
        graph.redraw()
    }

    fun flush() {
        graph.flush()
    }

    override fun close() {
        outputSurface = null
        graph.release()
    }

    private companion object {
        fun resolveSourceVideoFormat(context: Context, clip: TimelineClip): Format {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, Uri.parse(clip.uri), null)
                for (index in 0 until extractor.trackCount) {
                    val mediaFormat = extractor.getTrackFormat(index)
                    val mime = mediaFormat.getString(android.media.MediaFormat.KEY_MIME)
                    if (mime?.startsWith("video/") == true) {
                        return MediaFormatUtil.createFormatFromMediaFormat(mediaFormat)
                    }
                }
                error("No video track in ${clip.label}")
            } finally {
                extractor.release()
            }
        }
    }
}
