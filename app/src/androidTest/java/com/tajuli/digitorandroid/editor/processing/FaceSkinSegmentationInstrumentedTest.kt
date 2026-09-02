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

/** Runtime smoke test for the bundled MediaPipe SelfieMulticlass face-skin model. */
@RunWith(AndroidJUnit4::class)
class FaceSkinSegmentationInstrumentedTest {
    @Test
    fun bundledMulticlassModelProducesCachedFaceSkinMask() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "face_skin_segmenter_smoke.png")
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(225, 225, 225))
            for (y in 48 until 210) {
                for (x in 72 until 184) bitmap.setPixel(x, y, Color.rgb(174, 126, 101))
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
            BeautyFaceSkinSegmenterV31(context).use { segmenter ->
                assertTrue(segmenter.segmentAndStore(context, clip, bitmap, 0L))
            }
            val frame = BeautyFaceSkinMaskStoreV31.index(context, clip).nearest(0L)
            assertTrue(frame != null && frame.file.isFile && frame.file.length() > 0L)
        } finally {
            bitmap.recycle()
            source.delete()
        }
    }
}
