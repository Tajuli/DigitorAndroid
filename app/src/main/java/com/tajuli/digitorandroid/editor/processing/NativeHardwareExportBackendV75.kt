package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.preview.PreviewExportCoordinator
import com.tajuli.digitorandroid.editor.render.NativeExportRenderCoreV75
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Native Android fast-path exporter.
 *
 * Compressed transport is fully platform-native:
 * MediaExtractor -> MediaCodec decoder Surface -> Digitor OpenGL/Vulkan processing ->
 * MediaCodec AVC encoder Surface -> MediaMuxer MP4.
 *
 * Media3 Transformer is deliberately not involved. The low-level Media3 VideoGraph remains only as
 * the host for Digitor's already-shipping GL Effect implementations, so all existing node/color/
 * denoise/beauty shaders stay byte-for-byte shared with preview. PP-Matting continues to use ncnn
 * Vulkan where available.
 *
 * V75 intentionally starts with the reliability-critical single moving-video/no-audio path. Complex
 * multitrack/audio/overlay timelines continue through the compatibility exporter until their native
 * scheduler/mixer lands; no editing feature is silently dropped just to claim native coverage.
 */
@UnstableApi
class NativeHardwareExportBackendV75(
    private val context: Context,
) {

    fun plan(project: TimelineProject): NativeHardwareExportPlanV75? = nativeHardwareExportPlanV75(project)

    suspend fun export(
        project: TimelineProject,
        output: File,
        quality: ExportQuality,
        onProgress: (ExportProgress) -> Unit,
    ): ExportResult {
        val plan = plan(project) ?: error("Native hardware export does not support this timeline yet")
        onProgress(ExportProgress.Stage("Native HW: releasing preview resources", 0.01f))
        val previewLease = PreviewExportCoordinator.acquireExportLease()
        return try {
            withContext(Dispatchers.Default) {
                output.parentFile?.mkdirs()
                if (output.exists()) output.delete()
                try {
                    runNativePassV75(plan, project, output, quality, forceSoftwareDecoder = false, onProgress)
                } catch (decoderFailure: NativeDecoderFailureV75) {
                    // Hardware remains the default. Only a real runtime decoder failure gets one
                    // software decode retry; GPU processing and hardware encoding remain unchanged.
                    if (!hasSoftwareDecoderV75(decoderFailure.mime)) throw decoderFailure
                    runCatching { if (output.exists()) output.delete() }
                    onProgress(
                        ExportProgress.Stage(
                            "Native HW decoder failed · retrying software decode + GPU render",
                            0.02f,
                        ),
                    )
                    runNativePassV75(plan, project, output, quality, forceSoftwareDecoder = true, onProgress)
                }
            }
        } finally {
            previewLease.close()
        }
    }

    private fun runNativePassV75(
        plan: NativeHardwareExportPlanV75,
        project: TimelineProject,
        output: File,
        quality: ExportQuality,
        forceSoftwareDecoder: Boolean,
        onProgress: (ExportProgress) -> Unit,
    ): ExportResult {
        val source = prepareVideoSourceV75(plan.clip)
        var encoder: NativeSurfaceAvcEncoderMuxV75? = null
        var renderCore: NativeExportRenderCoreV75? = null
        var decoder: MediaCodec? = null
        val graphEnded = AtomicBoolean(false)
        val graphError = AtomicReference<Throwable?>(null)
        val directExecutor = Executor { command -> command.run() }
        val requestedBitrate = quality.videoBitrate(project.width, project.height, project.frameRate)

        try {
            val createdEncoder = NativeSurfaceAvcEncoderMuxV75(
                width = project.width,
                height = project.height,
                frameRate = project.frameRate,
                requestedBitrate = requestedBitrate,
                output = output,
            )
            encoder = createdEncoder

            val core = NativeExportRenderCoreV75(
                context = context,
                project = project,
                track = plan.track,
                clip = plan.clip,
                sourceFormat = source.media3Format,
                encoderSurface = createdEncoder.inputSurface,
                listenerExecutor = directExecutor,
                listener = object : NativeExportRenderCoreV75.Listener {
                    override fun onEnded(finalFramePresentationTimeUs: Long) {
                        graphEnded.set(true)
                    }

                    override fun onError(error: Throwable) {
                        graphError.compareAndSet(null, error)
                    }
                },
            )
            renderCore = core

            val decoderName = chooseDecoderNameV75(
                mime = source.mime,
                format = source.platformFormat,
                forceSoftware = forceSoftwareDecoder,
            ) ?: throw NativeDecoderFailureV75(source.mime, "No decoder for ${source.mime}")
            val createdDecoder = try {
                MediaCodec.createByCodecName(decoderName).also { codec ->
                    codec.configure(source.platformFormat, core.inputSurface(), null, 0)
                    codec.start()
                }
            } catch (error: Throwable) {
                throw NativeDecoderFailureV75(source.mime, "Decoder $decoderName could not start", error)
            }
            decoder = createdDecoder

            source.extractor.seekTo(plan.clip.sourceInUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            onProgress(
                ExportProgress.Stage(
                    "Native HW: $decoderName → GPU → ${createdEncoder.encoderName} → MediaMuxer",
                    0.03f,
                ),
            )

            val decoderInfo = MediaCodec.BufferInfo()
            var decoderInputEos = false
            var decoderOutputEos = false
            var graphInputEnded = false
            var encoderInputEnded = false
            var encoderOutputEos = false
            var nextOutputTimelineUs = plan.clip.timelineStartUs
            val targetFrameDurationUs = (1_000_000L / project.frameRate.coerceAtLeast(1)).coerceAtLeast(1L)
            var idleSinceNs = System.nanoTime()

            while (!encoderOutputEos) {
                graphError.get()?.let { throw it }
                var didWork = false

                if (!decoderInputEos) {
                    for (attempt in 0 until 8) {
                        val inputIndex = try {
                            createdDecoder.dequeueInputBuffer(0L)
                        } catch (error: Throwable) {
                            throw NativeDecoderFailureV75(source.mime, "Decoder input failed", error)
                        }
                        if (inputIndex < 0) break
                        val input = createdDecoder.getInputBuffer(inputIndex)
                            ?: throw NativeDecoderFailureV75(source.mime, "Decoder input buffer unavailable")
                        input.clear()
                        val sampleTimeUs = source.extractor.sampleTime
                        if (sampleTimeUs < 0L || sampleTimeUs >= plan.clip.sourceOutUs) {
                            try {
                                createdDecoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    plan.clip.sourceOutUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                            } catch (error: Throwable) {
                                throw NativeDecoderFailureV75(source.mime, "Decoder EOS queue failed", error)
                            }
                            decoderInputEos = true
                            didWork = true
                            break
                        }
                        val size = source.extractor.readSampleData(input, 0)
                        if (size < 0) {
                            try {
                                createdDecoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    sampleTimeUs.coerceAtLeast(0L),
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                            } catch (error: Throwable) {
                                throw NativeDecoderFailureV75(source.mime, "Decoder EOS queue failed", error)
                            }
                            decoderInputEos = true
                        } else {
                            try {
                                createdDecoder.queueInputBuffer(inputIndex, 0, size, sampleTimeUs, 0)
                            } catch (error: Throwable) {
                                throw NativeDecoderFailureV75(source.mime, "Decoder sample queue failed", error)
                            }
                            source.extractor.advance()
                        }
                        didWork = true
                    }
                }

                if (!decoderOutputEos) {
                    for (attempt in 0 until 12) {
                        val outputIndex = try {
                            createdDecoder.dequeueOutputBuffer(decoderInfo, 0L)
                        } catch (error: Throwable) {
                            throw NativeDecoderFailureV75(source.mime, "Decoder output failed", error)
                        }
                        when {
                            outputIndex >= 0 -> {
                                val sourceUs = decoderInfo.presentationTimeUs
                                val eos = decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                val timelineUs = plan.clip.timelineStartUs + (sourceUs - plan.clip.sourceInUs)
                                val insideClip = sourceUs >= plan.clip.sourceInUs && sourceUs < plan.clip.sourceOutUs
                                val hitsNextOutputSlot = insideClip && timelineUs + 1_000L >= nextOutputTimelineUs
                                if (hitsNextOutputSlot) {
                                    while (nextOutputTimelineUs <= timelineUs) {
                                        nextOutputTimelineUs += targetFrameDurationUs
                                    }
                                    val waitStartedNs = System.nanoTime()
                                    while (core.pendingInputFrames() >= MAX_GRAPH_PENDING_FRAMES || !core.registerInputFrame()) {
                                        graphError.get()?.let { throw it }
                                        createdEncoder.drainAvailable()
                                        if ((System.nanoTime() - waitStartedNs) / 1_000_000L > GRAPH_BACKPRESSURE_TIMEOUT_MS) {
                                            error("Native GPU graph stopped accepting decoder frames")
                                        }
                                        Thread.sleep(1L)
                                    }
                                    try {
                                        createdDecoder.releaseOutputBuffer(outputIndex, true)
                                    } catch (error: Throwable) {
                                        throw NativeDecoderFailureV75(source.mime, "Decoder Surface release failed", error)
                                    }
                                    val fraction = if (project.durationUs <= 0L) 0f else
                                        (timelineUs.toDouble() / project.durationUs.toDouble()).toFloat().coerceIn(0f, .96f)
                                    onProgress(ExportProgress.Stage("Native HW + GPU rendering", fraction))
                                } else {
                                    try {
                                        createdDecoder.releaseOutputBuffer(outputIndex, false)
                                    } catch (error: Throwable) {
                                        throw NativeDecoderFailureV75(source.mime, "Decoder buffer release failed", error)
                                    }
                                }
                                if (eos) decoderOutputEos = true
                                didWork = true
                            }
                            outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> didWork = true
                            outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        }
                    }
                }

                if (decoderOutputEos && !graphInputEnded) {
                    core.signalEndOfInput()
                    graphInputEnded = true
                    didWork = true
                }

                if (graphEnded.get() && !encoderInputEnded) {
                    createdEncoder.signalEndOfInput()
                    encoderInputEnded = true
                    didWork = true
                }

                if (createdEncoder.drainAvailable()) didWork = true
                encoderOutputEos = createdEncoder.outputEnded

                if (didWork) {
                    idleSinceNs = System.nanoTime()
                } else {
                    if ((System.nanoTime() - idleSinceNs) / 1_000_000L > PIPELINE_IDLE_TIMEOUT_MS) {
                        error("Native export pipeline stalled")
                    }
                    Thread.sleep(1L)
                }
            }

            check(createdEncoder.muxerStarted) { "Native encoder produced no MP4 track" }
            check(output.length() > 0L) { "Native export produced an empty MP4" }
            onProgress(ExportProgress.Stage("Native HW export complete", 1f))
            return ExportResult(
                output = output,
                backend = Backend.GPU,
                note = buildString {
                    append("Native MediaExtractor/MediaCodec/MediaMuxer · ")
                    append(if (forceSoftwareDecoder) "software decode fallback" else "hardware decode")
                    append(" · Digitor OpenGL/Vulkan GPU · ")
                    append(createdEncoder.encoderName)
                    append(" · ")
                    append(createdEncoder.actualBitrate / 1_000_000f)
                    append(" Mbps")
                },
            )
        } catch (error: Throwable) {
            runCatching { if (output.exists()) output.delete() }
            throw error
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { renderCore?.close() }
            runCatching { encoder?.close() }
            runCatching { source.extractor.release() }
        }
    }

    private fun prepareVideoSourceV75(clip: TimelineClip): PreparedNativeSourceV75 {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, Uri.parse(clip.uri), null)
            var videoTrackIndex = -1
            var platformFormat: MediaFormat? = null
            var mime: String? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                val candidateMime = candidate.getString(MediaFormat.KEY_MIME)
                if (candidateMime?.startsWith("video/") == true) {
                    videoTrackIndex = index
                    platformFormat = candidate
                    mime = candidateMime
                    break
                }
            }
            require(videoTrackIndex >= 0 && platformFormat != null && mime != null) {
                "No moving-video track in ${clip.label}"
            }
            extractor.selectTrack(videoTrackIndex)
            val width = platformFormat.intValueV75(MediaFormat.KEY_WIDTH, 1).coerceAtLeast(1)
            val height = platformFormat.intValueV75(MediaFormat.KEY_HEIGHT, 1).coerceAtLeast(1)
            val rotation = platformFormat.intValueV75(MediaFormat.KEY_ROTATION, 0)
            val frameRate = platformFormat.frameRateV75()
            val formatBuilder = Format.Builder()
                .setSampleMimeType(mime)
                .setWidth(width)
                .setHeight(height)
                .setPixelWidthHeightRatio(1f)
                .setRotationDegrees(rotation)
                .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            if (frameRate > 0f) formatBuilder.setFrameRate(frameRate)
            return PreparedNativeSourceV75(extractor, platformFormat, formatBuilder.build(), mime)
        } catch (error: Throwable) {
            runCatching { extractor.release() }
            throw error
        }
    }

    private fun chooseDecoderNameV75(
        mime: String,
        format: MediaFormat,
        forceSoftware: Boolean,
    ): String? {
        val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { info -> !info.isEncoder && info.supportedTypes.any { it.equals(mime, true) } }
        if (candidates.isEmpty()) return null
        val ranked = candidates.sortedWith(
            compareBy<MediaCodecInfo> { info ->
                val software = info.isSoftwareCodecV75()
                when {
                    forceSoftware && software -> 0
                    forceSoftware -> 2
                    !forceSoftware && !software -> 0
                    else -> 1
                }
            }.thenByDescending { info ->
                runCatching { info.getCapabilitiesForType(mime).isFormatSupported(format) }.getOrDefault(false)
            },
        )
        return if (forceSoftware) ranked.firstOrNull { it.isSoftwareCodecV75() }?.name else ranked.first().name
    }

    private fun hasSoftwareDecoderV75(mime: String): Boolean =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.isSoftwareCodecV75() && info.supportedTypes.any { it.equals(mime, true) }
        }

    private companion object {
        const val MAX_GRAPH_PENDING_FRAMES = 4
        const val GRAPH_BACKPRESSURE_TIMEOUT_MS = 15_000L
        const val PIPELINE_IDLE_TIMEOUT_MS = 30_000L
    }
}

