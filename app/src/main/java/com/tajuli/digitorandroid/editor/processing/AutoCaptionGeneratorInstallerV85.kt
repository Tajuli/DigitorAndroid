package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private data class GeneratorRemoteV85(
    val baseUrl: String,
    val directoryName: String,
    val remotePath: String,
    val localName: String,
    val minimumBytes: Long,
)

private val GENERATOR_FILES_V85 = listOf(
    GeneratorRemoteV85(
        baseUrl = "https://huggingface.co/alphacep/vosk-model-small-streaming-bn/resolve/dfabeea5eee1f33d81436826d0575d8cfd64bd1d",
        directoryName = "bn-zipformer2-2026-02-09",
        remotePath = "am-onnx/encoder.onnx",
        localName = "encoder.onnx",
        minimumBytes = 80_000_000L,
    ),
    GeneratorRemoteV85(
        baseUrl = "https://huggingface.co/alphacep/vosk-model-small-streaming-bn/resolve/dfabeea5eee1f33d81436826d0575d8cfd64bd1d",
        directoryName = "bn-zipformer2-2026-02-09",
        remotePath = "am-onnx/decoder.onnx",
        localName = "decoder.onnx",
        minimumBytes = 1_500_000L,
    ),
    GeneratorRemoteV85(
        baseUrl = "https://huggingface.co/alphacep/vosk-model-small-streaming-bn/resolve/dfabeea5eee1f33d81436826d0575d8cfd64bd1d",
        directoryName = "bn-zipformer2-2026-02-09",
        remotePath = "am-onnx/joiner.onnx",
        localName = "joiner.onnx",
        minimumBytes = 700_000L,
    ),
    GeneratorRemoteV85(
        baseUrl = "https://huggingface.co/alphacep/vosk-model-small-streaming-bn/resolve/dfabeea5eee1f33d81436826d0575d8cfd64bd1d",
        directoryName = "bn-zipformer2-2026-02-09",
        remotePath = "lang/tokens.txt",
        localName = "tokens.txt",
        minimumBytes = 4_000L,
    ),
    GeneratorRemoteV85(
        baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/resolve/d42f2d9f7ca24806fb667456a18a9f1b60f70d16",
        directoryName = "en-zipformer-20m-int8-2023-02-17",
        remotePath = "encoder-epoch-99-avg-1.int8.onnx",
        localName = "encoder.onnx",
        minimumBytes = 35_000_000L,
    ),
    GeneratorRemoteV85(
        baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/resolve/d42f2d9f7ca24806fb667456a18a9f1b60f70d16",
        directoryName = "en-zipformer-20m-int8-2023-02-17",
        remotePath = "decoder-epoch-99-avg-1.int8.onnx",
        localName = "decoder.onnx",
        minimumBytes = 400_000L,
    ),
    GeneratorRemoteV85(
        baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/resolve/d42f2d9f7ca24806fb667456a18a9f1b60f70d16",
        directoryName = "en-zipformer-20m-int8-2023-02-17",
        remotePath = "joiner-epoch-99-avg-1.int8.onnx",
        localName = "joiner.onnx",
        minimumBytes = 200_000L,
    ),
    GeneratorRemoteV85(
        baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/resolve/d42f2d9f7ca24806fb667456a18a9f1b60f70d16",
        directoryName = "en-zipformer-20m-int8-2023-02-17",
        remotePath = "tokens.txt",
        localName = "tokens.txt",
        minimumBytes = 4_000L,
    ),
)

/**
 * One-time installer shown before the full Auto CC UI.
 *
 * It deliberately installs both Bengali and English Zipformer assets into the exact V83 cache
 * paths. After this succeeds, changing the language or using Auto mode never triggers a surprise
 * model download from the Generate button.
 */
class AutoCaptionGeneratorInstallerV85(private val context: Context) {
    private val root = File(context.filesDir, "auto_cc_models_v79")

    fun isInstalled(): Boolean = GENERATOR_FILES_V85.all { remote ->
        val target = File(File(root, remote.directoryName), remote.localName)
        target.isFile && target.length() > remote.minimumBytes
    }

    suspend fun download(onProgress: (AutoCaptionProgressV77) -> Unit = {}) = withContext(Dispatchers.IO) {
        val total = GENERATOR_FILES_V85.size.coerceAtLeast(1)
        GENERATOR_FILES_V85.forEachIndexed { index, remote ->
            currentCoroutineContext().ensureActive()
            val directory = File(root, remote.directoryName).apply { mkdirs() }
            val target = File(directory, remote.localName)
            if (target.isFile && target.length() > remote.minimumBytes) {
                val completed = (index + 1f) / total
                onProgress(
                    AutoCaptionProgressV77(
                        completed,
                        "Downloading Auto Caption Generator · ${(completed * 100).roundToInt()}%",
                    ),
                )
                return@forEachIndexed
            }

            downloadFileV85(
                remote = remote,
                target = target,
                fileIndex = index,
                fileCount = total,
                onProgress = onProgress,
            )
        }
        check(isInstalled()) { "Auto Caption Generator download is incomplete" }
        onProgress(AutoCaptionProgressV77(1f, "Auto Caption Generator ready"))
    }

    private suspend fun downloadFileV85(
        remote: GeneratorRemoteV85,
        target: File,
        fileIndex: Int,
        fileCount: Int,
        onProgress: (AutoCaptionProgressV77) -> Unit,
    ) {
        val url = "${remote.baseUrl}/${remote.remotePath}?download=true"
        val temp = File(target.parentFile, "${target.name}.download")
        var lastError: Throwable? = null

        for (attempt in 0 until 3) {
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
                require(connection.responseCode in 200..299) {
                    "Auto Caption Generator download returned HTTP ${connection.responseCode}"
                }

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
                            val fileRatio = when {
                                expected != null -> copied.toFloat() / expected.toFloat()
                                else -> copied.toFloat() / remote.minimumBytes.coerceAtLeast(1L).toFloat()
                            }.coerceIn(0f, 1f)
                            val overall = (fileIndex + fileRatio) / fileCount.toFloat()
                            onProgress(
                                AutoCaptionProgressV77(
                                    overall.coerceIn(0f, 1f),
                                    "Downloading Auto Caption Generator · ${(overall * 100).roundToInt()}%",
                                ),
                            )
                        }
                    }
                }

                require(temp.length() > remote.minimumBytes) { "Downloaded generator file is incomplete" }
                target.delete()
                check(temp.renameTo(target)) { "Could not install Auto Caption Generator" }
                return
            } catch (cancelled: CancellationException) {
                temp.delete()
                throw cancelled
            } catch (error: Throwable) {
                lastError = error
                temp.delete()
            } finally {
                connection?.disconnect()
            }
        }
        throw IllegalStateException(lastError?.message ?: "Auto Caption Generator download failed", lastError)
    }
}
