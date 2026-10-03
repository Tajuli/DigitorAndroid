package com.tajuli.digitorandroid.editor.processing

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real codec -> SurfaceTexture -> GL -> Bitmap -> getPixels contract. */
@RunWith(AndroidJUnit4::class)
class TrackingFrameDecoderInstrumentedTest {
    @Test fun trackingFramesKeepColorOrientationAndNormalizedPositions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File.createTempFile("tracking-colors", ".mp4", context.cacheDir)
        source.writeBytes(Base64.decode(QUADRANTS, Base64.DEFAULT))
        try {
            for (rotation in listOf(0, 90, 180, 270)) {
                val rotated = File.createTempFile("tracking-rotation", ".mp4", context.cacheDir)
                try {
                    remux(source, rotated, rotation)
                    for (longEdge in listOf(128, 64)) {
                        var frames = 0
                        var previousUs = -1L
                        GpuSequentialCutoutDecoderV47(context, longEdge).decodeTargets(
                            Uri.fromFile(rotated), 0L, 500_000L, emptyList(), true,
                        ) { pts, bitmap ->
                            try {
                                assertTrue("Monotonic source PTS", pts > previousUs)
                                previousUs = pts
                                val portrait = rotation == 90 || rotation == 270
                                assertEquals(if (portrait) longEdge * 3 / 4 else longEdge, bitmap.width)
                                assertEquals(if (portrait) longEdge else longEdge * 3 / 4, bitmap.height)
                                // Order is TL, TR, BL, BR after clockwise metadata rotation.
                                val expected = when (rotation) {
                                    90 -> intArrayOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN)
                                    180 -> intArrayOf(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED)
                                    270 -> intArrayOf(Color.GREEN, Color.YELLOW, Color.RED, Color.BLUE)
                                    else -> intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
                                }
                                for (i in expected.indices) {
                                    val x = bitmap.width * (if (i % 2 == 0) 1 else 3) / 4
                                    val y = bitmap.height * (if (i < 2) 1 else 3) / 4
                                    assertColor(expected[i], bitmap.getPixel(x, y), "rotation=$rotation edge=$longEdge quadrant=$i")
                                }
                                frames++
                            } finally { bitmap.recycle() }
                        }
                        assertEquals("Every encoded frame is analyzed", 5, frames)
                    }
                } finally { rotated.delete() }
            }
        } finally { source.delete() }
    }

    @Test fun rawRgbaBufferIsNotAnArgbIntBuffer() {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(byteArrayOf(230.toByte(), 40, 15, 255.toByte())))
            assertEquals(Color.rgb(230, 40, 15), bitmap.getPixel(0, 0))
        } finally { bitmap.recycle() }
    }

    private fun assertColor(expected: Int, actual: Int, label: String) {
        // Allow codec YUV conversion, but not swapped channels or misplaced quadrants.
        for (shift in listOf(16, 8, 0)) {
            assertTrue(label, kotlin.math.abs(((expected shr shift) and 255) - ((actual shr shift) and 255)) < 45)
        }
    }

    private fun remux(source: File, destination: File, rotation: Int) {
        val extractor = MediaExtractor()
        val muxer = MediaMuxer(destination.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            extractor.setDataSource(source.path)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            extractor.selectTrack(track)
            muxer.setOrientationHint(rotation)
            val outputTrack = muxer.addTrack(extractor.getTrackFormat(track))
            muxer.start()
            val buffer = ByteBuffer.allocateDirect(64 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
                muxer.writeSampleData(outputTrack, buffer, info)
                extractor.advance()
            }
            muxer.stop()
        } finally { extractor.release(); muxer.release() }
    }

    private companion object {
        // Generated 128x96 H.264 fixture: red/green/blue/yellow quadrants, five 10 fps frames.
        // Embedded to avoid downloads and avoid packaging test video in the production APK.
        const val QUADRANTS = """
AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAAN2bW9vdgAAAGxtdmhkAAAAAAAAAAAAAAAAAAAD6AAAAfQAAQAAAQAA
AAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAA
AqB0cmFrAAAAXHRraGQAAAADAAAAAAAAAAAAAAABAAAAAAAAAfQAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAA
AAAAAAAAAAAAAABAAAAAAIAAAABgAAAAAAAkZWR0cwAAABxlbHN0AAAAAAAAAAEAAAH0AAAIAAABAAAAAAIYbWRpYQAAACBtZGhk
AAAAAAAAAAAAAAAAAAAoAAAAFABVxAAAAAAALWhkbHIAAAAAAAAAAHZpZGUAAAAAAAAAAAAAAABWaWRlb0hhbmRsZXIAAAABw21p
bmYAAAAUdm1oZAAAAAEAAAAAAAAAAAAAACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAAAAx1cmwgAAAAAQAAAYNzdGJsAAAAv3N0c2QA
AAAAAAAAAQAAAK9hdmMxAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAIAAYABIAAAASAAAAAAAAAABFUxhdmM2MC4zMS4xMDIgbGli
eDI2NAAAAAAAAAAAAAAAGP//AAAANWF2Y0MBZAAK/+EAGGdkAAqs2UINsBEAAAMAAQAAAwAUDxIllgEABmjr48siwP34+AAAAAAQ
cGFzcAAAAAEAAAABAAAAFGJ0cnQAAAAAAAA0QAAANEAAAAAYc3R0cwAAAAAAAAABAAAABQAABAAAAAAUc3RzcwAAAAAAAAABAAAA
AQAAADhjdHRzAAAAAAAAAAUAAAABAAAIAAAAAAEAABQAAAAAAQAACAAAAAABAAAAAAAAAAEAAAQAAAAAHHN0c2MAAAAAAAAAAQAA
AAEAAAAFAAAAAQAAAChzdHN6AAAAAAAAAAAAAAAFAAADDgAAAA8AAAANAAAADQAAAA0AAAAUc3RjbwAAAAAAAAABAAADpgAAAGJ1
ZHRhAAAAWm1ldGEAAAAAAAAAIWhkbHIAAAAAAAAAAG1kaXJhcHBsAAAAAAAAAAAAAAAALWlsc3QAAAAlqXRvbwAAAB1kYXRhAAAA
AQAAAABMYXZmNjAuMTYuMTAwAAAACGZyZWUAAANMbWRhdAAAAq4GBf//qtxF6b3m2Ui3lizYINkj7u94MjY0IC0gY29yZSAxNjQg
cjMxMDggMzFlMTlmOSAtIEguMjY0L01QRUctNCBBVkMgY29kZWMgLSBDb3B5bGVmdCAyMDAzLTIwMjMgLSBodHRwOi8vd3d3LnZp
ZGVvbGFuLm9yZy94MjY0Lmh0bWwgLSBvcHRpb25zOiBjYWJhYz0xIHJlZj0zIGRlYmxvY2s9MTowOjAgYW5hbHlzZT0weDM6MHgx
MTMgbWU9aGV4IHN1Ym1lPTcgcHN5PTEgcHN5X3JkPTEuMDA6MC4wMCBtaXhlZF9yZWY9MSBtZV9yYW5nZT0xNiBjaHJvbWFfbWU9
MSB0cmVsbGlzPTEgOHg4ZGN0PTEgY3FtPTAgZGVhZHpvbmU9MjEsMTEgZmFzdF9wc2tpcD0xIGNocm9tYV9xcF9vZmZzZXQ9LTIg
dGhyZWFkcz0zIGxvb2thaGVhZF90aHJlYWRzPTEgc2xpY2VkX3RocmVhZHM9MCBucj0wIGRlY2ltYXRlPTEgaW50ZXJsYWNlZD0w
IGJsdXJheV9jb21wYXQ9MCBjb25zdHJhaW5lZF9pbnRyYT0wIGJmcmFtZXM9MyBiX3B5cmFtaWQ9MiBiX2FkYXB0PTEgYl9iaWFz
PTAgZGlyZWN0PTEgd2VpZ2h0Yj0xIG9wZW5fZ29wPTAgd2VpZ2h0cD0yIGtleWludD0yNTAga2V5aW50X21pbj0xMCBzY2VuZWN1
dD00MCBpbnRyYV9yZWZyZXNoPTAgcmNfbG9va2FoZWFkPTQwIHJjPWNyZiBtYnRyZWU9MSBjcmY9MjMuMCBxY29tcD0wLjYwIHFw
bWluPTAgcXBtYXg9NjkgcXBzdGVwPTQgaXBfcmF0aW89MS40MCBhcT0xOjEuMDAAgAAAAFhliIQAEP/+5sD5llUNV3/9aT0r+M0i
5SXijzUkdJ+CaeCN6v/LyLP4xrWSzovQsIUqNQg5ipx4HMRuOhbWmmpX4eHGjD0FvuHFosyMVTliwT6tGP8mPhUhAAAAC0GaJGxD
f/6nhAOmAAAACUGeQniHfwCOgQAAAAkBnmF0Q38AzIAAAAAJAZ5jakN/AMyB
        """
    }
}