internal data class NativeHardwareExportPlanV75(
    val track: TimelineTrack,
    val clip: TimelineClip,
)

/** Pure eligibility gate kept testable so unsupported timelines never silently lose audio/overlays. */
internal fun nativeHardwareExportPlanV75(project: TimelineProject): NativeHardwareExportPlanV75? {
    if (project.validate().isNotEmpty()) return null
    if (project.textOverlays.isNotEmpty() || project.visualOverlaysV19.orEmpty().isNotEmpty()) return null
    if (project.tracks.any { it.kind == TrackKind.AUDIO && !it.muted && it.clips.isNotEmpty() }) return null
    val videoTracks = project.tracks.filter { it.kind == TrackKind.VIDEO && !it.muted && it.clips.isNotEmpty() }
    if (videoTracks.size != 1) return null
    val track = videoTracks.single()
    if (track.clips.size != 1) return null
    val clip = track.clips.single()
    if (clip.isImageV21) return null
    // V75 has no blank-frame generator yet. Restrict native scheduling to a continuous clip so a
    // leading/trailing timeline gap cannot disappear from the result.
    if (clip.timelineStartUs != 0L || clip.timelineEndUs != project.durationUs) return null
    return NativeHardwareExportPlanV75(track, clip)
}

private data class PreparedNativeSourceV75(
    val extractor: MediaExtractor,
    val platformFormat: MediaFormat,
    val media3Format: Format,
    val mime: String,
)

