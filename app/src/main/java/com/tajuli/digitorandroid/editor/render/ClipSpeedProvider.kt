package com.tajuli.digitorandroid.editor.render

import androidx.media3.common.C
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import com.tajuli.digitorandroid.editor.model.*

/** Media3 queries the clipped source clock, not composition time. */
@UnstableApi
internal class ClipSpeedProvider(private val clip: TimelineClip) : SpeedProvider {
    private val spec = clip.retime
    private val schedule = spec?.curve?.sampledSchedule((spec.sourceEndUs - spec.sourceStartUs).coerceAtLeast(1L))
    override fun getSpeed(timeUs: Long): Float = clip.speedAtSource(clip.sourceInUs + timeUs)
    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long {
        val origin = spec?.sourceStartUs ?: return C.TIME_UNSET
        val next = schedule?.nextChangeAfter(clip.sourceInUs + timeUs - origin) ?: return C.TIME_UNSET
        val relative = next + origin - clip.sourceInUs
        return if (relative < clip.sourceDurationUs) relative else C.TIME_UNSET
    }
}
