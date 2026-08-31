package com.tajuli.digitorandroid.editor.model

import java.util.UUID

enum class VisualOverlayKindV19 { IMAGE, STICKER, SHAPE }
enum class StickerPresetV19 { HEART, STAR, LIGHTNING, CHECK, ARROW, SMILE }
enum class ShapePresetV19 { RECTANGLE, CIRCLE, TRIANGLE, ARROW }

data class VisualOverlayClipV19(
    val id: String = UUID.randomUUID().toString(),
    val kind: VisualOverlayKindV19,
    val label: String,
    val timelineStartUs: Long,
    val timelineEndUs: Long,
    val imageUri: String? = null,
    val stickerPreset: StickerPresetV19? = null,
    val shapePreset: ShapePresetV19? = null,
    val colorArgb: Long = 0xFFFFFFFFL,
    /** Normalized project coordinates. +X is right, +Y is down. */
    val positionX: Float = 0f,
    val positionY: Float = 0f,
    /** Fraction of the project width occupied by the overlay before aspect-ratio fitting. */
    val scale: Float = .30f,
    val rotationDegrees: Float = 0f,
    val opacity: Float = 1f,
    /** Resolve-style V-track binding. Nullable keeps old projects migration-safe. */
    val videoTrackIdV19: String? = null,
) {
    val durationUs: Long get() = (timelineEndUs - timelineStartUs).coerceAtLeast(1L)
    fun activeAt(timeUs: Long): Boolean = timeUs in timelineStartUs until timelineEndUs

    fun normalized(): VisualOverlayClipV19 = copy(
        timelineStartUs = timelineStartUs.coerceAtLeast(0L),
        timelineEndUs = timelineEndUs.coerceAtLeast(timelineStartUs + 100_000L),
        positionX = positionX.coerceIn(-1f, 1f),
        positionY = positionY.coerceIn(-1f, 1f),
        scale = scale.coerceIn(.03f, 1.5f),
        rotationDegrees = ((rotationDegrees % 360f) + 360f) % 360f,
        opacity = opacity.coerceIn(0f, 1f),
    )
}

fun TimelineProject.resolvedVisualOverlaysV19(): List<VisualOverlayClipV19> = visualOverlaysV19.orEmpty()

fun VisualOverlayClipV19.resolvedVideoTrackIdV19(project: TimelineProject): String? =
    videoTrackIdV19?.takeIf { id -> project.track(id)?.kind == TrackKind.VIDEO }
        ?: project.tracks.firstOrNull { it.kind == TrackKind.VIDEO && it.name == "V1" }?.id
        ?: project.tracks.lastOrNull { it.kind == TrackKind.VIDEO }?.id

fun TimelineProject.visualOverlaysForVideoTrackV19(trackId: String): List<VisualOverlayClipV19> =
    resolvedVisualOverlaysV19().filter { it.resolvedVideoTrackIdV19(this) == trackId }

fun TimelineProject.activeVisualOverlaysAtV19(timeUs: Long): List<VisualOverlayClipV19> =
    resolvedVisualOverlaysV19()
        .filter { it.activeAt(timeUs) }
        .sortedByDescending { overlay ->
            val trackId = overlay.resolvedVideoTrackIdV19(this)
            tracks.indexOfFirst { it.id == trackId }.let { if (it < 0) Int.MAX_VALUE else it }
        }

/**
 * Media, text and visual overlays share the same V-track lane. A visual overlay on V2 may overlap
 * V1 video, but it may not occupy the same time slot as another V2 item.
 */
fun TimelineProject.visualOverlaySlotAvailableV19(
    trackId: String,
    startUs: Long,
    endUs: Long,
    ignoreVisualId: String? = null,
): Boolean {
    val track = track(trackId)?.takeIf { it.kind == TrackKind.VIDEO } ?: return false
    val safeStart = startUs.coerceAtLeast(0L)
    val safeEnd = endUs.coerceAtLeast(safeStart + 1L)
    fun overlaps(otherStartUs: Long, otherEndUs: Long): Boolean =
        safeStart < otherEndUs && safeEnd > otherStartUs

    if (track.clips.any { overlaps(it.timelineStartUs, it.timelineEndUs) }) return false
    if (textOverlaysForVideoTrackV3(trackId).any { overlaps(it.timelineStartUs, it.timelineEndUs) }) return false
    return visualOverlaysForVideoTrackV19(trackId)
        .filterNot { it.id == ignoreVisualId }
        .none { overlaps(it.timelineStartUs, it.timelineEndUs) }
}

fun TimelineProject.hasCompositionOverlaysV19(): Boolean =
    textOverlays.isNotEmpty() || resolvedVisualOverlaysV19().isNotEmpty()
