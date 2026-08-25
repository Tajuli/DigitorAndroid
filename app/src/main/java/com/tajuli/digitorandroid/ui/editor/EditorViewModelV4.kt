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
import com.tajuli.digitorandroid.editor.model.NodeCorrections
import com.tajuli.digitorandroid.editor.model.NodeEdge
import com.tajuli.digitorandroid.editor.model.NodeEffect
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.NodePosition
import com.tajuli.digitorandroid.editor.model.RgbCurves
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.TransformProperty
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

@UnstableApi
class EditorViewModelV4(application: Application) : AndroidViewModel(application) {
    data class UiState(
        val project: TimelineProject = TimelineProject(),
        val selectedTrackId: String? = null,
        val selectedClipId: String? = null,
        val selectedClipIds: Set<String> = emptySet(),
        val status: String = "Ready",
        val qualifierPickerActive: Boolean = false,
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
            qualifierPickerActive = false,
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
        val prefix = if (kind == TrackKind.VIDEO) "V" else "A"
        val nextNumber = project.tracks
            .asSequence()
            .filter { it.kind == kind && it.name.startsWith(prefix) }
            .mapNotNull { it.name.removePrefix(prefix).toIntOrNull() }
            .maxOrNull()
            ?.plus(1)
            ?: 1
        val track = TimelineTrack(name = "$prefix$nextNumber", kind = kind)
        val tracks = if (kind == TrackKind.VIDEO) {
            listOf(track) + project.tracks
        } else {
            project.tracks + track
        }
        _state.value = _state.value.copy(
            project = project.copy(tracks = tracks),
            selectedTrackId = track.id,
            status = "${track.name} added",
        )
    }

    /**
     * V tracks accept video only and A tracks accept audio only. Embedded source audio is linked
     * to the matching numbered audio track (V1 -> A1, V2 -> A2, ...). A matching A track is
     * created lazily only when the imported video actually contains an audio stream.
     */
    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val app = getApplication<Application>()
        var state = _state.value
        var project = state.project
        val selected = project.track(state.selectedTrackId) ?: return
        val videoTrackNumber = if (selected.kind == TrackKind.VIDEO && selected.name.startsWith("V")) {
            selected.name.removePrefix("V").toIntOrNull()
        } else {
            null
        }
        var sourceAudioTrack = videoTrackNumber?.let { number ->
            project.tracks.firstOrNull { it.kind == TrackKind.AUDIO && it.name == "A$number" }
        }
        var startUs = selected.clips.maxOfOrNull { it.timelineEndUs } ?: 0L

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
                    if (!mime.startsWith("video/")) {
                        skipped++
                        continue
                    }

                    val embeddedAudio = hasEmbeddedAudio(uri)
                    if (embeddedAudio && sourceAudioTrack == null && videoTrackNumber != null) {
                        val audioTrack = TimelineTrack(name = "A$videoTrackNumber", kind = TrackKind.AUDIO)
                        project = project.copy(tracks = project.tracks + audioTrack)
                        sourceAudioTrack = audioTrack
                    }
                    val pairedAudioTrack = if (embeddedAudio) sourceAudioTrack else null
                    if (pairedAudioTrack != null) {
                        val existingEndUs = pairedAudioTrack.clips.maxOfOrNull { it.timelineEndUs } ?: 0L
                        val pendingEndUs = additions[pairedAudioTrack.id]
                            ?.maxOfOrNull { it.timelineEndUs }
                            ?: 0L
                        startUs = max(startUs, max(existingEndUs, pendingEndUs))
                    }

