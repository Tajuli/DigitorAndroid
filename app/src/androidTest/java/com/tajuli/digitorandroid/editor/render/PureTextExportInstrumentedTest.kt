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
import com.tajuli.digitorandroid.editor.model.ShapePresetV19
import com.tajuli.digitorandroid.editor.model.TextOverlayClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.VisualOverlayClipV19
import com.tajuli.digitorandroid.editor.model.VisualOverlayKindV19
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runtime regressions for composition overlays without imported video clips. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PureTextExportInstrumentedTest {

    @Test
    fun pureTextTimelineExportsNonEmptyMp4() {
        val project = TimelineProject(
            width = 320,
            height = 240,
            frameRate = 24,
            tracks = listOf(
                TimelineTrack(id = "v1", name = "V1", kind = TrackKind.VIDEO),
            ),
            textOverlays = listOf(
                TextOverlayClip(
                    id = "text-only",
                    text = "Text",
                    timelineStartUs = 0L,
                    timelineEndUs = 1_000_000L,
                    videoTrackIdV3 = "v1",
                ),
            ),
        )

        assertTrue(needsPureTextVideoSourceV18(project))
        exportOverlayOnlyProject(project, "pure_text")
    }

    @Test
    fun pureShapeOverlayTimelineExportsNonEmptyMp4() {
        val project = TimelineProject(
            width = 320,
            height = 240,
            frameRate = 24,
            tracks = listOf(
                TimelineTrack(id = "v1", name = "V1", kind = TrackKind.VIDEO),
            ),
            visualOverlaysV19 = listOf(
                VisualOverlayClipV19(
                    id = "shape-only",
                    kind = VisualOverlayKindV19.SHAPE,
                    label = "Shape",
                    timelineStartUs = 0L,
                    timelineEndUs = 1_000_000L,
                    shapePreset = ShapePresetV19.CIRCLE,
                    colorArgb = 0xFFFFD54FL,
                    scale = .35f,
                    positionX = .2f,
                    positionY = -.1f,
                    rotationDegrees = 25f,
                    opacity = .8f,
                    videoTrackIdV19 = "v1",
                ),
            ),
        )

        assertTrue(needsPureTextVideoSourceV18(project))
        exportOverlayOnlyProject(project, "pure_shape")
    }

    private fun exportOverlayOnlyProject(project: TimelineProject, name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        VisualOverlayRenderEnvironmentV19.install(context)
        val supportDir = File(context.cacheDir, "composition_overlay_export_test").apply { mkdirs() }
        val blank = File(supportDir, "$name-black.png")
        val output = File(supportDir, "$name.mp4")
        if (output.exists()) output.delete()

        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.BLACK)
            blank.outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        } finally {
            bitmap.recycle()
        }

        val composition = Media3CompositionBuilder(Uri.fromFile(blank).toString()).build(project)
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val transformerRef = AtomicReference<Transformer?>(null)

        try {
            // Transformer binds itself to the application looper. Instrumentation tests execute on a
            // worker thread, so construct/start/cancel it on main exactly like app code does.
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

            assertTrue("Timed out waiting for $name export", done.await(30, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("$name export failed", it) }
            assertTrue("$name export produced no bytes", output.exists() && output.length() > 0L)
        } finally {
            instrumentation.runOnMainSync {
                transformerRef.get()?.let { transformer -> runCatching { transformer.cancel() } }
            }
            output.delete()
            blank.delete()
        }
    }
}
