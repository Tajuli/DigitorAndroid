package com.tajuli.digitorandroid.ui.editor

import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TrackKind

/**
 * Compose draws text and bitmap overlays through separate composables. This tiny UI-thread registry
 * gives both renderers the same V-track z order used by Media3 export without changing the legacy
 * TextOverlayPreviewV2 call surface. New VIDEO tracks are inserted above older ones, so a smaller
 * project track index receives a larger z value.
 */
internal object PreviewOverlayLayerOrderV19 {
    private var trackZ: Map<String, Float> = emptyMap()
    private var fallbackVideoTrackId: String? = null

    fun install(project: TimelineProject) {
        val videos = project.tracks.filter { it.kind == TrackKind.VIDEO }
        val total = project.tracks.size.coerceAtLeast(1)
        trackZ = videos.associate { track ->
            val index = project.tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            track.id to (total - index).toFloat()
        }
        fallbackVideoTrackId = videos.firstOrNull { it.name == "V1" }?.id ?: videos.lastOrNull()?.id
    }

    fun zFor(trackId: String?): Float =
        trackZ[trackId ?: fallbackVideoTrackId] ?: trackZ[fallbackVideoTrackId] ?: 0f
}
