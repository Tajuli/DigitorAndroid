package com.tajuli.digitorandroid.editor.render

import android.content.Context
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
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import java.io.Closeable
import java.util.concurrent.Executor

/** Maximum final-surface size for realtime editing. Export always keeps project resolution. */
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
 * Realtime preview intentionally downsizes only the FINAL compositor/output surface to at most
 * 720 px on the long edge. Source decoding and per-layer color/spatial effects still receive the
 * original decoder dimensions, so the shared 33^3 LUT, qualifier/node math and spatial shader are
 * evaluated on the same source pixels as export. Geometry is normalized in
 * [ResolveVideoCompositorSettings]. The only approximation is the final display resample; export
 * remains full project resolution.
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

    init {
        graph.initialize()

        // Per-layer effects still run from the original decoder formats. Only the final compositor
        // target is reduced for realtime display; export uses the full project canvas separately.
        graph.setCompositorSettings(
            ResolveVideoCompositorSettings(
                outputWidth = previewOutputWidth,
                outputHeight = previewOutputHeight,
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
                SurfaceInfo(it, previewOutputWidth, previewOutputHeight)
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
}
