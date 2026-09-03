package com.tajuli.digitorandroid.ui.editor

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.tajuli.digitorandroid.editor.model.ClipCutoutV43
import com.tajuli.digitorandroid.editor.model.CutoutModeV43
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.processing.PersonCutoutAnalyzerV43
import com.tajuli.digitorandroid.editor.processing.PersonCutoutMaskStoreV43
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
    if (PersonCutoutMaskStoreV43.hasAny(app.applicationContext, clip)) {
        setEditorStatusV19("Auto Cutout ready · cached matte")
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
    val analysisKey = clip.uri
    if (!personCutoutAnalysisInFlightV43.add(analysisKey)) {
        setEditorStatusV19("Auto Cutout analysis already running")
        return
    }

    setEditorStatusV19("Auto Cutout · analyzing person matte…")
    val app = getApplication<Application>()
    viewModelScope.launch(Dispatchers.Default) {
        try {
            val result = runCatching { PersonCutoutAnalyzerV43(app.applicationContext).analyzeAndStore(clip) }
            withContext(Dispatchers.Main) {
                result.onSuccess { track ->
                    setEditorStatusV19("Auto Cutout ready · ${track.frames.size} matte frame(s)")
                }.onFailure { error ->
                    setEditorStatusV19(error.message ?: "Auto Cutout analysis failed")
                }
            }
        } finally {
            personCutoutAnalysisInFlightV43.remove(analysisKey)
        }
    }
}
