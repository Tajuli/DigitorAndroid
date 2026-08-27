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
 * Source/decoder metadata is normalized with [ParityRenderContract], including the same SDR graph
 * output-color rule used by Media3 Transformer. Every input preprocessor converts into that common
 * graph color before the compositor, exactly like Transformer, so mixed SDR source metadata does not
 * require a preview-only rejection path.
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

    // Mirror the decoded Format that Transformer hands to VideoEncoderGraphInput: valid ColorInfo
    // plus the same 90/270-degree decoder rotation normalization.
    private val renderFormats: List<Format> = layers.map { layer ->
        ParityRenderContract.decoderOutputFormat(resolveSourceVideoFormat(appContext, layer.clip))
    }

    // Transformer chooses one common graph output color from the first decoded video input. Each
    // MultipleInputVideoGraph preprocessor converts its source into this color before compositing.
    private val outputColorInfo: ColorInfo = renderFormats.firstOrNull()
        ?.let(ParityRenderContract::videoGraphOutputColor)
        ?: ColorInfo.SDR_BT709_LIMITED

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
        require(renderFormats.none { ColorInfo.isTransferHdr(it.colorInfo) }) {
            "Pixel-parity realtime compositor currently supports SDR input only"
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
