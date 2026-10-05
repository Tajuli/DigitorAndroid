package com.tajuli.digitorandroid.editor.processing

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tajuli.digitorandroid.editor.model.*
import com.tajuli.digitorandroid.editor.model.TimelineClip
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real codec regression: a silent derived video must keep its separately linked original sound. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class SpeedAudioInstrumentedTest {
    @Test fun slowVideoKeepsLinkedAudio() = verify(.5f, false)
    @Test fun fastVideoKeepsLinkedAudio() = verify(2f, false)
    @Test fun smoothVideoKeepsLinkedAudio() = verify(.5f, true)

    @Test fun metadataSlowAudioIsAudible() = verify(.5f, false, true)
    @Test fun metadataFastAudioIsAudible() = verify(2f, false, true)
    @Test fun metadataCurveAudioIsAudible() = verify(1f, false, true)
    @Test fun metadataSmoothExportKeepsAudio() = verify(.5f, true, true)
    @Test fun cpuFallbackKeepsRetimedAudio() = verify(2f, false, true, true)

    /** Container/packet checks pass even for encoded digital silence; decode the actual waveform. */
    private fun assertAudible(context: android.content.Context, uri: String) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(uri), null)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(checkNotNull(format.getString(MediaFormat.KEY_MIME)))
            decoder = codec
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var ended = false
            var peak = 0
            val deadline = android.os.SystemClock.elapsedRealtime() + 30_000L
            while (!ended && android.os.SystemClock.elapsedRealtime() < deadline) {
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = checkNotNull(codec.getInputBuffer(inputIndex)).apply { clear() }
                        val count = extractor.readSampleData(buffer, 0)
                        if (count < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, count, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index >= 0) {
                    val pcm = checkNotNull(codec.getOutputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                    pcm.position(info.offset)
                    pcm.limit(info.offset + info.size)
                    while (pcm.remaining() >= 2) peak = maxOf(peak, abs(pcm.short.toInt()))
                    ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                }
            }
            assertTrue("Audio decode timed out", ended)
            assertTrue("Retimed tone became silence: peak=$peak", peak > 1000)
        } finally {
            runCatching { decoder?.stop() }
            decoder?.release()
            extractor.release()
        }
    }

    private fun verify(speed: Float, smooth: Boolean, metadata: Boolean = false, cpu: Boolean = false) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "speed_audio_test").apply { mkdirs() }
        val video = File(dir, "silent.mp4")
        val audio = File(dir, "tone.wav")
        CpuAvcEncoder(64, 64, 30, video).use { encoder ->
            val pixels = IntArray(64 * 64) { 0xff4466aa.toInt() }
            repeat(60) { encoder.encodeFrame(pixels, it * 1_000_000L / 30) }
            encoder.finish()
        }
        val samples = 48_000 * 2
        val wav = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
        wav.putInt(16).putShort(1.toShort()).putShort(1.toShort()).putInt(48_000).putInt(96_000)
        wav.putShort(2.toShort()).putShort(16.toShort()).put("data".toByteArray()).putInt(samples * 2)
        repeat(samples) { wav.putShort((sin(it * 2.0 * Math.PI * 440 / 48_000) * 12_000).toInt().toShort()) }
        audio.writeBytes(wav.array())
        val clip = TimelineClip(uri = Uri.fromFile(video).toString(), label = "video",
            timelineStartUs = 0L, sourceInUs = if (metadata) 250_000L else 0L,
            sourceOutUs = if (metadata) 1_750_000L else 2_000_000L, linkGroupId = "pair")
        val linked = clip.copy(id = "audio", uri = Uri.fromFile(audio).toString())
        var result: CreatorMediaProcessor.DerivedMedia? = null
        try {
            val baked = if (metadata) {
                val curve = if (speed == 1f) SpeedCurveSpec.preset(SpeedCurvePreset.HERO)
                    else SpeedCurveSpec.constant(speed, smooth)
                val project = TimelineProject(width = 64, height = 64, frameRate = 30, tracks = listOf(
                    TimelineTrack(name = "V1", kind = TrackKind.VIDEO, clips = listOf(clip)),
                    TimelineTrack(name = "A1", kind = TrackKind.AUDIO, clips = listOf(linked)),
                )).withClipSpeed(clip.id, curve)
                val output = File(dir, if (smooth || cpu) "metadata-export.mp4" else "metadata-audio.m4a")
                if (smooth || cpu) {
                    withTimeout(120_000) {
                        if (cpu) CpuExportBackend(context).export(project, output, ExportQuality.LOW, {})
                        else NativeHardwareExportBackendV75(context).export(project, output, ExportQuality.LOW, {})
                    }
                    val frames = MediaExtractor()
                    try {
                        frames.setDataSource(output.path)
                        val videoIndex = (0 until frames.trackCount).first {
                            frames.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                        }
                        frames.selectTrack(videoIndex)
                        var count = 0
                        var last = -1L
                        do {
                            val time = frames.sampleTime
                            if (time < 0) break
                            assertTrue("Video timestamps must increase", time > last)
                            last = time
                            count++
                        } while (frames.advance())
                        val expected = (project.durationUs * project.frameRate / 1_000_000L).toInt()
                        assertTrue("Expected $expected frames, got $count", abs(count - expected) <= 1)
                    } finally { frames.release() }
                } else NativeAudioMixdownV76(context).encode(project, output, {})
                CreatorMediaProcessor.DerivedMedia(Uri.fromFile(output).toString(), project.durationUs, true)
            } else withTimeout(120_000) {
                withContext(Dispatchers.Main) {
                    CreatorMediaProcessor(context).bakeSpeedCurve(clip,
                        SpeedCurveSpec.constant(speed, smooth), 30, linked)
                }
            }
            result = baked
            assertTrue(baked.hasAudio)
            assertAudible(context, baked.uri)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, Uri.parse(baked.uri), null)
                val index = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }
                assertTrue("No audio stream in speed output", index != null)
                extractor.selectTrack(checkNotNull(index))
                val buffer = ByteBuffer.allocate(256 * 1024)
                var lastSampleUs = -1L
                var packets = 0
                while (extractor.readSampleData(buffer, 0) > 0) {
                    lastSampleUs = extractor.sampleTime
                    packets++
                    extractor.advance()
                    buffer.clear()
                }
                assertTrue("Audio stream has no packets", packets > 0)
                assertTrue("Audio was not retimed to video duration: $lastSampleUs",
                    abs(lastSampleUs - baked.durationUs) < 150_000L)
            } finally { extractor.release() }
        } finally {
            result?.uri?.let { File(checkNotNull(Uri.parse(it).path)).delete() }
            video.delete()
            audio.delete()
        }
    }
}
