package com.tajuli.digitorandroid.editor.render

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tajuli.digitorandroid.editor.model.ClipTransition
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TimelineVisualMediaV21
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.TransitionStyleV22
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runtime regression for a real two-source transition going through Media3 compositor export. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class TransitionExportInstrumentedTest {

    @Test
    fun crossDissolveBetweenNativeImagesExportsVisibleBlend() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val supportDir = File(context.cacheDir, "transition_export_test").apply { mkdirs() }
        val firstSource = File(supportDir, "first.png")
        val secondSource = File(supportDir, "second.png")
        val output = File(supportDir, "cross_dissolve.mp4")
        if (output.exists()) output.delete()

        writeSolidPng(firstSource, Color.rgb(220, 45, 50))
        writeSolidPng(secondSource, Color.rgb(35, 95, 225))

        val first = imageClip(
            id = "first",
            file = firstSource,
            startUs = 0L,
            durationUs = 1_000_000L,
        )
        val second = imageClip(
            id = "second",
            file = secondSource,
            startUs = 1_000_000L,
            durationUs = 1_000_000L,
        ).copy(
            transition = ClipTransition(
                styleV22 = TransitionStyleV22.CROSS_DISSOLVE,
                durationUsV22 = 500_000L,
            ),
        )
        val project = TimelineProject(
            width = 320,
            height = 240,
            frameRate = 24,
            tracks = listOf(
                TimelineTrack(
                    id = "v1",
                    name = "V1",
                    kind = TrackKind.VIDEO,
                    clips = listOf(first, second),
                ),
            ),
        )

        assertFalse(shouldUseStableSingleInputExportV17(project))
        val composition = StableGpuExportCompositionBuilder().build(project)
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val transformerRef = AtomicReference<Transformer?>(null)

        try {
            instrumentation.runOnMainSync {
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            done.countDown()
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            failure.set(exportException)
                            done.countDown()
                        }
                    })
                    .build()
                transformerRef.set(transformer)
                transformer.start(composition, output.absolutePath)
            }

            assertTrue("Timed out waiting for transition export", done.await(30, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Transition export failed", it) }
            assertTrue("Transition export produced no bytes", output.exists() && output.length() > 0L)
            assertMidpointIsVisibleBlend(output)
            assertVideoCoversWholeProject(output, project.durationUs)
            assertTailReturnsToIncomingClip(output)
        } finally {
            instrumentation.runOnMainSync {
                transformerRef.get()?.let { transformer -> runCatching { transformer.cancel() } }
            }
            output.delete()
            firstSource.delete()
            secondSource.delete()
        }
    }

    private fun assertMidpointIsVisibleBlend(output: File) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(output.absolutePath)
            val frame = retriever.getFrameAtTime(
                1_250_000L,
                MediaMetadataRetriever.OPTION_CLOSEST,
            ) ?: throw AssertionError("Could not decode exported transition midpoint")
            try {
                val pixel = frame.getPixel(frame.width / 2, frame.height / 2)
                val red = Color.red(pixel)
                val green = Color.green(pixel)
                val blue = Color.blue(pixel)

                // Pure first source is roughly (220,45,50), pure second roughly (35,95,225).
                // A half cross-dissolve must contain substantial red and blue at the same pixel.
                assertTrue("Midpoint is not blended: r=$red g=$green b=$blue", red in 75..190)
                assertTrue("Midpoint is not blended: r=$red g=$green b=$blue", blue in 85..200)
                assertTrue("Midpoint stayed too close to first source", blue > 85)
                assertTrue("Midpoint stayed too close to second source", red > 75)
            } finally {
                frame.recycle()
            }
        } finally {
            retriever.release()
        }
    }

    private fun assertVideoCoversWholeProject(output: File, projectDurationUs: Long) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(output.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: throw AssertionError("Exported video has no duration metadata")
            val expectedMs = projectDurationUs / 1000L
            assertTrue(
                "Transition compositor truncated video: duration=${durationMs}ms expected≈${expectedMs}ms",
                durationMs >= expectedMs - 100L,
            )
        } finally {
            retriever.release()
        }
    }

    private fun assertTailReturnsToIncomingClip(output: File) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(output.absolutePath)
            val frame = retriever.getFrameAtTime(
                1_800_000L,
                MediaMetadataRetriever.OPTION_CLOSEST,
            ) ?: throw AssertionError("Could not decode frame after transition")
            try {
                val pixel = frame.getPixel(frame.width / 2, frame.height / 2)
                val red = Color.red(pixel)
                val blue = Color.blue(pixel)
                assertTrue("Tail did not return to incoming clip: r=$red b=$blue", blue > 150)
                assertTrue("Outgoing frame leaked after transition: r=$red b=$blue", red < 100)
            } finally {
                frame.recycle()
            }
        } finally {
            retriever.release()
        }
    }

    private fun imageClip(
        id: String,
        file: File,
        startUs: Long,
        durationUs: Long,
    ): TimelineClip = TimelineClip(
        id = id,
        uri = Uri.fromFile(file).toString(),
        label = file.name,
        timelineStartUs = startUs,
        sourceInUs = 0L,
        sourceOutUs = durationUs,
        visualMediaV21 = TimelineVisualMediaV21.IMAGE,
        sourceMimeTypeV21 = "image/png",
    )

    private fun writeSolidPng(file: File, color: Int) {
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            file.outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
