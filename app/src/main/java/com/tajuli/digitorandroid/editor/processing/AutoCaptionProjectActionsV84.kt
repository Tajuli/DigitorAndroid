package com.tajuli.digitorandroid.editor.processing

import com.tajuli.digitorandroid.editor.model.TextOverlayClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TrackKind

private const val AUTO_CC_ID_PREFIX_V84 = "auto-cc-v77-"
private const val AUTO_CC_TRACK_NAME_V84 = "CC"

/** Generated Auto CC text items in timeline order. */
fun TimelineProject.autoCaptionOverlaysV84(): List<TextOverlayClip> =
    textOverlays
        .filter { it.id.startsWith(AUTO_CC_ID_PREFIX_V84) }
        .sortedWith(compareBy<TextOverlayClip> { it.timelineStartUs }.thenBy { it.timelineEndUs })

/** Dedicated CC track if Auto CC has created one. */
fun TimelineProject.autoCaptionTrackIdV84(): String? =
    tracks.firstOrNull { it.kind == TrackKind.VIDEO && it.name == AUTO_CC_TRACK_NAME_V84 }?.id

/**
 * Edit one generated caption without changing its timing/style/track assignment.
 * Keeping the generated id means a later Auto CC regeneration can intentionally replace it.
 */
fun TimelineProject.updateAutoCaptionTextV84(textId: String, text: String): TimelineProject {
    val cleaned = text.trim()
    require(cleaned.isNotEmpty()) { "Caption text cannot be empty" }
    if (textOverlays.none { it.id == textId && it.id.startsWith(AUTO_CC_ID_PREFIX_V84) }) return this
    return copy(
        textOverlays = textOverlays.map { overlay ->
            if (overlay.id == textId) overlay.copy(text = cleaned) else overlay
        },
    )
}

/** Remove one generated caption while preserving the CC track and every other text item. */
fun TimelineProject.deleteAutoCaptionTextV84(textId: String): TimelineProject =
    copy(
        textOverlays = textOverlays.filterNot {
            it.id == textId && it.id.startsWith(AUTO_CC_ID_PREFIX_V84)
        },
    )

/**
 * Delete the CC track like a normal video track: remove the track and every text item assigned to
 * it. `previousTrackId` lets the UI finish cleanup when TimelineEditor has already removed the track
 * through its generic long-press track-delete action. Generated captions are removed even from
 * older projects whose track assignment was lost. Manual text on V1/V2/etc is preserved.
 */
fun TimelineProject.deleteAutoCaptionTrackV84(previousTrackId: String? = null): TimelineProject {
    val liveCcTrackId = autoCaptionTrackIdV84()
    val ccTrackId = liveCcTrackId ?: previousTrackId
    val hasGenerated = textOverlays.any { it.id.startsWith(AUTO_CC_ID_PREFIX_V84) }
    val hasAssigned = ccTrackId != null && textOverlays.any { it.videoTrackIdV3 == ccTrackId }
    if (liveCcTrackId == null && !hasGenerated && !hasAssigned) return this

    return copy(
        tracks = if (liveCcTrackId == null) tracks else tracks.filterNot { it.id == liveCcTrackId },
        textOverlays = textOverlays.filterNot { overlay ->
            overlay.id.startsWith(AUTO_CC_ID_PREFIX_V84) ||
                (ccTrackId != null && overlay.videoTrackIdV3 == ccTrackId)
        },
    )
}
