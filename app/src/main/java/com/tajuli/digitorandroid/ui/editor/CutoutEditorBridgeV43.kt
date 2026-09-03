package com.tajuli.digitorandroid.ui.editor

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.tajuli.digitorandroid.editor.model.ClipCutoutV43
import com.tajuli.digitorandroid.editor.model.CutoutModeV43
import com.tajuli.digitorandroid.editor.model.PreviewTransformClock
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.preview.PreviewExportCoordinator
import com.tajuli.digitorandroid.editor.processing.PersonCutoutAnalyzerV43
import com.tajuli.digitorandroid.editor.processing.hasPersonCutoutCoverageV43
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val personCutoutAnalysisInFlightV43 = ConcurrentHashMap.newKeySet<String>()

/** Public-state bridge keeps V43 isolated without widening EditorViewModelV4's private mutation API. */
fun EditorViewModelV4.setSelectedCutoutV43(
    settings: ClipCutoutV43,
    status: String = "Cutout updated",
    coalesce: Boolean = true,
) {
    val snapshot = state.value
    val id = snapshot.selectedClipId ?: run {
        setEditorStatusV19("Select a video/image clip for Cutout")
        return
    }
    if (snapshot.project.trackContaining(id)?.kind != TrackKind.VIDEO) {
        setEditorStatusV19("Cutout works on video/image clips")
        return
    }
    val normalized = settings.normalized()
    val tracks = snapshot.project.tracks.map { track ->
        track.copy(clips = track.clips.map { clip ->
            if (clip.id == id) clip.copy(cutoutV43 = normalized) else clip
        })
    }
    commitProjectV19(
        label = "cutout-v43",
        project = snapshot.project.copy(tracks = tracks),
        status = status,
        coalesce = coalesce,
    )
}

fun EditorViewModelV4.enablePersonCutoutV43(settings: ClipCutoutV43) {
    val person = settings.copy(mode = CutoutModeV43.PERSON).normalized()
    setSelectedCutoutV43(person, status = "Auto Cutout enabled", coalesce = false)
    val clip = state.value.project.clip(state.value.selectedClipId) ?: return
    val app = getApplication<Application>()
    if (hasPersonCutoutCoverageV43(app.applicationContext, clip)) {
        setEditorStatusV19("Auto Cutout ready · cached matte")
        PreviewExportCoordinator.refreshActivePreviews()
    } else {
        analyzeSelectedPersonCutoutV43()
    }
}

fun EditorViewModelV4.analyzeSelectedPersonCutoutV43() {
    val snapshot = state.value
    val clip = snapshot.project.clip(snapshot.selectedClipId) ?: run {
        setEditorStatusV19("Select a video/image clip for Auto Cutout")
        return
    }
    if (snapshot.project.trackContaining(clip.id)?.kind != TrackKind.VIDEO) {
        setEditorStatusV19("Auto Cutout works on video/image clips")
        return
    }
    val analysisKey = "${clip.uri}|${clip.sourceInUs}|${clip.sourceOutUs}"
    if (!personCutoutAnalysisInFlightV43.add(analysisKey)) {
        setEditorStatusV19("Auto Cutout · analysis already running")
        return
    }

    val clockLocalUs = PreviewTransformClock.snapshotFor(clip.id)?.localUs
    val prioritySourceUs = clockLocalUs?.let { localUs ->
        (clip.sourceInUs + localUs).coerceIn(
            clip.sourceInUs.coerceAtLeast(0L),
            (clip.sourceOutUs - 1L).coerceAtLeast(clip.sourceInUs.coerceAtLeast(0L)),
        )
    }

    setEditorStatusV19("Auto Cutout · preparing analysis…")
    val app = getApplication<Application>()
    viewModelScope.launch(Dispatchers.Default) {
        try {
            val result = runCatching {
                // MediaMetadataRetriever can contend with the realtime MediaCodec preview on
                // low/mid-range devices. Give semantic analysis exclusive ownership of decoder/GPU
                // resources; the lease restores the exact paused preview when analysis finishes.
                PreviewExportCoordinator.acquireAnalysisLease().use {
                    PersonCutoutAnalyzerV43(app.applicationContext).analyzeAndStore(
                        clip = clip,
                        prioritySourceUs = prioritySourceUs,
                        onAnchorStored = { completed ->
                            if (completed == 1 || completed % 8 == 0) {
                                viewModelScope.launch(Dispatchers.Main) {
                                    setEditorStatusV19("Auto Cutout · analyzing… $completed matte frame(s)")
                                }
                            }
                        },
                    )
                }
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { track ->
                    val ready = hasPersonCutoutCoverageV43(app.applicationContext, clip)
                    PreviewExportCoordinator.refreshActivePreviews(220L)
                    if (ready) {
                        setEditorStatusV19("Auto Cutout ready · ${track.frames.size} cached matte frame(s)")
                    } else {
                        setEditorStatusV19("Auto Cutout incomplete · tap Analyze again")
                    }
                }.onFailure { error ->
                    PreviewExportCoordinator.refreshActivePreviews(220L)
                    val detail = error.message?.takeIf { it.isNotBlank() }
                        ?: error::class.java.simpleName
                    setEditorStatusV19("Auto Cutout failed · $detail")
                }
            }
        } finally {
            personCutoutAnalysisInFlightV43.remove(analysisKey)
        }
    }
}
