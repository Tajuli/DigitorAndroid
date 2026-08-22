package com.tajuli.digitorandroid.ui.editor

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.tajuli.digitorandroid.editor.model.ClipNodeGraph
import com.tajuli.digitorandroid.editor.model.ColorNode
import com.tajuli.digitorandroid.editor.model.NodeCorrections
import com.tajuli.digitorandroid.editor.model.NodeEdge
import com.tajuli.digitorandroid.editor.model.NodeEffect
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.NodePosition
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.processing.Backend
import com.tajuli.digitorandroid.editor.processing.ExportProgress
import com.tajuli.digitorandroid.editor.processing.ProcessingRouter
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

@UnstableApi
class EditorViewModel(application: Application) : AndroidViewModel(application) {
    data class UiState(
        val project: TimelineProject = TimelineProject(),
        val selectedTrackId: String? = null,
        val selectedClipId: String? = null,
        val selectedClipIds: Set<String> = emptySet(),
        val status: String = "Ready",
        val exportFraction: Float? = null,
        val lastBackend: Backend? = null,
        val lastOutput: String? = null,
    )

    private val router = ProcessingRouter(application)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        _state.value = _state.value.copy(selectedTrackId = _state.value.project.tracks[1].id)
    }

    fun selectTrack(id: String) {
        _state.value = _state.value.copy(selectedTrackId = id)
    }

    /** A normal tap follows the link group. Until Unlink, video + source audio act as one edit item. */
    fun selectClip(id: String) {
        val state = _state.value
        val project = state.project
        val ids = project.linkedClipIds(id).ifEmpty { setOf(id) }
        val primary = primaryClipId(project, ids, id)
        _state.value = state.copy(
            selectedTrackId = project.trackContaining(id)?.id ?: state.selectedTrackId,
            selectedClipId = primary,
            selectedClipIds = ids,
        )
    }

    fun addTrack(kind: TrackKind) {
        val p = _state.value.project
        val count = p.tracks.count { it.kind == kind } + 1
        val prefix = if (kind == TrackKind.VIDEO) "V" else "A"
        val track = TimelineTrack(name = "$prefix$count", kind = kind)
        _state.value = _state.value.copy(
            project = p.copy(tracks = p.tracks + track),
            selectedTrackId = track.id,
        )
    }

    /** Importing video on a video track creates a linked video/audio pair. */
    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val app = getApplication<Application>()
        var state = _state.value
        var project = state.project
        var selected = project.track(state.selectedTrackId) ?: return

        if (selected.kind == TrackKind.VIDEO && project.tracks.none { it.kind == TrackKind.AUDIO }) {
            val audio = TimelineTrack(name = "A1", kind = TrackKind.AUDIO)
            project = project.copy(tracks = project.tracks + audio)
        }

        val audioTrack = project.tracks.firstOrNull { it.kind == TrackKind.AUDIO }
        var startUs = selected.clips.maxOfOrNull { it.timelineEndUs } ?: 0L
        if (selected.kind == TrackKind.VIDEO && audioTrack != null) {
            startUs = max(startUs, audioTrack.clips.maxOfOrNull { it.timelineEndUs } ?: 0L)
        }

        val additions = mutableMapOf<String, MutableList<TimelineClip>>()
        var firstSelection: Set<String>? = null
        var firstPrimary: String? = null

        uris.forEach { uri ->
            runCatching {
                app.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val mime = app.contentResolver.getType(uri).orEmpty()
            val durationUs = readDurationUs(uri).coerceAtLeast(1_000_000L)
            val label = uri.lastPathSegment?.substringAfterLast('/') ?: "Media"

            if (selected.kind == TrackKind.VIDEO && mime.startsWith("video/")) {
                val groupId = UUID.randomUUID().toString()
                val video = TimelineClip(
                    uri = uri.toString(),
                    label = label,
                    timelineStartUs = startUs,
                    sourceOutUs = durationUs,
                    linkGroupId = groupId,
                )
                additions.getOrPut(selected.id) { mutableListOf() } += video

                val audio = audioTrack?.let {
                    TimelineClip(
                        uri = uri.toString(),
                        label = "$label · audio",
                        timelineStartUs = startUs,
                        sourceOutUs = durationUs,
                        linkGroupId = groupId,
                    )
                }
                if (audio != null) {
                    additions.getOrPut(audioTrack.id) { mutableListOf() } += audio
                    if (firstSelection == null) firstSelection = linkedSetOf(video.id, audio.id)
                } else if (firstSelection == null) {
                    firstSelection = setOf(video.id)
                }
                if (firstPrimary == null) firstPrimary = video.id
                startUs += durationUs
                return@forEach
            }

            if (selected.kind == TrackKind.AUDIO && (mime.startsWith("audio/") || mime.startsWith("video/"))) {
                val clip = TimelineClip(
                    uri = uri.toString(),
                    label = label,
                    timelineStartUs = startUs,
                    sourceOutUs = durationUs,
                )
                additions.getOrPut(selected.id) { mutableListOf() } += clip
                if (firstSelection == null) firstSelection = setOf(clip.id)
                if (firstPrimary == null) firstPrimary = clip.id
                startUs += durationUs
            }
        }

        if (additions.isEmpty()) return
        val tracks = project.tracks.map { track ->
            val extra = additions[track.id].orEmpty()
            if (extra.isEmpty()) track else track.copy(clips = track.clips + extra)
        }
        project = project.copy(tracks = tracks)
        state = state.copy(
            project = project,
            selectedClipId = firstPrimary,
            selectedClipIds = firstSelection.orEmpty(),
            status = "Imported",
        )
        _state.value = state
    }

    /** Long-press drag calls this. A linked group is clamped and moved atomically. */
    fun moveClip(trackId: String, clipId: String, deltaUs: Long) {
        if (deltaUs == 0L) return
        val state = _state.value
        val project = state.project
        val target = project.clip(clipId) ?: return
        val movingIds = if (target.linkGroupId == null) setOf(clipId) else project.linkedClipIds(clipId)
        var lower = Long.MIN_VALUE / 4
        var upper = Long.MAX_VALUE / 4

        movingIds.forEach { id ->
            val clip = project.clip(id) ?: return@forEach
            val track = project.trackContaining(id) ?: return@forEach
            val others = track.clips.filter { it.id !in movingIds }
            val previous = others.filter { it.timelineEndUs <= clip.timelineStartUs }.maxByOrNull { it.timelineEndUs }
            val next = others.filter { it.timelineStartUs >= clip.timelineEndUs }.minByOrNull { it.timelineStartUs }
            val clipLower = max(-clip.timelineStartUs, previous?.let { it.timelineEndUs - clip.timelineStartUs } ?: Long.MIN_VALUE / 4)
            val clipUpper = next?.let { it.timelineStartUs - clip.timelineEndUs } ?: Long.MAX_VALUE / 4
            lower = max(lower, clipLower)
            upper = min(upper, clipUpper)
        }
        if (lower > upper) return
        val actualDelta = deltaUs.coerceIn(lower, upper)
        if (actualDelta == 0L) return

        val tracks = project.tracks.map { track ->
            track.copy(clips = track.clips.map { clip ->
                if (clip.id in movingIds) clip.copy(timelineStartUs = clip.timelineStartUs + actualDelta) else clip
            })
        }
        _state.value = state.copy(project = project.copy(tracks = tracks))
    }

    fun unlinkSelected() {
        val state = _state.value
        val project = state.project
        val groups = state.selectedClipIds.mapNotNull { project.clip(it)?.linkGroupId }.toSet()
        if (groups.isEmpty()) {
            _state.value = state.copy(status = "Selected clip is already unlinked")
            return
        }
        val tracks = project.tracks.map { track ->
            track.copy(clips = track.clips.map { clip ->
                if (clip.linkGroupId in groups) clip.copy(linkGroupId = null) else clip
            })
        }
        val primary = state.selectedClipId
        _state.value = state.copy(
            project = project.copy(tracks = tracks),
            selectedClipIds = primary?.let(::setOf).orEmpty(),
            status = "Audio and video unlinked",
        )
    }

    fun deleteSelected() {
        val state = _state.value
        val ids = state.selectedClipIds.ifEmpty {
            state.selectedClipId?.let { state.project.linkedClipIds(it) }.orEmpty()
        }
        if (ids.isEmpty()) return
        val tracks = state.project.tracks.map { track ->
            track.copy(clips = track.clips.filterNot { it.id in ids })
        }
        _state.value = state.copy(
            project = state.project.copy(tracks = tracks),
            selectedClipId = null,
            selectedClipIds = emptySet(),
            status = "Deleted ${ids.size} clip${if (ids.size == 1) "" else "s"}",
        )
    }

    fun splitSelectedAt(timelineUs: Long) {
        val state = _state.value
        val project = state.project
        val ids = state.selectedClipIds.ifEmpty {
            state.selectedClipId?.let { project.linkedClipIds(it) }.orEmpty()
        }
        if (ids.isEmpty()) return

        val originalGroup = ids.mapNotNull { project.clip(it)?.linkGroupId }.firstOrNull()
        val leftGroup = originalGroup?.let { UUID.randomUUID().toString() }
        val rightGroup = originalGroup?.let { UUID.randomUUID().toString() }
        val rightIds = linkedSetOf<String>()

        val tracks = project.tracks.map { track ->
            val rebuilt = mutableListOf<TimelineClip>()
            track.clips.forEach { clip ->
                if (clip.id !in ids || timelineUs <= clip.timelineStartUs || timelineUs >= clip.timelineEndUs) {
                    rebuilt += clip
                } else {
                    val sourceSplitUs = clip.sourceInUs + (timelineUs - clip.timelineStartUs)
                    val left = clip.copy(
                        sourceOutUs = sourceSplitUs,
                        linkGroupId = if (clip.linkGroupId == null) null else leftGroup,
                    )
                    val right = clip.copy(
                        id = UUID.randomUUID().toString(),
                        timelineStartUs = timelineUs,
                        sourceInUs = sourceSplitUs,
                        linkGroupId = if (clip.linkGroupId == null) null else rightGroup,
                    )
                    rebuilt += left
                    rebuilt += right
                    rightIds += right.id
                }
            }
            track.copy(clips = rebuilt)
        }

        if (rightIds.isEmpty()) {
            _state.value = state.copy(status = "Move playhead inside the selected clip to split")
            return
        }
        val nextProject = project.copy(tracks = tracks)
        val primary = primaryClipId(nextProject, rightIds, rightIds.first())
        _state.value = state.copy(
            project = nextProject,
            selectedClipId = primary,
            selectedClipIds = rightIds,
            selectedTrackId = nextProject.trackContaining(primary)?.id ?: state.selectedTrackId,
            status = "Split",
        )
    }

    fun selectNode(nodeId: String) {
        updatePrimaryClip { clip ->
            if (clip.nodeGraph.nodes.none { it.id == nodeId }) clip
            else clip.copy(nodeGraph = clip.nodeGraph.copy(selectedNodeId = nodeId))
        }
    }

    fun moveNode(nodeId: String, dx: Float, dy: Float) {
        updatePrimaryClip { clip ->
            val graph = clip.nodeGraph
            clip.copy(nodeGraph = graph.copy(nodes = graph.nodes.map { node ->
                if (node.id == nodeId) node.copy(
                    position = NodePosition(
                        (node.position.x + dx).coerceAtLeast(8f),
                        (node.position.y + dy).coerceAtLeast(8f),
                    ),
                ) else node
            }))
        }
    }

    fun addSerialNode(afterNodeId: String) {
        updatePrimaryClip { clip ->
            val graph = clip.nodeGraph
            val anchor = graph.nodes.firstOrNull { it.id == afterNodeId } ?: return@updatePrimaryClip clip
            if (anchor.kind == NodeKind.OUTPUT) return@updatePrimaryClip clip
            val outgoing = graph.edges.firstOrNull { it.fromId == anchor.id }
            val shiftStart = anchor.position.x + 24f
            val shifted = graph.nodes.map { node ->
                if (node.id != anchor.id && node.position.x > shiftStart) {
                    node.copy(position = node.position.copy(x = node.position.x + 126f))
                } else node
            }
            val index = shifted.count { it.kind == NodeKind.SERIAL || it.kind == NodeKind.PARALLEL } + 1
            val newNode = ColorNode(
                kind = NodeKind.SERIAL,
                label = index.toString().padStart(2, '0'),
                position = NodePosition(anchor.position.x + 126f, anchor.position.y),
            )
            val edges = graph.edges.filterNot { it == outgoing }.toMutableList()
            edges += NodeEdge(anchor.id, newNode.id)
            if (outgoing != null) edges += NodeEdge(newNode.id, outgoing.toId)
            val nextGraph = graph.copy(
                nodes = shifted + newNode,
                edges = edges,
                selectedNodeId = newNode.id,
            )
            clip.copy(nodeGraph = nextGraph, colorGrade = nextGraph.effectiveColorGrade())
        }
    }

    fun addParallelNode(anchorNodeId: String) {
        updatePrimaryClip { clip ->
            val graph = clip.nodeGraph
            val anchor = graph.nodes.firstOrNull { it.id == anchorNodeId } ?: return@updatePrimaryClip clip
            if (anchor.kind != NodeKind.SERIAL && anchor.kind != NodeKind.PARALLEL) return@updatePrimaryClip clip

            if (anchor.kind == NodeKind.PARALLEL) {
                val incoming = graph.edges.firstOrNull { it.toId == anchor.id } ?: return@updatePrimaryClip clip
                val mixEdge = graph.edges.firstOrNull { it.fromId == anchor.id }
                val mix = mixEdge?.toId?.let { id -> graph.nodes.firstOrNull { it.id == id && it.kind == NodeKind.MIX } }
                    ?: return@updatePrimaryClip clip
                val siblingCount = graph.edges.count { it.toId == mix.id }
                val parallel = ColorNode(
                    kind = NodeKind.PARALLEL,
                    label = "P${siblingCount + 1}",
                    position = NodePosition(anchor.position.x, anchor.position.y + 76f),
                )
                val nextGraph = graph.copy(
                    nodes = graph.nodes + parallel,
                    edges = graph.edges + listOf(NodeEdge(incoming.fromId, parallel.id), NodeEdge(parallel.id, mix.id)),
                    selectedNodeId = parallel.id,
                )
                return@updatePrimaryClip clip.copy(nodeGraph = nextGraph, colorGrade = nextGraph.effectiveColorGrade())
            }

            val incoming = graph.edges.firstOrNull { it.toId == anchor.id } ?: return@updatePrimaryClip clip
            val outgoing = graph.edges.firstOrNull { it.fromId == anchor.id } ?: return@updatePrimaryClip clip
            val shiftedNodes = graph.nodes.map { node ->
                if (node.position.x > anchor.position.x + 20f) node.copy(position = node.position.copy(x = node.position.x + 154f)) else node
            }
            val parallel = ColorNode(
                kind = NodeKind.PARALLEL,
                label = "P2",
                position = NodePosition(anchor.position.x, anchor.position.y + 76f),
            )
            val mix = ColorNode(
                kind = NodeKind.MIX,
                label = "Mix",
                position = NodePosition(anchor.position.x + 142f, anchor.position.y + 38f),
            )
            val edges = graph.edges.filterNot { it == outgoing }.toMutableList()
            edges += NodeEdge(incoming.fromId, parallel.id)
            edges += NodeEdge(anchor.id, mix.id)
            edges += NodeEdge(parallel.id, mix.id)
            edges += NodeEdge(mix.id, outgoing.toId)
            val nextGraph = graph.copy(
                nodes = shiftedNodes + parallel + mix,
                edges = edges,
                selectedNodeId = parallel.id,
            )
            clip.copy(nodeGraph = nextGraph, colorGrade = nextGraph.effectiveColorGrade())
        }
    }

    fun setSelectedNodeCorrection(parameter: String, value: Float) {
        updatePrimaryClip { clip ->
            val graph = clip.nodeGraph
            val selectedId = graph.selectedNodeId ?: return@updatePrimaryClip clip
            val selected = graph.nodes.firstOrNull { it.id == selectedId } ?: return@updatePrimaryClip clip
            if (selected.kind != NodeKind.SERIAL && selected.kind != NodeKind.PARALLEL) return@updatePrimaryClip clip
            val nextCorrections = selected.corrections.withValue(parameter, value)
            val nextGraph = graph.copy(nodes = graph.nodes.map { node ->
                if (node.id == selectedId) node.copy(corrections = nextCorrections) else node
            })
            clip.copy(nodeGraph = nextGraph, colorGrade = nextGraph.effectiveColorGrade())
        }
    }

    fun addEffectToSelectedNode(name: String, amount: Float = 1f) {
        updatePrimaryClip { clip ->
            val graph = clip.nodeGraph
            val selectedId = graph.selectedNodeId ?: return@updatePrimaryClip clip
            val selected = graph.nodes.firstOrNull { it.id == selectedId } ?: return@updatePrimaryClip clip
            if (selected.kind != NodeKind.SERIAL && selected.kind != NodeKind.PARALLEL) return@updatePrimaryClip clip
            val nextGraph = graph.copy(nodes = graph.nodes.map { node ->
                if (node.id == selectedId) {
                    val existing = node.effects.indexOfFirst { it.name == name }
                    if (existing >= 0) node.copy(effects = node.effects.mapIndexed { index, effect ->
                        if (index == existing) effect.copy(amount = amount, enabled = true) else effect
                    }) else node.copy(effects = node.effects + NodeEffect(name = name, amount = amount))
                } else node
            })
            clip.copy(nodeGraph = nextGraph)
        }
    }

    fun export() {
        val project = _state.value.project
        if (project.durationUs <= 0) {
            _state.value = _state.value.copy(status = "Timeline is empty")
            return
        }
        viewModelScope.launch {
            val output = File(getApplication<Application>().cacheDir, "digitor_${System.currentTimeMillis()}.mp4")
            runCatching {
                router.export(project, output) { progress ->
                    if (progress is ExportProgress.Stage) {
                        _state.value = _state.value.copy(status = progress.name, exportFraction = progress.fraction)
                    }
                }
            }.onSuccess { result ->
                _state.value = _state.value.copy(
                    status = result.note ?: "Export complete",
                    exportFraction = 1f,
                    lastBackend = result.backend,
                    lastOutput = result.output.absolutePath,
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(status = error.message ?: "Export failed", exportFraction = null)
            }
        }
    }

    private fun updatePrimaryClip(transform: (TimelineClip) -> TimelineClip) {
        val state = _state.value
        val id = state.selectedClipId ?: return
        val tracks = state.project.tracks.map { track ->
            track.copy(clips = track.clips.map { clip -> if (clip.id == id) transform(clip) else clip })
        }
        _state.value = state.copy(project = state.project.copy(tracks = tracks))
    }

    private fun primaryClipId(project: TimelineProject, ids: Set<String>, fallback: String): String {
        return ids.firstOrNull { project.trackContaining(it)?.kind == TrackKind.VIDEO } ?: fallback
    }

    private fun NodeCorrections.withValue(parameter: String, value: Float): NodeCorrections = when (parameter.lowercase()) {
        "exposure" -> copy(exposure = value)
        "contrast" -> copy(contrast = value)
        "saturation" -> copy(saturation = value)
        "temperature", "temp" -> copy(temperature = value)
        "tint" -> copy(tint = value)
        "highlights" -> copy(highlights = value)
        "shadows" -> copy(shadows = value)
        "hue" -> copy(hue = value)
        "color boost", "colorboost" -> copy(colorBoost = value)
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
