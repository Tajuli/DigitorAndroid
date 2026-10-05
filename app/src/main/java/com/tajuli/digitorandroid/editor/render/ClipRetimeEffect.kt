package com.tajuli.digitorandroid.editor.render

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.TimestampAdjustment
import com.tajuli.digitorandroid.editor.model.*
import com.tajuli.digitorandroid.editor.preview.PreviewProjectRegistry

/** Native decoder surfaces carry source PTS plus the stream's fixed composition offset. */
@Suppress("DEPRECATION")
@UnstableApi
internal fun nativeRetimeEffects(clip: TimelineClip, live: Boolean): List<Effect> = listOf(
    TimestampAdjustment({ inputUs, consumer ->
        val current = if (live) PreviewProjectRegistry.clip(clip.id) ?: clip else clip
        val sourceUs = inputUs - clip.timelineStartUs + clip.sourceInUs
        consumer.onTimestamp(current.timelineTimeAtSource(sourceUs))
    }, ClipSpeedProvider(clip)),
)
