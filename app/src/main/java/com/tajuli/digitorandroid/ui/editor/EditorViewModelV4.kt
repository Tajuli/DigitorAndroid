package com.tajuli.digitorandroid.ui.editor

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.util.UnstableApi
import com.tajuli.digitorandroid.editor.model.ColorNode
import com.tajuli.digitorandroid.editor.model.ColorWheelValue
import com.tajuli.digitorandroid.editor.model.Curve5
import com.tajuli.digitorandroid.editor.model.HslQualifier
import com.tajuli.digitorandroid.editor.model.LogWheels
import com.tajuli.digitorandroid.editor.model.NodeCorrections
import com.tajuli.digitorandroid.editor.model.NodeEdge
import com.tajuli.digitorandroid.editor.model.NodeEffect
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.NodePosition
import com.tajuli.digitorandroid.editor.model.PrimaryWheels
import com.tajuli.digitorandroid.editor.model.RgbCurves
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.min

@UnstableApi
class EditorViewModelV4(application: Application) : AndroidViewModel(application) {
    data class UiState(
        val project: TimelineProject = TimelineProject(),
        val selectedTrackId: String? = null,
        val selectedClipId: String? = null,
        val selectedClipIds: Set<String> = emptySet(),
        val status: String = "Ready",
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        val initial = _state.value.project.tracks.firstOrNull { it.name == "V1" }
            ?: _state.value.project.tracks.firstOrNull()
        _state.value = _state.value.copy(selectedTrackId = initial?.id)
    }

    fun selectTrack(id: String) {
        _state.value = _state.value.copy(selectedTrackId = id)
    }

    fun selectClip(id: String) {
        val state = _state.value
        val ids = state.project.linkedClipIds(id).ifEmpty { setOf(id) }
        val primary = ids.firstOrNull { state.project.trackContaining(it)?.kind == TrackKind.VIDEO } ?: id
        _state.value = state.copy(
            selectedTrackId = state.project.trackContaining(id)?.id ?: state.selectedTrackId,
            selectedClipId = primary,
            selectedClipIds = ids,
        )
    }

    fun selectedImportMimeTypes(): Array<String> {
        val kind = _state.value.project.track(_state.value.selectedTrackId)?.kind
        return when (kind) {
            TrackKind.AUDIO -> arrayOf("audio/*")
            TrackKind.VIDEO -> arrayOf("video/*")
            null -> arrayOf("video/*", "audio/*")
        }
    }

    fun addTrack(kind: TrackKind) {
        val project = _state.value.project
        val count = project.tracks.count { it.kind == kind } + 1
        val prefix = if (kind == TrackKind.VIDEO) "V" else "A"
        val track = TimelineTrack(name = "$prefix$count", kind = kind)
        _state.value = _state.value.copy(
            project = project.copy(tracks = project.tracks + track),
            selectedTrackId = track.id,
            status = "${track.name} added",
        )
    }

    /** V tracks accept video only. A tracks accept audio only. Video imports create linked source audio. */
    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val app = getApplication<Application>()
        var state = _state.value
        var project = state.project
        val selected = project.track(state.selectedTrackId) ?: return

        if (selected.kind == TrackKind.VIDEO && project.tracks.none { it.kind == TrackKind.AUDIO }) {
            project = project.copy(tracks = project.tracks + TimelineTrack(name = "A1", kind = TrackKind.AUDIO))
        }
        val sourceAudioTrack = project.tracks.firstOrNull { it.kind == TrackKind.AUDIO }
        var startUs = selected.clips.maxOfOrNull { it.timelineEndUs } ?: 0L
        if (selected.kind == TrackKind.VIDEO && sourceAudioTrack != null) {
            startUs = max(startUs, sourceAudioTrack.clips.maxOfOrNull { it.timelineEndUs } ?: 0L)
        }

