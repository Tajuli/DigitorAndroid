package com.tajuli.digitorandroid.ui.editor

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.tajuli.digitorandroid.editor.model.ClipCutoutV43
import com.tajuli.digitorandroid.editor.model.CutoutAnalysisQualityV47
import com.tajuli.digitorandroid.editor.model.CutoutModeV43
import com.tajuli.digitorandroid.editor.model.PreviewTransformClock
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.resolvedCutoutV43
import com.tajuli.digitorandroid.editor.preview.PreviewExportCoordinator
import com.tajuli.digitorandroid.editor.processing.CutoutAnalysisPowerGuardV48
import com.tajuli.digitorandroid.editor.processing.GpuPersonCutoutAnalyzerV47
import com.tajuli.digitorandroid.editor.processing.hasPersonCutoutCoverageV43
import com.tajuli.digitorandroid.editor.processing.markPersonCutoutGenerationV47Ready
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val personCutoutAnalysisInFlightV43 = ConcurrentHashMap.newKeySet<String>()

/** V49 bridge retains historical symbols so existing project/editor code remains source-compatible. */
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
        label = "cutout-v49",
        project = snapshot.project.copy(tracks = tracks),
        status = status,
        coalesce = coalesce,
    )
}

fun EditorViewModelV4.enablePersonCutoutV43(settings: ClipCutoutV43) {
    val person = settings.copy(mode = CutoutModeV43.PERSON).normalized()
    val label = person.analysisQualityV47.uiLabelV47()
    setSelectedCutoutV43(
        person,
        status = "Pro Cutout enabled · $label · tap Analyze",
        coalesce = false,
    )
    val clip = state.value.project.clip(state.value.selectedClipId) ?: return
    val app = getApplication<Application>()
    if (hasPersonCutoutCoverageV43(app.applicationContext, clip)) {
        setEditorStatusV19("Pro Cutout ready · $label · cached matte")
        PreviewExportCoordinator.refreshActivePreviews()
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
    val quality = settings.analysisQualityV47
    val label = quality.uiLabelV47()
    val analysisKey = buildString {
        append("v52-adaptive-safe-gpu|")
        append(clip.uri); append('|'); append(clip.sourceInUs); append('|'); append(clip.sourceOutUs)
        append('|'); append(quality.name)
        append('|'); append(settings.hairDetailV44); append('|'); append(settings.temporalStabilityV44)
    }
    if (!personCutoutAnalysisInFlightV43.add(analysisKey)) {
        setEditorStatusV19("Pro Cutout · $label · analysis already running")
        return
    }

    val clockLocalUs = PreviewTransformClock.snapshotFor(clip.id)?.localUs
    val prioritySourceUs = clockLocalUs?.let { localUs ->
        (clip.sourceInUs + localUs).coerceIn(
            clip.sourceInUs.coerceAtLeast(0L),
            (clip.sourceOutUs - 1L).coerceAtLeast(clip.sourceInUs.coerceAtLeast(0L)),
        )
    }

    setEditorStatusV19("Pro Cutout · $label · starting adaptive GPU pipeline…")
    val app = getApplication<Application>()
    val progressStride = when (quality) {
        CutoutAnalysisQualityV47.LOW -> 8
        CutoutAnalysisQualityV47.MEDIUM -> 24
        CutoutAnalysisQualityV47.HIGH -> 30
    }
    val backendResolved = AtomicReference<String?>(null)

    viewModelScope.launch(Dispatchers.Default) {
        try {
            val result = runCatching {
                CutoutAnalysisPowerGuardV48.acquire(app.applicationContext).use {
                    PreviewExportCoordinator.acquireAnalysisLease().use {
                        GpuPersonCutoutAnalyzerV47(app.applicationContext).analyzeAndStore(
                            clip = clip,
                            prioritySourceUs = prioritySourceUs,
                            onBackendResolved = { backend ->
                                backendResolved.set(backend)
                                viewModelScope.launch(Dispatchers.Main) {
                                    setEditorStatusV19("Pro Cutout · $label · $backend")
                                }
                            },
                            onAnchorStored = { completed ->
                                if (completed == 1 || completed % progressStride == 0) {
                                    val backend = backendResolved.get()
                                    viewModelScope.launch(Dispatchers.Main) {
                                        val backendPart = backend?.let { "$it · " }.orEmpty()
                                        setEditorStatusV19(
                                            "Pro Cutout · $label · $backendPart$completed refined frame(s)",
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { track ->
                    val currentClip = state.value.project.clip(clip.id) ?: clip
                    val ready = hasPersonCutoutCoverageV43(app.applicationContext, currentClip)
                    val backend = backendResolved.get()
                    PreviewExportCoordinator.refreshActivePreviews(220L)
                    if (ready) {
                        val backendPart = backend?.let { " · $it" }.orEmpty()
                        setEditorStatusV19(
                            "Pro Cutout ready · $label$backendPart · ${track.frames.size} refined matte frame(s)",
                        )
                    } else {
                        setEditorStatusV19("Pro Cutout incomplete · $label · tap Analyze again")
                    }
                }.onFailure { error ->
                    val currentClip = state.value.project.clip(clip.id) ?: clip
                    val recoveredHigh =
                        quality == CutoutAnalysisQualityV47.HIGH &&
                            hasPersonCutoutCoverageV43(app.applicationContext, currentClip)
                    PreviewExportCoordinator.refreshActivePreviews(220L)
                    if (recoveredHigh) {
                        markPersonCutoutGenerationV47Ready(app.applicationContext, currentClip)
                        val backendPart = backendResolved.get()?.let { " · $it" }.orEmpty()
                        setEditorStatusV19(
                            "Pro Cutout ready · $label$backendPart · recovered complete matte after decoder tail stop",
                        )
                    } else {
                        val detail = error.message?.takeIf { it.isNotBlank() }
                            ?: error::class.java.simpleName
                        setEditorStatusV19("Pro Cutout failed · $label · $detail")
                    }
                }
            }
        } finally {
            personCutoutAnalysisInFlightV43.remove(analysisKey)
        }
    }
}

internal fun CutoutAnalysisQualityV47.uiLabelV47(): String = when (this) {
    CutoutAnalysisQualityV47.LOW -> "Low · 4 fps"
    CutoutAnalysisQualityV47.MEDIUM -> "Medium · 12 fps"
    CutoutAnalysisQualityV47.HIGH -> "High · every frame"
}
