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

/**
 * Full-resolution GPU bridge for the native MediaCodec/MediaMuxer exporter.
 *
 * Transport and muxing are intentionally outside Media3 Transformer: compressed source packets are
 * read with MediaExtractor, decoded by MediaCodec directly into [inputSurface], processed by the
 * same Digitor OpenGL shader/compositor chain used by preview/export, and rendered straight into a
 * hardware MediaCodec encoder Surface. PP-Matting keeps using its existing ncnn Vulkan path inside
 * the shared effect chain.
 *
 * Media3's low-level VideoGraph is retained only as the host for Digitor's existing GL Effect
 * implementations. It does not own demux, decode, encode, timestamps, output format, or muxing.
 */
@UnstableApi
internal class NativeExportRenderCoreV75(
    context: Context,
    project: TimelineProject,
    track: TimelineTrack,
    clip: TimelineClip,
    sourceFormat: Format,
    encoderSurface: Surface,
    listenerExecutor: Executor,
    listener: Listener,
) : Closeable {

    interface Listener {
        fun onEnded(finalFramePresentationTimeUs: Long)
        fun onError(error: Throwable)
    }

    private val graph: MultipleInputVideoGraph = MultipleInputVideoGraph.Factory().create(
        context.applicationContext,
        ColorInfo.SDR_BT709_LIMITED,
        DebugViewProvider.NONE,
        object : VideoGraph.Listener {
            override fun onEnded(finalFramePresentationTimeUs: Long) {
                listener.onEnded(finalFramePresentationTimeUs)
            }

            override fun onError(exception: VideoFrameProcessingException) {
                listener.onError(exception)
            }
        },
        listenerExecutor,
        0L,
        true,
    )

    init {
        graph.initialize()
        graph.setCompositorSettings(
            ResolveVideoCompositorSettings(
                outputWidth = project.width,
                outputHeight = project.height,
                videoTracks = listOf(track),
                livePreview = false,
                inputsV22 = listOf(ResolveCompositorInputV22.TrackInput(track)),
            ),
        )
        graph.setOutputSurfaceInfo(
            SurfaceInfo(
                encoderSurface,
                project.width.coerceAtLeast(2),
                project.height.coerceAtLeast(2),
            ),
        )
        graph.registerInput(0)

        // Keep the decoder's untouched platform MediaFormat for codec/profile compatibility, while
        // the GL graph sees normalized decoder-output geometry and SDR code values. Optional camera
        // Log/HDR transforms still execute later in SharedColorPipeline exactly as in preview.
        val renderFormat = ParityRenderContract.decoderOutputFormat(sourceFormat)
            .buildUpon()
            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            .build()
        graph.registerInputStream(
            0,
            VideoFrameProcessor.INPUT_TYPE_SURFACE,
            renderFormat,
            SharedVideoPipeline.compositedExportEffectsFor(clip),
            clip.timelineStartUs - clip.sourceInUs,
        )
    }

    fun inputSurface(): Surface = graph.getInputSurface(0)

    fun pendingInputFrames(): Int = graph.getPendingInputFrameCount(0)

    fun registerInputFrame(): Boolean = graph.registerInputFrame(0)

    fun signalEndOfInput() {
        graph.signalEndOfInput(0)
    }

    override fun close() {
        graph.release()
    }
}