private class NativeDecoderFailureV75(
    val mime: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

private class NativeEncoderCapabilityFailureV75(message: String) : IllegalStateException(message)

private class NativeSurfaceAvcEncoderMuxV75(
    width: Int,
    height: Int,
    frameRate: Int,
    requestedBitrate: Int,
    output: File,
) : AutoCloseable {
    private val choice = chooseAvcEncoderV75(width, height, frameRate, requestedBitrate)
    val encoderName: String = choice.name
    val actualBitrate: Int = choice.bitrate
    private val codec = MediaCodec.createByCodecName(choice.name)
    val inputSurface: android.view.Surface
    private val muxer: android.media.MediaMuxer
    private val info = MediaCodec.BufferInfo()
    private var videoTrack = -1
    var muxerStarted: Boolean = false
        private set
    var outputEnded: Boolean = false
        private set
    private var inputEnded = false

    init {
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, actualBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()
        } catch (error: Throwable) {
            runCatching { codec.release() }
            throw NativeEncoderCapabilityFailureV75("Native AVC encoder ${choice.name} could not start: ${error.message}")
        }
        muxer = android.media.MediaMuxer(output.absolutePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    fun signalEndOfInput() {
        if (inputEnded) return
        codec.signalEndOfInputStream()
        inputEnded = true
    }

    /** Returns true when at least one encoder event/buffer was consumed. */
    fun drainAvailable(): Boolean {
        var didWork = false
        while (!outputEnded) {
            val outputIndex = codec.dequeueOutputBuffer(info, 0L)
            when {
                outputIndex >= 0 -> {
                    val encoded = codec.getOutputBuffer(outputIndex)
                        ?: error("Native encoder output buffer unavailable")
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0) {
                        check(muxerStarted) { "Native muxer did not receive encoder format" }
                        encoded.position(info.offset)
                        encoded.limit(info.offset + info.size)
                        muxer.writeSampleData(videoTrack, encoded, info)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEnded = true
                    codec.releaseOutputBuffer(outputIndex, false)
                    didWork = true
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "Native encoder format changed twice" }
                    videoTrack = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                    didWork = true
                }
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return didWork
            }
        }
        return didWork
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { inputSurface.release() }
        if (muxerStarted) runCatching { muxer.stop() }
        runCatching { muxer.release() }
    }
}

private data class NativeEncoderChoiceV75(val name: String, val bitrate: Int)

private fun chooseAvcEncoderV75(
    width: Int,
    height: Int,
    frameRate: Int,
    requestedBitrate: Int,
): NativeEncoderChoiceV75 {
    require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
        "Native AVC encoder requires positive even dimensions"
    }
    val mime = MediaFormat.MIMETYPE_VIDEO_AVC
    val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { info -> info.isEncoder && info.supportedTypes.any { it.equals(mime, true) } }
        .mapNotNull { info ->
            val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: return@mapNotNull null
            if (!caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)) return@mapNotNull null
            val videoCaps = caps.videoCapabilities ?: return@mapNotNull null
            val sizeRateSupported = runCatching {
                videoCaps.areSizeAndRateSupported(width, height, frameRate.toDouble())
            }.getOrDefault(false)
            if (!sizeRateSupported) return@mapNotNull null
            val bitrate = runCatching { videoCaps.bitrateRange.clamp(requestedBitrate) }.getOrDefault(requestedBitrate)
            Triple(info, bitrate, info.isHardwareCodecV75())
        }
        .sortedByDescending { it.third }
    val selected = candidates.firstOrNull()
        ?: throw NativeEncoderCapabilityFailureV75("No AVC Surface encoder supports ${width}×${height} @ ${frameRate} fps")
    return NativeEncoderChoiceV75(selected.first.name, selected.second)
}

private fun MediaCodecInfo.isSoftwareCodecV75(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return isSoftwareOnly
    val normalized = name.lowercase()
    return normalized.startsWith("omx.google.") || normalized.startsWith("c2.android.") ||
        normalized.contains("software") || normalized.contains("sw.")
}

private fun MediaCodecInfo.isHardwareCodecV75(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return isHardwareAccelerated
    return !isSoftwareCodecV75()
}

private fun MediaFormat.intValueV75(key: String, fallback: Int): Int =
    if (!containsKey(key)) fallback else runCatching { getInteger(key) }.getOrDefault(fallback)

private fun MediaFormat.frameRateV75(): Float {
    if (!containsKey(MediaFormat.KEY_FRAME_RATE)) return 0f
    return runCatching { getFloat(MediaFormat.KEY_FRAME_RATE) }
        .recoverCatching { getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
        .getOrDefault(0f)
}
