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
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TrackKind
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val Z83_RATE = 16_000
private const val Z83_TAIL_SAMPLES = 6_400 // sherpa streaming examples use ~0.4 s tail padding
private const val Z83_FEED_SAMPLES = 8_000 // 0.5 s incremental feed
private const val Z83_FRAME_SAMPLES = 320 // 20 ms
private const val Z83_MIN_CAPTION_US = 180_000L
private const val Z83_MAX_CAPTION_CHARS = 42
private const val Z83_MAX_CAPTION_SPAN_US = 4_000_000L
private const val Z83_FORCE_SEGMENT_SAMPLES = Z83_RATE * 10
private const val Z83_MIN_SPLIT_SAMPLES = Z83_RATE * 2
private const val Z83_MIN_SILENCE_FRAMES = 12 // 240 ms

private data class Z83Remote(val remotePath: String, val localName: String, val minimumBytes: Long)
private data class Z83Spec(
    val language: AutoCaptionLanguageV79,
    val directoryName: String,
    val baseUrl: String,
    val displayName: String,
    val modelType: String,
    val dither: Float,
    val files: List<Z83Remote>,
)
private data class Z83Installed(val spec: Z83Spec, val dir: File) {
    val encoder get() = File(dir, "encoder.onnx")
    val decoder get() = File(dir, "decoder.onnx")
    val joiner get() = File(dir, "joiner.onnx")
    val tokens get() = File(dir, "tokens.txt")
}
private data class Z83Recognition(val language: AutoCaptionLanguageV79, val result: OnlineRecognizerResult)
private data class Z83AudioSegment(val start: Int, val end: Int)

private val Z83_BN = Z83Spec(
    AutoCaptionLanguageV79.BANGLA,
    "bn-zipformer2-2026-02-09",
    "https://huggingface.co/alphacep/vosk-model-small-streaming-bn/resolve/dfabeea5eee1f33d81436826d0575d8cfd64bd1d",
    "Bengali Zipformer2 0.60",
    "zipformer2",
    3e-5f,
    listOf(
        Z83Remote("am-onnx/encoder.onnx", "encoder.onnx", 80_000_000L),
        Z83Remote("am-onnx/decoder.onnx", "decoder.onnx", 1_500_000L),
        Z83Remote("am-onnx/joiner.onnx", "joiner.onnx", 700_000L),
        Z83Remote("lang/tokens.txt", "tokens.txt", 4_000L),
    ),
)
private val Z83_EN = Z83Spec(
    AutoCaptionLanguageV79.ENGLISH,
    "en-zipformer-20m-int8-2023-02-17",
    "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/resolve/d42f2d9f7ca24806fb667456a18a9f1b60f70d16",
    "English Zipformer 20M INT8",
    "zipformer",
    0f,
    listOf(
        Z83Remote("encoder-epoch-99-avg-1.int8.onnx", "encoder.onnx", 35_000_000L),
        Z83Remote("decoder-epoch-99-avg-1.int8.onnx", "decoder.onnx", 400_000L),
        Z83Remote("joiner-epoch-99-avg-1.int8.onnx", "joiner.onnx", 200_000L),
        Z83Remote("tokens.txt", "tokens.txt", 4_000L),
    ),
)