        val additions = mutableMapOf<String, MutableList<TimelineClip>>()
        var firstPrimary: String? = null
        var firstSelection: Set<String>? = null
        var skipped = 0

        for (uri in uris) {
            runCatching {
                app.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val mime = app.contentResolver.getType(uri).orEmpty()
            val durationUs = readDurationUs(uri).coerceAtLeast(1_000_000L)
            val label = uri.lastPathSegment?.substringAfterLast('/') ?: "Media"

            when (selected.kind) {
                TrackKind.VIDEO -> {
                    if (!mime.startsWith("video/")) { skipped++; continue }
                    val group = UUID.randomUUID().toString()
                    val video = TimelineClip(
                        uri = uri.toString(),
                        label = label,
                        timelineStartUs = startUs,
                        sourceOutUs = durationUs,
                        linkGroupId = group,
                    )
                    additions.getOrPut(selected.id) { mutableListOf() } += video
                    val ids = linkedSetOf(video.id)
                    sourceAudioTrack?.let { audioTrack ->
                        val audio = TimelineClip(
                            uri = uri.toString(),
                            label = "$label · audio",
                            timelineStartUs = startUs,
                            sourceOutUs = durationUs,
                            linkGroupId = group,
                        )
                        additions.getOrPut(audioTrack.id) { mutableListOf() } += audio
                        ids += audio.id
                    }
                    if (firstPrimary == null) firstPrimary = video.id
                    if (firstSelection == null) firstSelection = ids
                    startUs += durationUs
                }
                TrackKind.AUDIO -> {
                    if (!mime.startsWith("audio/")) { skipped++; continue }
                    val audio = TimelineClip(
                        uri = uri.toString(),
                        label = label,
                        timelineStartUs = startUs,
                        sourceOutUs = durationUs,
                    )
                    additions.getOrPut(selected.id) { mutableListOf() } += audio
                    if (firstPrimary == null) firstPrimary = audio.id
                    if (firstSelection == null) firstSelection = setOf(audio.id)
                    startUs += durationUs
                }
            }
        }

        if (additions.isEmpty()) {
            _state.value = state.copy(status = if (selected.kind == TrackKind.AUDIO) "A track accepts audio files only" else "V track accepts video files only")
            return
        }
        val tracks = project.tracks.map { track ->
            val extra = additions[track.id].orEmpty()
            if (extra.isEmpty()) track else track.copy(clips = track.clips + extra)
        }
        project = project.copy(tracks = tracks)
        state = state.copy(
            project = project,
            selectedClipId = firstPrimary,
            selectedClipIds = firstSelection.orEmpty(),
            status = if (skipped > 0) "Imported · skipped $skipped incompatible file(s)" else "Imported",
        )
        _state.value = state
    }

    fun moveClip(trackId: String, clipId: String, deltaUs: Long) {
        if (deltaUs == 0L) return
        val state = _state.value
        val project = state.project
        val target = project.clip(clipId) ?: return
        val movingIds = if (target.linkGroupId == null) setOf(clipId) else project.linkedClipIds(clipId)
        var lower = Long.MIN_VALUE / 4
        var upper = Long.MAX_VALUE / 4
        for (id in movingIds) {
            val clip = project.clip(id) ?: continue
            val track = project.trackContaining(id) ?: continue
            val others = track.clips.filter { it.id !in movingIds }
            val previous = others.filter { it.timelineEndUs <= clip.timelineStartUs }.maxByOrNull { it.timelineEndUs }
            val next = others.filter { it.timelineStartUs >= clip.timelineEndUs }.minByOrNull { it.timelineStartUs }
            lower = max(lower, max(-clip.timelineStartUs, previous?.let { it.timelineEndUs - clip.timelineStartUs } ?: Long.MIN_VALUE / 4))
            upper = min(upper, next?.let { it.timelineStartUs - clip.timelineEndUs } ?: Long.MAX_VALUE / 4)
        }
        if (lower > upper) return
        val actual = deltaUs.coerceIn(lower, upper)
        if (actual == 0L) return
        val tracks = project.tracks.map { track ->
            track.copy(clips = track.clips.map { clip ->
                if (clip.id in movingIds) clip.copy(timelineStartUs = clip.timelineStartUs + actual) else clip
            })
        }
        _state.value = state.copy(project = project.copy(tracks = tracks))
    }

