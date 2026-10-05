package com.tajuli.digitorandroid.editor.model

import com.google.gson.Gson
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class ClipRetimeTest {
    private fun clip(speed: Float) = TimelineClip(uri = "content://source", label = "test",
        timelineStartUs = 3_000_000L, sourceInUs = 2_000_000L, sourceOutUs = 12_000_000L,
        retime = ClipRetime(SpeedCurveSpec.constant(speed), 2_000_000L, 12_000_000L))

    @Test fun normalSpeedsHaveCorrectDurationAndInverse() {
        for (speed in listOf(.1f, .5f, 1f, 2f, 10f, 100f)) {
            val clip = clip(speed)
            assertTrue(abs(clip.durationUs - (10_000_000.0 / speed).toLong()) <= 1)
            for (i in 0..100) {
                val source = clip.sourceInUs + i * 100_000L
                assertTrue(abs(source - clip.sourceTimeForOutput(clip.outputTimeForSource(source))) <= 101)
            }
        }
    }

    @Test fun curveSplitAndTrimKeepOriginalCurveCoordinates() {
        val clip = clip(1f).copy(retime = ClipRetime(
            SpeedCurveSpec.preset(SpeedCurvePreset.MONTAGE), 2_000_000L, 12_000_000L))
        val splitSource = clip.sourceTimeForOutput(clip.durationUs / 2)
        val left = clip.copy(sourceOutUs = splitSource)
        val right = clip.copy(sourceInUs = splitSource, timelineStartUs = clip.timelineStartUs + left.durationUs)
        assertTrue(abs(clip.durationUs - left.durationUs - right.durationUs) <= 2)
        for (i in 0..100) {
            val local = right.durationUs * i / 100
            assertTrue(abs(clip.sourceTimeForOutput(left.durationUs + local) - right.sourceTimeForOutput(local)) <= 20)
        }
        val extended = clip(.5f).copy(sourceInUs = 0L, sourceOutUs = 14_000_000L)
        assertEquals(28_000_000L, extended.durationUs)
        assertEquals(0L, clip(.5f).sourceTimeForOutputUnbounded(-4_000_000L))
    }

    @Test fun legacyAndNewJsonRoundTrip() {
        val gson = Gson()
        val original = clip(.5f)
        val restored = gson.fromJson(gson.toJson(original), TimelineClip::class.java)
        assertEquals(original.retime, restored.retime)
        assertEquals(gson.toJson(original), gson.toJson(restored))
        assertEquals(original.durationUs, restored.durationUs)
        val legacy = gson.toJsonTree(original).asJsonObject.apply { remove("retime") }
        assertEquals(10_000_000L, gson.fromJson(legacy, TimelineClip::class.java).durationUs)
    }

    @Test fun linkedAudioAndRippleKeepSourcesUntouched() {
        val video = clip(1f).copy(id = "video", linkGroupId = "linked", retime = null)
        val audio = video.copy(id = "audio", uri = "content://original-sound", sourceInUs = 0, sourceOutUs = 10_000_000)
        val next = video.copy(id = "next", linkGroupId = null, timelineStartUs = video.timelineEndUs)
        val project = TimelineProject(tracks = listOf(
            TimelineTrack(name = "V1", kind = TrackKind.VIDEO, clips = listOf(video, next)),
            TimelineTrack(name = "A1", kind = TrackKind.AUDIO, clips = listOf(audio))))
        val changed = project.withClipSpeed("video", SpeedCurveSpec.constant(.5f))
        assertEquals(video.uri, changed.clip("video")!!.uri)
        assertEquals(audio.uri, changed.clip("audio")!!.uri)
        assertEquals(20_000_000L, changed.clip("audio")!!.durationUs)
        assertEquals(changed.clip("video")!!.timelineEndUs, changed.clip("next")!!.timelineStartUs)
        assertEquals(10_000_000L, project.clip("video")!!.durationUs)
        assertEquals(video.sourceInUs, changed.clip("video")!!.sourceInUs)
    }

    @Test fun visibleSegmentUsesRetimeMap() {
        val clip = clip(.5f)
        val fragment = VisibleVideoSegment(clip, 7_000_000L, 11_000_000L).asTimelineClip()
        assertEquals(4_000_000L, fragment.sourceInUs)
        assertEquals(6_000_000L, fragment.sourceOutUs)
        assertEquals(4_000_000L, fragment.durationUs)
    }
}
