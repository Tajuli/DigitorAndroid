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
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TimelineVisualMediaV21
import com.tajuli.digitorandroid.editor.model.TrackKind
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runtime regression for a native V-track still image going through the real export router. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class NativeImageExportInstrumentedTest {

    @Test
    fun nativeImageTimelineExportsNonEmptyMp4() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val supportDir = File(context.cacheDir, "native_image_export_test").apply { mkdirs() }
        val source = File(supportDir, "source.png")
        val output = File(supportDir, "native_image.mp4")
        if (output.exists()) output.delete()

        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(42, 130, 210))
            source.outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        } finally {
            bitmap.recycle()
        }

        val clip = TimelineClip(
            id = "native-image",
            uri = Uri.fromFile(source).toString(),
            label = "source.png",
            timelineStartUs = 0L,
            sourceInUs = 0L,
            sourceOutUs = 1_000_000L,
            visualMediaV21 = TimelineVisualMediaV21.IMAGE,
            sourceMimeTypeV21 = "image/png",
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
                    clips = listOf(clip),
                ),
            ),
        )

        assertTrue(shouldUseStableSingleInputExportV17(project))

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

            assertTrue("Timed out waiting for native image export", done.await(30, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Native image export failed", it) }
            assertTrue("Native image export produced no bytes", output.exists() && output.length() > 0L)
        } finally {
            instrumentation.runOnMainSync {
                transformerRef.get()?.let { transformer -> runCatching { transformer.cancel() } }
            }
            output.delete()
            source.delete()
        }
    }
}
