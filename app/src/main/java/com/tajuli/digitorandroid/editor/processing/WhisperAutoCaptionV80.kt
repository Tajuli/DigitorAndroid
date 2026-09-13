package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.tajuli.digitorandroid.editor.model.AutoCaptionLanguageV80
import com.tajuli.digitorandroid.editor.model.AutoCaptionSegmentV80
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.normalizeAutoCaptionSegmentsV80
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max

/** JNI surface backed by the pinned whisper.cpp v1.9.4 native target. */
internal object WhisperNativeV80 {
    init {
        System.loadLibrary("digitor_whisper_jni")
    }

    /** Loads the model once and reports the active native backend. */
    external fun prepareBackend(modelPath: String): String

    external fun transcribe(
        modelPath: String,
        samples: FloatArray,
        language: String,
    ): Array<String>
}

private data class CaptionSourceV84(
    val trackId: String,
    val clip: TimelineClip,
)

private data class CaptionBatchV84(
    val sources: List<CaptionSourceV84>,
) {
    val timelineStartUs: Long get() = sources.first().clip.timelineStartUs
    val timelineEndUs: Long get() = sources.last().clip.timelineEndUs
    val durationUs: Long get() = (timelineEndUs - timelineStartUs).coerceAtLeast(1L)
}

/**
 * On-device, no-per-minute-cost auto captions.
 *
 * V83 uses the multilingual small-q5_1 model instead of tiny-q5_1 for materially better
 * international/Bengali recognition. V84 uses whisper.cpp's Vulkan GPU backend on the production
 * arm64 phone build; that build no longer silently falls back to CPU.
 *
 * Split clips that are still contiguous pieces of the same source are batched back together before
 * Whisper and decoded through one MediaExtractor/MediaCodec pass. Splitting a 60-second video into
 * four 15-second clips therefore does not cause four decoder/model setup cycles.
 */