/** Zipformer-only Auto CC with accuracy-oriented preprocessing for offline video speech. */
class ZipformerAutoCaptionEngineV83(private val context: Context) {
    companion object {
        private val abis = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        fun supportedOnThisDevice(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && Build.SUPPORTED_ABIS.any { it in abis }
    }

    init {
        // Experimental V80 Omnilingual files are deliberately retired.
        runCatching { File(context.filesDir, "auto_cc_models_v80").deleteRecursively() }
    }

    suspend fun generate(
        project: TimelineProject,
        audioTrackId: String,
        quality: AutoCaptionQualityV77,
        language: AutoCaptionLanguageV79 = AutoCaptionLanguageV79.BANGLA,
        onProgress: (AutoCaptionProgressV77) -> Unit = {},
    ): AutoCaptionResultV77 {
        check(supportedOnThisDevice()) { "Auto CC is not available on this Android ABI" }
        val track = project.track(audioTrackId)?.takeIf { it.kind == TrackKind.AUDIO && !it.muted }
            ?: error("Select an unmuted audio track")
        val clips = track.sortedClips()
        require(clips.isNotEmpty()) { "${track.name} has no audio clips" }

        val recognizers = mutableMapOf<AutoCaptionLanguageV79, OnlineRecognizer>()
        val models = mutableMapOf<AutoCaptionLanguageV79, Z83Installed>()
        val used = linkedSetOf<String>()

        suspend fun recognizerFor(lang: AutoCaptionLanguageV79): OnlineRecognizer {
            require(lang != AutoCaptionLanguageV79.AUTO)
            recognizers[lang]?.let { return it }
            val spec = if (lang == AutoCaptionLanguageV79.BANGLA) Z83_BN else Z83_EN
            val model = models[lang] ?: ensureModel(spec, onProgress).also { models[lang] = it }
            return withContext(Dispatchers.Default) {
                createRecognizer(model, quality).also {
                    recognizers[lang] = it
                    used += spec.displayName
                }
            }
        }

        return try {
            val drafts = mutableListOf<AutoCaptionDraftV77>()
            val totalUs = clips.sumOf { it.durationUs }.coerceAtLeast(1L)
            var completedUs = 0L

            clips.forEachIndexed { clipIndex, clip ->
                currentCoroutineContext().ensureActive()
                onProgress(
                    AutoCaptionProgressV77(
                        .18f + .72f * (completedUs.toFloat() / totalUs).coerceIn(0f, 1f),
                        "Preparing speech ${clipIndex + 1}/${clips.size}",
                    ),
                )
                val decoded = decodeClip(clip)
                if (decoded.isNotEmpty()) {
                    val prepared = if (quality == AutoCaptionQualityV77.ACCURATE) normalizeSpeechLevel(decoded) else decoded
                    val segments = if (quality == AutoCaptionQualityV77.ACCURATE) speechSegments(prepared) else listOf(Z83AudioSegment(0, prepared.size))

                    segments.forEach { segment ->
                        currentCoroutineContext().ensureActive()
                        if (segment.end <= segment.start) return@forEach
                        val samples = prepared.copyOfRange(segment.start, segment.end)
                        val recognition = when (language) {
                            AutoCaptionLanguageV79.BANGLA -> Z83Recognition(
                                AutoCaptionLanguageV79.BANGLA,
                                recognize(recognizerFor(AutoCaptionLanguageV79.BANGLA), samples),
                            )
                            AutoCaptionLanguageV79.ENGLISH -> Z83Recognition(
                                AutoCaptionLanguageV79.ENGLISH,
                                recognize(recognizerFor(AutoCaptionLanguageV79.ENGLISH), samples),
                            )
                            AutoCaptionLanguageV79.AUTO -> recognizeAuto(
                                samples,
                                recognizerFor(AutoCaptionLanguageV79.BANGLA),
                            ) { recognizerFor(AutoCaptionLanguageV79.ENGLISH) }
                        }
                        val localStartUs = segment.start.toLong() * 1_000_000L / Z83_RATE
                        val localEndUs = segment.end.toLong() * 1_000_000L / Z83_RATE
                        val segmentClip = clip.copy(
                            timelineStartUs = clip.timelineStartUs + localStartUs,
                            sourceInUs = clip.sourceInUs + localStartUs,
                            sourceOutUs = min(clip.sourceOutUs, clip.sourceInUs + localEndUs),
                        )
                        drafts += recognition.result.toDrafts(segmentClip)
                    }
                }
                completedUs += clip.durationUs
            }

            val normalized = drafts.normalizeDrafts()
            require(normalized.isNotEmpty()) { "No speech was detected on the selected audio track" }
            onProgress(AutoCaptionProgressV77(1f, "${normalized.size} captions ready"))
            AutoCaptionResultV77(
                captions = normalized,
                backend = if (quality == AutoCaptionQualityV77.ACCURATE) {
                    "sherpa-onnx CPU · Zipformer V83 tuned"
                } else {
                    "sherpa-onnx CPU · Zipformer fast"
                },
                model = used.joinToString(" + ").ifBlank { "Zipformer" },
            )
        } finally {
            recognizers.values.forEach { runCatching { it.release() } }
        }
    }

    private fun createRecognizer(model: Z83Installed, quality: AutoCaptionQualityV77): OnlineRecognizer {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        return OnlineRecognizer(
            config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = Z83_RATE, featureDim = 80, dither = model.spec.dither),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = model.encoder.absolutePath,
                        decoder = model.decoder.absolutePath,
                        joiner = model.joiner.absolutePath,
                    ),
                    tokens = model.tokens.absolutePath,
                    numThreads = threads,
                    provider = "cpu",
                    modelType = model.spec.modelType,
                ),
                enableEndpoint = false,
                decodingMethod = if (quality == AutoCaptionQualityV77.ACCURATE) "modified_beam_search" else "greedy_search",
                maxActivePaths = if (quality == AutoCaptionQualityV77.ACCURATE) 24 else 4,
            ),
        )
    }

    private fun recognize(recognizer: OnlineRecognizer, audio: FloatArray): OnlineRecognizerResult {
        val stream = recognizer.createStream()
        try {
            var offset = 0
            while (offset < audio.size) {
                val end = min(audio.size, offset + Z83_FEED_SAMPLES)
                stream.acceptWaveform(audio.copyOfRange(offset, end), Z83_RATE)
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                offset = end
            }
            stream.acceptWaveform(FloatArray(Z83_TAIL_SAMPLES), Z83_RATE)
            stream.inputFinished()
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            return recognizer.getResult(stream)
        } finally {
            runCatching { stream.release() }
        }
    }

    private suspend fun recognizeAuto(
        audio: FloatArray,
        bangla: OnlineRecognizer,
        englishProvider: suspend () -> OnlineRecognizer,
    ): Z83Recognition {
        val bn = recognize(bangla, audio)
        val bnText = bn.text.cleanText()
        if (bnText.isNotBlank() && bnText.banglaRatio() >= .25f && bn.meanProbability() >= .42f) {
            return Z83Recognition(AutoCaptionLanguageV79.BANGLA, bn)
        }
        currentCoroutineContext().ensureActive()
        val en = recognize(englishProvider(), audio)
        val enText = en.text.cleanText()
        if (enText.isBlank()) return Z83Recognition(AutoCaptionLanguageV79.BANGLA, bn)
        if (bnText.isBlank()) return Z83Recognition(AutoCaptionLanguageV79.ENGLISH, en)
        return if (en.meanProbability() > bn.meanProbability() + .05f || enText.asciiRatio() > bnText.asciiRatio() + .15f) {
            Z83Recognition(AutoCaptionLanguageV79.ENGLISH, en)
        } else Z83Recognition(AutoCaptionLanguageV79.BANGLA, bn)
    }

    private fun normalizeSpeechLevel(input: FloatArray): FloatArray {
        if (input.isEmpty()) return input
        val mean = input.sumOf { it.toDouble() }.toFloat() / input.size
        val centered = FloatArray(input.size) { (input[it] - mean).coerceIn(-1f, 1f) }
        val frameRms = frameRms(centered)
        if (frameRms.isEmpty()) return centered
        val sorted = frameRms.sorted()
        val noise = sorted[(sorted.size * .2f).toInt().coerceIn(0, sorted.lastIndex)]
        val speechThreshold = max(.008f, noise * 2.2f)
        var sumSq = 0.0
        var count = 0
        frameRms.forEachIndexed { frame, rms ->
            if (rms >= speechThreshold) {
                val start = frame * Z83_FRAME_SAMPLES
                val end = min(centered.size, start + Z83_FRAME_SAMPLES)
                for (i in start until end) {
                    sumSq += centered[i] * centered[i]
                    count++
                }
            }
        }
        if (count == 0) return centered
        val speechRms = sqrt(sumSq / count).toFloat().coerceAtLeast(.001f)
        val gain = (.10f / speechRms).coerceIn(.75f, 2.5f)
        return FloatArray(centered.size) { (centered[it] * gain).coerceIn(-.98f, .98f) }
    }

    private fun speechSegments(audio: FloatArray): List<Z83AudioSegment> {
        if (audio.size <= Z83_FORCE_SEGMENT_SAMPLES) return listOf(Z83AudioSegment(0, audio.size))
        val rms = frameRms(audio)
        if (rms.isEmpty()) return listOf(Z83AudioSegment(0, audio.size))
        val sorted = rms.sorted()
        val noise = sorted[(sorted.size * .2f).toInt().coerceIn(0, sorted.lastIndex)]
        val silenceThreshold = max(.006f, noise * 1.8f)
        val boundaries = mutableListOf(0)
        var segmentStartSample = 0
        var silenceStartFrame = -1

        for (frame in rms.indices) {
            val sample = frame * Z83_FRAME_SAMPLES
            val silent = rms[frame] < silenceThreshold
            if (silent && silenceStartFrame < 0) silenceStartFrame = frame
            if (!silent && silenceStartFrame >= 0) {
                val silenceFrames = frame - silenceStartFrame
                val silenceMidSample = ((silenceStartFrame + frame) / 2) * Z83_FRAME_SAMPLES
                if (silenceFrames >= Z83_MIN_SILENCE_FRAMES && silenceMidSample - segmentStartSample >= Z83_MIN_SPLIT_SAMPLES) {
                    boundaries += silenceMidSample.coerceIn(segmentStartSample + 1, audio.size)
                    segmentStartSample = boundaries.last()
                }
                silenceStartFrame = -1
            }
            if (sample - segmentStartSample >= Z83_FORCE_SEGMENT_SAMPLES) {
                boundaries += sample.coerceIn(segmentStartSample + 1, audio.size)
                segmentStartSample = boundaries.last()
                silenceStartFrame = -1
            }
        }
        if (boundaries.last() != audio.size) boundaries += audio.size

        val raw = boundaries.zipWithNext().map { (a, b) -> Z83AudioSegment(a, b) }.filter { it.end > it.start }
        if (raw.size <= 1) return raw
        val merged = mutableListOf<Z83AudioSegment>()
        raw.forEach { segment ->
            if (segment.end - segment.start < Z83_RATE / 2 && merged.isNotEmpty()) {
                val prev = merged.removeAt(merged.lastIndex)
                merged += Z83AudioSegment(prev.start, segment.end)
            } else merged += segment
        }
        return merged
    }

    private fun frameRms(audio: FloatArray): List<Float> {
        if (audio.isEmpty()) return emptyList()
        val count = (audio.size + Z83_FRAME_SAMPLES - 1) / Z83_FRAME_SAMPLES
        return List(count) { frame ->
            val start = frame * Z83_FRAME_SAMPLES
            val end = min(audio.size, start + Z83_FRAME_SAMPLES)
            var sum = 0.0
            for (i in start until end) sum += audio[i] * audio[i]
            sqrt(sum / max(1, end - start)).toFloat()
        }
    }

    private suspend fun ensureModel(spec: Z83Spec, onProgress: (AutoCaptionProgressV77) -> Unit): Z83Installed =
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "auto_cc_models_v79/${spec.directoryName}").apply { mkdirs() }
            spec.files.forEachIndexed { index, remote ->
                currentCoroutineContext().ensureActive()
                val target = File(dir, remote.localName)
                if (target.isFile && target.length() > remote.minimumBytes) return@forEachIndexed
                download(
                    "${spec.baseUrl}/${remote.remotePath}?download=true",
                    target,
                    remote.minimumBytes,
                    spec.displayName,
                    index,
                    spec.files.size,
                    onProgress,
                )
            }
            onProgress(AutoCaptionProgressV77(.17f, "Model ready · ${spec.displayName}"))
            Z83Installed(spec, dir)
        }

    private suspend fun download(
        url: String,
        target: File,
        minimumBytes: Long,
        label: String,
        index: Int,
        count: Int,
        onProgress: (AutoCaptionProgressV77) -> Unit,
    ) {
        val temp = File(target.parentFile, "${target.name}.download")
        var last: Throwable? = null
        repeat(3) { attempt ->
            currentCoroutineContext().ensureActive()
            temp.delete()
            var connection: HttpURLConnection? = null
            try {
                connection = URI(url).toURL().openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 30_000
                connection.readTimeout = 300_000
                connection.setRequestProperty("User-Agent", "DigitorAndroid-AutoCC/1.0")
                connection.setRequestProperty("Accept", "application/octet-stream,*/*")
                require(connection.responseCode in 200..299) { "Model download returned HTTP ${connection.responseCode}" }
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
                            val fileRatio = if (expected != null) (copied.toDouble() / expected).toFloat() else 0f
                            val overall = (index + fileRatio.coerceIn(0f, 1f)) / count.toFloat()
                            onProgress(AutoCaptionProgressV77(.01f + overall * .15f, "Downloading $label · ${(overall * 100).roundToInt()}%"))
                        }
                    }
                }
                require(temp.length() > minimumBytes) { "Downloaded model file is incomplete" }
                target.delete()
                check(temp.renameTo(target)) { "Could not install ${target.name}" }
                return
            } catch (error: Throwable) {
                last = error
                temp.delete()
                if (attempt < 2) Thread.sleep(1_500L * (attempt + 1))
            } finally {
                connection?.disconnect()
            }
        }
        throw IllegalStateException("Could not download $label (${target.name})", last)
    }

    private suspend fun decodeClip(clip: TimelineClip): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var started = false
        val chunks = mutableListOf<ShortArray>()
        var total = 0
        try {
            val uri = Uri.parse(clip.uri)
            if (uri.scheme.isNullOrBlank()) extractor.setDataSource(clip.uri) else extractor.setDataSource(context, uri, null)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                if (candidate.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    track = i; format = candidate; break
                }
            }
            require(track >= 0 && format != null) { "${clip.label}: no decodable audio stream" }
            extractor.selectTrack(track)
            extractor.seekTo(clip.sourceInUs.coerceAtLeast(0), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Audio MIME missing")
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start(); started = true
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buffer = codec.getInputBuffer(i) ?: error("Audio input buffer missing")
                        buffer.clear()
                        val time = extractor.sampleTime
                        if (time < 0 || time >= clip.sourceOutUs) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else {
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else { codec.queueInputBuffer(i, 0, size, time, 0); extractor.advance() }
                        }
                    }
                }
                when (val out = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (out >= 0) {
                        val buffer = codec.getOutputBuffer(out)
                        if (buffer != null && info.size > 0 && info.presentationTimeUs >= clip.sourceInUs && info.presentationTimeUs < clip.sourceOutUs) {
                            val end = (info.offset + info.size).coerceAtMost(buffer.capacity())
                            val bytes = buffer.duplicate().apply { position(info.offset.coerceAtLeast(0)); limit(end) }.slice().order(ByteOrder.LITTLE_ENDIAN)
                            val mono = bytes.toMono(channels, encoding)
                            val resampled = resample(mono, rate, Z83_RATE)
                            if (resampled.isNotEmpty()) { chunks += resampled; total += resampled.size }
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(out, false)
                    }
                }
            }
        } finally {
            runCatching { extractor.release() }
            codec?.let { if (started) runCatching { it.stop() }; runCatching { it.release() } }
        }
        val output = FloatArray(total)
        var p = 0
        chunks.forEach { chunk -> chunk.forEach { output[p++] = (it.toInt() / 32768f).coerceIn(-1f, 1f) } }
        return output
    }
}

