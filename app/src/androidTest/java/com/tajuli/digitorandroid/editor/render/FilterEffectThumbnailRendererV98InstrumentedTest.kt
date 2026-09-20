package com.tajuli.digitorandroid.editor.render

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Arrays
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FilterEffectThumbnailRendererV98InstrumentedTest {

    @Test
    fun beforeAfterComposition_keepsExactLeftAndRightHalves() {
        val original = solidBitmap(16, 9, Color.RED)
        val processed = solidBitmap(16, 9, Color.BLUE)

        val output = FilterEffectThumbnailRendererV98.composeBeforeAfterV98(
            original,
            processed,
            failed = false,
        )

        assertEquals(Color.RED, output.getPixel(2, 4))
        assertEquals(Color.BLUE, output.getPixel(13, 4))
        assertEquals(16, output.width)
        assertEquals(9, output.height)
    }

    @Test
    fun renderedThumbnail_isReusedFromMemoryCache() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        FilterEffectThumbnailRendererV98.clearMemoryCacheForTest()
        FilterEffectThumbnailRendererV98.resetStatsForTest()

        val first = FilterEffectThumbnailRendererV98.renderFilter(context, "fresh_lime")
        val missesAfterFirst = FilterEffectThumbnailRendererV98.cacheMissCountForTest()
        val second = FilterEffectThumbnailRendererV98.renderFilter(context, "fresh_lime")

        assertSame(first, second)
        assertEquals(1, missesAfterFirst)
        assertEquals(missesAfterFirst, FilterEffectThumbnailRendererV98.cacheMissCountForTest())
    }

    @Test
    fun samePresetAtFixedTimestamp_isDeterministicAcrossColdRenders() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        FilterEffectThumbnailRendererV98.clearMemoryCacheForTest()
        val first = FilterEffectThumbnailRendererV98.renderEffect(context, "Vivid Missing")
        // The unknown path is intentionally deterministic too, but exercise a real shader below.
        assertTrue(first.width > 0)

        FilterEffectThumbnailRendererV98.clearMemoryCacheForTest()
        val a = FilterEffectThumbnailRendererV98.renderEffect(context, "RGB Split")
        val pixelsA = pixels(a)

        FilterEffectThumbnailRendererV98.clearMemoryCacheForTest()
        val b = FilterEffectThumbnailRendererV98.renderEffect(context, "RGB Split")
        val pixelsB = pixels(b)

        assertArrayEquals(pixelsA, pixelsB)
    }

    @Test
    fun unknownEffect_fallsBackWithoutCrashing() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        FilterEffectThumbnailRendererV98.clearMemoryCacheForTest()
        FilterEffectThumbnailRendererV98.resetStatsForTest()

        val output = FilterEffectThumbnailRendererV98.renderEffect(context, "__missing_effect__")

        assertEquals(320, output.width)
        assertEquals(180, output.height)
        assertEquals(1, FilterEffectThumbnailRendererV98.fallbackCountForTest())
    }

    @Test
    fun identityThumbnail_preservesOriginalOnBothSides() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        FilterEffectThumbnailRendererV98.clearMemoryCacheForTest()
        val base = FilterEffectThumbnailRendererV98.baseThumbnailForTest(context)
        val identity = FilterEffectThumbnailRendererV98.renderIdentity(context)

        val sampleXs = intArrayOf(8, 72, 140, 180, 248, 310)
        val sampleYs = intArrayOf(12, 66, 120, 166)
        sampleYs.forEach { y ->
            sampleXs.forEach { x ->
                assertEquals(base.getPixel(x, y), identity.getPixel(x, y))
            }
        }
    }

    private fun solidBitmap(width: Int, height: Int, color: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun pixels(bitmap: Bitmap): IntArray =
        IntArray(bitmap.width * bitmap.height).also { output ->
            bitmap.getPixels(output, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
}
