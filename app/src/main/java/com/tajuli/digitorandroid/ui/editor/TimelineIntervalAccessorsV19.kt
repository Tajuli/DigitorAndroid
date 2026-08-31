package com.tajuli.digitorandroid.ui.editor

/**
 * Timeline snapping represents lane occupancy as Pair<startUs, endUs> to keep the hot drag path
 * allocation-light and compatible with the existing editor code. Named accessors make the intent
 * explicit when media, text and visual-overlay intervals are merged into one lane.
 */
internal val Pair<Long, Long>.timelineStartUs: Long get() = first
internal val Pair<Long, Long>.timelineEndUs: Long get() = second
