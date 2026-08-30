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
    /** DaVinci-style playhead keyframes for position, size and opacity. */
    val manualAnimationV2: TextManualAnimationV2? = null,
    /**
     * V3 Resolve-style lane binding. Titles live on a real VIDEO track (V1/V2/V3...) instead of
     * a synthetic T1 lane. Nullable preserves old saved projects; legacy text resolves to V1/top
     * available video track until the user explicitly moves it.
     */
    val videoTrackIdV3: String? = null,
) {
    val durationUs: Long get() = (timelineEndUs - timelineStartUs).coerceAtLeast(1L)
    fun activeAt(timeUs: Long): Boolean = timeUs in timelineStartUs until timelineEndUs
}

fun TextOverlayClip.resolvedVideoTrackIdV3(project: TimelineProject): String? =
    videoTrackIdV3?.takeIf { id -> project.track(id)?.kind == TrackKind.VIDEO }
        ?: project.tracks.firstOrNull { it.kind == TrackKind.VIDEO }?.id

fun TimelineProject.textOverlaysForVideoTrackV3(trackId: String): List<TextOverlayClip> =
    textOverlays.filter { it.resolvedVideoTrackIdV3(this) == trackId }

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