private fun OnlineRecognizerResult.toDrafts(clip: TimelineClip): List<AutoCaptionDraftV77> {
    val clean = text.cleanText()
    if (clean.isBlank()) return emptyList()
    val words = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.isEmpty()) return emptyList()
    val times = timestamps.filter { it.isFinite() && it >= 0f }
        .map { (it * 1_000_000f).toLong().coerceIn(0, clip.durationUs) }
    fun timeFor(index: Int): Long {
        if (times.isEmpty()) return clip.durationUs * index / words.size.coerceAtLeast(1)
        if (words.size == 1) return times.first()
        val mapped = ((index.toDouble() / (words.size - 1)) * (times.size - 1)).roundToInt().coerceIn(0, times.lastIndex)
        return times[mapped]
    }
    val groups = mutableListOf<Pair<List<String>, Long>>()
    var current = mutableListOf<String>()
    var start = timeFor(0)
    words.forEachIndexed { i, word ->
        val t = timeFor(i)
        val chars = current.sumOf { it.length } + current.size + word.length
        if (current.isNotEmpty() && (chars > Z83_MAX_CAPTION_CHARS || t - start > Z83_MAX_CAPTION_SPAN_US || current.last().endsSentence())) {
            groups += current.toList() to start
            current = mutableListOf(); start = t
        }
        if (current.isEmpty()) start = t
        current += word
    }
    if (current.isNotEmpty()) groups += current.toList() to start
    return groups.mapIndexedNotNull { index, (group, localStart) ->
        val next = groups.getOrNull(index + 1)?.second
        val end = (next ?: min(clip.durationUs, max(localStart + 900_000, (times.lastOrNull() ?: localStart) + 1_000_000)))
            .coerceAtLeast(localStart + Z83_MIN_CAPTION_US).coerceAtMost(clip.durationUs)
        if (end <= localStart) null else AutoCaptionDraftV77(group.joinToString(" ").cleanText(), clip.timelineStartUs + localStart, clip.timelineStartUs + end)
    }
}

