package com.tajuli.digitorandroid.editor.processing

import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NativeHardwareExportV75Test {
    private fun clip(
        id: String = "v1",
        uri: String = "content://video",
        startUs: Long = 0L,
        outUs: Long = 10_000_000L,
    ) = TimelineClip(
        id = id,
        uri = uri,
        label = id,
        timelineStartUs = startUs,
        sourceOutUs = outUs,
    )

    @Test
    fun singleVideoOnlyClipUsesNativeFastPath() {
        val video = clip()
        val project = TimelineProject(
            width = 2560,
            height = 1440,
            frameRate = 30,
            tracks = listOf(
                TimelineTrack(name = "V1", kind = TrackKind.VIDEO, clips = listOf(video)),
                TimelineTrack(name = "A1", kind = TrackKind.AUDIO),
            ),
        )
        val plan = nativeHardwareExportPlanV75(project)
        assertNotNull(plan)
        assertEquals(video.id, plan!!.clip.id)
    }

    @Test
    fun activeAudioKeepsCompatibilityExporterUntilNativeMixerLands() {
        val video = clip()
        val audio = clip(id = "a1")
        val project = TimelineProject(
            tracks = listOf(
                TimelineTrack(name = "V1", kind = TrackKind.VIDEO, clips = listOf(video)),
                TimelineTrack(name = "A1", kind = TrackKind.AUDIO, clips = listOf(audio)),
            ),
        )
        assertNull(nativeHardwareExportPlanV75(project))
    }

    @Test
    fun mutedAudioDoesNotBlockNativeVideoFastPath() {
        val video = clip()
        val audio = clip(id = "a1")
        val project = TimelineProject(
            tracks = listOf(
                TimelineTrack(name = "V1", kind = TrackKind.VIDEO, clips = listOf(video)),
                TimelineTrack(name = "A1", kind = TrackKind.AUDIO, clips = listOf(audio), muted = true),
            ),
        )
        assertNotNull(nativeHardwareExportPlanV75(project))
    }

    @Test
    fun multipleVideoClipsStayOnCompatibilityExporter() {
        val first = clip(id = "v1", outUs = 5_000_000L)
        val second = TimelineClip(
            id = "v2",
            uri = "content://video2",
            label = "v2",
            timelineStartUs = 5_000_000L,
            sourceOutUs = 5_000_000L,
        )
        val project = TimelineProject(
            tracks = listOf(
                TimelineTrack(name = "V1", kind = TrackKind.VIDEO, clips = listOf(first, second)),
            ),
        )
        assertNull(nativeHardwareExportPlanV75(project))
    }

    @Test
    fun timelineGapStaysOnCompatibilityExporter() {
        val delayed = clip(startUs = 1_000_000L, outUs = 5_000_000L)
        val project = TimelineProject(
            tracks = listOf(TimelineTrack(name = "V1", kind = TrackKind.VIDEO, clips = listOf(delayed))),
        )
        assertNull(nativeHardwareExportPlanV75(project))
    }
}
