package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TrackKind
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TARGET_SAMPLE_RATE_V80 = 16_000
private const val MIN_CAPTION_US_V80 = 180_000L
private const val MAX_CAPTION_CHARS_V80 = 40
private const val MAX_CAPTION_SPAN_US_V80 = 3_800_000L
private const val GAP_BREAK_US_V80 = 700_000L
private const val OMNI_MODEL_MIN_BYTES_V80 = 150_000_000L
private const val OMNI_ARCHIVE_MIN_BYTES_V80 = 200_000_000L
private const val OMNI_REQUIRED_FREE_BYTES_V80 = 620_000_000L
private const val OMNI_ARCHIVE_SHA256_V80 = "b72bef9be75862684098e722d79fefecf9f10fefd3a0b2950738977b4c6b4147"
private const val OMNI_REVISION_V80 = "117cc213211720a50668777a4b2c390dedeb5718"
private const val OMNI_ARCHIVE_NAME_V80 =
    "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-v2-int8-2026-02-05.tar.bz2"
private const val OMNI_BASE_URL_V80 =
    "https://huggingface.co/Edison2ST/sherpa-onnx-omnilingual-asr-1600-languages-ctc-v2/resolve/$OMNI_REVISION_V80"

private data class InstalledOmnilingualV80(
    val model: File,
    val tokens: File,
)

/**
 * V80 accuracy tier. Fast keeps the small streaming language-specific recognizers from V79; the
 * Accurate button uses Meta Omnilingual ASR v2 300M INT8 through sherpa-onnx's offline CTC runtime.
 * The large model is optional/lazy and is never bundled into the APK.
 */
class HybridAutoCaptionEngineV80(private val context: Context) {
    private val fastEngine = ZipformerAutoCaptionEngineV79(context)
    private val accurateEngine = OmnilingualAccurateAutoCaptionEngineV80(context)

    companion object {
        fun supportedOnThisDevice(): Boolean = ZipformerAutoCaptionEngineV79.supportedOnThisDevice()
    }

    suspend fun generate(
        project: TimelineProject,
        audioTrackId: String,
        quality: AutoCaptionQualityV77,
        language: AutoCaptionLanguageV79 = AutoCaptionLanguageV79.BANGLA,
        onProgress: (AutoCaptionProgressV77) -> Unit = {},
    ): AutoCaptionResultV77 {
        if (quality == AutoCaptionQualityV77.FAST) {
            return fastEngine.generate(project, audioTrackId, quality, language, onProgress)
        }

        return try {
            accurateEngine.generate(project, audioTrackId, language, onProgress)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // The accurate model is deliberately optional: low-storage/low-memory phones and a first
            // use network failure should still get captions rather than losing the feature entirely.
            onProgress(
                AutoCaptionProgressV77(
                    .08f,
                    "Accurate model unavailable · using Zipformer fallback",
                ),
            )
            fastEngine.generate(
                project = project,
                audioTrackId = audioTrackId,
                quality = AutoCaptionQualityV77.ACCURATE,
                language = language,
                onProgress = onProgress,
            )
        }
    }
}

private class OmnilingualAccurateAutoCaptionEngineV80(private val context: Context) {
    suspend fun generate(
        project: TimelineProject,
        audioTrackId: String,
        language: AutoCaptionLanguageV79,
        onProgress: (AutoCaptionProgressV77) -> Unit,
    ): AutoCaptionResultV77 {
        val track = project.track(audioTrackId)
            ?.takeIf { it.kind == TrackKind.AUDIO && !it.muted }
            ?: error("Select an unmuted audio track")
        val clips = track.sortedClips()
        require(clips.isNotEmpty()) { "${track.name} has no audio clips" }

        val installed = ensureOmnilingualModelV80(onProgress)
        val recognizer = withContext(Dispatchers.Default) {
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            OfflineRecognizer(
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(
                        sampleRate = TARGET_SAMPLE_RATE_V80,
                        featureDim = 80,
                        dither = 0f,
                    ),
                    modelConfig = OfflineModelConfig(
                        omnilingual = OfflineOmnilingualAsrCtcModelConfig(
                            model = installed.model.absolutePath,
                        ),
                        tokens = installed.tokens.absolutePath,
                        numThreads = threads,
                        provider = "cpu",
                    ),
                    decodingMethod = "greedy_search",
                ),
            )
        }