private fun OnlineRecognizerResult.meanProbability(): Float {
    val safe = ysProbs.filter { it.isFinite() && it in 0f..1f }
    return if (safe.isEmpty()) .5f else safe.average().toFloat()
}
private fun String.cleanText() = replace('▁', ' ').replace(Regex("\\s+"), " ").trim()
private fun String.banglaRatio(): Float {
    val letters = count { it.isLetter() }.coerceAtLeast(1)
    return count { it.code in 0x0980..0x09FF }.toFloat() / letters
}
private fun String.asciiRatio(): Float {
    val letters = count { it.isLetter() }.coerceAtLeast(1)
    return count { it in 'A'..'Z' || it in 'a'..'z' }.toFloat() / letters
}
private fun String.endsSentence() = endsWith('.') || endsWith('?') || endsWith('!') || endsWith('।') || endsWith(';') || endsWith(':')

private fun ByteBuffer.toMono(channels: Int, encoding: Int): ShortArray {
    val ch = channels.coerceAtLeast(1)
    return when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> {
            val frames = remaining() / 4 / ch
            ShortArray(frames) { frame ->
                var sum = 0f
                repeat(ch) { c -> sum += getFloat((frame * ch + c) * 4) }
                ((sum / ch).coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort()
            }
        }
        AudioFormat.ENCODING_PCM_8BIT -> {
            val frames = remaining() / ch
            ShortArray(frames) { frame ->
                var sum = 0
                repeat(ch) { c -> sum += (get(frame * ch + c).toInt() and 0xff) - 128 }
                ((sum / ch) shl 8).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
        else -> {
            val frames = remaining() / 2 / ch
            ShortArray(frames) { frame ->
                var sum = 0L
                repeat(ch) { c -> sum += getShort((frame * ch + c) * 2) }
                (sum / ch).coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toShort()
            }
        }
    }
}

private fun resample(input: ShortArray, sourceRate: Int, targetRate: Int): ShortArray {
    if (input.isEmpty() || sourceRate <= 0 || targetRate <= 0) return ShortArray(0)
    if (sourceRate == targetRate) return input
    val count = max(1, (input.size.toLong() * targetRate / sourceRate).toInt())
    val ratio = sourceRate.toDouble() / targetRate
    return ShortArray(count) { i ->
        val pos = i * ratio
        val left = floor(pos).toInt().coerceIn(0, input.lastIndex)
        val right = min(left + 1, input.lastIndex)
        val t = pos - left
        (input[left] + (input[right] - input[left]) * t).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}

private fun List<AutoCaptionDraftV77>.normalizeDrafts(): List<AutoCaptionDraftV77> {
    val sorted = sortedWith(compareBy<AutoCaptionDraftV77> { it.timelineStartUs }.thenBy { it.timelineEndUs })
    val out = mutableListOf<AutoCaptionDraftV77>()
    var previousEnd = -1L
    sorted.forEach { item ->
        val start = max(item.timelineStartUs, previousEnd)
        val end = max(start + Z83_MIN_CAPTION_US, item.timelineEndUs)
        if (item.text.isNotBlank() && end > start) {
            out += item.copy(timelineStartUs = start, timelineEndUs = end)
            previousEnd = end
        }
    }
    return out
}
