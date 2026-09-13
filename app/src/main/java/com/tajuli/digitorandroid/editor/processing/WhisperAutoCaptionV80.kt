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

private const val GPU_UNAVAILABLE_PREFIX_V87 = "GPU_BACKENDS_UNAVAILABLE:"

/** JNI ABI is retained while the native implementation is now ncnn Vulkan-only. */
internal object WhisperNativeV80 {
    init {
        System.loadLibrary("digitor_whisper_jni")
    }

    external fun prepareBackend(modelPath: String, allowCpuFallback: Boolean): String

    external fun activeBackend(): String

    external fun transcribe(
        modelPath: String,
        samples: FloatArray,
        language: String,
        allowCpuFallback: Boolean,
    ): Array<String>
}

private inline fun <T> translateGpuFailureV87(block: () -> T): T = try {
    block()
} catch (error: IllegalStateException) {
    val raw = error.message.orEmpty()
    if (raw.startsWith(GPU_UNAVAILABLE_PREFIX_V87)) {
        val detail = raw.removePrefix(GPU_UNAVAILABLE_PREFIX_V87).trim()
        throw IllegalStateException("ncnn Vulkan GPU unavailable · $detail", error)
    }
    throw error
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
 * Fully on-device Auto Caption using Digitor's existing ncnn Vulkan runtime.
 *
 * V89 uses the official multilingual Whisper tiny ncnn graph pack for the mobile profile. The base
 * graph proved too slow and unstable on the target Mali-G57: a ~22 second clip could spend about
 * 20 minutes in inference and then return an ncnn stage failure. Tiny keeps Bengali/international
 * language support while materially reducing model memory and decoder work on low-end mobile GPUs.
 *
 * The model is downloaded once into app-private storage. Audio never leaves the device. Split clips
 * that are contiguous pieces of the same source are still batched before decode.
 */
internal class WhisperAutoCaptionV80(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val modelStore = WhisperNcnnModelStoreV89(appContext)

    fun transcribeProject(
        project: TimelineProject,
        language: AutoCaptionLanguageV80,
        onStatus: (String) -> Unit = {},
    ): List<AutoCaptionSegmentV80> {
        val modelDirectory = modelStore.ensureModel(onStatus)
        onStatus("Auto Caption · preparing ncnn Vulkan GPU")
        val backend = translateGpuFailureV87 {
            WhisperNativeV80.prepareBackend(modelDirectory.absolutePath, false)
        }
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
                val first = batch.sources.first().clip
                val last = batch.sources.last().clip
                decodedClipCount += batch.sources.size
                onStatus("Auto Caption · decoding split batch $decodedClipCount/${sources.size} · $backend")
                decodeClipToMono16k(first.copy(sourceOutUs = last.sourceOutUs))
            }
            if (samples.isEmpty()) return@forEachIndexed

            onStatus("Auto Caption · transcribing ${batchIndex + 1}/${batches.size} · $backend · ${language.label}")
            val raw = translateGpuFailureV87 {
                WhisperNativeV80.transcribe(
                    modelDirectory.absolutePath,
                    samples,
                    language.whisperCode,
                    false,
                )
            }
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

    private fun ensureCapacity(required: Int) {
        if (required <= values.size) return
        var next = values.size.coerceAtLeast(16)
        while (next < required) next = (next * 2).coerceAtLeast(required)
        values = values.copyOf(next)
    }

    fun toFloatArray(): FloatArray = values.copyOf(size)
}

private data class NcnnWhisperAssetV89(
    val name: String,
    val bytes: Long,
    val sha256: String,
    val label: String,
)

/** Downloads the official ncnn Whisper tiny multilingual graph pack once and verifies every file. */
private class WhisperNcnnModelStoreV89(
    private val context: Context,
) {
    fun ensureModel(onStatus: (String) -> Unit): File {
        val directory = File(context.filesDir, "speech/whisper-ncnn-tiny-v2").apply { mkdirs() }
        val marker = File(directory, VERIFIED_MARKER)
        if (marker.isFile && ASSETS.all { File(directory, it.name).length() == it.bytes }) {
            return directory
        }

        val totalBytes = ASSETS.sumOf { it.bytes }
        var completedBytes = 0L
        ASSETS.forEach { asset ->
            val target = File(directory, asset.name)
            val alreadyValid = target.isFile && target.length() == asset.bytes && sha256(target) == asset.sha256
            if (!alreadyValid) {
                if (target.exists()) target.delete()
                downloadAsset(asset, target, completedBytes, totalBytes, onStatus)
            }
            completedBytes += asset.bytes
            val percent = (completedBytes * 100L / totalBytes).coerceIn(0L, 100L)
            onStatus("Auto Caption · mobile GPU model $percent% · ${asset.label}")
        }

        marker.writeText("ncnn-whisper-tiny-v2\n")
        // Reclaim older packs only after the complete tiny pack is verified.
        runCatching { File(context.filesDir, "speech/whisper-ncnn-base-v1").deleteRecursively() }
        runCatching { File(context.filesDir, "speech/whisper").deleteRecursively() }
        return directory
    }

    private fun downloadAsset(
        asset: NcnnWhisperAssetV89,
        target: File,
        completedBytes: Long,
        totalBytes: Long,
        onStatus: (String) -> Unit,
    ) {
        var lastError: Throwable? = null
        repeat(3) { zeroBasedAttempt ->
            val attempt = zeroBasedAttempt + 1
            val partial = File(target.parentFile, "${asset.name}.download")
            if (partial.exists()) partial.delete()
            var connection: HttpURLConnection? = null
            try {
                onStatus(
                    "Auto Caption · downloading mobile GPU model · ${asset.label}" +
                        if (attempt > 1) " · retry $attempt" else "",
                )
                connection = URI("$MODEL_BASE_URL/${asset.name}").toURL().openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 30_000
                connection.readTimeout = 300_000
                connection.setRequestProperty("User-Agent", "DigitorAndroid/0.1")
                connection.setRequestProperty("Accept", "application/octet-stream,*/*")
                val code = connection.responseCode
                require(code in 200..299) { "GPU model download returned HTTP $code for ${asset.name}" }

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
                            val overall = ((completedBytes + copied.coerceAtMost(asset.bytes)) * 100L / totalBytes)
                                .coerceIn(0L, 100L)
                            onStatus("Auto Caption · mobile GPU model $overall% · ${asset.label}")
                        }
                    }
                }
                require(partial.length() == asset.bytes) {
                    "Incomplete GPU model file ${asset.name}: ${partial.length()}/${asset.bytes} bytes"
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                require(actual.equals(asset.sha256, ignoreCase = true)) {
                    "GPU model checksum mismatch for ${asset.name}"
                }
                if (!partial.renameTo(target)) {
                    partial.copyTo(target, overwrite = true)
                    partial.delete()
                }
                return
            } catch (error: Throwable) {
                lastError = error
                partial.delete()
            } finally {
                connection?.disconnect()
            }
        }
        throw IllegalStateException("Could not download ncnn Whisper mobile GPU model (${asset.label})", lastError)
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
        private const val MODEL_BASE_URL =
            "https://github.com/nihui/ncnn-android-whisper/releases/download/models"
        private const val VERIFIED_MARKER = ".verified-v2"

        private val ASSETS = listOf(
            NcnnWhisperAssetV89("whisper_tiny_decoder.ncnn.bin", 18_979_008L, "8b5be3db26af79c8d0408e7a07affae07e5464af3e9f5cf1eba503b63cab4dec", "decoder"),
            NcnnWhisperAssetV89("whisper_tiny_decoder.ncnn.param", 5_390L, "371cc62f8ea86d24ee170732e848dfc9a15a16ba7a3e22a175cb3ceefac3757b", "decoder graph"),
            NcnnWhisperAssetV89("whisper_tiny_embed_position.ncnn.bin", 344_068L, "02d1ce594b3917847f0cc333337af4999787bae77823d0655fd5df40177a64f9", "position embedding"),
            NcnnWhisperAssetV89("whisper_tiny_embed_position.ncnn.param", 158L, "b5fbfba5ea5c294258bf5cb9f4b6704f459099921e2cdbd1dfdef2c0c14085b4", "position graph"),
            NcnnWhisperAssetV89("whisper_tiny_embed_token.ncnn.bin", 39_832_324L, "e33c7db8ebb73eafc2cc791ae77b0e710c9cf54bd14321ae28277cb86b0f4968", "token embedding"),
            NcnnWhisperAssetV89("whisper_tiny_embed_token.ncnn.param", 162L, "c1917f8b90e81cede6fe8b82cab7d05d7d6461f9ff04ad58932bd7a62ec38627", "token graph"),
            NcnnWhisperAssetV89("whisper_tiny_encoder.ncnn.bin", 17_599_624L, "14b8d453780ca0df7971d12134b4826bb6206624e4217236204183f793dae74a", "encoder"),
            NcnnWhisperAssetV89("whisper_tiny_encoder.ncnn.param", 3_816L, "675a3141db1ed515f1d08d722ddeb31e2a556b1a7baf35c3dd14a2adf575c058", "encoder graph"),
            NcnnWhisperAssetV89("whisper_tiny_fbank.ncnn.bin", 64_320L, "2150c30cbbeb6029f52002ffa666c1c72d83dbf53f463cb8462052055806e891", "audio features"),
            NcnnWhisperAssetV89("whisper_tiny_fbank.ncnn.param", 869L, "9ff0d0da904ea62c9c4d1e806f943df31209bb4df36cebb1a3fea955eaca3ac4", "audio graph"),
            // Tiny proj_out weights are byte-identical to tiny embed_token weights.
            NcnnWhisperAssetV89("whisper_tiny_proj_out.ncnn.param", 177L, "428368e792a833f6efa5905c21e977e7c44ee917ed3a826db1ac67fc7a8bb3c7", "projection graph"),
            NcnnWhisperAssetV89("whisper_vocab.txt", 444_530L, "c3e28c60daa5956c08e02a08e82dc6ef4c8882805db4940d59343638234b6c6e", "vocabulary"),
        )
    }
}