internal class WhisperAutoCaptionV80(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val modelStore = WhisperModelStoreV80(appContext)

    fun transcribeProject(
        project: TimelineProject,
        language: AutoCaptionLanguageV80,
        onStatus: (String) -> Unit = {},
    ): List<AutoCaptionSegmentV80> {
        val model = modelStore.ensureModel(onStatus)
        onStatus("Auto Caption · preparing GPU")
        val backend = WhisperNativeV80.prepareBackend(model.absolutePath)
        onStatus("Auto Caption · $backend ready")

        val audioSources = project.tracks
            .filter { it.kind == TrackKind.AUDIO && !it.muted }
            .flatMap { track -> track.clips.map { clip -> CaptionSourceV84(track.id, clip) } }
            .sortedBy { it.clip.timelineStartUs }
        val sources = if (audioSources.isNotEmpty()) {
            audioSources
        } else {
            // Legacy projects may contain video without the linked A-track introduced by V12.
            project.tracks
                .filter { it.kind == TrackKind.VIDEO }
                .flatMap { track -> track.clips.map { clip -> CaptionSourceV84(track.id, clip) } }
                .sortedBy { it.clip.timelineStartUs }
        }
        require(sources.isNotEmpty()) { "No audio/video clips available for Auto Caption" }

        val batches = buildCaptionBatchesV84(sources)
        val segments = mutableListOf<AutoCaptionSegmentV80>()
        var decodedClipCount = 0

        batches.forEachIndexed { batchIndex, batch ->
            val samples = if (batch.sources.size == 1) {
                decodedClipCount++
                onStatus("Auto Caption · decoding $decodedClipCount/${sources.size} · $backend")
                decodeClipToMono16k(batch.sources.first().clip)
            } else {
                // buildCaptionBatchesV84 only joins same-track, same-URI, source-contiguous pieces.
                // Decode that source range once instead of reopening MediaExtractor/MediaCodec for
                // every user split. This is especially important for short split-clip Auto CC.
                val first = batch.sources.first().clip
                val last = batch.sources.last().clip
                decodedClipCount += batch.sources.size
                onStatus(
                    "Auto Caption · decoding split batch $decodedClipCount/${sources.size} · $backend",
                )
                decodeClipToMono16k(first.copy(sourceOutUs = last.sourceOutUs))
            }
            if (samples.isEmpty()) return@forEachIndexed

            onStatus(
                "Auto Caption · transcribing ${batchIndex + 1}/${batches.size} · $backend · ${language.label}",
            )
            val raw = WhisperNativeV80.transcribe(model.absolutePath, samples, language.whisperCode)
            raw.forEach { line ->
                parseNativeSegment(line)?.let { local ->
                    val start = batch.timelineStartUs + local.startUs.coerceIn(0L, batch.durationUs)
                    val end = batch.timelineStartUs + local.endUs.coerceIn(0L, batch.durationUs)
                    if (end > start) segments += local.copy(startUs = start, endUs = end)
                }
            }
        }
        return normalizeAutoCaptionSegmentsV80(segments)
    }

    private fun buildCaptionBatchesV84(sources: List<CaptionSourceV84>): List<CaptionBatchV84> {
        if (sources.isEmpty()) return emptyList()
        val result = mutableListOf<CaptionBatchV84>()
        var current = mutableListOf(sources.first())

        sources.drop(1).forEach { next ->
            val previous = current.last()
            val sameTrack = previous.trackId == next.trackId
            val sameMedia = previous.clip.uri == next.clip.uri
            val timelineContinuous = abs(previous.clip.timelineEndUs - next.clip.timelineStartUs) <= CONTIGUOUS_TOLERANCE_US
            val sourceContinuous = abs(previous.clip.sourceOutUs - next.clip.sourceInUs) <= CONTIGUOUS_TOLERANCE_US
            val proposedDuration = next.clip.timelineEndUs - current.first().clip.timelineStartUs
            val canJoin = sameTrack && sameMedia && timelineContinuous && sourceContinuous &&
                proposedDuration <= MAX_BATCH_DURATION_US

            if (canJoin) {
                current += next
            } else {
                result += CaptionBatchV84(current.toList())
                current = mutableListOf(next)
            }
        }
        result += CaptionBatchV84(current.toList())
        return result
    }

    private fun parseNativeSegment(line: String): AutoCaptionSegmentV80? {
        val first = line.indexOf('\t')
        val second = if (first >= 0) line.indexOf('\t', first + 1) else -1
        if (first <= 0 || second <= first) return null
        val start = line.substring(0, first).toLongOrNull() ?: return null
        val end = line.substring(first + 1, second).toLongOrNull() ?: return null
        val text = line.substring(second + 1).trim()
        if (text.isBlank()) return null
        return AutoCaptionSegmentV80(startUs = start, endUs = end, text = text)
    }

    private fun decodeClipToMono16k(clip: TimelineClip): FloatArray {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(appContext, Uri.parse(clip.uri), null)
            var trackIndex = -1
            var format: MediaFormat? = null
            var mime: String? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                val candidateMime = candidate.getString(MediaFormat.KEY_MIME)
                if (candidateMime?.startsWith("audio/") == true) {
                    trackIndex = index
                    format = candidate
                    mime = candidateMime
                    break
                }
            }
            if (trackIndex < 0 || format == null || mime == null) return FloatArray(0)

            extractor.selectTrack(trackIndex)
            extractor.seekTo(clip.sourceInUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val codec = MediaCodec.createDecoderByType(mime)
            decoder = codec
            codec.configure(format, null, null, 0)
            codec.start()

            var sampleRate = format.intValue(MediaFormat.KEY_SAMPLE_RATE, TARGET_SAMPLE_RATE).coerceAtLeast(1)
            var channelCount = format.intValue(MediaFormat.KEY_CHANNEL_COUNT, 2).coerceAtLeast(1)
            var pcmEncoding = format.intValue(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val nativePcm = FloatBuilderV80(initialCapacity = minOf(524_288, estimatedNativeSamples(clip, sampleRate)))
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var idleLoops = 0

            while (!outputEnded) {
                var didWork = false
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(2_000L)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex) ?: error("Audio decoder input unavailable")
                        input.clear()
                        val sampleTimeUs = extractor.sampleTime
                        if (sampleTimeUs < 0L || sampleTimeUs >= clip.sourceOutUs) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                max(0L, clip.sourceOutUs),
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    max(0L, sampleTimeUs),
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputEnded = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, size, sampleTimeUs, 0)
                                extractor.advance()
                            }
                        }
                        didWork = true
                    }
                }

                repeat(8) {
                    if (outputEnded) return@repeat
                    val outputIndex = codec.dequeueOutputBuffer(info, 0L)
                    when {
                        outputIndex >= 0 -> {
                            if (info.size > 0) {
                                codec.getOutputBuffer(outputIndex)?.let { buffer ->
                                    appendDecodedNativeMono(
                                        clip = clip,
                                        decoded = buffer,
                                        info = info,
                                        sampleRate = sampleRate,
                                        channelCount = channelCount,
                                        pcmEncoding = pcmEncoding,
                                        output = nativePcm,
                                    )
                                }
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEnded = true
                            codec.releaseOutputBuffer(outputIndex, false)
                            didWork = true
                        }
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val changed = codec.outputFormat
                            sampleRate = changed.intValue(MediaFormat.KEY_SAMPLE_RATE, sampleRate).coerceAtLeast(1)
                            channelCount = changed.intValue(MediaFormat.KEY_CHANNEL_COUNT, channelCount).coerceAtLeast(1)
                            pcmEncoding = changed.intValue(MediaFormat.KEY_PCM_ENCODING, pcmEncoding)
                            didWork = true
                        }
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return@repeat
                    }
                }

                if (didWork) {
                    idleLoops = 0
                } else {
                    idleLoops++
                    if (idleLoops > 5_000) error("Audio decoder stalled for ${clip.label}")
                    Thread.sleep(1L)
                }
            }

            val decoded = nativePcm.toFloatArray()
            if (decoded.isEmpty()) return decoded
            return resampleMonoForWhisperV83(decoded, inputRate = sampleRate, outputRate = TARGET_SAMPLE_RATE)
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Keep the decoded stream at its native rate; resampling happens once after decode. */
    private fun appendDecodedNativeMono(
        clip: TimelineClip,
        decoded: ByteBuffer,
        info: MediaCodec.BufferInfo,
        sampleRate: Int,
        channelCount: Int,
        pcmEncoding: Int,
        output: FloatBuilderV80,
    ) {
        val bytesPerSample = bytesPerSample(pcmEncoding)
        val frameBytes = bytesPerSample * channelCount
        if (frameBytes <= 0 || info.size < frameBytes) return
        val frames = info.size / frameBytes
        val source = decoded.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply {
            position(info.offset)
            limit(info.offset + info.size)
        }

        for (frame in 0 until frames) {
            val sourceTimeUs = info.presentationTimeUs + frame.toLong() * 1_000_000L / sampleRate.toLong()
            if (sourceTimeUs < clip.sourceInUs) continue
            if (sourceTimeUs >= clip.sourceOutUs) break
            val frameOffset = info.offset + frame * frameBytes
            var mono = 0f
            for (channel in 0 until channelCount) {
                mono += readSample(source, frameOffset + channel * bytesPerSample, pcmEncoding)
            }
            output.add((mono / channelCount.toFloat()).coerceIn(-1f, 1f))
        }
    }

    private fun readSample(buffer: ByteBuffer, offset: Int, encoding: Int): Float = when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> buffer.getFloat(offset).coerceIn(-1f, 1f)
        AudioFormat.ENCODING_PCM_8BIT -> (((buffer.get(offset).toInt() and 0xFF) - 128) / 128f).coerceIn(-1f, 1f)
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
            var value = (buffer.get(offset).toInt() and 0xFF) or
                ((buffer.get(offset + 1).toInt() and 0xFF) shl 8) or
                ((buffer.get(offset + 2).toInt() and 0xFF) shl 16)
            if (value and 0x800000 != 0) value = value or -0x1000000
            (value / 8_388_608f).coerceIn(-1f, 1f)
        }
        AudioFormat.ENCODING_PCM_32BIT -> (buffer.getInt(offset) / 2_147_483_648f).coerceIn(-1f, 1f)
        else -> (buffer.getShort(offset) / 32_768f).coerceIn(-1f, 1f)
    }

    private fun bytesPerSample(encoding: Int): Int = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 1
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
        else -> 2
    }

    private fun MediaFormat.intValue(key: String, fallback: Int): Int =
        runCatching { if (containsKey(key)) getInteger(key) else fallback }.getOrDefault(fallback)

    private fun estimatedNativeSamples(clip: TimelineClip, sampleRate: Int): Int =
        ((clip.durationUs / 1_000_000.0) * sampleRate.toDouble())
            .toLong()
            .coerceIn(16_000L, 8_000_000L)
            .toInt()

    companion object {
        private const val TARGET_SAMPLE_RATE = 16_000
        private const val CONTIGUOUS_TOLERANCE_US = 25_000L
        private const val MAX_BATCH_DURATION_US = 120_000_000L
    }
}

