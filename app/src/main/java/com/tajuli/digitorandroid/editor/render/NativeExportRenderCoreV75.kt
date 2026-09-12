package com.tajuli.digitorandroid.editor.render

import android.content.Context
import android.graphics.Bitmap
import android.view.Surface
import androidx.media3.common.ColorInfo
import androidx.media3.common.DebugViewProvider
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.SurfaceInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.VideoFrameProcessor
import androidx.media3.common.VideoGraph
import androidx.media3.common.util.TimestampIterator
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MultipleInputVideoGraph
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import java.io.Closeable
import java.util.concurrent.Executor

/**
 * Full-resolution GPU bridge for the native MediaCodec/MediaMuxer exporter.
 *
 * Transport and muxing stay platform-native: MediaExtractor/MediaCodec own compressed source
 * transport, while this class only hosts Digitor's already-shipping OpenGL effects/compositor and
 * renders the composed frames directly into a hardware encoder Surface. PP-Matting continues to
 * execute through the existing ncnn Vulkan effect path.
 *
 * V76 generalises the original one-input bridge. Every real V-track, transition tail and the
 * timeline sentinel gets its own VideoGraph input. A single input may register multiple sequential
 * streams (gap bitmap -> decoded clip -> gap bitmap -> ...), which mirrors Media3 Composition's
 * sequence semantics without giving Transformer ownership of demux, decode, encode or muxing.
 */
@UnstableApi
internal class NativeExportRenderCoreV75(
    context: Context,
    project: TimelineProject,
    inputs: List<ResolveCompositorInputV22>,
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
        require(inputs.isNotEmpty()) { "Native render graph requires at least one compositor input" }
        graph.initialize()
        graph.setCompositorSettings(
            ResolveVideoCompositorSettings(
                outputWidth = project.width,
                outputHeight = project.height,
                videoTracks = resolveCompositionVideoTracks(project),
                livePreview = false,
                inputsV22 = inputs,
            ),
        )
        // Text/caption and V19 visual overlays intentionally run after video composition, matching
        // the compatibility exporter and exact-preview ordering.
        graph.setCompositionEffects(projectTextEffects(project))
        graph.setOutputSurfaceInfo(
            SurfaceInfo(
                encoderSurface,
                project.width.coerceAtLeast(2),
                project.height.coerceAtLeast(2),
            ),
        )
        inputs.indices.forEach(graph::registerInput)
    }

    fun registerSurfaceStream(inputId: Int, clip: TimelineClip, sourceFormat: Format) {
        // Keep the decoder's untouched platform MediaFormat for codec/profile compatibility, while
        // the GL graph sees normalized decoder-output geometry and SDR code values. Optional camera
        // Log/HDR transforms still execute later in SharedColorPipeline exactly as in preview.
        val renderFormat = ParityRenderContract.decoderOutputFormat(sourceFormat)
            .buildUpon()
            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            .build()
        graph.registerInputStream(
            inputId,
            VideoFrameProcessor.INPUT_TYPE_SURFACE,
            renderFormat,
            SharedVideoPipeline.compositedExportEffectsFor(clip),
            clip.timelineStartUs - clip.sourceInUs,
        )
    }

    fun registerBitmapStream(
        inputId: Int,
        format: Format,
        effects: List<Effect>,
        offsetToAddUs: Long,
    ) {
        graph.registerInputStream(
            inputId,
            VideoFrameProcessor.INPUT_TYPE_BITMAP,
            format,
            effects,
            offsetToAddUs,
        )
    }

    fun inputSurface(inputId: Int): Surface = graph.getInputSurface(inputId)

    fun pendingInputFrames(inputId: Int): Int = graph.getPendingInputFrameCount(inputId)

    fun registerInputFrame(inputId: Int): Boolean = graph.registerInputFrame(inputId)

    fun queueInputBitmap(inputId: Int, bitmap: Bitmap, timestamps: TimestampIterator): Boolean =
        graph.queueInputBitmap(inputId, bitmap, timestamps)

    fun signalEndOfInput(inputId: Int) {
        graph.signalEndOfInput(inputId)
    }

    override fun close() {
        graph.release()
    }
}
