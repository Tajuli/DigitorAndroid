package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ConstantRateTimestampIterator
import androidx.media3.common.util.UnstableApi
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.TransitionPairV22
import com.tajuli.digitorandroid.editor.model.transitionPairsV22
import com.tajuli.digitorandroid.editor.preview.PreviewExportCoordinator
import com.tajuli.digitorandroid.editor.render.NativeExportRenderCoreV75
import com.tajuli.digitorandroid.editor.render.ResolveCompositorInputV22
import com.tajuli.digitorandroid.editor.render.SharedVideoPipeline
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Digitor native Android hardware exporter.
 *
 * V76 removes V75's single-clip envelope. Compressed transport is still fully platform-native:
 *
 * MediaExtractor -> MediaCodec decoder Surface -> Digitor OpenGL/Vulkan compositor/effects ->
 * MediaCodec AVC encoder Surface -> MediaMuxer MP4.
 *
 * Multiple V-tracks, sequential clips, timeline gaps, still images, transitions and project
 * text/visual overlays are scheduled directly into the low-level GPU graph. Active A-tracks are
 * independently decoded/mixed/encoded by [NativeAudioMixdownV76] and remuxed into the final MP4.
 * Media3 Transformer is not involved in this path; only Media3's low-level VideoGraph remains the
 * host for Digitor's existing GL Effect implementations. PP-Matting keeps its ncnn Vulkan path.
 */