    fun moveClipToTrack(clipId: String, targetTrackId: String) {
        val state = _state.value
        val project = state.project
        val clip = project.clip(clipId) ?: return
        val from = project.trackContaining(clipId) ?: return
        val target = project.track(targetTrackId) ?: return
        if (from.id == target.id || from.kind != target.kind) return
        if (target.clips.any { other -> other.id != clip.id && clip.timelineStartUs < other.timelineEndUs && clip.timelineEndUs > other.timelineStartUs }) {
            _state.value = state.copy(status = "Cannot drop: target track overlaps")
            return
        }
        val tracks = project.tracks.map { track -> when (track.id) {
            from.id -> track.copy(clips = track.clips.filterNot { it.id == clip.id })
            target.id -> track.copy(clips = track.clips + clip)
            else -> track
        } }
        _state.value = state.copy(project = project.copy(tracks = tracks), selectedTrackId = target.id, status = "Moved to ${target.name}")
    }

    fun unlinkSelected() {
        val state = _state.value
        val groups = state.selectedClipIds.mapNotNull { state.project.clip(it)?.linkGroupId }.toSet()
        if (groups.isEmpty()) { _state.value = state.copy(status = "Already unlinked"); return }
        val tracks = state.project.tracks.map { track ->
            track.copy(clips = track.clips.map { clip -> if (clip.linkGroupId in groups) clip.copy(linkGroupId = null) else clip })
        }
        val primary = state.selectedClipId
        _state.value = state.copy(project = state.project.copy(tracks = tracks), selectedClipIds = primary?.let(::setOf).orEmpty(), status = "Unlinked")
    }

    fun deleteSelected() {
        val state = _state.value
        val ids = state.selectedClipIds.ifEmpty { state.selectedClipId?.let { state.project.linkedClipIds(it) }.orEmpty() }
        if (ids.isEmpty()) return
        val tracks = state.project.tracks.map { track -> track.copy(clips = track.clips.filterNot { it.id in ids }) }
        _state.value = state.copy(project = state.project.copy(tracks = tracks), selectedClipId = null, selectedClipIds = emptySet(), status = "Deleted")
    }

    fun splitSelectedAt(timelineUs: Long) {
        val state = _state.value
        val project = state.project
        val ids = state.selectedClipIds.ifEmpty { state.selectedClipId?.let { project.linkedClipIds(it) }.orEmpty() }
        if (ids.isEmpty()) return
        val linked = ids.any { project.clip(it)?.linkGroupId != null }
        val leftGroup = if (linked) UUID.randomUUID().toString() else null
        val rightGroup = if (linked) UUID.randomUUID().toString() else null
        val rightIds = linkedSetOf<String>()
        val tracks = project.tracks.map { track ->
            val rebuilt = mutableListOf<TimelineClip>()
            for (clip in track.clips) {
                if (clip.id !in ids || timelineUs <= clip.timelineStartUs || timelineUs >= clip.timelineEndUs) {
                    rebuilt += clip
                } else {
                    val sourceSplit = clip.sourceInUs + timelineUs - clip.timelineStartUs
                    rebuilt += clip.copy(sourceOutUs = sourceSplit, linkGroupId = if (clip.linkGroupId == null) null else leftGroup)
                    val right = clip.copy(
                        id = UUID.randomUUID().toString(),
                        timelineStartUs = timelineUs,
                        sourceInUs = sourceSplit,
                        linkGroupId = if (clip.linkGroupId == null) null else rightGroup,
                    )
                    rebuilt += right
                    rightIds += right.id
                }
            }
            track.copy(clips = rebuilt)
        }
        if (rightIds.isEmpty()) { _state.value = state.copy(status = "Put cursor inside selected clip"); return }
        val next = project.copy(tracks = tracks)
        val primary = rightIds.firstOrNull { next.trackContaining(it)?.kind == TrackKind.VIDEO } ?: rightIds.first()
        _state.value = state.copy(project = next, selectedClipId = primary, selectedClipIds = rightIds, selectedTrackId = next.trackContaining(primary)?.id, status = "Split")
    }

