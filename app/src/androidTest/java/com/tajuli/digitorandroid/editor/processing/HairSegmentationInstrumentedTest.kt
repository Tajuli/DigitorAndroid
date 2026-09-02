package com.tajuli.digitorandroid.editor.processing

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineVisualMediaV21
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runtime smoke test for the bundled dedicated MediaPipe HairSegmenter model. */
@RunWith(AndroidJUnit4::class)
class HairSegmentationInstrumentedTest {
    @Test
    fun bundledHairModelProducesCachedSemanticMask() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "hair_segmenter_smoke.png")
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(180, 150, 125))
            // Add a dark upper region so the input vaguely resembles a portrait; the smoke test only
            // requires model execution and mask persistence, not a particular semantic prediction.
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
                assertTrue(segmenter.segmentAndStore(context, clip, bitmap, 0L))
            }
            val frame = BeautyHairMaskStoreV29.index(context, clip).nearest(0L)
            assertTrue(frame != null && frame.file.isFile && frame.file.length() > 0L)
        } finally {
            bitmap.recycle()
            source.delete()
        }
    }
}
