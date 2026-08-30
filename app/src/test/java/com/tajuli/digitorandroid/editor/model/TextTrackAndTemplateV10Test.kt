package com.tajuli.digitorandroid.editor.model

import com.tajuli.digitorandroid.ui.editor.TextTemplateCatalogV10
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextTrackAndTemplateV10Test {
    @Test
    fun textOverlay_resolvesExplicitVideoTrack_andLegacyFallsBackToFirstVideoTrack() {
        val v1 = TimelineTrack(name = "V1", kind = TrackKind.VIDEO)
        val v2 = TimelineTrack(name = "V2", kind = TrackKind.VIDEO)
        val audio = TimelineTrack(name = "A1", kind = TrackKind.AUDIO)
        val explicit = TextOverlayClip(
            text = "V2 title",
            timelineStartUs = 0L,
            timelineEndUs = 2_000_000L,
            videoTrackIdV3 = v2.id,
        )
        val legacy = TextOverlayClip(
            text = "Legacy title",
            timelineStartUs = 0L,
            timelineEndUs = 2_000_000L,
        )
        val project = TimelineProject(tracks = listOf(v1, v2, audio), textOverlays = listOf(explicit, legacy))

        assertEquals(v2.id, explicit.resolvedVideoTrackIdV3(project))
        assertEquals(v1.id, legacy.resolvedVideoTrackIdV3(project))
        assertEquals(listOf(explicit.id), project.textOverlaysForVideoTrackV3(v2.id).map { it.id })
        assertEquals(listOf(legacy.id), project.textOverlaysForVideoTrackV3(v1.id).map { it.id })
    }

    @Test
    fun manualTextKeyframes_interpolateAtPlayhead() {
        val overlay = TextOverlayClip(
            text = "Animate",
            timelineStartUs = 1_000_000L,
            timelineEndUs = 3_000_000L,
            manualAnimationV2 = TextManualAnimationV2(
                listOf(
                    TextTransformKeyframeV2(0L, -1f, .5f, .5f, 0f),
                    TextTransformKeyframeV2(2_000_000L, 1f, -.5f, 1.5f, 1f),
                ),
            ),
        )

        val frame = overlay.textManualFrameV2(2_000_000L)
        assertEquals(0f, frame.positionX, .0001f)
        assertEquals(0f, frame.positionY, .0001f)
        assertEquals(1f, frame.sizeScale, .0001f)
        assertEquals(.5f, frame.alpha, .0001f)
    }

    @Test
    fun capcutStyleCatalog_hasManyPresets_andKeyframedTemplatesNormalize() {
        assertTrue("Expected a broad ready-made catalog", TextTemplateCatalogV10.size >= 24)
        assertTrue(TextTemplateCatalogV10.map { it.category }.distinct().size >= 5)

        val animated = TextTemplateCatalogV10.filter { it.manualKeyframes.isNotEmpty() }
        assertTrue("Expected several manual-keyframe presets", animated.size >= 6)
        animated.forEach { template ->
            val animation = template.manualAnimationFor(3_000_000L)
            requireNotNull(animation)
            assertTrue(animation.keyframes.isNotEmpty())
            assertTrue(animation.keyframes.zipWithNext().all { (a, b) -> a.localUs <= b.localUs })
            assertTrue(animation.keyframes.all { it.localUs in 0L..3_000_000L })
        }
    }
}
