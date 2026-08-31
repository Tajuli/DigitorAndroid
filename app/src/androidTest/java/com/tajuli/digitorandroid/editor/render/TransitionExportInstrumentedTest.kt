package com.tajuli.digitorandroid.editor.render

import android.graphics.Bitmap
import android.graphics.Color
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
    fun crossDissolveBetweenNativeImagesExportsNonEmptyMp4() {
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
        } finally {
            instrumentation.runOnMainSync {
                transformerRef.get()?.let { transformer -> runCatching { transformer.cancel() } }
            }
            output.delete()
            firstSource.delete()
            secondSource.delete()
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
