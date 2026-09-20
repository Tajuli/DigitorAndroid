package com.tajuli.digitorandroid.editor.model

import java.util.UUID

/** Creator-facing transition catalog. Stored on the incoming clip for the cut immediately before it. */
enum class TransitionStyleV22(val label: String) {
    NONE("None"),
    CROSS_DISSOLVE("Cross Dissolve"),
    SMOOTH_CUT("Smooth Cut"),
    DIP_TO_BLACK("Dip to Black"),
    DIP_TO_WHITE("Dip to White"),
    FADE("Fade"),
    PUSH_LEFT("Push Left"),
    PUSH_RIGHT("Push Right"),
    PUSH_UP("Push Up"),
    PUSH_DOWN("Push Down"),
    SLIDE("Slide"),
    ZOOM_IN("Zoom In"),
    ZOOM_OUT("Zoom Out"),
    BLUR("Blur"),
    WHIP("Whip"),
    SPIN("Spin"),
    FLASH("Flash"),
    MASK_WIPE("Mask Wipe"),
    CIRCLE_WIPE("Circle Wipe"),
    SPLIT("Split"),
    LIGHT_LEAK("Light Leak"),
}

/**
 * Transition metadata.
 *
 * fadeIn/fadeOut are retained for saved-project compatibility with the original compositor-native
 * edge fades. V22 stores a cut transition on the incoming clip. Nullable style keeps old Gson
 * projects safe: a missing field resolves to NONE instead of relying on Gson to synthesize an enum.
 * presetIdV24 is also nullable so legacy projects continue to resolve through the stable V22 style.
 */
data class ClipTransition(
    val fadeInUs: Long = 0L,
    val fadeOutUs: Long = 0L,
    val styleV22: TransitionStyleV22? = null,
    val durationUsV22: Long = 0L,
    val presetIdV24: String? = null,
) {
    val resolvedStyleV22: TransitionStyleV22
        get() = styleV22 ?: TransitionStyleV22.NONE

    val resolvedDurationUsV22: Long
        get() = if (resolvedStyleV22 == TransitionStyleV22.NONE) 0L else durationUsV22.coerceAtLeast(0L)

    val hasCutTransitionV22: Boolean
        get() = resolvedStyleV22 != TransitionStyleV22.NONE && resolvedDurationUsV22 > 0L

    val isIdentity: Boolean
        get() = fadeInUs <= 0L && fadeOutUs <= 0L && !hasCutTransitionV22

    fun normalizedFor(durationUs: Long): ClipTransition {
        val safeDuration = durationUs.coerceAtLeast(1L)
        val maxEdge = safeDuration / 2L
        return copy(
            fadeInUs = fadeInUs.coerceIn(0L, maxEdge),
            fadeOutUs = fadeOutUs.coerceIn(0L, maxEdge),
            durationUsV22 = if (resolvedStyleV22 == TransitionStyleV22.NONE) {
                0L
            } else {
                durationUsV22.coerceIn(0L, maxEdge)
            },
            presetIdV24 = if (resolvedStyleV22 == TransitionStyleV22.NONE) null else presetIdV24,
        )
    }
}

/** Per-audio-clip mix controls shared by realtime preview and export. */
data class AudioMix(
    val volume: Float = 1f,
    val fadeInUs: Long = 0L,
    val fadeOutUs: Long = 0L,
    /** 0 = off, 1 = strongest lightweight speech/background-noise suppression. */
    val noiseReduction: Float = 0f,
) {
    fun normalizedFor(durationUs: Long): AudioMix {
        val safeDuration = durationUs.coerceAtLeast(1L)
        return copy(
            volume = volume.coerceIn(0f, 1f),
            fadeInUs = fadeInUs.coerceIn(0L, safeDuration),
            fadeOutUs = fadeOutUs.coerceIn(0L, safeDuration),
            noiseReduction = noiseReduction.coerceIn(0f, 1f),
        )
    }
}

/**
 * Stateful mobile-friendly noise reducer shared by realtime/Media3 and native export.
 *
 * The reducer learns the low-level background floor, uses a soft knee instead of a hard gate,
 * opens quickly for speech transients and closes slowly after speech. This avoids the abrupt
 * silence/pumping sound of a sample-by-sample gate while remaining lightweight enough for preview.
 */
class AdaptiveNoiseReducer(
    amount: Float,
) {
    private val strength = amount.coerceIn(0f, 1f)
    private var envelope = 0f
    private var noiseFloor = 0.006f
    private var smoothedGain = 1f

    fun processFrame(level: Float, sampleRate: Int): Float {
        if (strength <= 0f) return 1f
        val safeRate = sampleRate.coerceAtLeast(1)
        val inputLevel = kotlin.math.abs(level).coerceIn(0f, 1f)

        // Fast attack catches the start of words; slower release avoids chatter between syllables.
        val envelopeTimeMs = if (inputLevel > envelope) 4f else 75f
        envelope += (inputLevel - envelope) * smoothingAlpha(envelopeTimeMs, safeRate)

        // Learn only near the current floor. A slow upward learner adapts to fans/AC without
        // mistaking normal speech for background noise; downward adaptation is deliberately faster.
        val floorTarget = envelope.coerceAtLeast(0.0005f)
        val floorTimeMs = when {
            floorTarget < noiseFloor -> 220f
            floorTarget <= noiseFloor * 1.65f -> 1400f
            else -> 9000f
        }
        val boundedFloorTarget = if (floorTarget > noiseFloor * 1.65f) {
            noiseFloor * 1.65f
        } else {
            floorTarget
        }
        noiseFloor += (boundedFloorTarget - noiseFloor) * smoothingAlpha(floorTimeMs, safeRate)
        noiseFloor = noiseFloor.coerceIn(0.0005f, 0.12f)

        val minimumThreshold = 0.0045f + 0.009f * strength
        val threshold = kotlin.math.max(minimumThreshold, noiseFloor * (1.55f + 0.75f * strength))
        val kneeEnd = threshold * (2.2f + 0.35f * (1f - strength))
        val floorGain = 1f - 0.84f * strength

        val targetGain = when {
            envelope <= threshold -> floorGain
            envelope >= kneeEnd -> 1f
            else -> {
                val t = ((envelope - threshold) / (kneeEnd - threshold)).coerceIn(0f, 1f)
                val smooth = t * t * (3f - 2f * t)
                floorGain + (1f - floorGain) * smooth
            }
        }

        // Opening is quick so consonants remain crisp; closing is intentionally slow/natural.
        val gainTimeMs = if (targetGain > smoothedGain) 7f else 170f
        smoothedGain += (targetGain - smoothedGain) * smoothingAlpha(gainTimeMs, safeRate)
        return smoothedGain.coerceIn(floorGain, 1f)
    }

    internal fun currentNoiseFloorForTest(): Float = noiseFloor

    private fun smoothingAlpha(timeMs: Float, sampleRate: Int): Float {
        val samples = (timeMs * sampleRate.toFloat() / 1000f).coerceAtLeast(1f)
        return (1f - kotlin.math.exp(-1f / samples)).coerceIn(0f, 1f)
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