    fun selectNode(nodeId: String) = updatePrimaryClip { clip ->
        if (clip.nodeGraph.nodes.none { it.id == nodeId }) clip else clip.copy(nodeGraph = clip.nodeGraph.copy(selectedNodeId = nodeId))
    }

    fun moveNode(nodeId: String, dx: Float, dy: Float) = updatePrimaryClip { clip ->
        clip.copy(nodeGraph = clip.nodeGraph.copy(nodes = clip.nodeGraph.nodes.map { node ->
            if (node.id == nodeId) node.copy(position = NodePosition((node.position.x + dx).coerceAtLeast(8f), (node.position.y + dy).coerceAtLeast(8f))) else node
        }))
    }

    fun addSerialNode(afterNodeId: String) = updatePrimaryClip { clip ->
        val graph = clip.nodeGraph
        val anchor = graph.nodes.firstOrNull { it.id == afterNodeId } ?: return@updatePrimaryClip clip
        if (anchor.kind == NodeKind.OUTPUT) return@updatePrimaryClip clip
        val outgoing = graph.edges.firstOrNull { it.fromId == anchor.id }
        val shifted = graph.nodes.map { node -> if (node.id != anchor.id && node.position.x > anchor.position.x + 24f) node.copy(position = node.position.copy(x = node.position.x + 126f)) else node }
        val count = shifted.count { it.kind == NodeKind.SERIAL || it.kind == NodeKind.PARALLEL } + 1
        val node = ColorNode(kind = NodeKind.SERIAL, label = count.toString().padStart(2, '0'), position = NodePosition(anchor.position.x + 126f, anchor.position.y))
        val edges = graph.edges.filterNot { it == outgoing }.toMutableList()
        edges += NodeEdge(anchor.id, node.id)
        if (outgoing != null) edges += NodeEdge(node.id, outgoing.toId)
        clip.copy(nodeGraph = graph.copy(nodes = shifted + node, edges = edges, selectedNodeId = node.id))
    }

    fun addParallelNode(anchorNodeId: String) = updatePrimaryClip { clip ->
        val graph = clip.nodeGraph
        val anchor = graph.nodes.firstOrNull { it.id == anchorNodeId } ?: return@updatePrimaryClip clip
        if (anchor.kind != NodeKind.SERIAL && anchor.kind != NodeKind.PARALLEL) return@updatePrimaryClip clip
        if (anchor.kind == NodeKind.PARALLEL) {
            val incoming = graph.edges.firstOrNull { it.toId == anchor.id } ?: return@updatePrimaryClip clip
            val mixId = graph.edges.firstOrNull { it.fromId == anchor.id }?.toId ?: return@updatePrimaryClip clip
            val mix = graph.nodes.firstOrNull { it.id == mixId && it.kind == NodeKind.MIX } ?: return@updatePrimaryClip clip
            val count = graph.edges.count { it.toId == mix.id }
            val node = ColorNode(kind = NodeKind.PARALLEL, label = "P${count + 1}", position = NodePosition(anchor.position.x, anchor.position.y + 76f))
            return@updatePrimaryClip clip.copy(nodeGraph = graph.copy(nodes = graph.nodes + node, edges = graph.edges + listOf(NodeEdge(incoming.fromId, node.id), NodeEdge(node.id, mix.id)), selectedNodeId = node.id))
        }
        val incoming = graph.edges.firstOrNull { it.toId == anchor.id } ?: return@updatePrimaryClip clip
        val outgoing = graph.edges.firstOrNull { it.fromId == anchor.id } ?: return@updatePrimaryClip clip
        val shifted = graph.nodes.map { node -> if (node.position.x > anchor.position.x + 20f) node.copy(position = node.position.copy(x = node.position.x + 154f)) else node }
        val parallel = ColorNode(kind = NodeKind.PARALLEL, label = "P2", position = NodePosition(anchor.position.x, anchor.position.y + 76f))
        val mixNode = ColorNode(kind = NodeKind.MIX, label = "Mix", position = NodePosition(anchor.position.x + 142f, anchor.position.y + 38f))
        val edges = graph.edges.filterNot { it == outgoing }.toMutableList()
        edges += NodeEdge(incoming.fromId, parallel.id)
        edges += NodeEdge(anchor.id, mixNode.id)
        edges += NodeEdge(parallel.id, mixNode.id)
        edges += NodeEdge(mixNode.id, outgoing.toId)
        clip.copy(nodeGraph = graph.copy(nodes = shifted + parallel + mixNode, edges = edges, selectedNodeId = parallel.id))
    }

