package com.tajuli.digitorandroid.editor.model

import java.util.UUID

/** Extendable transition metadata. V1 intentionally implements compositor-native fades only. */
data class ClipTransition(
    val fadeInUs: Long = 0L,
    val fadeOutUs: Long = 0L,
) {
    val isIdentity: Boolean get() = fadeInUs <= 0L && fadeOutUs <= 0L

    fun normalizedFor(durationUs: Long): ClipTransition {
        val safeDuration = durationUs.coerceAtLeast(1L)
        val maxEdge = safeDuration / 2L
        return copy(
            fadeInUs = fadeInUs.coerceIn(0L, maxEdge),
            fadeOutUs = fadeOutUs.coerceIn(0L, maxEdge),
        )
    }
}

/** Per-audio-clip mix controls shared by realtime preview and export. */
data class AudioMix(
    val volume: Float = 1f,
    val fadeInUs: Long = 0L,
    val fadeOutUs: Long = 0L,
) {
    fun normalizedFor(durationUs: Long): AudioMix {
        val safeDuration = durationUs.coerceAtLeast(1L)
        return copy(
            volume = volume.coerceIn(0f, 1f),
            fadeInUs = fadeInUs.coerceIn(0L, safeDuration),
            fadeOutUs = fadeOutUs.coerceIn(0L, safeDuration),
        )
    }
}

/** Project-level text/caption layer rendered after video composition. */
data class TextOverlayClip(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val timelineStartUs: Long,
    val timelineEndUs: Long,
    /** Normalized project coordinates. +X is right, +Y is down. */
    val positionX: Float = 0f,
    val positionY: Float = 0f,
    val sizeScale: Float = 1f,
    val argb: Long = 0xFFFFFFFFL,
    val bold: Boolean = true,
    val background: Boolean = false,
    /**
     * V2 fields stay nullable on purpose. Gson gives missing reference fields null when an older
     * project is opened, so legacy projects keep working without a custom migration adapter.
     */
    val styleV2: TextStyleV2? = null,
    val entryAnimationV2: TextAnimationSpecV2? = null,
    val exitAnimationV2: TextAnimationSpecV2? = null,
    /** DaVinci-style playhead keyframes for position, size, opacity and rotation. */
    val manualAnimationV2: TextManualAnimationV2? = null,
    /**
     * V3 Resolve-style lane binding. A title is a free timeline item on a real VIDEO track
     * (V1/V2/V3...) and occupies that track's time slot just like a normal video clip.
     * Nullable preserves old saved projects; legacy text resolves to V1 when it exists.
     */
    val videoTrackIdV3: String? = null,
) {
    val durationUs: Long get() = (timelineEndUs - timelineStartUs).coerceAtLeast(1L)
    fun activeAt(timeUs: Long): Boolean = timeUs in timelineStartUs until timelineEndUs
}

fun TextOverlayClip.resolvedVideoTrackIdV3(project: TimelineProject): String? =
    videoTrackIdV3?.takeIf { id -> project.track(id)?.kind == TrackKind.VIDEO }
        ?: project.tracks.firstOrNull { it.kind == TrackKind.VIDEO && it.name == "V1" }?.id
        ?: project.tracks.lastOrNull { it.kind == TrackKind.VIDEO }?.id

fun TimelineProject.textOverlaysForVideoTrackV3(trackId: String): List<TextOverlayClip> =
    textOverlays.filter { it.resolvedVideoTrackIdV3(this) == trackId }

/**
 * Resolve-style occupancy rule: media, title and visual-overlay items share one V-track lane, so
 * two timeline items may not overlap on the same V track. Upper V tracks may still overlap lower
 * V tracks freely for normal compositing.
 */
fun TimelineProject.videoTrackSlotAvailableV3(
    trackId: String,
    startUs: Long,
    endUs: Long,
    ignoreTextId: String? = null,
): Boolean {
    val track = track(trackId)?.takeIf { it.kind == TrackKind.VIDEO } ?: return false
    val safeStart = startUs.coerceAtLeast(0L)
    val safeEnd = endUs.coerceAtLeast(safeStart + 1L)
    fun overlaps(otherStartUs: Long, otherEndUs: Long): Boolean =
        safeStart < otherEndUs && safeEnd > otherStartUs

    if (track.clips.any { overlaps(it.timelineStartUs, it.timelineEndUs) }) return false
    if (visualOverlaysForVideoTrackV19(trackId).any { overlaps(it.timelineStartUs, it.timelineEndUs) }) return false
    return textOverlaysForVideoTrackV3(trackId)
        .filterNot { it.id == ignoreTextId }
        .none { overlaps(it.timelineStartUs, it.timelineEndUs) }
}

fun TimelineProject.activeTextOverlaysAt(timeUs: Long): List<TextOverlayClip> =
    textOverlays.filter { it.activeAt(timeUs) }

fun TimelineProject.audioSelection(selectedClipId: String?, selectedClipIds: Set<String>): List<TimelineClip> {
    val explicit = selectedClipIds.mapNotNull(::clip).filter { candidate ->
        trackContaining(candidate.id)?.kind == TrackKind.AUDIO
    }
    if (explicit.isNotEmpty()) return explicit

    val primary = clip(selectedClipId) ?: return emptyList()
    if (trackContaining(primary.id)?.kind == TrackKind.AUDIO) return listOf(primary)
    val group = primary.linkGroupId ?: return emptyList()
    return tracks
        .filter { it.kind == TrackKind.AUDIO }
        .flatMap { it.clips }
        .filter { it.linkGroupId == group }
}