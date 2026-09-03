package com.tajuli.digitorandroid.editor.render

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
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
import com.tajuli.digitorandroid.editor.processing.ExportQuality
import com.tajuli.digitorandroid.editor.processing.GpuExportBackend
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runtime regressions for native V-track still images going through the real export paths. */
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

        val project = singleImageProject(Uri.fromFile(source).toString())
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

    @Test
    fun galleryContentUriImageExportsThroughGpuBackend() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val sourceUri = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "digitor_native_image_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            },
        ) ?: error("Could not create MediaStore image for export regression")
        val output = File(context.cacheDir, "gallery_content_image_export.mp4")
        if (output.exists()) output.delete()

        try {
            val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.rgb(195, 72, 64))
                resolver.openOutputStream(sourceUri, "w")?.use { stream ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                } ?: error("Could not write MediaStore image")
            } finally {
                bitmap.recycle()
            }

            val project = singleImageProject(sourceUri.toString())
            runBlocking {
                withTimeout(45_000L) {
                    GpuExportBackend(context).export(project, output, ExportQuality.MEDIUM) { }
                }
            }
            assertTrue("Gallery content URI image export produced no bytes", output.exists() && output.length() > 0L)
        } finally {
            output.delete()
            runCatching { resolver.delete(sourceUri, null, null) }
        }
    }

    private fun singleImageProject(uri: String): TimelineProject {
        val clip = TimelineClip(
            id = "native-image",
            uri = uri,
            label = "source.png",
            timelineStartUs = 0L,
            sourceInUs = 0L,
            sourceOutUs = 1_000_000L,
            visualMediaV21 = TimelineVisualMediaV21.IMAGE,
            sourceMimeTypeV21 = "image/png",
        )
        return TimelineProject(
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
    }
}