    fun setSelectedNodeCorrection(parameter: String, value: Float) = updateSelectedEditableNode { node ->
        val c = node.corrections
        val next = when (parameter.lowercase()) {
            "exposure" -> c.copy(exposure = value)
            "contrast" -> c.copy(contrast = value)
            "saturation" -> c.copy(saturation = value)
            "temperature", "temp" -> c.copy(temperature = value)
            "tint" -> c.copy(tint = value)
            "highlights" -> c.copy(highlights = value)
            "shadows" -> c.copy(shadows = value)
            "hue" -> c.copy(hue = value)
            "color boost", "colorboost" -> c.copy(colorBoost = value)
            else -> c
        }
        node.copy(corrections = next)
    }

    fun addEffectToSelectedNode(name: String, amount: Float = 1f) = updateSelectedEditableNode { node ->
        val existing = node.effects.indexOfFirst { it.name == name }
        if (existing >= 0) node.copy(effects = node.effects.mapIndexed { index, effect -> if (index == existing) effect.copy(amount = amount, enabled = true) else effect })
        else node.copy(effects = node.effects + NodeEffect(name = name, amount = amount))
    }

    fun setPrimaryWheel(wheel: String, component: String, value: Float) = updateSelectedEditableNode { node ->
        val p = node.advancedColor.primary
        val next = when (wheel.lowercase()) {
            "lift" -> p.copy(lift = p.lift.withComponent(component, value))
            "gamma" -> p.copy(gamma = p.gamma.withComponent(component, value))
            "gain" -> p.copy(gain = p.gain.withComponent(component, value))
            "offset" -> p.copy(offset = p.offset.withComponent(component, value))
            else -> p
        }
        node.copy(advancedColor = node.advancedColor.copy(primary = next))
    }

    fun setLogWheel(zone: String, component: String, value: Float) = updateSelectedEditableNode { node ->
        val log = node.advancedColor.log
        val next = when (zone.lowercase()) {
            "shadows" -> log.copy(shadows = log.shadows.withComponent(component, value))
            "midtones" -> log.copy(midtones = log.midtones.withComponent(component, value))
            "highlights" -> log.copy(highlights = log.highlights.withComponent(component, value))
            else -> log
        }
        node.copy(advancedColor = node.advancedColor.copy(log = next))
    }

    fun setLogRange(shadowRange: Float? = null, highlightRange: Float? = null) = updateSelectedEditableNode { node ->
        val log = node.advancedColor.log
        node.copy(advancedColor = node.advancedColor.copy(log = log.copy(
            shadowRange = (shadowRange ?: log.shadowRange).coerceIn(.05f, .48f),
            highlightRange = (highlightRange ?: log.highlightRange).coerceIn(.52f, .95f),
        )))
    }

