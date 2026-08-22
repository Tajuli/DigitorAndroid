package com.tajuli.digitorandroid.editor.model

import java.util.UUID

const val US_PER_SECOND = 1_000_000L

enum class TrackKind { VIDEO, AUDIO }

data class ColorGrade(
    val redScale: Float = 1f,
    val greenScale: Float = 1f,
    val blueScale: Float = 1f,
    val hueDegrees: Float = 0f,
    val saturationDelta: Float = 0f,
    val lightnessDelta: Float = 0f,
) {
    val isIdentity: Boolean
        get() = redScale == 1f && greenScale == 1f && blueScale == 1f &&
            hueDegrees == 0f && saturationDelta == 0f && lightnessDelta == 0f
}

data class TimelineClip(
    val id: String = UUID.randomUUID().toString(),
    val uri: String,
    val label: String,
    val timelineStartUs: Long,
    val sourceInUs: Long = 0L,
    val sourceOutUs: Long,
    val opacity: Float = 1f,
    val colorGrade: ColorGrade = ColorGrade(),
) {
    val durationUs: Long get() = (sourceOutUs - sourceInUs).coerceAtLeast(1L)
    val timelineEndUs: Long get() = timelineStartUs + durationUs
}

data class TimelineTrack(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val kind: TrackKind,
    val clips: List<TimelineClip> = emptyList(),
    val muted: Boolean = false,
) {
    fun sortedClips(): List<TimelineClip> = clips.sortedBy { it.timelineStartUs }
}

data class TimelineProject(
    val title: String = "Untitled",
    val width: Int = 1920,
    val height: Int = 1080,
    val frameRate: Int = 30,
    val tracks: List<TimelineTrack> = listOf(
        TimelineTrack(name = "V2", kind = TrackKind.VIDEO),
        TimelineTrack(name = "V1", kind = TrackKind.VIDEO),
        TimelineTrack(name = "A1", kind = TrackKind.AUDIO),
    ),
) {
    val durationUs: Long
        get() = tracks.flatMap { it.clips }.maxOfOrNull { it.timelineEndUs } ?: 0L

    fun track(id: String?): TimelineTrack? = tracks.firstOrNull { it.id == id }

    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        require(width > 0 && height > 0 && frameRate > 0)
        tracks.forEach { track ->
            val clips = track.sortedClips()
            clips.zipWithNext().forEach { (a, b) ->
                if (a.timelineEndUs > b.timelineStartUs) {
                    errors += "${track.name}: clips '${a.label}' and '${b.label}' overlap inside one track"
                }
            }
        }
        return errors
    }
}