        return try {
            val drafts = mutableListOf<AutoCaptionDraftV77>()
            val totalDuration = clips.sumOf { it.durationUs }.coerceAtLeast(1L)
            var completedUs = 0L

            clips.forEachIndexed { index, clip ->
                currentCoroutineContext().ensureActive()
                val base = completedUs.toFloat() / totalDuration.toFloat()
                onProgress(
                    AutoCaptionProgressV77(
                        .20f + .68f * base.coerceIn(0f, 1f),
                        "Accurate transcription ${index + 1}/${clips.size}",
                    ),
                )
                val audio = decodeClipToFloatV80(clip)
                if (audio.isNotEmpty()) {
                    val result = withContext(Dispatchers.Default) {
                        recognizeOfflineV80(recognizer, audio)
                    }
                    val candidate = result.toTimelineDraftsV80(clip)
                    drafts += candidate
                }
                completedUs += clip.durationUs
            }

            val normalized = drafts.normalizeCaptionOrderV80()
            require(normalized.isNotEmpty()) { "No speech was detected on the selected audio track" }

            // A language button is still useful for creator intent/status, but Omnilingual v2 itself
            // detects/decodes the spoken script. Keep code-switching intact instead of discarding valid
            // English words inside Bangla (or vice versa).
            val languageLabel = when (language) {
                AutoCaptionLanguageV79.BANGLA -> "Bangla"
                AutoCaptionLanguageV79.ENGLISH -> "English"
                AutoCaptionLanguageV79.AUTO -> "Auto"
            }
            onProgress(AutoCaptionProgressV77(1f, "${normalized.size} captions ready · $languageLabel"))
            AutoCaptionResultV77(
                captions = normalized,
                backend = "sherpa-onnx offline CPU",
                model = "Omnilingual ASR v2 300M INT8",
            )
        } finally {
            runCatching { recognizer.release() }
        }
    }

    private fun recognizeOfflineV80(
        recognizer: OfflineRecognizer,
        audio: FloatArray,
    ): OfflineRecognizerResult {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(audio, TARGET_SAMPLE_RATE_V80)
            recognizer.decode(stream)
            return recognizer.getResult(stream)
        } finally {
            runCatching { stream.release() }
        }
    }

    private suspend fun ensureOmnilingualModelV80(
        onProgress: (AutoCaptionProgressV77) -> Unit,
    ): InstalledOmnilingualV80 = withContext(Dispatchers.IO) {
        val directory = File(
            context.filesDir,
            "auto_cc_models_v80/omnilingual-asr-v2-300m-int8-2026-02-05",
        ).apply { mkdirs() }
        val model = File(directory, "model.int8.onnx")
        val tokens = File(directory, "tokens.txt")
        if (model.isFile && model.length() > OMNI_MODEL_MIN_BYTES_V80 && tokens.isFile && tokens.length() > 1_000L) {
            onProgress(AutoCaptionProgressV77(.18f, "Accurate model ready · local cache"))
            return@withContext InstalledOmnilingualV80(model, tokens)
        }

        require(directory.usableSpace >= OMNI_REQUIRED_FREE_BYTES_V80) {
            "Accurate Auto CC needs about 620 MB free space for first-time model setup"
        }

        val archive = File(directory, OMNI_ARCHIVE_NAME_V80)
        downloadArchiveV80(archive, onProgress)
        currentCoroutineContext().ensureActive()
        onProgress(AutoCaptionProgressV77(.13f, "Preparing Accurate speech model…"))
        extractOmnilingualArchiveV80(archive, model, tokens, onProgress)
        check(model.length() > OMNI_MODEL_MIN_BYTES_V80) { "Accurate speech model extraction is incomplete" }
        check(tokens.length() > 1_000L) { "Accurate speech tokens extraction is incomplete" }
        archive.delete()
        onProgress(AutoCaptionProgressV77(.18f, "Accurate model ready · Omnilingual v2"))
        InstalledOmnilingualV80(model, tokens)
    }

    private suspend fun downloadArchiveV80(
        archive: File,
        onProgress: (AutoCaptionProgressV77) -> Unit,
    ) {
        if (archive.isFile && archive.length() > OMNI_ARCHIVE_MIN_BYTES_V80) {
            if (sha256V80(archive).equals(OMNI_ARCHIVE_SHA256_V80, ignoreCase = true)) return
            archive.delete()
        }
        val temp = File(archive.parentFile, "${archive.name}.download")
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            currentCoroutineContext().ensureActive()
            temp.delete()
            var connection: HttpURLConnection? = null
            try {
                val url = "$OMNI_BASE_URL_V80/$OMNI_ARCHIVE_NAME_V80?download=true"
                connection = URI(url).toURL().openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 30_000
                connection.readTimeout = 300_000
                connection.setRequestProperty("User-Agent", "DigitorAndroid-AutoCC/1.0")
                connection.setRequestProperty("Accept", "application/octet-stream,*/*")
                val response = connection.responseCode
                require(response in 200..299) { "Accurate model download returned HTTP $response" }
                val expected = connection.contentLengthLong.takeIf { it > 0L }
                var copied = 0L
                connection.inputStream.buffered().use { input ->
                    temp.outputStream().buffered().use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            val ratio = if (expected != null) {
                                copied.toDouble() / expected.toDouble()
                            } else {
                                copied.toDouble() / max(OMNI_ARCHIVE_MIN_BYTES_V80, copied).toDouble()
                            }.toFloat().coerceIn(0f, 1f)
                            onProgress(
                                AutoCaptionProgressV77(
                                    .01f + ratio * .11f,
                                    "Downloading Accurate model · ${(ratio * 100f).roundToInt()}%",
                                ),
                            )
                        }
                    }
                }
                require(temp.length() > OMNI_ARCHIVE_MIN_BYTES_V80) { "Accurate model download is incomplete" }
                require(sha256V80(temp).equals(OMNI_ARCHIVE_SHA256_V80, ignoreCase = true)) {
                    "Accurate model checksum mismatch"
                }
                archive.delete()
                check(temp.renameTo(archive)) { "Could not install Accurate model archive" }
                return
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                lastError = error
                temp.delete()
                if (attempt < 2) Thread.sleep(1_500L * (attempt + 1))
            } finally {
                connection?.disconnect()
            }
        }
        throw IllegalStateException("Could not download Accurate speech model", lastError)
    }

    private suspend fun extractOmnilingualArchiveV80(
        archive: File,
        model: File,
        tokens: File,
        onProgress: (AutoCaptionProgressV77) -> Unit,
    ) {
        val modelTemp = File(model.parentFile, "${model.name}.extract")
        val tokensTemp = File(tokens.parentFile, "${tokens.name}.extract")
        modelTemp.delete()
        tokensTemp.delete()
        try {
            FileInputStream(archive).use { fileInput ->
                BufferedInputStream(fileInput, 1024 * 1024).use { buffered ->
                    BZip2CompressorInputStream(buffered, true).use { bzip ->
                        TarArchiveInputStream(bzip).use { tar ->
                            var entry = tar.nextEntry
                            while (entry != null) {
                                currentCoroutineContext().ensureActive()
                                if (!entry.isDirectory) {
                                    val name = entry.name.substringAfterLast('/')
                                    val target = when (name) {
                                        "model.int8.onnx" -> modelTemp
                                        "tokens.txt" -> tokensTemp
                                        else -> null
                                    }
                                    if (target != null) {
                                        target.outputStream().buffered().use { output ->
                                            val buffer = ByteArray(1024 * 1024)
                                            var written = 0L
                                            while (true) {
                                                currentCoroutineContext().ensureActive()
                                                val read = tar.read(buffer)
                                                if (read <= 0) break
                                                output.write(buffer, 0, read)
                                                written += read
                                                if (target === modelTemp) {
                                                    val fraction = (written.toDouble() / 300_000_000.0)
                                                        .toFloat().coerceIn(0f, 1f)
                                                    onProgress(
                                                        AutoCaptionProgressV77(
                                                            .13f + fraction * .04f,
                                                            "Preparing Accurate model · ${(fraction * 100f).roundToInt()}%",
                                                        ),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                entry = tar.nextEntry
                            }
                        }
                    }
                }
            }
            require(modelTemp.length() > OMNI_MODEL_MIN_BYTES_V80) { "model.int8.onnx missing from Accurate archive" }
            require(tokensTemp.length() > 1_000L) { "tokens.txt missing from Accurate archive" }
            model.delete()
            tokens.delete()
            check(modelTemp.renameTo(model)) { "Could not install Accurate model" }
            check(tokensTemp.renameTo(tokens)) { "Could not install Accurate tokens" }
        } finally {
            modelTemp.delete()
            tokensTemp.delete()
        }
    }

    private fun sha256V80(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    /** Decode the selected source range only; output is 16 kHz mono float PCM. */
    private suspend fun decodeClipToFloatV80(clip: TimelineClip): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var codecStarted = false
        val chunks = mutableListOf<ShortArray>()
        var totalSamples = 0
        try {
            val sourceUri = Uri.parse(clip.uri)
            if (sourceUri.scheme.isNullOrBlank()) extractor.setDataSource(clip.uri)
            else extractor.setDataSource(context, sourceUri, null)

            var audioTrack = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                if (candidate.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    audioTrack = i
                    inputFormat = candidate
                    break
                }
            }
            require(audioTrack >= 0 && inputFormat != null) { "${clip.label}: no decodable audio stream" }

            extractor.selectTrack(audioTrack)
            extractor.seekTo(clip.sourceInUs.coerceAtLeast(0L), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("${clip.label}: audio MIME missing")
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            codecStarted = true

            var outputRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var outputChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var outputEncoding = AudioFormat.ENCODING_PCM_16BIT
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex) ?: error("Audio decoder input buffer missing")
                        input.clear()
                        val sampleTime = extractor.sampleTime
                        if (sampleTime < 0L || sampleTime >= clip.sourceOutUs) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, size, sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000L)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        outputRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        outputChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        outputEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else AudioFormat.ENCODING_PCM_16BIT
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (
                            outputBuffer != null && info.size > 0 &&
                            info.presentationTimeUs >= clip.sourceInUs &&
                            info.presentationTimeUs < clip.sourceOutUs
                        ) {
                            val safeEnd = (info.offset + info.size).coerceAtMost(outputBuffer.capacity())
                            val bytes = outputBuffer.duplicate().apply {
                                position(info.offset.coerceAtLeast(0))
                                limit(safeEnd)
                            }.slice().order(ByteOrder.LITTLE_ENDIAN)
                            val mono = bytes.toMonoPcm16V80(outputChannels, outputEncoding)
                            val target = resampleMonoV80(mono, outputRate, TARGET_SAMPLE_RATE_V80)
                            if (target.isNotEmpty()) {
                                chunks += target
                                totalSamples += target.size
                            }
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        } finally {
            runCatching { extractor.release() }
            codec?.let { decoder ->
                if (codecStarted) runCatching { decoder.stop() }
                runCatching { decoder.release() }
            }
        }

        if (totalSamples <= 0) return FloatArray(0)
        val output = FloatArray(totalSamples)
        var offset = 0
        chunks.forEach { chunk ->
            chunk.forEach { sample -> output[offset++] = (sample.toInt() / 32768f).coerceIn(-1f, 1f) }
        }
        return output
    }
}

private fun OfflineRecognizerResult.toTimelineDraftsV80(clip: TimelineClip): List<AutoCaptionDraftV77> {
    data class Piece(val token: String, val startUs: Long)

    val count = min(tokens.size, timestamps.size)
    val pieces = (0 until count).mapNotNull { index ->
        val raw = tokens[index]
        val time = timestamps[index]
        if (!time.isFinite() || time < 0f) return@mapNotNull null
        if (raw.startsWith("<") && raw.endsWith(">")) return@mapNotNull null
        val token = raw.replace('▁', ' ')
        if (token.isBlank() && raw != "▁") return@mapNotNull null
        Piece(token, (time * 1_000_000f).toLong().coerceIn(0L, clip.durationUs))
    }

    if (pieces.isEmpty()) {
        val clean = text.normalizeOmniTextV80()
        if (clean.isBlank()) return emptyList()
        val words = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val groups = words.chunked(7)
        return groups.mapIndexedNotNull { index, group ->
            val start = clip.durationUs * index / groups.size
            val end = clip.durationUs * (index + 1) / groups.size
            if (end <= start) null else AutoCaptionDraftV77(
                group.joinToString(" "),
                clip.timelineStartUs + start,
                clip.timelineStartUs + end,
            )
        }
    }

    data class Group(val text: String, val startUs: Long)
    val groups = mutableListOf<Group>()
    var buffer = StringBuilder()
    var groupStart = pieces.first().startUs

    fun flush() {
        val clean = buffer.toString().normalizeOmniTextV80()
        if (clean.isNotBlank()) groups += Group(clean, groupStart)
        buffer = StringBuilder()
    }

    pieces.forEachIndexed { index, piece ->
        if (buffer.isEmpty()) groupStart = piece.startUs
        buffer.append(piece.token)
        val clean = buffer.toString().normalizeOmniTextV80()
        val nextStart = pieces.getOrNull(index + 1)?.startUs
        val longText = clean.length >= MAX_CAPTION_CHARS_V80
        val longTime = piece.startUs - groupStart >= MAX_CAPTION_SPAN_US_V80
        val punctuation = clean.endsWithAnyPunctuationV80()
        val pause = nextStart != null && nextStart - piece.startUs >= GAP_BREAK_US_V80
        if (longText || longTime || punctuation || pause) flush()
    }
    if (buffer.isNotEmpty()) flush()

    return groups.mapIndexedNotNull { index, group ->
        val nextStart = groups.getOrNull(index + 1)?.startUs
        val localStart = group.startUs.coerceIn(0L, clip.durationUs)
        val localEnd = (nextStart ?: min(clip.durationUs, localStart + MAX_CAPTION_SPAN_US_V80))
            .coerceAtLeast(localStart + MIN_CAPTION_US_V80)
            .coerceAtMost(clip.durationUs)
        if (localEnd <= localStart) null else AutoCaptionDraftV77(
            text = group.text,
            timelineStartUs = clip.timelineStartUs + localStart,
            timelineEndUs = clip.timelineStartUs + localEnd,
        )
    }
}

private fun String.normalizeOmniTextV80(): String =
    replace('▁', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()

private fun String.endsWithAnyPunctuationV80(): Boolean =
    endsWith('.') || endsWith('?') || endsWith('!') || endsWith('।') || endsWith(';') || endsWith(':') || endsWith('…')

private fun ByteBuffer.toMonoPcm16V80(channels: Int, encoding: Int): ShortArray {
    val ch = channels.coerceAtLeast(1)
    return when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> {
            val sampleCount = remaining() / 4
            val frameCount = sampleCount / ch
            ShortArray(frameCount) { frame ->
                var sum = 0f
                repeat(ch) { channel -> sum += getFloat((frame * ch + channel) * 4) }
                ((sum / ch).coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort()
            }
        }
        AudioFormat.ENCODING_PCM_8BIT -> {
            val sampleCount = remaining()
            val frameCount = sampleCount / ch
            ShortArray(frameCount) { frame ->
                var sum = 0
                repeat(ch) { channel -> sum += (get(frame * ch + channel).toInt() and 0xFF) - 128 }
                ((sum / ch) shl 8).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
        else -> {
            val sampleCount = remaining() / 2
            val frameCount = sampleCount / ch
            ShortArray(frameCount) { frame ->
                var sum = 0L
                repeat(ch) { channel -> sum += getShort((frame * ch + channel) * 2).toLong() }
                (sum / ch).coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toShort()
            }
        }
    }
}

private fun resampleMonoV80(input: ShortArray, sourceRate: Int, targetRate: Int): ShortArray {
    if (input.isEmpty() || sourceRate <= 0 || targetRate <= 0) return ShortArray(0)
    if (sourceRate == targetRate) return input
    val outputCount = max(1, (input.size.toLong() * targetRate / sourceRate).toInt())
    val ratio = sourceRate.toDouble() / targetRate.toDouble()
    return ShortArray(outputCount) { index ->
        val position = index * ratio
        val left = floor(position).toInt().coerceIn(0, input.lastIndex)
        val right = min(left + 1, input.lastIndex)
        val t = position - left
        (input[left] + (input[right] - input[left]) * t)
            .roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }
}

private fun List<AutoCaptionDraftV77>.normalizeCaptionOrderV80(): List<AutoCaptionDraftV77> {
    if (isEmpty()) return emptyList()
    val sorted = sortedWith(compareBy<AutoCaptionDraftV77> { it.timelineStartUs }.thenBy { it.timelineEndUs })
    val result = mutableListOf<AutoCaptionDraftV77>()
    var previousEnd = -1L
    sorted.forEach { item ->
        val start = max(item.timelineStartUs, previousEnd)
        val end = max(start + MIN_CAPTION_US_V80, item.timelineEndUs)
        if (end > start && item.text.isNotBlank()) {
            result += item.copy(timelineStartUs = start, timelineEndUs = end)
            previousEnd = end
        }
    }
    return result
}
