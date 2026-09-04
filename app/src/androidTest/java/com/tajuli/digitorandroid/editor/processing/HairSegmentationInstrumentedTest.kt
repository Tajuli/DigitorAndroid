package com.tajuli.digitorandroid.editor.processing

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineVisualMediaV21
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runtime smoke tests for semantic hair and the GPU temporal matte refinement used by Pro Cutout. */
@RunWith(AndroidJUnit4::class)
class HairSegmentationInstrumentedTest {
    @Test
    fun bundledHairModelProducesCachedSemanticMask() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "hair_segmenter_smoke.png")
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(180, 150, 125))
            for (y in 0 until 92) {
                for (x in 48 until 208) bitmap.setPixel(x, y, Color.rgb(30, 24, 22))
            }
            source.outputStream().use { stream -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) }

            val clip = TimelineClip(
                uri = Uri.fromFile(source).toString(),
                label = source.name,
                timelineStartUs = 0L,
                sourceOutUs = 1_000_000L,
                visualMediaV21 = TimelineVisualMediaV21.IMAGE,
                sourceMimeTypeV21 = "image/png",
            )
            BeautyHairSegmenterV29(context).use { segmenter ->
                val direct = segmenter.segmentSoftMask(bitmap)
                try {
                    assertTrue(direct != null && direct.width > 0 && direct.height > 0)
                } finally {
                    direct?.recycle()
                }
                assertTrue(segmenter.segmentAndStore(context, clip, bitmap, 0L))
            }
            val frame = BeautyHairMaskStoreV29.index(context, clip).nearest(0L)
            assertTrue(frame != null && frame.file.isFile && frame.file.length() > 0L)
        } finally {
            bitmap.recycle()
            source.delete()
        }
    }

    @Test
    fun v47OffscreenGpuFlowAndMatteShadersExecute() {
        val width = 64
        val height = 64
        val sourceA = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val sourceB = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val matteA = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val matteB = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val inA = x in 14..46 && y in 10..54
                    val inB = x in 16..48 && y in 10..54
                    sourceA.setPixel(x, y, if (inA) Color.rgb(60, 80, 135) else Color.rgb(210, 205, 195))
                    sourceB.setPixel(x, y, if (inB) Color.rgb(60, 80, 135) else Color.rgb(210, 205, 195))
                    val edgeA = x == 14 || x == 46 || y == 10 || y == 54
                    val edgeB = x == 16 || x == 48 || y == 10 || y == 54
                    val a = if (inA) if (edgeA) 150 else 255 else 0
                    val b = if (inB) if (edgeB) 150 else 255 else 0
                    matteA.setPixel(x, y, Color.argb(255, a, a, a))
                    matteB.setPixel(x, y, Color.argb(255, b, b, b))
                }
            }

            GpuSpatialFlowTemporalMatteStabilizerV47().use { gpu ->
                val first = gpu.stabilize(
                    source = sourceA,
                    currentMatte = matteA,
                    hairMask = null,
                    sourceTimeUs = 0L,
                    hairStrength = 0f,
                    temporalStrength = .54f,
                )
                val second = gpu.stabilize(
                    source = sourceB,
                    currentMatte = matteB,
                    hairMask = null,
                    sourceTimeUs = 250_000L,
                    hairStrength = 0f,
                    temporalStrength = .54f,
                )
                try {
                    assertEquals(width, first.width)
                    assertEquals(height, first.height)
                    assertEquals(width, second.width)
                    assertEquals(height, second.height)
                    assertTrue(Color.red(second.getPixel(32, 32)) > 200)
                    assertTrue(Color.red(second.getPixel(2, 2)) < 40)
                } finally {
                    first.recycle()
                    second.recycle()
                }
            }
        } finally {
            sourceA.recycle()
            sourceB.recycle()
            matteA.recycle()
            matteB.recycle()
        }
    }
}
