package com.tajuli.digitorandroid.editor.preview

import com.tajuli.digitorandroid.editor.model.CutoutModeV43
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.resolvedCutoutV43
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class PreviewInvalidationReason {
    INITIAL,
    PARAMETER_CHANGE,
    EFFECT_STRUCTURE_CHANGE,
    NODE_GRAPH_CHANGE,
    SEMANTIC_ASSET_CHANGE,
}

internal data class PreviewProjectSnapshot(
    val project: TimelineProject?,
    val revision: Long,
    val reason: PreviewInvalidationReason,
)

/**
 * Latest immutable editor snapshot for long-lived GPU preview effects/compositor settings.
 *
 * Render parameters can be read from the newest project without tearing down decoders every time a
 * slider moves. The StateFlow is also used by the SurfaceView host so the physical preview surface
 * always follows the project/canvas aspect ratio instead of stretching to the editor panel bounds.
 */
internal object PreviewProjectRegistry {
    private val latest = AtomicReference<TimelineProject?>(null)
    private val mutableSnapshot = MutableStateFlow(
        PreviewProjectSnapshot(null, 0L, PreviewInvalidationReason.INITIAL),
    )

    val snapshots: StateFlow<PreviewProjectSnapshot> = mutableSnapshot.asStateFlow()

    @Synchronized
    fun update(project: TimelineProject): PreviewProjectSnapshot {
        val previous = latest.getAndSet(project)
        if (previous == project) return mutableSnapshot.value
        val next = PreviewProjectSnapshot(
            project = project,
            revision = mutableSnapshot.value.revision + 1L,
            reason = previewInvalidationReason(previous, project),
        )
        mutableSnapshot.value = next
        return next
    }

    fun project(): TimelineProject? = latest.get()

    fun clip(id: String): TimelineClip? {
        val clip = latest.get()?.clip(id) ?: return null
        val settings = clip.resolvedCutoutV43()
        return if (
            settings.mode == CutoutModeV43.CHROMA_KEY &&
            !settings.chromaKeyColorPickedV71
        ) {
            // The preview picker must see the untouched screen/background. Keep every other live
            // clip setting intact, but present pending Chroma as a no-op until a sample is accepted.
            clip.copy(cutoutV43 = settings.copy(mode = CutoutModeV43.NONE))
        } else {
            clip
        }
    }

    fun clear(project: TimelineProject? = null) {
        if (project == null) {
            latest.set(null)
            mutableSnapshot.value = PreviewProjectSnapshot(
                null,
                mutableSnapshot.value.revision + 1L,
                PreviewInvalidationReason.INITIAL,
            )
            return
        }
        if (latest.compareAndSet(project, null)) {
            mutableSnapshot.value = PreviewProjectSnapshot(
                null,
                mutableSnapshot.value.revision + 1L,
                PreviewInvalidationReason.INITIAL,
            )
        }
    }
}

private fun previewInvalidationReason(
    previous: TimelineProject?,
    next: TimelineProject,
): PreviewInvalidationReason {
    if (previous == null) return PreviewInvalidationReason.INITIAL
    val oldClips = previous.tracks.flatMap { it.clips }.associateBy { it.id }
    val newClips = next.tracks.flatMap { it.clips }.associateBy { it.id }
    if (oldClips.keys != newClips.keys) return PreviewInvalidationReason.NODE_GRAPH_CHANGE
    for ((id, clip) in newClips) {
        val old = oldClips.getValue(id)
        if (old.nodeGraph.edges != clip.nodeGraph.edges ||
            old.nodeGraph.nodes.map { it.id to it.kind } != clip.nodeGraph.nodes.map { it.id to it.kind }
        ) return PreviewInvalidationReason.NODE_GRAPH_CHANGE
        val oldEffects = old.nodeGraph.nodes.flatMap { node ->
            node.effects.map { listOf(it.id, it.name, it.enabled, it.sourceStartUsV26, it.sourceEndUsV26) }
        }
        val newEffects = clip.nodeGraph.nodes.flatMap { node ->
            node.effects.map { listOf(it.id, it.name, it.enabled, it.sourceStartUsV26, it.sourceEndUsV26) }
        }
        if (oldEffects != newEffects) return PreviewInvalidationReason.EFFECT_STRUCTURE_CHANGE
        if (old.resolvedCutoutV43() != clip.resolvedCutoutV43()) {
            return PreviewInvalidationReason.SEMANTIC_ASSET_CHANGE
        }
    }
    return PreviewInvalidationReason.PARAMETER_CHANGE
}
