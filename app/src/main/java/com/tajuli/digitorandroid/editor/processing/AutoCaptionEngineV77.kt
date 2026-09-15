package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.tajuli.digitorandroid.editor.model.TextAlignmentV2
import com.tajuli.digitorandroid.editor.model.TextOverlayClip
import com.tajuli.digitorandroid.editor.model.TextStyleV2
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val AUTO_CC_ID_PREFIX_V77 = "auto-cc-v77-"
private const val AUTO_CC_TRACK_NAME_V77 = "CC"
private const val TARGET_SAMPLE_RATE_V77 = 16_000
private const val MIN_CAPTION_US_V77 = 180_000L
private const val MAX_CAPTION_CHARS_V77 = 42
private const val MAX_CAPTION_SPAN_US_V79 = 4_200_000L
private const val TAIL_SILENCE_SAMPLES_V79 = 16_000

/**
 * V79 intentionally stops using Whisper. Dedicated streaming Zipformer models are faster and much
 * less fragile on Android because Digitor consumes sherpa-onnx's prebuilt Android runtime instead of
 * compiling a second Vulkan stack into the editor.
 */
enum class AutoCaptionQualityV77(val label: String, val detail: String) {
    FAST("Fast", "Greedy decode · lower CPU use"),
    ACCURATE("Accurate", "Beam search · better recognition"),
}

enum class AutoCaptionLanguageV79(val label: String, val detail: String) {
    BANGLA("বাংলা", "Dedicated Bengali Zipformer2 model"),
    ENGLISH("English", "Small English Zipformer INT8 model"),
    AUTO("Auto", "Try Bengali first; use English when the result is not Bengali"),
}

data class AutoCaptionProgressV77(
    val fraction: Float,
    val message: String,
)

data class AutoCaptionDraftV77(
    val text: String,
    val timelineStartUs: Long,
    val timelineEndUs: Long,
)

data class AutoCaptionResultV77(
    val captions: List<AutoCaptionDraftV77>,
    val backend: String,
    val model: String,
)

private data class RemoteModelFileV79(
    val remotePath: String,
    val localName: String,
    val minimumBytes: Long,
)

private data class ZipformerModelSpecV79(
    val language: AutoCaptionLanguageV79,
    val directoryName: String,
    val baseUrl: String,
    val displayName: String,
    val modelType: String,
    val dither: Float,
    val files: List<RemoteModelFileV79>,
)

private data class InstalledZipformerModelV79(
    val spec: ZipformerModelSpecV79,
    val directory: File,
) {
    val encoder: File get() = File(directory, "encoder.onnx")
    val decoder: File get() = File(directory, "decoder.onnx")
    val joiner: File get() = File(directory, "joiner.onnx")
    val tokens: File get() = File(directory, "tokens.txt")
}

private data class RecognitionV79(
    val language: AutoCaptionLanguageV79,
    val result: OnlineRecognizerResult,
)

private val BANGLA_MODEL_V79 = ZipformerModelSpecV79(
    language = AutoCaptionLanguageV79.BANGLA,
    directoryName = "bn-zipformer2-2026-02-09",
    baseUrl = "https://huggingface.co/alphacep/vosk-model-small-streaming-bn/resolve/dfabeea5eee1f33d81436826d0575d8cfd64bd1d",
    displayName = "Bengali Zipformer2 0.60",
    modelType = "zipformer2",
    dither = 3e-5f,
    files = listOf(
        RemoteModelFileV79("am-onnx/encoder.onnx", "encoder.onnx", 80_000_000L),
        RemoteModelFileV79("am-onnx/decoder.onnx", "decoder.onnx", 1_500_000L),
        RemoteModelFileV79("am-onnx/joiner.onnx", "joiner.onnx", 700_000L),
        RemoteModelFileV79("lang/tokens.txt", "tokens.txt", 4_000L),
    ),
)