private class FloatBuilderV80(initialCapacity: Int) {
    private var values = FloatArray(initialCapacity.coerceAtLeast(1))
    private var size = 0

    fun add(value: Float) {
        ensureCapacity(size + 1)
        values[size++] = value
    }

    fun addAll(source: FloatArray) {
        if (source.isEmpty()) return
        ensureCapacity(size + source.size)
        source.copyInto(values, destinationOffset = size)
        size += source.size
    }

    private fun ensureCapacity(required: Int) {
        if (required <= values.size) return
        var next = values.size.coerceAtLeast(16)
        while (next < required) next = (next * 2).coerceAtLeast(required)
        values = values.copyOf(next)
    }

    fun toFloatArray(): FloatArray = values.copyOf(size)
}

private class WhisperModelStoreV80(
    private val context: Context,
) {
    fun ensureModel(onStatus: (String) -> Unit): File {
        val directory = File(context.filesDir, "speech/whisper").apply { mkdirs() }
        val model = File(directory, MODEL_NAME)
        if (model.isFile && model.length() >= MIN_MODEL_BYTES && sha256(model) == MODEL_SHA256) {
            deleteLegacyTinyModel(directory)
            return model
        }
        if (model.exists()) model.delete()

        val partial = File(directory, "$MODEL_NAME.download")
        if (partial.exists()) partial.delete()
        var lastError: Throwable? = null
        for (attempt in 1..3) {
            var connection: HttpURLConnection? = null
            try {
                onStatus("Auto Caption · downloading accuracy model${if (attempt > 1) " (retry $attempt)" else ""}")
                connection = URI(MODEL_URL).toURL().openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 30_000
                connection.readTimeout = 300_000
                connection.setRequestProperty("User-Agent", "DigitorAndroid/0.1")
                connection.setRequestProperty("Accept", "application/octet-stream,*/*")
                val code = connection.responseCode
                require(code in 200..299) { "Speech model download returned HTTP $code" }
                val total = connection.contentLengthLong
                val digest = MessageDigest.getInstance("SHA-256")
                var copied = 0L
                connection.inputStream.buffered().use { input ->
                    FileOutputStream(partial).buffered().use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            copied += read
                            if (total > 0L) {
                                val percent = (copied * 100L / total).coerceIn(0L, 100L)
                                onStatus("Auto Caption · accuracy model $percent%")
                            }
                        }
                    }
                }
                require(partial.length() >= MIN_MODEL_BYTES) { "Downloaded speech model is incomplete" }
                val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                require(actualSha.equals(MODEL_SHA256, ignoreCase = true)) { "Speech model checksum mismatch" }
                if (!partial.renameTo(model)) {
                    partial.copyTo(model, overwrite = true)
                    partial.delete()
                }
                deleteLegacyTinyModel(directory)
                return model
            } catch (error: Throwable) {
                lastError = error
                partial.delete()
            } finally {
                connection?.disconnect()
            }
        }
        throw IllegalStateException("Could not download the free Whisper speech model", lastError)
    }

    private fun deleteLegacyTinyModel(directory: File) {
        runCatching { File(directory, LEGACY_TINY_MODEL_NAME).delete() }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MODEL_NAME = "ggml-small-q5_1.bin"
        private const val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin?download=true"
        private const val MODEL_SHA256 = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"
        private const val MIN_MODEL_BYTES = 185_000_000L
        private const val LEGACY_TINY_MODEL_NAME = "ggml-tiny-q5_1.bin"
    }
}