                    val group = pairedAudioTrack?.let { UUID.randomUUID().toString() }
                    val video = TimelineClip(
                        uri = uri.toString(),
                        label = label,
                        timelineStartUs = startUs,
                        sourceOutUs = durationUs,
                        linkGroupId = group,
                    )
                    additions.getOrPut(selected.id) { mutableListOf() } += video
                    val ids = linkedSetOf(video.id)
                    if (pairedAudioTrack != null && group != null) {
                        val audio = TimelineClip(
                            uri = uri.toString(),
                            label = "$label · audio",
                            timelineStartUs = startUs,
                            sourceOutUs = durationUs,
                            linkGroupId = group,
                        )
                        additions.getOrPut(pairedAudioTrack.id) { mutableListOf() } += audio
                        ids += audio.id
                    }
                    if (firstPrimary == null) firstPrimary = video.id
                    if (firstSelection == null) firstSelection = ids
                    startUs += durationUs
                }

                TrackKind.AUDIO -> {
                    if (!mime.startsWith("audio/")) {
                        skipped++
                        continue
                    }
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
            _state.value = state.copy(
                status = if (selected.kind == TrackKind.AUDIO) "A track accepts audio files only" else "V track accepts video files only",
            )
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
            lower = max(
                lower,
                max(-clip.timelineStartUs, previous?.let { it.timelineEndUs - clip.timelineStartUs } ?: Long.MIN_VALUE / 4),
            )
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
        if (target.clips.any { other ->
                other.id != clip.id && clip.timelineStartUs < other.timelineEndUs && clip.timelineEndUs > other.timelineStartUs
            }
        ) {
            _state.value = state.copy(status = "Cannot drop: target track overlaps")
            return
        }
        val tracks = project.tracks.map { track ->
            when (track.id) {
                from.id -> track.copy(clips = track.clips.filterNot { it.id == clip.id })
                target.id -> track.copy(clips = track.clips + clip)
                else -> track
            }
        }
        _state.value = state.copy(
            project = project.copy(tracks = tracks),
            selectedTrackId = target.id,
            status = "Moved to ${target.name}",
        )
    }

    fun unlinkSelected() {
        val state = _state.value
        val groups = state.selectedClipIds.mapNotNull { state.project.clip(it)?.linkGroupId }.toSet()
        if (groups.isEmpty()) {
            _state.value = state.copy(status = "Already unlinked")
            return
        }
        val tracks = state.project.tracks.map { track ->
            track.copy(clips = track.clips.map { clip ->
                if (clip.linkGroupId in groups) clip.copy(linkGroupId = null) else clip
            })
        }
        val primary = state.selectedClipId
        _state.value = state.copy(
            project = state.project.copy(tracks = tracks),
            selectedClipIds = primary?.let(::setOf).orEmpty(),
            status = "Unlinked",
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
            status = "Deleted",
            qualifierPickerActive = false,
        )
    }

    fun splitSelectedAt(timelineUs: Long) {
        val state = _state.value
        val project = state.project
        val ids = state.selectedClipIds.ifEmpty {
            state.selectedClipId?.let { project.linkedClipIds(it) }.orEmpty()
        }
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
                    val splitLocalUs = timelineUs - clip.timelineStartUs
                    val sourceSplit = clip.sourceInUs + splitLocalUs
                    val (leftTransform, rightTransform) = clip.transform.splitAt(splitLocalUs)
                    rebuilt += clip.copy(
                        sourceOutUs = sourceSplit,
                        linkGroupId = if (clip.linkGroupId == null) null else leftGroup,
                        transform = leftTransform,
                    )
                    val right = clip.copy(
                        id = UUID.randomUUID().toString(),
                        timelineStartUs = timelineUs,
                        sourceInUs = sourceSplit,
                        linkGroupId = if (clip.linkGroupId == null) null else rightGroup,
                        transform = rightTransform,
                    )
                    rebuilt += right
                    rightIds += right.id
                }
            }
            track.copy(clips = rebuilt)
        }
        if (rightIds.isEmpty()) {
            _state.value = state.copy(status = "Put cursor inside selected clip")
            return
        }
        val next = project.copy(tracks = tracks)
        val primary = rightIds.firstOrNull { next.trackContaining(it)?.kind == TrackKind.VIDEO } ?: rightIds.first()
        _state.value = state.copy(
            project = next,
            selectedClipId = primary,
            selectedClipIds = rightIds,
            selectedTrackId = next.trackContaining(primary)?.id,
            status = "Split",
        )
    }

    fun setTransformProperty(property: TransformProperty, value: Float, timelineUs: Long) {
        val state = _state.value
        val frameRate = state.project.frameRate
        updatePrimaryClip { clip ->
            val localUs = snappedTransformTimeUs(clip, timelineUs, frameRate)
            clip.copy(transform = clip.transform.setEditorValue(property, localUs, value))
        }
    }

    fun toggleTransformKeyframe(property: TransformProperty, timelineUs: Long) {
        val state = _state.value
        val frameRate = state.project.frameRate
        updatePrimaryClip { clip ->
            val localUs = snappedTransformTimeUs(clip, timelineUs, frameRate)
            clip.copy(transform = clip.transform.toggleKeyframe(property, localUs))
        }
        _state.value = _state.value.copy(status = "Transform keyframe updated")
    }

    fun toggleAllTransformKeyframes(timelineUs: Long) {
        val state = _state.value
        val frameRate = state.project.frameRate
        updatePrimaryClip { clip ->
            val localUs = snappedTransformTimeUs(clip, timelineUs, frameRate)
            clip.copy(transform = clip.transform.toggleAllKeyframes(localUs))
        }
        _state.value = _state.value.copy(status = "Transform keyframes updated")
    }

    fun resetTransformAt(timelineUs: Long) {
        val state = _state.value
        val frameRate = state.project.frameRate
        updatePrimaryClip { clip ->
            val localUs = snappedTransformTimeUs(clip, timelineUs, frameRate)
            clip.copy(transform = clip.transform.resetAt(localUs))
        }
        _state.value = _state.value.copy(status = "Transform reset")
    }

    fun transformKeyframeLocalUs(clip: TimelineClip, timelineUs: Long): Long =
        snappedTransformTimeUs(clip, timelineUs, _state.value.project.frameRate)

    private fun snappedTransformTimeUs(clip: TimelineClip, timelineUs: Long, frameRate: Int): Long {
        val raw = (timelineUs - clip.timelineStartUs).coerceIn(0L, clip.durationUs)
        val frameUs = (1_000_000.0 / frameRate.coerceAtLeast(1)).roundToLong().coerceAtLeast(1L)
        return ((raw.toDouble() / frameUs).roundToLong() * frameUs).coerceIn(0L, clip.durationUs)
    }

    fun selectNode(nodeId: String) = updatePrimaryClip { clip ->
        if (clip.nodeGraph.nodes.none { it.id == nodeId }) clip
        else clip.copy(nodeGraph = clip.nodeGraph.copy(selectedNodeId = nodeId))
    }

    fun moveNode(nodeId: String, dx: Float, dy: Float) = updatePrimaryClip { clip ->
        clip.copy(nodeGraph = clip.nodeGraph.copy(nodes = clip.nodeGraph.nodes.map { node ->
            if (node.id == nodeId) {
                node.copy(position = NodePosition((node.position.x + dx).coerceAtLeast(8f), (node.position.y + dy).coerceAtLeast(8f)))
            } else node
        }))
    }

    fun addSerialNode(afterNodeId: String) = updatePrimaryClip { clip ->
        val graph = clip.nodeGraph
        val anchor = graph.nodes.firstOrNull { it.id == afterNodeId } ?: return@updatePrimaryClip clip
        if (anchor.kind == NodeKind.OUTPUT) return@updatePrimaryClip clip
        val outgoing = graph.edges.firstOrNull { it.fromId == anchor.id }
        val shifted = graph.nodes.map { node ->
            if (node.id != anchor.id && node.position.x > anchor.position.x + 24f) {
                node.copy(position = node.position.copy(x = node.position.x + 126f))
            } else node
        }
        val count = shifted.count { it.kind == NodeKind.SERIAL || it.kind == NodeKind.PARALLEL } + 1
        val node = ColorNode(
            kind = NodeKind.SERIAL,
            label = count.toString().padStart(2, '0'),
            position = NodePosition(anchor.position.x + 126f, anchor.position.y),
        )
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
            val node = ColorNode(
                kind = NodeKind.PARALLEL,
                label = "P${count + 1}",
                position = NodePosition(anchor.position.x, anchor.position.y + 76f),
            )
            return@updatePrimaryClip clip.copy(
                nodeGraph = graph.copy(
                    nodes = graph.nodes + node,
                    edges = graph.edges + listOf(NodeEdge(incoming.fromId, node.id), NodeEdge(node.id, mix.id)),
                    selectedNodeId = node.id,
                ),
            )
        }
        val incoming = graph.edges.firstOrNull { it.toId == anchor.id } ?: return@updatePrimaryClip clip
        val outgoing = graph.edges.firstOrNull { it.fromId == anchor.id } ?: return@updatePrimaryClip clip
        val shifted = graph.nodes.map { node ->
            if (node.position.x > anchor.position.x + 20f) {
                node.copy(position = node.position.copy(x = node.position.x + 154f))
            } else node
        }
        val parallel = ColorNode(
            kind = NodeKind.PARALLEL,
            label = "P2",
            position = NodePosition(anchor.position.x, anchor.position.y + 76f),
        )
        val mixNode = ColorNode(
            kind = NodeKind.MIX,
            label = "Mix",
            position = NodePosition(anchor.position.x + 142f, anchor.position.y + 38f),
        )
        val edges = graph.edges.filterNot { it == outgoing }.toMutableList()
        edges += NodeEdge(incoming.fromId, parallel.id)
        edges += NodeEdge(anchor.id, mixNode.id)
        edges += NodeEdge(parallel.id, mixNode.id)
        edges += NodeEdge(mixNode.id, outgoing.toId)
        clip.copy(nodeGraph = graph.copy(nodes = shifted + parallel + mixNode, edges = edges, selectedNodeId = parallel.id))
    }

    fun deleteNode(nodeId: String) = updatePrimaryClip { clip ->
        clip.copy(nodeGraph = clip.nodeGraph.deleteEditableNodeV4(nodeId))
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
        if (existing >= 0) {
            node.copy(effects = node.effects.mapIndexed { index, effect ->
                if (index == existing) effect.copy(amount = amount, enabled = true) else effect
            })
        } else {
            node.copy(effects = node.effects + NodeEffect(name = name, amount = amount))
        }
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

    fun setPrimaryWheelPuck(wheel: String, x: Float, y: Float) = updateSelectedEditableNode { node ->
        val p = node.advancedColor.primary
        val next = when (wheel.lowercase()) {
            "lift" -> p.copy(lift = p.lift.withPuck(x, y, .72f))
            "gamma" -> p.copy(gamma = p.gamma.withPuck(x, y, .52f))
            "gain" -> p.copy(gain = p.gain.withPuck(x, y, .72f))
            "offset" -> p.copy(offset = p.offset.withPuck(x, y, .52f))
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
            "global" -> log.copy(global = log.global.withComponent(component, value))
            else -> log
        }
        node.copy(advancedColor = node.advancedColor.copy(log = next))
    }

    fun setLogWheelPuck(zone: String, x: Float, y: Float) = updateSelectedEditableNode { node ->
        val log = node.advancedColor.log
        val next = when (zone.lowercase()) {
            "shadows" -> log.copy(shadows = log.shadows.withPuck(x, y, .90f))
            "midtones" -> log.copy(midtones = log.midtones.withPuck(x, y, .90f))
            "highlights" -> log.copy(highlights = log.highlights.withPuck(x, y, .90f))
            "global" -> log.copy(global = log.global.withPuck(x, y, .75f))
            else -> log
        }
        node.copy(advancedColor = node.advancedColor.copy(log = next))
    }

    fun setLogRange(shadowRange: Float? = null, highlightRange: Float? = null) = updateSelectedEditableNode { node ->
        val log = node.advancedColor.log
        node.copy(
            advancedColor = node.advancedColor.copy(
                log = log.copy(
                    shadowRange = (shadowRange ?: log.shadowRange).coerceIn(.05f, .48f),
                    highlightRange = (highlightRange ?: log.highlightRange).coerceIn(.52f, .95f),
                ),
            ),
        )
    }

    /** Compatibility method used by older V4 UI. */
    fun setCurvePoint(channel: String, index: Int, value: Float) = updateCurve(channel) { curve ->
        curve.withPoint(index, value)
    }

    fun setCurvePoint(channel: String, index: Int, x: Float, y: Float) = updateCurve(channel) { curve ->
        curve.withPoint(index, x, y)
    }

    fun insertCurvePoint(channel: String, x: Float, y: Float) = updateCurve(channel) { curve ->
        curve.insertPoint(x, y)
    }

    fun deleteCurvePoint(channel: String, index: Int) = updateCurve(channel) { curve ->
        curve.deletePoint(index)
    }

    fun setQualifierEnabled(enabled: Boolean) = updateSelectedEditableNode { node ->
        node.copy(
            advancedColor = node.advancedColor.copy(
                qualifier = node.advancedColor.qualifier.copy(enabled = enabled),
            ),
        )
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

    fun setQualifierPickerActive(active: Boolean) {
        _state.value = _state.value.copy(
            qualifierPickerActive = active,
            status = if (active) "Qualifier picker: tap a color in Preview" else _state.value.status,
        )
    }

    /**
     * Samples the displayed source frame at the tapped preview location. PlayerView uses FIT, so
     * letterbox bars are removed from the coordinate mapping before reading the source pixel.
     */
    fun pickQualifierFromPreview(
        timelineUs: Long,
        tapX: Float,
        tapY: Float,
        previewWidth: Float,
        previewHeight: Float,
    ) {
        val state = _state.value
        val clip = state.project.clip(state.selectedClipId) ?: return
        if (state.project.trackContaining(clip.id)?.kind != TrackKind.VIDEO) {
            _state.value = state.copy(status = "Select a video clip for qualifier picking")
            return
        }
        if (previewWidth <= 0f || previewHeight <= 0f) return

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(getApplication<Application>(), Uri.parse(clip.uri))
            val sourceUs = (clip.sourceInUs + (timelineUs - clip.timelineStartUs))
                .coerceIn(clip.sourceInUs, clip.sourceOutUs.coerceAtLeast(clip.sourceInUs))
            val bitmap = retriever.getFrameAtTime(sourceUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: run {
                    _state.value = state.copy(status = "Could not sample this frame")
                    return
                }
            try {
                val scale = min(previewWidth / bitmap.width.toFloat(), previewHeight / bitmap.height.toFloat())
                val shownWidth = bitmap.width * scale
                val shownHeight = bitmap.height * scale
                val left = (previewWidth - shownWidth) * .5f
                val top = (previewHeight - shownHeight) * .5f
                if (tapX < left || tapX > left + shownWidth || tapY < top || tapY > top + shownHeight) {
                    _state.value = state.copy(status = "Tap inside the video image")
                    return
                }
                val imageX = (((tapX - left) / shownWidth) * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
                val imageY = (((tapY - top) / shownHeight) * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)

                var rr = 0f
                var gg = 0f
                var bb = 0f
                var samples = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val x = (imageX + dx).coerceIn(0, bitmap.width - 1)
                        val y = (imageY + dy).coerceIn(0, bitmap.height - 1)
                        val argb = bitmap.getPixel(x, y)
                        rr += ((argb ushr 16) and 0xFF) / 255f
                        gg += ((argb ushr 8) and 0xFF) / 255f
                        bb += (argb and 0xFF) / 255f
                        samples++
                    }
                }
                val r = rr / samples
                val g = gg / samples
                val b = bb / samples
                val hsl = rgbToHsl(r, g, b)
                val hue = hsl[0] * 360f
                val sat = hsl[1]
                val lum = hsl[2]
                updateSelectedEditableNode { node ->
                    val old = node.advancedColor.qualifier
                    val picked = old.copy(
                        enabled = true,
                        hueCenterDegrees = hue,
                        hueWidthDegrees = 34f,
                        saturationMin = (sat - .18f).coerceIn(0f, 1f),
                        saturationMax = (sat + .18f).coerceIn(0f, 1f),
                        luminanceMin = (lum - .18f).coerceIn(0f, 1f),
                        luminanceMax = (lum + .18f).coerceIn(0f, 1f),
                        softness = .12f,
                        pickedRed = r,
                        pickedGreen = g,
                        pickedBlue = b,
                    )
                    node.copy(advancedColor = node.advancedColor.copy(qualifier = picked))
                }
                _state.value = _state.value.copy(
                    qualifierPickerActive = false,
                    status = "Qualifier color sampled",
                )
            } finally {
                bitmap.recycle()
            }
        } catch (error: Throwable) {
            _state.value = _state.value.copy(
                qualifierPickerActive = false,
                status = error.message ?: "Qualifier sampling failed",
            )
        } finally {
            retriever.release()
        }
    }

    private fun updateCurve(channel: String, transform: (Curve5) -> Curve5) = updateSelectedEditableNode { node ->
        val curves = node.advancedColor.curves
        val next: RgbCurves = when (channel.lowercase()) {
            "master", "rgb", "y" -> curves.copy(master = transform(curves.master))
            "red", "r" -> curves.copy(red = transform(curves.red))
            "green", "g" -> curves.copy(green = transform(curves.green))
            "blue", "b" -> curves.copy(blue = transform(curves.blue))
            else -> curves
        }
        node.copy(advancedColor = node.advancedColor.copy(curves = next))
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
        val tracks = state.project.tracks.map { track ->
            track.copy(clips = track.clips.map { clip -> if (clip.id == id) transform(clip) else clip })
        }
        _state.value = state.copy(project = state.project.copy(tracks = tracks))
    }

    private fun ColorWheelValue.withComponent(component: String, value: Float): ColorWheelValue = when (component.lowercase()) {
        "red", "r" -> copy(red = value.coerceIn(-1f, 1f))
        "green", "g" -> copy(green = value.coerceIn(-1f, 1f))
        "blue", "b" -> copy(blue = value.coerceIn(-1f, 1f))
        "luma", "y" -> copy(luma = value.coerceIn(-1f, 1f))
        else -> this
    }

    private fun ColorWheelValue.withPuck(x: Float, y: Float, scale: Float): ColorWheelValue {
        var nx = x.coerceIn(-1f, 1f)
        var ny = y.coerceIn(-1f, 1f)
        val length = sqrt(nx * nx + ny * ny)
        if (length > 1f) {
            nx /= length
            ny /= length
        }
        val radius = sqrt(nx * nx + ny * ny).coerceIn(0f, 1f)
        if (radius < .0001f) {
            return copy(red = 0f, green = 0f, blue = 0f, puckX = 0f, puckY = 0f)
        }
        var hue = atan2((-ny).toDouble(), nx.toDouble()) / (2.0 * PI)
        if (hue < 0.0) hue += 1.0
        val rgb = hueToRgb(hue.toFloat())
        val average = (rgb[0] + rgb[1] + rgb[2]) / 3f
        return copy(
            red = ((rgb[0] - average) * radius * scale).coerceIn(-1f, 1f),
            green = ((rgb[1] - average) * radius * scale).coerceIn(-1f, 1f),
            blue = ((rgb[2] - average) * radius * scale).coerceIn(-1f, 1f),
            puckX = nx,
            puckY = ny,
        )
    }

    private fun hueToRgb(h: Float): FloatArray {
        val hh = ((h % 1f) + 1f) % 1f * 6f
        val sector = hh.toInt().coerceIn(0, 5)
        val f = hh - sector
        return when (sector) {
            0 -> floatArrayOf(1f, f, 0f)
            1 -> floatArrayOf(1f - f, 1f, 0f)
            2 -> floatArrayOf(0f, 1f, f)
            3 -> floatArrayOf(0f, 1f - f, 1f)
            4 -> floatArrayOf(f, 0f, 1f)
            else -> floatArrayOf(1f, 0f, 1f - f)
        }
    }

    private fun rgbToHsl(r0: Float, g0: Float, b0: Float): FloatArray {
        val r = r0.coerceIn(0f, 1f)
        val g = g0.coerceIn(0f, 1f)
        val b = b0.coerceIn(0f, 1f)
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) * .5f
        if (mx == mn) return floatArrayOf(0f, 0f, l)
        val d = mx - mn
        val s = if (l > .5f) d / (2f - mx - mn) else d / (mx + mn)
        val h = when (mx) {
            r -> ((g - b) / d + if (g < b) 6f else 0f) / 6f
            g -> ((b - r) / d + 2f) / 6f
            else -> ((r - g) / d + 4f) / 6f
        }
        return floatArrayOf(h, s, l)
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

    private fun hasEmbeddedAudio(uri: Uri): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(getApplication<Application>(), uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                ?.equals("yes", ignoreCase = true) == true
        } catch (_: Throwable) {
            false
        } finally {
            retriever.release()
        }
    }
}
