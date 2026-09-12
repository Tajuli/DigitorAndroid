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
import kotlin.math.max

/** JNI surface backed by the pinned whisper.cpp v1.9.4 native target. */
internal object WhisperNativeV80 {
    init {
        System.loadLibrary("digitor_whisper_jni")
    }

    external fun transcribe(
        modelPath: String,
        samples: FloatArray,
        language: String,
    ): Array<String>
}

/**
 * On-device, no-per-minute-cost auto captions.
 *
 * The 31 MiB multilingual tiny-q5_1 model is downloaded once, SHA-256 verified and then reused
 * offline. Project audio is decoded only with Android platform codecs, converted to 16 kHz mono
 * PCM, and passed to whisper.cpp in-process; user audio is never uploaded.
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
        val audioClips = project.tracks
            .filter { it.kind == TrackKind.AUDIO && !it.muted }
            .flatMap { it.clips }
            .sortedBy { it.timelineStartUs }
        val sources = if (audioClips.isNotEmpty()) {
            audioClips
        } else {
            // Legacy projects may contain video without the linked A-track introduced by V12.
            project.tracks.filter { it.kind == TrackKind.VIDEO }.flatMap { it.clips }.sortedBy { it.timelineStartUs }
        }
        require(sources.isNotEmpty()) { "No audio/video clips available for Auto Caption" }

        val segments = mutableListOf<AutoCaptionSegmentV80>()
        sources.forEachIndexed { index, clip ->
            onStatus("Auto Caption · audio ${index + 1}/${sources.size}")
            val samples = decodeClipToMono16k(clip)
            if (samples.isEmpty()) return@forEachIndexed
            onStatus("Auto Caption · transcribing ${index + 1}/${sources.size}")
            val raw = WhisperNativeV80.transcribe(model.absolutePath, samples, language.whisperCode)
            raw.forEach { line ->
                parseNativeSegment(line)?.let { local ->
                    val start = clip.timelineStartUs + local.startUs.coerceIn(0L, clip.durationUs)
                    val end = clip.timelineStartUs + local.endUs.coerceIn(0L, clip.durationUs)
                    if (end > start) segments += local.copy(startUs = start, endUs = end)
                }
            }
        }
        return normalizeAutoCaptionSegmentsV80(segments)
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
            val output = FloatBuilderV80(initialCapacity = minOf(262_144, estimatedTargetSamples(clip)))
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var nextTargetIndex = 0L
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
                            codec.queueInputBuffer(inputIndex, 0, 0, max(0L, clip.sourceOutUs), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, max(0L, sampleTimeUs), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
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
                                val buffer = codec.getOutputBuffer(outputIndex)
                                if (buffer != null) {
                                    nextTargetIndex = appendDecodedBuffer(
                                        clip = clip,
                                        decoded = buffer,
                                        info = info,
                                        sampleRate = sampleRate,
                                        channelCount = channelCount,
                                        pcmEncoding = pcmEncoding,
                                        output = output,
                                        nextTargetIndex = nextTargetIndex,
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
            return output.toFloatArray()
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun appendDecodedBuffer(
        clip: TimelineClip,
        decoded: ByteBuffer,
        info: MediaCodec.BufferInfo,
        sampleRate: Int,
        channelCount: Int,
        pcmEncoding: Int,
        output: FloatBuilderV80,
        nextTargetIndex: Long,
    ): Long {
        val bytesPerSample = bytesPerSample(pcmEncoding)
        val frameBytes = bytesPerSample * channelCount
        if (frameBytes <= 0 || info.size < frameBytes) return nextTargetIndex
        val frames = info.size / frameBytes
        val source = decoded.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply {
            position(info.offset)
            limit(info.offset + info.size)
        }
        var next = nextTargetIndex

        for (frame in 0 until frames) {
            val sourceTimeUs = info.presentationTimeUs + frame.toLong() * 1_000_000L / sampleRate.toLong()
            if (sourceTimeUs < clip.sourceInUs) continue
            if (sourceTimeUs >= clip.sourceOutUs) break
            val targetIndex = (sourceTimeUs - clip.sourceInUs) * TARGET_SAMPLE_RATE / 1_000_000L
            if (targetIndex < next) continue
            val frameOffset = info.offset + frame * frameBytes
            var mono = 0f
            for (channel in 0 until channelCount) {
                mono += readSample(source, frameOffset + channel * bytesPerSample, pcmEncoding)
            }
            mono = (mono / channelCount.toFloat()).coerceIn(-1f, 1f)
            while (next <= targetIndex) {
                output.add(mono)
                next++
            }
        }
        return next
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

    private fun estimatedTargetSamples(clip: TimelineClip): Int =
        ((clip.durationUs / 1_000_000.0) * TARGET_SAMPLE_RATE).toLong().coerceIn(16_000L, 4_000_000L).toInt()

    companion object {
        private const val TARGET_SAMPLE_RATE = 16_000L
    }
}

private class FloatBuilderV80(initialCapacity: Int) {
    private var values = FloatArray(initialCapacity.coerceAtLeast(1))
    private var size = 0

    fun add(value: Float) {
        if (size == values.size) values = values.copyOf((values.size * 2).coerceAtLeast(16))
        values[size++] = value
    }

    fun toFloatArray(): FloatArray = values.copyOf(size)
}

private class WhisperModelStoreV80(
    private val context: Context,
) {
    fun ensureModel(onStatus: (String) -> Unit): File {
        val directory = File(context.filesDir, "speech/whisper").apply { mkdirs() }
        val model = File(directory, MODEL_NAME)
        if (model.isFile && model.length() >= MIN_MODEL_BYTES && sha256(model) == MODEL_SHA256) return model
        if (model.exists()) model.delete()

        val partial = File(directory, "$MODEL_NAME.download")
        if (partial.exists()) partial.delete()
        var lastError: Throwable? = null
        for (attempt in 1..3) {
            var connection: HttpURLConnection? = null
            try {
                onStatus("Auto Caption · downloading speech model${if (attempt > 1) " (retry $attempt)" else ""}")
                connection = URI(MODEL_URL).toURL().openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 30_000
                connection.readTimeout = 180_000
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
                                onStatus("Auto Caption · model $percent%")
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
        private const val MODEL_NAME = "ggml-tiny-q5_1.bin"
        private const val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin?download=true"
        private const val MODEL_SHA256 = "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7"
        private const val MIN_MODEL_BYTES = 30_000_000L
    }
}
