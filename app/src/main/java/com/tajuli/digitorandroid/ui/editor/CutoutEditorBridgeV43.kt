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

/** V44 bridge retains historical symbols so existing project/editor code remains source-compatible. */
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
        label = "cutout-v44",
        project = snapshot.project.copy(tracks = tracks),
        status = status,
        coalesce = coalesce,
    )
}

fun EditorViewModelV4.enablePersonCutoutV43(settings: ClipCutoutV43) {
    val person = settings.copy(mode = CutoutModeV43.PERSON).normalized()
    setSelectedCutoutV43(person, status = "Pro Cutout enabled", coalesce = false)
    val clip = state.value.project.clip(state.value.selectedClipId) ?: return
    val app = getApplication<Application>()
    if (hasPersonCutoutCoverageV43(app.applicationContext, clip)) {
        setEditorStatusV19("Pro Cutout ready · cached MODNet matte")
        PreviewExportCoordinator.refreshActivePreviews()
    } else {
        analyzeSelectedPersonCutoutV43()
    }
}

fun EditorViewModelV4.analyzeSelectedPersonCutoutV43() {
    val snapshot = state.value
    val clip = snapshot.project.clip(snapshot.selectedClipId) ?: run {
        setEditorStatusV19("Select a video/image clip for Pro Cutout")
        return
    }
    if (snapshot.project.trackContaining(clip.id)?.kind != TrackKind.VIDEO) {
        setEditorStatusV19("Pro Cutout works on video/image clips")
        return
    }
    val settings = clip.resolvedCutoutV43()
    val analysisKey = buildString {
        append(clip.uri); append('|'); append(clip.sourceInUs); append('|'); append(clip.sourceOutUs)
        append('|'); append(settings.hairDetailV44); append('|'); append(settings.temporalStabilityV44)
    }
    if (!personCutoutAnalysisInFlightV43.add(analysisKey)) {
        setEditorStatusV19("Pro Cutout · analysis already running")
        return
    }

    val clockLocalUs = PreviewTransformClock.snapshotFor(clip.id)?.localUs
    val prioritySourceUs = clockLocalUs?.let { localUs ->
        (clip.sourceInUs + localUs).coerceIn(
            clip.sourceInUs.coerceAtLeast(0L),
            (clip.sourceOutUs - 1L).coerceAtLeast(clip.sourceInUs.coerceAtLeast(0L)),
        )
    }

    setEditorStatusV19("Pro Cutout · loading portrait matting engine…")
    val app = getApplication<Application>()
    viewModelScope.launch(Dispatchers.Default) {
        try {
            val result = runCatching {
                PreviewExportCoordinator.acquireAnalysisLease().use {
                    PersonCutoutAnalyzerV43(app.applicationContext).analyzeAndStore(
                        clip = clip,
                        prioritySourceUs = prioritySourceUs,
                        onAnchorStored = { completed ->
                            if (completed == 1 || completed % 8 == 0) {
                                viewModelScope.launch(Dispatchers.Main) {
                                    setEditorStatusV19("Pro Cutout · MODNet matting… $completed frame(s)")
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
                        setEditorStatusV19("Pro Cutout ready · ${track.frames.size} refined matte frame(s)")
                    } else {
                        setEditorStatusV19("Pro Cutout incomplete · tap Analyze again")
                    }
                }.onFailure { error ->
                    PreviewExportCoordinator.refreshActivePreviews(220L)
                    val detail = error.message?.takeIf { it.isNotBlank() }
                        ?: error::class.java.simpleName
                    setEditorStatusV19("Pro Cutout failed · $detail")
                }
            }
        } finally {
            personCutoutAnalysisInFlightV43.remove(analysisKey)
        }
    }
}
