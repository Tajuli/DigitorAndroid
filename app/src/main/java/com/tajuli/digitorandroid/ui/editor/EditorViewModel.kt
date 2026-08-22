package com.tajuli.digitorandroid.ui.editor

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.processing.Backend
import com.tajuli.digitorandroid.editor.processing.ExportProgress
import com.tajuli.digitorandroid.editor.processing.ProcessingRouter
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@UnstableApi
class EditorViewModel(application: Application) : AndroidViewModel(application) {
    data class UiState(
        val project: TimelineProject = TimelineProject(),
        val selectedTrackId: String? = null,
        val selectedClipId: String? = null,
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

    fun selectTrack(id: String) { _state.value = _state.value.copy(selectedTrackId = id) }
    fun selectClip(id: String) { _state.value = _state.value.copy(selectedClipId = id) }

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

    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val app = getApplication<Application>()
        val current = _state.value
        val selected = current.project.track(current.selectedTrackId) ?: return
        var startUs = selected.clips.maxOfOrNull { it.timelineEndUs } ?: 0L
        val newClips = mutableListOf<TimelineClip>()
        uris.forEach { uri ->
            runCatching {
                app.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val mime = app.contentResolver.getType(uri).orEmpty()
            val compatible = when (selected.kind) {
                TrackKind.VIDEO -> mime.startsWith("video/")
                TrackKind.AUDIO -> mime.startsWith("audio/") || mime.startsWith("video/")
            }
            if (!compatible) return@forEach
            val durationUs = readDurationUs(uri).coerceAtLeast(1_000_000L)
            newClips += TimelineClip(
                uri = uri.toString(),
                label = uri.lastPathSegment?.substringAfterLast('/') ?: "Media",
                timelineStartUs = startUs,
                sourceOutUs = durationUs,
            )
            startUs += durationUs
        }
        if (newClips.isEmpty()) return
        val tracks = current.project.tracks.map {
            if (it.id == selected.id) it.copy(clips = it.clips + newClips) else it
        }
        _state.value = current.copy(
            project = current.project.copy(tracks = tracks),
            selectedClipId = newClips.first().id,
        )
    }

    fun moveClip(trackId: String, clipId: String, deltaUs: Long) {
        val p = _state.value.project
        val tracks = p.tracks.map { track ->
            if (track.id != trackId) return@map track
            val moving = track.clips.firstOrNull { it.id == clipId } ?: return@map track
            val others = track.clips.filterNot { it.id == clipId }.sortedBy { it.timelineStartUs }
            var nextStart = (moving.timelineStartUs + deltaUs).coerceAtLeast(0L)
            val previous = others.filter { it.timelineStartUs <= nextStart }.maxByOrNull { it.timelineStartUs }
            val next = others.filter { it.timelineStartUs > nextStart }.minByOrNull { it.timelineStartUs }
            if (previous != null) nextStart = nextStart.coerceAtLeast(previous.timelineEndUs)
            if (next != null) nextStart = nextStart.coerceAtMost((next.timelineStartUs - moving.durationUs).coerceAtLeast(0L))
            track.copy(clips = track.clips.map {
                if (it.id == clipId) it.copy(timelineStartUs = nextStart) else it
            })
        }
        _state.value = _state.value.copy(project = p.copy(tracks = tracks))
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
