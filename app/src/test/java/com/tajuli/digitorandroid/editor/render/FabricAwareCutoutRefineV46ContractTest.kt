package com.tajuli.digitorandroid.editor.render

import com.tajuli.digitorandroid.editor.model.ClipCutoutV43
import com.tajuli.digitorandroid.editor.model.CutoutModeV43
import com.tajuli.digitorandroid.editor.model.TimelineClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FabricAwareCutoutRefineV46ContractTest {
    private fun clip(mode: CutoutModeV43): TimelineClip = TimelineClip(
        uri = "content://sample/video",
        label = "sample",
        timelineStartUs = 0L,
        sourceOutUs = 1_000_000L,
        cutoutV43 = ClipCutoutV43(mode = mode),
    )

    @Test
    fun personPipelineGetsFabricRefineImmediatelyAfterCutout() {
        val effects = SharedVideoPipeline.compositedExportEffectsFor(clip(CutoutModeV43.PERSON))
        val names = effects.map { it.javaClass.simpleName }
        val cutoutIndex = names.indexOf("CutoutEffectV43")
        val fabricIndex = names.indexOf("FabricAwareCutoutRefineV46")

        assertTrue(cutoutIndex >= 0)
        assertEquals(cutoutIndex + 1, fabricIndex)
    }

    @Test
    fun chromaKeyDoesNotReceivePortraitFabricRefine() {
        val effects = SharedVideoPipeline.compositedExportEffectsFor(clip(CutoutModeV43.CHROMA_KEY))
        assertTrue(effects.none { it.javaClass.simpleName == "FabricAwareCutoutRefineV46" })
    }
}
