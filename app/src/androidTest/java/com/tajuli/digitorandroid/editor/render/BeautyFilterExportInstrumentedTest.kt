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
import com.tajuli.digitorandroid.editor.model.BEAUTY_EYE_POP_V28
import com.tajuli.digitorandroid.editor.model.BEAUTY_HAIR_BROW_DARK_V28
import com.tajuli.digitorandroid.editor.model.BEAUTY_PINK_LIP_V28
import com.tajuli.digitorandroid.editor.model.BEAUTY_SKIN_BRIGHT_V28
import com.tajuli.digitorandroid.editor.model.BeautyFaceGeometryV28
import com.tajuli.digitorandroid.editor.model.BeautyFaceSampleV28
import com.tajuli.digitorandroid.editor.model.BeautyFaceTrackV28
import com.tajuli.digitorandroid.editor.model.BeautyRectV28
import com.tajuli.digitorandroid.editor.model.ClipNodeGraph
import com.tajuli.digitorandroid.editor.model.NodeEffect
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TimelineVisualMediaV21
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.creatorFilterMarkerNameV36
import com.tajuli.digitorandroid.editor.processing.BeautyFaceTrackStoreV28
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Executes the real semantic beauty GPU stages plus the V37 full-frame reference LUT stage.
 *
 * Keeping the calibrated look marker in this already-CI-enumerated test means every pixel-parity run
 * actually compiles, uploads and samples the GLES2 17^3 LUT atlas instead of merely compiling Kotlin.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class BeautyFilterExportInstrumentedTest {
    @Test
    fun stackedBeautyAndReferenceLookExportAndChangePixels() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val dir = File(context.cacheDir, "beauty_filter_export_test").apply { mkdirs() }
        val source = File(dir, "portrait.png")
        val output = File(dir, "beauty.mp4")
        output.delete()

        val sourceRed = 92
        val sourceGreen = 64
        val sourceBlue = 54
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(sourceRed, sourceGreen, sourceBlue))
            source.outputStream().use { stream -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
        } finally {
            bitmap.recycle()
        }

        val baseGraph = ClipNodeGraph.default()
        val selectedNodeId = baseGraph.selectedNodeId!!
        val beautyGraph = baseGraph.copy(
            nodes = baseGraph.nodes.map { node ->
                if (node.id != selectedNodeId) node else node.copy(
                    effects = listOf(
                        // V37 reference LOOK: full-frame LUT stage, no face/skin semantics.
                        NodeEffect(name = creatorFilterMarkerNameV36("moody_cinema"), amount = 1f),
                        // BEAUTY remains the only semantic/spatial portion of this stack.
                        NodeEffect(name = BEAUTY_SKIN_BRIGHT_V28, amount = .75f),
                        NodeEffect(name = BEAUTY_PINK_LIP_V28, amount = .55f),
                        NodeEffect(name = BEAUTY_HAIR_BROW_DARK_V28, amount = .50f),
                        NodeEffect(name = BEAUTY_EYE_POP_V28, amount = .45f),
                    ),
                )
            },
        )
        val clip = TimelineClip(
            id = "beauty-image",
            uri = Uri.fromFile(source).toString(),
            label = "portrait.png",
            timelineStartUs = 0L,
            sourceInUs = 0L,
            sourceOutUs = 1_000_000L,
            nodeGraph = beautyGraph,
            visualMediaV21 = TimelineVisualMediaV21.IMAGE,
            sourceMimeTypeV21 = "image/png",
        )
        val geometry = BeautyFaceGeometryV28(
            face = BeautyRectV28(.24f, .18f, .76f, .80f),
            lips = BeautyRectV28(.40f, .58f, .60f, .68f),
            leftEye = BeautyRectV28(.31f, .38f, .45f, .47f),
            rightEye = BeautyRectV28(.55f, .38f, .69f, .47f),
            leftBrow = BeautyRectV28(.30f, .31f, .46f, .37f),
            rightBrow = BeautyRectV28(.54f, .31f, .70f, .37f),
            hair = BeautyRectV28(.18f, .02f, .82f, .36f),
        )
        BeautyFaceTrackStoreV28.save(
            context,
            BeautyFaceTrackV28(
                sourceUri = clip.uri,
                analyzedStartUs = 0L,
                analyzedEndUs = 1_000_000L,
                samples = listOf(
                    BeautyFaceSampleV28(0L, geometry),
                    BeautyFaceSampleV28(1_000_000L, geometry),
                ),
            ),
        )
        val project = TimelineProject(
            width = 320,
            height = 320,
            frameRate = 24,
            tracks = listOf(TimelineTrack(id = "v1", name = "V1", kind = TrackKind.VIDEO, clips = listOf(clip))),
        )
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

            assertTrue("Timed out waiting for beauty/look export", done.await(30, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Beauty/look export failed", it) }
            assertTrue("Beauty/look export produced no bytes", output.exists() && output.length() > 0L)

            val retriever = MediaMetadataRetriever()
            val exportedFrame = try {
                retriever.setDataSource(output.absolutePath)
                retriever.getFrameAtTime(500_000L, MediaMetadataRetriever.OPTION_CLOSEST)
            } finally {
                runCatching { retriever.release() }
            }
            assertNotNull("Could not decode exported beauty/look frame", exportedFrame)
            val frame = exportedFrame!!
            try {
                val center = frame.getPixel(frame.width / 2, frame.height / 2)
                val delta = abs(Color.red(center) - sourceRed) +
                    abs(Color.green(center) - sourceGreen) +
                    abs(Color.blue(center) - sourceBlue)
                assertTrue(
                    "V37 LOOK + beauty stack was visually identity; RGB=${Color.red(center)},${Color.green(center)},${Color.blue(center)}",
                    delta >= 18,
                )
            } finally {
                frame.recycle()
            }
        } finally {
            instrumentation.runOnMainSync {
                transformerRef.get()?.let { transformer -> runCatching { transformer.cancel() } }
            }
            output.delete()
            source.delete()
        }
    }
}