private val ENGLISH_MODEL_V79 = ZipformerModelSpecV79(
    language = AutoCaptionLanguageV79.ENGLISH,
    directoryName = "en-zipformer-20m-int8-2023-02-17",
    baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/resolve/d42f2d9f7ca24806fb667456a18a9f1b60f70d16",
    displayName = "English Zipformer 20M INT8",
    modelType = "zipformer",
    dither = 0f,
    files = listOf(
        RemoteModelFileV79("encoder-epoch-99-avg-1.int8.onnx", "encoder.onnx", 35_000_000L),
        RemoteModelFileV79("decoder-epoch-99-avg-1.int8.onnx", "decoder.onnx", 400_000L),
        RemoteModelFileV79("joiner-epoch-99-avg-1.int8.onnx", "joiner.onnx", 200_000L),
        RemoteModelFileV79("tokens.txt", "tokens.txt", 4_000L),
    ),
)

class ZipformerAutoCaptionEngineV79(private val context: Context) {

    companion object {
        private val supportedAbis = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

        fun supportedOnThisDevice(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                Build.SUPPORTED_ABIS.any { it in supportedAbis }
    }

    suspend fun generate(
        project: TimelineProject,
        audioTrackId: String,
        quality: AutoCaptionQualityV77,
        language: AutoCaptionLanguageV79 = AutoCaptionLanguageV79.BANGLA,
        onProgress: (AutoCaptionProgressV77) -> Unit = {},
    ): AutoCaptionResultV77 {
        check(supportedOnThisDevice()) { "Auto CC is not available on this Android ABI" }
        val track = project.track(audioTrackId)
            ?.takeIf { it.kind == TrackKind.AUDIO && !it.muted }
            ?: error("Select an unmuted audio track")
        val clips = track.sortedClips()
        require(clips.isNotEmpty()) { "${track.name} has no audio clips" }

        val recognizers = mutableMapOf<AutoCaptionLanguageV79, OnlineRecognizer>()
        val installed = mutableMapOf<AutoCaptionLanguageV79, InstalledZipformerModelV79>()
        val usedModels = linkedSetOf<String>()

        suspend fun recognizerFor(requested: AutoCaptionLanguageV79): OnlineRecognizer {
            require(requested != AutoCaptionLanguageV79.AUTO)
            recognizers[requested]?.let { return it }
            val spec = when (requested) {
                AutoCaptionLanguageV79.BANGLA -> BANGLA_MODEL_V79
                AutoCaptionLanguageV79.ENGLISH -> ENGLISH_MODEL_V79
                AutoCaptionLanguageV79.AUTO -> error("AUTO has no direct recognizer")
            }
            val model = installed[requested] ?: ensureModelV79(spec, onProgress).also {
                installed[requested] = it
            }
            return withContext(Dispatchers.Default) {
                createRecognizerV79(model, quality).also {
                    recognizers[requested] = it
                    usedModels += spec.displayName
                }
            }
        }

        return try {
            val drafts = mutableListOf<AutoCaptionDraftV77>()
            val totalDuration = clips.sumOf { it.durationUs }.coerceAtLeast(1L)
            var completedDuration = 0L

            clips.forEachIndexed { index, clip ->
                currentCoroutineContext().ensureActive()
                val progressBase = completedDuration.toFloat() / totalDuration.toFloat()
                onProgress(
                    AutoCaptionProgressV77(
                        .18f + .70f * progressBase.coerceIn(0f, 1f),
                        "Decoding speech ${index + 1}/${clips.size}",
                    ),
                )
                val audio = decodeClipToFloatV79(clip)
                if (audio.isNotEmpty()) {
                    val recognition = when (language) {
                        AutoCaptionLanguageV79.BANGLA -> RecognitionV79(
                            AutoCaptionLanguageV79.BANGLA,
                            recognizeV79(recognizerFor(AutoCaptionLanguageV79.BANGLA), audio),
                        )
                        AutoCaptionLanguageV79.ENGLISH -> RecognitionV79(
                            AutoCaptionLanguageV79.ENGLISH,
                            recognizeV79(recognizerFor(AutoCaptionLanguageV79.ENGLISH), audio),
                        )
                        AutoCaptionLanguageV79.AUTO -> recognizeAutoV79(
                            audio = audio,
                            bangla = recognizerFor(AutoCaptionLanguageV79.BANGLA),
                            englishProvider = { recognizerFor(AutoCaptionLanguageV79.ENGLISH) },
                        )
                    }
                    recognition.result.toTimelineDraftsV79(clip).let(drafts::addAll)
                }
                completedDuration += clip.durationUs
                onProgress(
                    AutoCaptionProgressV77(
                        .18f + .78f * (completedDuration.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f),
                        "Transcribing ${index + 1}/${clips.size}",
                    ),
                )
            }

            val normalized = drafts.normalizeCaptionOrderV77()
            require(normalized.isNotEmpty()) { "No speech was detected on the selected audio track" }
            onProgress(AutoCaptionProgressV77(1f, "${normalized.size} captions ready"))
            AutoCaptionResultV77(
                captions = normalized,
                backend = "sherpa-onnx CPU",
                model = usedModels.joinToString(" + ").ifBlank { "Zipformer" },
            )
        } finally {
            recognizers.values.forEach { recognizer -> runCatching { recognizer.release() } }
        }
    }

    private fun createRecognizerV79(
        model: InstalledZipformerModelV79,
        quality: AutoCaptionQualityV77,
    ): OnlineRecognizer {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = model.encoder.absolutePath,
                decoder = model.decoder.absolutePath,
                joiner = model.joiner.absolutePath,
            ),
            tokens = model.tokens.absolutePath,
            numThreads = threads,
            provider = "cpu",
            modelType = model.spec.modelType,
        )
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = TARGET_SAMPLE_RATE_V77,
                featureDim = 80,
                dither = model.spec.dither,
            ),
            modelConfig = modelConfig,
            enableEndpoint = false,
            decodingMethod = if (quality == AutoCaptionQualityV77.ACCURATE) {
                "modified_beam_search"
            } else {
                "greedy_search"
            },
            maxActivePaths = if (quality == AutoCaptionQualityV77.ACCURATE) 10 else 4,
        )
        return OnlineRecognizer(config = config)
    }

    private fun recognizeV79(recognizer: OnlineRecognizer, audio: FloatArray): OnlineRecognizerResult {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(audio, TARGET_SAMPLE_RATE_V77)
            stream.acceptWaveform(FloatArray(TAIL_SILENCE_SAMPLES_V79), TARGET_SAMPLE_RATE_V77)
            stream.inputFinished()
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream)
            }
            return recognizer.getResult(stream)
        } finally {
            runCatching { stream.release() }
        }
    }

    private suspend fun recognizeAutoV79(
        audio: FloatArray,
        bangla: OnlineRecognizer,
        englishProvider: suspend () -> OnlineRecognizer,
    ): RecognitionV79 {
        val bn = recognizeV79(bangla, audio)
        val bnText = bn.text.normalizeCaptionTextV79()
        if (bnText.isNotBlank() && bnText.banglaCharacterRatioV79() >= .20f) {
            return RecognitionV79(AutoCaptionLanguageV79.BANGLA, bn)
        }

        currentCoroutineContext().ensureActive()
        val en = recognizeV79(englishProvider(), audio)
        val enText = en.text.normalizeCaptionTextV79()
        if (enText.isBlank()) return RecognitionV79(AutoCaptionLanguageV79.BANGLA, bn)
        if (bnText.isBlank()) return RecognitionV79(AutoCaptionLanguageV79.ENGLISH, en)

        val bnConfidence = bn.safeMeanProbabilityV79()
        val enConfidence = en.safeMeanProbabilityV79()
        return if (enConfidence > bnConfidence + .04f || enText.asciiLetterRatioV79() > bnText.asciiLetterRatioV79()) {
            RecognitionV79(AutoCaptionLanguageV79.ENGLISH, en)
        } else {
            RecognitionV79(AutoCaptionLanguageV79.BANGLA, bn)
        }
    }

    private suspend fun ensureModelV79(
        spec: ZipformerModelSpecV79,
        onProgress: (AutoCaptionProgressV77) -> Unit,
    ): InstalledZipformerModelV79 = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "auto_cc_models_v79/${spec.directoryName}").apply { mkdirs() }
        val totalFiles = spec.files.size
        spec.files.forEachIndexed { index, remote ->
            currentCoroutineContext().ensureActive()
            val target = File(directory, remote.localName)
            if (target.isFile && target.length() > remote.minimumBytes) return@forEachIndexed
            downloadModelFileV79(
                url = "${spec.baseUrl}/${remote.remotePath}?download=true",
                target = target,
                minimumBytes = remote.minimumBytes,
                label = spec.displayName,
                fileIndex = index,
                fileCount = totalFiles,
                onProgress = onProgress,
            )
        }
        onProgress(AutoCaptionProgressV77(.17f, "Model ready · ${spec.displayName}"))
        InstalledZipformerModelV79(spec, directory)
    }

    private suspend fun downloadModelFileV79(
        url: String,
        target: File,
        minimumBytes: Long,
        label: String,
        fileIndex: Int,
        fileCount: Int,
        onProgress: (AutoCaptionProgressV77) -> Unit,
    ) {
        val temp = File(target.parentFile, "${target.name}.download")
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            currentCoroutineContext().ensureActive()
            if (temp.exists()) temp.delete()
            var connection: HttpURLConnection? = null
            try {
                connection = URI(url).toURL().openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 30_000
                connection.readTimeout = 300_000
                connection.setRequestProperty("User-Agent", "DigitorAndroid-AutoCC/1.0")
                connection.setRequestProperty("Accept", "application/octet-stream,*/*")
                val response = connection.responseCode
                require(response in 200..299) { "Model download returned HTTP $response" }
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
                            val withinFile = if (expected != null) {
                                (copied.toDouble() / expected.toDouble()).toFloat().coerceIn(0f, 1f)
                            } else {
                                (copied.toDouble() / max(minimumBytes, copied).toDouble()).toFloat().coerceIn(0f, 1f)
                            }
                            val overall = (fileIndex + withinFile) / fileCount.toFloat()
                            onProgress(
                                AutoCaptionProgressV77(
                                    .01f + overall * .15f,
                                    "Downloading $label · ${(overall * 100f).roundToInt()}%",
                                ),
                            )
                        }
                    }
                }
                require(temp.length() > minimumBytes) { "Downloaded model file is incomplete" }
                if (target.exists()) target.delete()
                check(temp.renameTo(target)) { "Could not install ${target.name}" }
                return
            } catch (error: Throwable) {
                lastError = error
                if (temp.exists()) temp.delete()
                if (attempt < 2) Thread.sleep(1_500L * (attempt + 1))
            } finally {
                connection?.disconnect()
            }
        }
        throw IllegalStateException("Could not download $label (${target.name})", lastError)
    }

    /** Decode only the selected timeline range to 16 kHz mono float PCM expected by sherpa-onnx. */
    private suspend fun decodeClipToFloatV79(clip: TimelineClip): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var codecStarted = false
        val chunks = mutableListOf<ShortArray>()
        var totalSamples = 0
        try {
            val sourceUri = Uri.parse(clip.uri)
            if (sourceUri.scheme.isNullOrBlank()) {
                extractor.setDataSource(clip.uri)
            } else {
                extractor.setDataSource(context, sourceUri, null)
            }

            var audioTrack = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                val mime = candidate.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
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
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: error("Audio decoder input buffer missing")
                        inputBuffer.clear()
                        val sampleTime = extractor.sampleTime
                        if (sampleTime < 0L || sampleTime >= clip.sourceOutUs) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTime, 0)
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
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
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
                            val mono = bytes.toMonoPcm16V77(outputChannels, outputEncoding)
                            val target = resampleMonoV77(mono, outputRate, TARGET_SAMPLE_RATE_V77)
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
            chunk.forEach { sample ->
                output[offset++] = (sample.toInt() / 32768f).coerceIn(-1f, 1f)
            }
        }
        return output
    }
}

