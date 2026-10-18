package com.tajuli.digitorandroid.editor.render

import android.content.Context
import android.graphics.Bitmap
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.TimestampIterator
import android.view.Surface
import androidx.media3.common.ColorInfo
import androidx.media3.common.DebugViewProvider
import androidx.media3.common.Format
import androidx.media3.common.SurfaceInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.VideoFrameProcessor
import androidx.media3.common.VideoGraph
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MultipleInputVideoGraph
import androidx.media3.effect.Presentation
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.transitionPairsV22
import java.io.Closeable
import java.util.concurrent.Executor

/** Upper preview tier. Devices can select 360p or 540p to reduce live compositor work. */
internal const val REALTIME_PREVIEW_LONG_EDGE = 720

/**
 * The one realtime video render graph used by Digitor preview.
 *
 * MediaCodec owns source decoding. The preview engine deliberately hands this class an SDR-tagged
 * graph-facing [Format] while the decoder itself keeps the original platform MediaFormat. This is
 * important for camera Log footage: a flat S-Log/C-Log clip must remain visible even before the user
 * chooses an Input Color profile, just like Resolve. Digitor therefore previews decoded code values
 * first; optional camera Log/HDR -> Rec.709 conversion happens later inside [SharedColorPipeline].
 *
 * Re-reading source ColorInfo here used to re-introduce camera/container metadata after the preview
 * engine had already normalised it. Some 8-bit S-Log3 files then entered Media3's color conversion
 * path instead of the raw-code path and could stall before the first rendered frame. The render core
 * now treats the prepared layer format as the single source of truth for realtime graph metadata.
 *
 * Realtime preview bounds the compositor to 720 px on its long edge. Each input is downscaled to
 * the same fraction of the project canvas after its source effects, so Media3's normalized overlay
 * placement and scale remain unchanged. Export uses its independent full-resolution graph.
 *
 * V36 uses [SharedVideoPipeline.compositedPreviewEffectsFor] here rather than the static byte-parity
 * snapshot. The realtime chain deliberately keeps creator/beauty stages resident so a filter tap or
 * intensity change is visible on the next submitted frame without rebuilding MediaCodec/GL state.
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
    private val previewOutputSize = resolvePreviewOutputSize(project, REALTIME_PREVIEW_LONG_EDGE)
    private val previewOutputWidth = previewOutputSize.first
    private val previewOutputHeight = previewOutputSize.second

    private fun previewInputSize(index: Int): Pair<Int, Int> = resolvePreviewInputSize(
        sourceWidth = layers[index].format.width,
        sourceHeight = layers[index].format.height,
        projectWidth = project.width,
        projectHeight = project.height,
        previewWidth = previewOutputWidth,
        previewHeight = previewOutputHeight,
    )

    private fun previewDownscaleEffect(index: Int) = Presentation.createForWidthAndHeight(
        previewInputSize(index).first,
        previewInputSize(index).second,
        Presentation.LAYOUT_SCALE_TO_FIT,
    )

    /**
     * Use exactly the format prepared next to MediaCodec in DavinciFramePreviewEngine. The decoder
     * remains configured with the untouched source MediaFormat, so this does not alter codec/profile
     * support or pixel decoding; it only tells the GL graph to display the decoded code values on an
     * SDR path. Rotation still follows the same decoder-output normalization as export.
     */
    private val renderFormats: List<Format> = layers.map { layer ->
        ParityRenderContract.decoderOutputFormat(layer.format)
            .buildUpon()
            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            .build()
    }

    private val outputColorInfo: ColorInfo = ColorInfo.SDR_BT709_LIMITED

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
    private val bitmapRegistered = BooleanArray(layers.size)

    init {
        graph.initialize()

        val transitionPairsByGhostId = project.transitionPairsV22()
            .associateBy(::transitionGhostIdV22)
        val compositorInputs = layers.map { layer ->
            val pair = transitionPairsByGhostId[layer.clip.id]
            if (pair != null) {
                ResolveCompositorInputV22.TransitionGhostInput(pair, layer.clip)
            } else {
                ResolveCompositorInputV22.TrackInput(layer.track)
            }
        }

        // Downscale inputs and canvas by the same project-relative factor. This preserves normalized
        // clip geometry while reducing per-pixel compositor blending and output-surface work.
        graph.setCompositorSettings(
            ResolveVideoCompositorSettings(
                outputWidth = previewOutputWidth,
                outputHeight = previewOutputHeight,
                videoTracks = layers.map { it.track },
                livePreview = true,
                inputsV22 = compositorInputs,
            ),
        )

        // MultipleInputVideoGraph requires all input slots to exist before any frame is rendered.
        layers.indices.forEach { index -> graph.registerInput(index) }
        layers.forEachIndexed { index, layer ->
            if (layer.clip.retime?.curve?.let { it.smoothSlowMotion && it.hasSlowMotion } == true) return@forEachIndexed
            graph.registerInputStream(
                index,
                VideoFrameProcessor.INPUT_TYPE_SURFACE,
                renderFormats[index],
                nativeRetimeEffects(layer.clip, live = true) +
                    SharedVideoPipeline.compositedPreviewEffectsFor(layer.clip) +
                    previewDownscaleEffect(index),
                layer.clip.timelineStartUs - layer.clip.sourceInUs,
            )
        }
    }

    fun setOutputSurface(surface: Surface?) {
        if (outputSurface === surface) return
        outputSurface = surface
        graph.setOutputSurfaceInfo(
            surface?.takeIf { it.isValid }?.let {
                SurfaceInfo(it, previewOutputWidth, previewOutputHeight)
            },
        )
    }

    fun queueSmoothBitmap(inputIndex: Int, bitmap: Bitmap, timestamp: TimestampIterator): Boolean {
        if (!bitmapRegistered[inputIndex]) {
            graph.registerInputStream(inputIndex, VideoFrameProcessor.INPUT_TYPE_BITMAP,
                Format.Builder().setSampleMimeType(MimeTypes.IMAGE_RAW)
                    .setWidth(bitmap.width).setHeight(bitmap.height)
                    .setColorInfo(ColorInfo.SRGB_BT709_FULL).build(),
                SharedVideoPipeline.compositedPreviewEffectsFor(layers[inputIndex].clip) +
                    previewDownscaleEffect(inputIndex), 0L)
            bitmapRegistered[inputIndex] = true
        }
        return graph.queueInputBitmap(inputIndex, bitmap, timestamp)
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
}