    fun setCurvePoint(channel: String, index: Int, value: Float) = updateSelectedEditableNode { node ->
        val curves = node.advancedColor.curves
        val next = when (channel.lowercase()) {
            "master", "rgb" -> curves.copy(master = curves.master.withPoint(index, value))
            "red", "r" -> curves.copy(red = curves.red.withPoint(index, value))
            "green", "g" -> curves.copy(green = curves.green.withPoint(index, value))
            "blue", "b" -> curves.copy(blue = curves.blue.withPoint(index, value))
            else -> curves
        }
        node.copy(advancedColor = node.advancedColor.copy(curves = next))
    }

    fun setQualifierEnabled(enabled: Boolean) = updateSelectedEditableNode { node ->
        node.copy(advancedColor = node.advancedColor.copy(qualifier = node.advancedColor.qualifier.copy(enabled = enabled)))
    }

    fun setQualifier(parameter: String, value: Float) = updateSelectedEditableNode { node ->
        val q = node.advancedColor.qualifier
        val next: HslQualifier = when (parameter.lowercase()) {
            "hue" -> q.copy(hueCenterDegrees = value.coerceIn(0f, 360f))
            "width" -> q.copy(hueWidthDegrees = value.coerceIn(1f, 360f))
            "satmin" -> q.copy(saturationMin = value.coerceIn(0f, 1f))
            "satmax" -> q.copy(saturationMax = value.coerceIn(0f, 1f))
            "lummin" -> q.copy(luminanceMin = value.coerceIn(0f, 1f))
            "lummax" -> q.copy(luminanceMax = value.coerceIn(0f, 1f))
            "softness" -> q.copy(softness = value.coerceIn(0f, 1f))
            "hueshift" -> q.copy(hueShiftDegrees = value.coerceIn(-180f, 180f))
            "satshift" -> q.copy(saturationShift = value.coerceIn(-1f, 1f))
            "lumshift" -> q.copy(luminanceShift = value.coerceIn(-1f, 1f))
            else -> q
        }
        node.copy(advancedColor = node.advancedColor.copy(qualifier = next))
    }

    private fun updateSelectedEditableNode(transform: (ColorNode) -> ColorNode) = updatePrimaryClip { clip ->
        val graph = clip.nodeGraph
        val selected = graph.selectedNodeId ?: return@updatePrimaryClip clip
        val node = graph.nodes.firstOrNull { it.id == selected } ?: return@updatePrimaryClip clip
        if (node.kind != NodeKind.SERIAL && node.kind != NodeKind.PARALLEL) return@updatePrimaryClip clip
        clip.copy(nodeGraph = graph.copy(nodes = graph.nodes.map { if (it.id == selected) transform(it) else it }))
    }

    private fun updatePrimaryClip(transform: (TimelineClip) -> TimelineClip) {
        val state = _state.value
        val id = state.selectedClipId ?: return
        val tracks = state.project.tracks.map { track -> track.copy(clips = track.clips.map { clip -> if (clip.id == id) transform(clip) else clip }) }
        _state.value = state.copy(project = state.project.copy(tracks = tracks))
    }

    private fun ColorWheelValue.withComponent(component: String, value: Float): ColorWheelValue = when (component.lowercase()) {
        "red", "r" -> copy(red = value.coerceIn(-1f, 1f))
        "green", "g" -> copy(green = value.coerceIn(-1f, 1f))
        "blue", "b" -> copy(blue = value.coerceIn(-1f, 1f))
        "luma", "y" -> copy(luma = value.coerceIn(-1f, 1f))
        else -> this
    }

    private fun readDurationUs(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(getApplication<Application>(), uri)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 1000L
            ms * 1000L
        } finally {
            retriever.release()
        }
    }
}