@UnstableApi
internal class NativeHardwareExportBackendV75(
    private val context: Context,
) {

    fun plan(project: TimelineProject): NativeHardwareExportPlanV75? = nativeHardwareExportPlanV75(project)

    suspend fun export(
        project: TimelineProject,
        output: File,
        quality: ExportQuality,
        onProgress: (ExportProgress) -> Unit,
    ): ExportResult {
        val plan = plan(project) ?: error("Native hardware export cannot schedule this project")
        onProgress(ExportProgress.Stage("Native HW: releasing preview resources", 0.01f))
        val previewLease = PreviewExportCoordinator.acquireExportLease()
        return try {
            withContext(Dispatchers.Default) {
                output.parentFile?.mkdirs()
                if (output.exists()) output.delete()

                val hasAudio = plan.audioClipCount > 0
                val videoOutput = if (hasAudio) {
                    File.createTempFile("digitor-native-video-", ".mp4", context.cacheDir)
                } else {
                    output
                }
                val audioOutput = if (hasAudio) {
                    File.createTempFile("digitor-native-audio-", ".m4a", context.cacheDir)
                } else {
                    null
                }

                try {
                    val videoResult = runVideoWithDecoderFallbackV76(
                        plan = plan,
                        project = project,
                        output = videoOutput,
                        quality = quality,
                        hasAudio = hasAudio,
                        onProgress = onProgress,
                    )

                    if (!hasAudio) return@withContext videoResult

                    val audioFile = checkNotNull(audioOutput)
                    val audioResult = NativeAudioMixdownV76(context).encode(
                        project = project,
                        output = audioFile,
                        onProgress = { progress ->
                            val stage = progress as ExportProgress.Stage
                            onProgress(
                                ExportProgress.Stage(
                                    stage.name,
                                    stage.fraction?.let { (0.80f + it.coerceIn(0f, 1f) * 0.14f).coerceAtMost(0.94f) },
                                ),
                            )
                        },
                    )
                    onProgress(ExportProgress.Stage("Native H.264 + AAC remux", 0.96f))
                    remuxNativeVideoAndAudioV76(videoOutput, audioFile, output)
                    check(output.length() > 0L) { "Native AV export produced an empty MP4" }
                    onProgress(ExportProgress.Stage("Native HW export complete", 1f))
                    videoResult.copy(
                        output = output,
                        note = buildString {
                            videoResult.note?.takeIf { it.isNotBlank() }?.let {
                                append(it)
                                append(" · ")
                            }
                            append("native PCM mix ")
                            append(audioResult.mixedClipCount)
                            append(" clip(s) → AAC-LC ")
                            append(audioResult.sampleRate / 1000)
                            append(" kHz stereo")
                        },
                    )
                } finally {
                    if (hasAudio) runCatching { videoOutput.delete() }
                    runCatching { audioOutput?.delete() }
                }
            }
        } finally {
            previewLease.close()
        }
    }

    private fun runVideoWithDecoderFallbackV76(
        plan: NativeHardwareExportPlanV75,
        project: TimelineProject,
        output: File,
        quality: ExportQuality,
        hasAudio: Boolean,
        onProgress: (ExportProgress) -> Unit,
    ): ExportResult {
        return try {
            runNativeVideoPassV76(
                plan,
                project,
                output,
                quality,
                forceSoftwareDecoder = false,
                hasAudio = hasAudio,
                onProgress = onProgress,
            )
        } catch (decoderFailure: NativeDecoderFailureV75) {
            if (!hasSoftwareDecoderV75(decoderFailure.mime)) throw decoderFailure
            runCatching { if (output.exists()) output.delete() }
            onProgress(
                ExportProgress.Stage(
                    "Native HW decoder failed · retrying software decode + GPU render",
                    0.02f,
                ),
            )
            runNativeVideoPassV76(
                plan,
                project,
                output,
                quality,
                forceSoftwareDecoder = true,
                hasAudio = hasAudio,
                onProgress = onProgress,
            )
        }
    }

    private fun runNativeVideoPassV76(
        plan: NativeHardwareExportPlanV75,
        project: TimelineProject,
        output: File,
        quality: ExportQuality,
        forceSoftwareDecoder: Boolean,
        hasAudio: Boolean,
        onProgress: (ExportProgress) -> Unit,
    ): ExportResult {
        var encoder: NativeSurfaceAvcEncoderMuxV75? = null
        var renderCore: NativeExportRenderCoreV75? = null
        val graphEnded = AtomicBoolean(false)
        val graphError = AtomicReference<Throwable?>(null)
        val producerError = AtomicReference<Throwable?>(null)
        val cancelProducers = AtomicBoolean(false)
        val completedProducers = AtomicInteger(0)
        val maxTimelineUs = AtomicLong(0L)
        val directExecutor = Executor { command -> command.run() }
        val requestedBitrate = quality.videoBitrate(project.width, project.height, project.frameRate)
        val producerThreads = mutableListOf<Thread>()

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
                inputs = plan.inputs.map { it.compositorInput },
                encoderSurface = createdEncoder.inputSurface,
                listenerExecutor = directExecutor,
                listener = object : NativeExportRenderCoreV75.Listener {
                    override fun onEnded(finalFramePresentationTimeUs: Long) {
                        maxTimelineUs.accumulateAndGet(finalFramePresentationTimeUs.coerceAtLeast(0L), ::maxOf)
                        graphEnded.set(true)
                    }

                    override fun onError(error: Throwable) {
                        graphError.compareAndSet(null, error)
                    }
                },
            )
            renderCore = core

            onProgress(
                ExportProgress.Stage(
                    buildString {
                        append("Native timeline · ")
                        append(plan.videoTrackCount)
                        append(" V track(s) · ")
                        append(plan.videoClipCount)
                        append(" clip(s) · GPU → ")
                        append(createdEncoder.encoderName)
                    },
                    0.03f,
                ),
            )

            plan.inputs.forEachIndexed { inputId, inputPlan ->
                val thread = Thread(
                    {
                        try {
                            feedInputV76(
                                inputId = inputId,
                                inputPlan = inputPlan,
                                project = project,
                                core = core,
                                encoderName = createdEncoder.encoderName,
                                forceSoftwareDecoder = forceSoftwareDecoder,
                                cancel = cancelProducers,
                                graphError = graphError,
                                maxTimelineUs = maxTimelineUs,
                            )
                        } catch (error: Throwable) {
                            producerError.compareAndSet(null, error)
                            cancelProducers.set(true)
                        } finally {
                            completedProducers.incrementAndGet()
                        }
                    },
                    "DigitorNativeV76-$inputId",
                ).apply { isDaemon = true }
                producerThreads += thread
                thread.start()
            }

            var encoderInputEnded = false
            var idleSinceNs = System.nanoTime()
            var lastProgressUs = -1L
            var lastCompleted = -1
            while (!createdEncoder.outputEnded) {
                producerError.get()?.let { throw it }
                graphError.get()?.let { throw it }
                var didWork = createdEncoder.drainAvailable()

                if (graphEnded.get() && !encoderInputEnded) {
                    createdEncoder.signalEndOfInput()
                    encoderInputEnded = true
                    didWork = true
                }

                val progressUs = maxTimelineUs.get().coerceIn(0L, project.durationUs.coerceAtLeast(1L))
                val completed = completedProducers.get()
                if (progressUs != lastProgressUs || completed != lastCompleted) {
                    lastProgressUs = progressUs
                    lastCompleted = completed
                    val raw = progressUs.toDouble() / project.durationUs.coerceAtLeast(1L).toDouble()
                    val ceiling = if (hasAudio) 0.78f else 0.97f
                    val fraction = (0.04f + raw.toFloat().coerceIn(0f, 1f) * (ceiling - 0.04f))
                        .coerceIn(0.04f, ceiling)
                    onProgress(ExportProgress.Stage("Native multitrack GPU rendering", fraction))
                    didWork = true
                }

                if (didWork) {
                    idleSinceNs = System.nanoTime()
                } else {
                    if ((System.nanoTime() - idleSinceNs) / 1_000_000L > PIPELINE_IDLE_TIMEOUT_MS) {
                        error(
                            "Native export pipeline stalled (${completedProducers.get()}/${plan.inputs.size} inputs complete)",
                        )
                    }
                    Thread.sleep(1L)
                }
            }

            cancelProducers.set(true)
            producerThreads.forEach { thread -> if (thread.isAlive) thread.join(PRODUCER_JOIN_TIMEOUT_MS) }
            producerError.get()?.let { throw it }
            graphError.get()?.let { throw it }
            check(completedProducers.get() == plan.inputs.size) {
                "Native GPU graph ended before every timeline input completed"
            }
            check(createdEncoder.muxerStarted) { "Native encoder produced no MP4 track" }
            check(output.length() > 0L) { "Native export produced an empty MP4" }
            if (!hasAudio) onProgress(ExportProgress.Stage("Native HW export complete", 1f))
            return ExportResult(
                output = output,
                backend = Backend.GPU,
                note = buildString {
                    append("Native MediaExtractor/MediaCodec/MediaMuxer V76 · ")
                    append(if (forceSoftwareDecoder) "software decode fallback" else "hardware decode")
                    append(" · ")
                    append(plan.videoTrackCount)
                    append(" V track(s) · Digitor OpenGL/Vulkan GPU · ")
                    append(createdEncoder.encoderName)
                    append(" · ")
                    append(createdEncoder.actualBitrate / 1_000_000f)
                    append(" Mbps")
                },
            )
        } catch (error: Throwable) {
            cancelProducers.set(true)
            runCatching { if (output.exists()) output.delete() }
            throw error
        } finally {
            cancelProducers.set(true)
            producerThreads.forEach { it.interrupt() }
            producerThreads.forEach { thread -> runCatching { if (thread.isAlive) thread.join(PRODUCER_JOIN_TIMEOUT_MS) } }
            runCatching { renderCore?.close() }
            runCatching { encoder?.close() }
        }
    }

    private fun feedInputV76(
        inputId: Int,
        inputPlan: NativeGraphInputPlanV76,
        project: TimelineProject,
        core: NativeExportRenderCoreV75,
        encoderName: String,
        forceSoftwareDecoder: Boolean,
        cancel: AtomicBoolean,
        graphError: AtomicReference<Throwable?>,
        maxTimelineUs: AtomicLong,
    ) {
        when (inputPlan) {
            is NativeGraphInputPlanV76.Track -> {
                var cursorUs = 0L
                inputPlan.track.sortedClips().forEach { clip ->
                    ensureProducerActiveV76(cancel, graphError)
                    if (clip.timelineStartUs > cursorUs) {
                        queueGapV76(
                            inputId,
                            cursorUs,
                            clip.timelineStartUs,
                            project,
                            core,
                            cancel,
                            graphError,
                            maxTimelineUs,
                        )
                    }
                    feedClipV76(
                        inputId,
                        clip,
                        project,
                        core,
                        encoderName,
                        forceSoftwareDecoder,
                        cancel,
                        graphError,
                        maxTimelineUs,
                    )
                    cursorUs = clip.timelineEndUs
                }
                if (project.durationUs > cursorUs) {
                    queueGapV76(
                        inputId,
                        cursorUs,
                        project.durationUs,
                        project,
                        core,
                        cancel,
                        graphError,
                        maxTimelineUs,
                    )
                }
            }

            is NativeGraphInputPlanV76.Ghost -> {
                if (inputPlan.clip.timelineStartUs > 0L) {
                    queueGapV76(
                        inputId,
                        0L,
                        inputPlan.clip.timelineStartUs,
                        project,
                        core,
                        cancel,
                        graphError,
                        maxTimelineUs,
                    )
                }
                feedClipV76(
                    inputId,
                    inputPlan.clip,
                    project,
                    core,
                    encoderName,
                    forceSoftwareDecoder,
                    cancel,
                    graphError,
                    maxTimelineUs,
                )
                if (project.durationUs > inputPlan.pair.endUs) {
                    queueGapV76(
                        inputId,
                        inputPlan.pair.endUs,
                        project.durationUs,
                        project,
                        core,
                        cancel,
                        graphError,
                        maxTimelineUs,
                    )
                }
            }

            NativeGraphInputPlanV76.Blank -> {
                queueGapV76(
                    inputId,
                    0L,
                    project.durationUs,
                    project,
                    core,
                    cancel,
                    graphError,
                    maxTimelineUs,
                )
            }
        }
        ensureProducerActiveV76(cancel, graphError)
        core.signalEndOfInput(inputId)
    }

    private fun feedClipV76(
        inputId: Int,
        clip: TimelineClip,
        project: TimelineProject,
        core: NativeExportRenderCoreV75,
        encoderName: String,
        forceSoftwareDecoder: Boolean,
        cancel: AtomicBoolean,
        graphError: AtomicReference<Throwable?>,
        maxTimelineUs: AtomicLong,
    ) {
        if (clip.isImageV21) {
            feedImageClipV76(inputId, clip, project, core, cancel, graphError, maxTimelineUs)
        } else {
            feedMovingVideoClipV76(
                inputId,
                clip,
                project,
                core,
                encoderName,
                forceSoftwareDecoder,
                cancel,
                graphError,
                maxTimelineUs,
            )
        }
    }

    private fun feedImageClipV76(
        inputId: Int,
        clip: TimelineClip,
        project: TimelineProject,
        core: NativeExportRenderCoreV75,
        cancel: AtomicBoolean,
        graphError: AtomicReference<Throwable?>,
        maxTimelineUs: AtomicLong,
    ) {
        val bitmap = decodeImageBitmapV76(Uri.parse(clip.uri))
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.IMAGE_RAW)
            .setWidth(bitmap.width.coerceAtLeast(1))
            .setHeight(bitmap.height.coerceAtLeast(1))
            .setPixelWidthHeightRatio(1f)
            .setColorInfo(ColorInfo.SRGB_BT709_FULL)
            .build()
        core.registerBitmapStream(
            inputId = inputId,
            format = format,
            effects = SharedVideoPipeline.compositedExportEffectsFor(clip),
            offsetToAddUs = clip.timelineStartUs,
        )
        queueBitmapWithBackpressureV76(
            inputId = inputId,
            bitmap = bitmap,
            durationUs = clip.durationUs,
            frameRate = project.frameRate,
            core = core,
            cancel = cancel,
            graphError = graphError,
        )
        maxTimelineUs.accumulateAndGet(clip.timelineEndUs, ::maxOf)
    }

    private fun queueGapV76(
        inputId: Int,
        startUs: Long,
        endUs: Long,
        project: TimelineProject,
        core: NativeExportRenderCoreV75,
        cancel: AtomicBoolean,
        graphError: AtomicReference<Throwable?>,
        maxTimelineUs: AtomicLong,
    ) {
        val durationUs = endUs - startUs
        if (durationUs <= 0L) return
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
        }
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.IMAGE_RAW)
            .setWidth(2)
            .setHeight(2)
            .setPixelWidthHeightRatio(1f)
            .setColorInfo(ColorInfo.SRGB_BT709_FULL)
            .build()
        core.registerBitmapStream(inputId, format, emptyList(), startUs)
        queueBitmapWithBackpressureV76(
            inputId = inputId,
            bitmap = bitmap,
            durationUs = durationUs,
            frameRate = project.frameRate,
            core = core,
            cancel = cancel,
            graphError = graphError,
        )
        maxTimelineUs.accumulateAndGet(endUs, ::maxOf)
    }

    private fun queueBitmapWithBackpressureV76(
        inputId: Int,
        bitmap: Bitmap,
        durationUs: Long,
        frameRate: Int,
        core: NativeExportRenderCoreV75,
        cancel: AtomicBoolean,
        graphError: AtomicReference<Throwable?>,
    ) {
        val iterator = ConstantRateTimestampIterator(
            durationUs.coerceAtLeast(1L),
            frameRate.coerceAtLeast(1).toFloat(),
        )
        val startedNs = System.nanoTime()
        while (true) {
            ensureProducerActiveV76(cancel, graphError)
            if (core.queueInputBitmap(inputId, bitmap, iterator)) return
            if ((System.nanoTime() - startedNs) / 1_000_000L > GRAPH_BACKPRESSURE_TIMEOUT_MS) {
                error("Native GPU graph stopped accepting bitmap input $inputId")
            }
            Thread.sleep(1L)
        }
    }

    private fun feedMovingVideoClipV76(
        inputId: Int,
        clip: TimelineClip,
        project: TimelineProject,
        core: NativeExportRenderCoreV75,
        encoderName: String,
        forceSoftwareDecoder: Boolean,
        cancel: AtomicBoolean,
        graphError: AtomicReference<Throwable?>,
        maxTimelineUs: AtomicLong,
    ) {
        val source = prepareVideoSourceV75(clip)
        var decoder: MediaCodec? = null
        try {
            core.registerSurfaceStream(inputId, clip, source.media3Format)
            val decoderName = chooseDecoderNameV75(
                mime = source.mime,
                format = source.platformFormat,
                forceSoftware = forceSoftwareDecoder,
            ) ?: throw NativeDecoderFailureV75(source.mime, "No decoder for ${source.mime}")
            val createdDecoder = try {
                MediaCodec.createByCodecName(decoderName).also { codec ->
                    codec.configure(source.platformFormat, core.inputSurface(inputId), null, 0)
                    codec.start()
                }
            } catch (error: Throwable) {
                throw NativeDecoderFailureV75(source.mime, "Decoder $decoderName could not start", error)
            }
            decoder = createdDecoder
            source.extractor.seekTo(clip.sourceInUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val info = MediaCodec.BufferInfo()
            var inputEos = false
            var outputEos = false
            var nextOutputTimelineUs = clip.timelineStartUs
            val targetFrameDurationUs = (1_000_000L / project.frameRate.coerceAtLeast(1)).coerceAtLeast(1L)
            var idleSinceNs = System.nanoTime()

            while (!outputEos) {
                ensureProducerActiveV76(cancel, graphError)
                var didWork = false

                if (!inputEos) {
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
                        if (sampleTimeUs < 0L || sampleTimeUs >= clip.sourceOutUs) {
                            try {
                                createdDecoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    clip.sourceOutUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                            } catch (error: Throwable) {
                                throw NativeDecoderFailureV75(source.mime, "Decoder EOS queue failed", error)
                            }
                            inputEos = true
                            didWork = true
                            break
                        }
                        val size = source.extractor.readSampleData(input, 0)
                        if (size < 0) {
                            createdDecoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                sampleTimeUs.coerceAtLeast(0L),
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEos = true
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

                for (attempt in 0 until 12) {
                    val outputIndex = try {
                        createdDecoder.dequeueOutputBuffer(info, 0L)
                    } catch (error: Throwable) {
                        throw NativeDecoderFailureV75(source.mime, "Decoder output failed", error)
                    }
                    when {
                        outputIndex >= 0 -> {
                            val sourceUs = info.presentationTimeUs
                            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            val timelineUs = clip.timelineStartUs + (sourceUs - clip.sourceInUs)
                            val insideClip = sourceUs >= clip.sourceInUs && sourceUs < clip.sourceOutUs
                            val hitsNextOutputSlot = insideClip && timelineUs + 1_000L >= nextOutputTimelineUs
                            if (hitsNextOutputSlot) {
                                while (nextOutputTimelineUs <= timelineUs) {
                                    nextOutputTimelineUs += targetFrameDurationUs
                                }
                                val waitStartedNs = System.nanoTime()
                                while (
                                    core.pendingInputFrames(inputId) >= MAX_GRAPH_PENDING_FRAMES ||
                                    !core.registerInputFrame(inputId)
                                ) {
                                    ensureProducerActiveV76(cancel, graphError)
                                    if ((System.nanoTime() - waitStartedNs) / 1_000_000L > GRAPH_BACKPRESSURE_TIMEOUT_MS) {
                                        error(
                                            "Native GPU graph stopped accepting decoder frames " +
                                                "($decoderName → $encoderName, input=$inputId)",
                                        )
                                    }
                                    Thread.sleep(1L)
                                }
                                try {
                                    createdDecoder.releaseOutputBuffer(outputIndex, true)
                                } catch (error: Throwable) {
                                    throw NativeDecoderFailureV75(source.mime, "Decoder Surface release failed", error)
                                }
                                maxTimelineUs.accumulateAndGet(timelineUs.coerceAtLeast(0L), ::maxOf)
                            } else {
                                createdDecoder.releaseOutputBuffer(outputIndex, false)
                            }
                            if (eos) outputEos = true
                            didWork = true
                        }
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> didWork = true
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                    }
                }

                if (didWork) {
                    idleSinceNs = System.nanoTime()
                } else {
                    if ((System.nanoTime() - idleSinceNs) / 1_000_000L > DECODER_IDLE_TIMEOUT_MS) {
                        throw NativeDecoderFailureV75(source.mime, "Decoder $decoderName stalled on ${clip.label}")
                    }
                    Thread.sleep(1L)
                }
            }
            maxTimelineUs.accumulateAndGet(clip.timelineEndUs, ::maxOf)
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { source.extractor.release() }
        }
    }

    private fun decodeImageBitmapV76(uri: Uri): Bitmap {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: error("Unable to open image $uri")
        } ?: error("Unable to decode image $uri")
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
            val resolvedFormat = platformFormat
            val resolvedMime = mime
            val width = resolvedFormat.intValueV75(MediaFormat.KEY_WIDTH, 1).coerceAtLeast(1)
            val height = resolvedFormat.intValueV75(MediaFormat.KEY_HEIGHT, 1).coerceAtLeast(1)
            val rotation = resolvedFormat.intValueV75(MediaFormat.KEY_ROTATION, 0)
            val frameRate = resolvedFormat.frameRateV75()
            val formatBuilder = Format.Builder()
                .setSampleMimeType(resolvedMime)
                .setWidth(width)
                .setHeight(height)
                .setPixelWidthHeightRatio(1f)
                .setRotationDegrees(rotation)
                .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            if (frameRate > 0f) formatBuilder.setFrameRate(frameRate)
            return PreparedNativeSourceV75(extractor, resolvedFormat, formatBuilder.build(), resolvedMime)
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

    private fun ensureProducerActiveV76(
        cancel: AtomicBoolean,
        graphError: AtomicReference<Throwable?>,
    ) {
        graphError.get()?.let { throw it }
        if (cancel.get() || Thread.currentThread().isInterrupted) {
            throw InterruptedException("Native timeline producer cancelled")
        }
    }

    private companion object {
        const val MAX_GRAPH_PENDING_FRAMES = 4
        const val GRAPH_BACKPRESSURE_TIMEOUT_MS = 30_000L
        const val DECODER_IDLE_TIMEOUT_MS = 30_000L
        const val PIPELINE_IDLE_TIMEOUT_MS = 60_000L
        const val PRODUCER_JOIN_TIMEOUT_MS = 2_000L
    }
}

internal data class NativeHardwareExportPlanV75(
    val inputs: List<NativeGraphInputPlanV76>,
    val videoTrackCount: Int,
    val videoClipCount: Int,
    val audioClipCount: Int,
)

internal sealed class NativeGraphInputPlanV76 {
    abstract val compositorInput: ResolveCompositorInputV22

    data class Track(val track: TimelineTrack) : NativeGraphInputPlanV76() {
        override val compositorInput: ResolveCompositorInputV22 = ResolveCompositorInputV22.TrackInput(track)
    }

    data class Ghost(
        val pair: TransitionPairV22,
        val clip: TimelineClip,
    ) : NativeGraphInputPlanV76() {
        override val compositorInput: ResolveCompositorInputV22 =
            ResolveCompositorInputV22.TransitionGhostInput(pair, clip)
    }

    data object Blank : NativeGraphInputPlanV76() {
        override val compositorInput: ResolveCompositorInputV22 = ResolveCompositorInputV22.BlankInput
    }
}

/**
 * Pure, unit-testable native timeline planner. V76 supports all editor timeline shapes that can be
 * represented by Digitor's current project model; runtime codec/image failures still fall through
 * ProcessingRouter's compatibility exporter rather than dropping a feature.
 */
internal fun nativeHardwareExportPlanV75(project: TimelineProject): NativeHardwareExportPlanV75? {
    if (project.validate().isNotEmpty() || project.durationUs <= 0L) return null
    val videoTracks = project.tracks.filter {
        it.kind == TrackKind.VIDEO && !it.muted && it.clips.isNotEmpty()
    }
    val hasVisualOutput = videoTracks.isNotEmpty() ||
        project.textOverlays.isNotEmpty() ||
        project.visualOverlaysV19.orEmpty().isNotEmpty()
    if (!hasVisualOutput) return null

    val inputs = buildList {
        videoTracks.forEach { track ->
            track.transitionPairsV22().forEach { pair ->
                val outgoing = pair.outgoing
                val sourceOutUs = outgoing.sourceOutUs
                val sourceInUs = (sourceOutUs - pair.durationUs).coerceAtLeast(outgoing.sourceInUs)
                val ghost = outgoing.copy(
                    id = "${outgoing.id}__native_transition_${pair.incoming.id}",
                    label = "${outgoing.label} · transition tail",
                    timelineStartUs = pair.startUs,
                    sourceInUs = sourceInUs,
                    sourceOutUs = sourceOutUs,
                    linkGroupId = null,
                    transition = pair.incoming.transition.copy(durationUsV22 = pair.durationUs),
                )
                add(NativeGraphInputPlanV76.Ghost(pair, ghost))
            }
            add(NativeGraphInputPlanV76.Track(track))
        }
        // Always keep one full-duration background/sentinel input. Besides preserving leading,
        // middle and trailing gaps, it gives text/visual-overlay-only projects a video clock.
        add(NativeGraphInputPlanV76.Blank)
    }
    val audioClipCount = project.tracks
        .filter { it.kind == TrackKind.AUDIO && !it.muted }
        .sumOf { it.clips.size }
    return NativeHardwareExportPlanV75(
        inputs = inputs,
        videoTrackCount = videoTracks.size,
        videoClipCount = videoTracks.sumOf { it.clips.size },
        audioClipCount = audioClipCount,
    )
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
            throw NativeEncoderCapabilityFailureV75(
                "Native AVC encoder ${choice.name} could not start: ${error.message}",
            )
        }
        muxer = android.media.MediaMuxer(
            output.absolutePath,
            android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        )
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
            val bitrate = runCatching { videoCaps.bitrateRange.clamp(requestedBitrate) }
                .getOrDefault(requestedBitrate)
            Triple(info, bitrate, info.isHardwareCodecV75())
        }
        .sortedByDescending { it.third }
    val selected = candidates.firstOrNull()
        ?: throw NativeEncoderCapabilityFailureV75(
            "No AVC Surface encoder supports ${width}×${height} @ ${frameRate} fps",
        )
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