private fun OnlineRecognizerResult.toTimelineDraftsV79(clip: TimelineClip): List<AutoCaptionDraftV77> {
    val cleanText = text.normalizeCaptionTextV79()
    if (cleanText.isBlank()) return emptyList()
    val words = cleanText.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.isEmpty()) return emptyList()

    val usableTimes = timestamps
        .filter { it.isFinite() && it >= 0f }
        .map { (it * 1_000_000f).toLong().coerceIn(0L, clip.durationUs) }

    fun startForWord(index: Int): Long {
        if (usableTimes.isEmpty()) {
            return clip.durationUs * index / words.size.coerceAtLeast(1)
        }
        if (words.size == 1) return usableTimes.first()
        val mapped = ((index.toDouble() / (words.size - 1).toDouble()) * (usableTimes.size - 1))
            .roundToInt()
            .coerceIn(0, usableTimes.lastIndex)
        return usableTimes[mapped]
    }

    val groups = mutableListOf<Pair<List<String>, Long>>()
    var current = mutableListOf<String>()
    var currentStart = startForWord(0)
    words.forEachIndexed { index, word ->
        val wordStart = startForWord(index)
        val candidateLength = current.sumOf { it.length } + current.size + word.length
        val punctuationBreak = current.lastOrNull()?.endsWithAnyPunctuationV79() == true
        val tooLong = current.isNotEmpty() && candidateLength > MAX_CAPTION_CHARS_V77
        val tooWide = current.isNotEmpty() && wordStart - currentStart > MAX_CAPTION_SPAN_US_V79
        if (current.isNotEmpty() && (tooLong || tooWide || punctuationBreak)) {
            groups += current.toList() to currentStart
            current = mutableListOf()
            currentStart = wordStart
        }
        if (current.isEmpty()) currentStart = wordStart
        current += word
    }
    if (current.isNotEmpty()) groups += current.toList() to currentStart

    return groups.mapIndexedNotNull { index, (groupWords, localStart) ->
        val nextStart = groups.getOrNull(index + 1)?.second
        val localEnd = if (nextStart != null) {
            nextStart
        } else {
            min(clip.durationUs, max(localStart + 900_000L, (usableTimes.lastOrNull() ?: localStart) + 1_200_000L))
        }.coerceAtLeast(localStart + MIN_CAPTION_US_V77)
            .coerceAtMost(clip.durationUs)
        if (localEnd <= localStart) return@mapIndexedNotNull null
        AutoCaptionDraftV77(
            text = groupWords.joinToString(" ").normalizeCaptionTextV79(),
            timelineStartUs = clip.timelineStartUs + localStart,
            timelineEndUs = clip.timelineStartUs + localEnd,
        )
    }
}

