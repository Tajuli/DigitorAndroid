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
import com.tajuli.digitorandroid.editor.model.TextOverlayClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runtime regression for a project containing a title but no imported video clips. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PureTextExportInstrumentedTest {

    @Test
    fun pureTextTimelineExportsNonEmptyMp4() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val supportDir = File(context.cacheDir, "pure_text_export_test").apply { mkdirs() }
        val blank = File(supportDir, "black.png")
        val output = File(supportDir, "pure_text.mp4")
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
        val composition = Media3CompositionBuilder(Uri.fromFile(blank).toString()).build(project)
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
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

        try {
            transformer.start(composition, output.absolutePath)
            assertTrue("Timed out waiting for pure-text export", done.await(30, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Pure-text export failed", it) }
            assertTrue("Pure-text export produced no bytes", output.exists() && output.length() > 0L)
        } finally {
            runCatching { transformer.cancel() }
            output.delete()
            blank.delete()
        }
    }
}
