package com.tajuli.digitorandroid.editor.processing

import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun singleVideoClipUsesNativeTimelineWithSentinel() {
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
        assertEquals(1, plan!!.videoTrackCount)
        assertEquals(1, plan.videoClipCount)
        assertEquals(0, plan.audioClipCount)
        assertEquals(2, plan.inputs.size)
        assertTrue(plan.inputs.first() is NativeGraphInputPlanV76.Track)
        assertTrue(plan.inputs.last() is NativeGraphInputPlanV76.Blank)
    }

    @Test
    fun activeAudioIsNativeMixdownEligible() {
        val video = clip()
        val audio = clip(id = "a1")
        val project = TimelineProject(
            tracks = listOf(
                TimelineTrack(name = "V1", kind = TrackKind.VIDEO, clips = listOf(video)),
                TimelineTrack(name = "A1", kind = TrackKind.AUDIO, clips = listOf(audio)),
            ),
        )
        val plan = nativeHardwareExportPlanV75(project)
        assertNotNull(plan)
        assertEquals(1, plan!!.audioClipCount)
        assertTrue(project.hasActiveNativeAudioV76())
    }

    @Test
    fun mutedAudioIsIgnoredByNativeMixdown() {
        val video = clip()
        val audio = clip(id = "a1")
        val project = TimelineProject(
            tracks = listOf(
                TimelineTrack(name = "V1", kind = TrackKind.VIDEO, clips = listOf(video)),
                TimelineTrack(name = "A1", kind = TrackKind.AUDIO, clips = listOf(audio), muted = true),
            ),
        )
        val plan = nativeHardwareExportPlanV75(project)
        assertNotNull(plan)
        assertEquals(0, plan!!.audioClipCount)
        assertTrue(!project.hasActiveNativeAudioV76())
    }

    @Test
    fun multipleSequentialVideoClipsUseNativeScheduler() {
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
        val plan = nativeHardwareExportPlanV75(project)
        assertNotNull(plan)
        assertEquals(2, plan!!.videoClipCount)
        assertEquals(2, plan.inputs.size)
    }

    @Test
    fun multipleVideoTracksUseNativeCompositorInputs() {
        val base = clip(id = "base")
        val overlay = clip(id = "overlay", uri = "content://overlay", outUs = 8_000_000L)
        val project = TimelineProject(
            tracks = listOf(
                TimelineTrack(name = "V1", kind = TrackKind.VIDEO, clips = listOf(base)),
                TimelineTrack(name = "V2", kind = TrackKind.VIDEO, clips = listOf(overlay)),
            ),
        )
        val plan = nativeHardwareExportPlanV75(project)
        assertNotNull(plan)
        assertEquals(2, plan!!.videoTrackCount)
        assertEquals(2, plan.videoClipCount)
        assertEquals(3, plan.inputs.size)
        assertEquals(2, plan.inputs.count { it is NativeGraphInputPlanV76.Track })
    }

    @Test
    fun timelineGapUsesNativeBitmapGapScheduler() {
        val delayed = clip(startUs = 1_000_000L, outUs = 5_000_000L)
        val project = TimelineProject(
            tracks = listOf(TimelineTrack(name = "V1", kind = TrackKind.VIDEO, clips = listOf(delayed))),
        )
        val plan = nativeHardwareExportPlanV75(project)
        assertNotNull(plan)
        assertEquals(2, plan!!.inputs.size)
    }

    @Test
    fun invalidOverlappingClipsRemainRejected() {
        val first = clip(id = "v1", outUs = 6_000_000L)
        val second = clip(id = "v2", uri = "content://video2", startUs = 5_000_000L, outUs = 5_000_000L)
        val project = TimelineProject(
            tracks = listOf(TimelineTrack(name = "V1", kind = TrackKind.VIDEO, clips = listOf(first, second))),
        )
        assertNull(nativeHardwareExportPlanV75(project))
    }

    @Test
    fun audioOnlyProjectStillNeedsVisualOutputForMp4EditorExport() {
        val audio = clip(id = "a1")
        val project = TimelineProject(
            tracks = listOf(TimelineTrack(name = "A1", kind = TrackKind.AUDIO, clips = listOf(audio))),
        )
        assertNull(nativeHardwareExportPlanV75(project))
    }
}