private fun OnlineRecognizerResult.safeMeanProbabilityV79(): Float {
    val safe = ysProbs.filter { it.isFinite() && it in 0f..1f }
    return if (safe.isEmpty()) .5f else safe.average().toFloat()
}

private fun String.normalizeCaptionTextV79(): String =
    replace('▁', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()

private fun String.banglaCharacterRatioV79(): Float {
    val letters = count { it.isLetter() }.coerceAtLeast(1)
    val bangla = count { it.code in 0x0980..0x09FF }
    return bangla.toFloat() / letters.toFloat()
}

private fun String.asciiLetterRatioV79(): Float {
    val letters = count { it.isLetter() }.coerceAtLeast(1)
    val ascii = count { it in 'A'..'Z' || it in 'a'..'z' }
    return ascii.toFloat() / letters.toFloat()
}

private fun String.endsWithAnyPunctuationV79(): Boolean =
    endsWith('.') || endsWith('?') || endsWith('!') || endsWith('।') || endsWith(';') || endsWith(':')

private fun ByteBuffer.toMonoPcm16V77(channels: Int, encoding: Int): ShortArray {
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

private fun resampleMonoV77(input: ShortArray, sourceRate: Int, targetRate: Int): ShortArray {
    if (input.isEmpty() || sourceRate <= 0 || targetRate <= 0) return ShortArray(0)
    if (sourceRate == targetRate) return input
    val outputCount = max(1, (input.size.toLong() * targetRate / sourceRate).toInt())
    val ratio = sourceRate.toDouble() / targetRate.toDouble()
    return ShortArray(outputCount) { index ->
        val sourcePosition = index * ratio
        val left = floor(sourcePosition).toInt().coerceIn(0, input.lastIndex)
        val right = min(left + 1, input.lastIndex)
        val t = sourcePosition - left
        (input[left] + (input[right] - input[left]) * t)
            .roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }
}

private fun List<AutoCaptionDraftV77>.normalizeCaptionOrderV77(): List<AutoCaptionDraftV77> {
    if (isEmpty()) return emptyList()
    val sorted = sortedWith(compareBy<AutoCaptionDraftV77> { it.timelineStartUs }.thenBy { it.timelineEndUs })
    val result = mutableListOf<AutoCaptionDraftV77>()
    var previousEnd = -1L
    sorted.forEach { item ->
        val start = max(item.timelineStartUs, previousEnd)
        val end = max(start + MIN_CAPTION_US_V77, item.timelineEndUs)
        if (end > start && item.text.isNotBlank()) {
            result += item.copy(timelineStartUs = start, timelineEndUs = end)
            previousEnd = end
        }
    }
    return result
}

/** Install/replace only captions generated by Auto CC; manual titles remain untouched and editable. */
fun TimelineProject.withAutoCaptionsV77(captions: List<AutoCaptionDraftV77>): TimelineProject {
    val existingCcTrack = tracks.firstOrNull { it.kind == TrackKind.VIDEO && it.name == AUTO_CC_TRACK_NAME_V77 }
    val ccTrack = existingCcTrack ?: TimelineTrack(name = AUTO_CC_TRACK_NAME_V77, kind = TrackKind.VIDEO)
    val nextTracks = if (existingCcTrack == null) listOf(ccTrack) + tracks else tracks
    val retained = textOverlays.filterNot { it.id.startsWith(AUTO_CC_ID_PREFIX_V77) }
    val style = TextStyleV2(
        colorArgb = 0xFFFFFFFFL,
        strokeWidth = 4f,
        strokeArgb = 0xFF000000L,
        shadowEnabled = true,
        shadowArgb = 0xC0000000L,
        shadowRadius = 5f,
        shadowDx = 1.5f,
        shadowDy = 2f,
        backgroundEnabled = false,
        alignment = TextAlignmentV2.CENTER,
    )
    val generated = captions.map { caption ->
        TextOverlayClip(
            id = "$AUTO_CC_ID_PREFIX_V77${UUID.randomUUID()}",
            text = caption.text,
            timelineStartUs = caption.timelineStartUs,
            timelineEndUs = caption.timelineEndUs,
            positionX = 0f,
            positionY = .72f,
            sizeScale = .88f,
            argb = 0xFFFFFFFFL,
            bold = true,
            background = false,
            styleV2 = style,
            videoTrackIdV3 = ccTrack.id,
        )
    }
    return copy(tracks = nextTracks, textOverlays = retained + generated)
}

fun TimelineProject.clearAutoCaptionsV77(): TimelineProject =
    copy(textOverlays = textOverlays.filterNot { it.id.startsWith(AUTO_CC_ID_PREFIX_V77) })

fun TimelineProject.autoCaptionCountV77(): Int = textOverlays.count { it.id.startsWith(AUTO_CC_ID_PREFIX_V77) }
