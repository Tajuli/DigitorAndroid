package com.tajuli.digitorandroid.ui.editor

import android.app.Application
import com.tajuli.digitorandroid.editor.model.ClipCutoutV43
import com.tajuli.digitorandroid.editor.model.CutoutAnalysisQualityV47
import com.tajuli.digitorandroid.editor.model.CutoutModeV43
import com.tajuli.digitorandroid.editor.model.PreviewTransformClock
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.resolvedCutoutV43
import com.tajuli.digitorandroid.editor.preview.PreviewExportCoordinator
import com.tajuli.digitorandroid.editor.processing.CutoutAnalysisCancelledV69
import com.tajuli.digitorandroid.editor.processing.CutoutAnalysisPausedV66
import com.tajuli.digitorandroid.editor.processing.CutoutAnalysisPhaseV66
import com.tajuli.digitorandroid.editor.processing.CutoutAnalysisPowerGuardV48
import com.tajuli.digitorandroid.editor.processing.CutoutAnalysisRuntimeV66
import com.tajuli.digitorandroid.editor.processing.GpuPersonCutoutAnalyzerV47
import com.tajuli.digitorandroid.editor.processing.hasPersonCutoutCoverageV43
import com.tajuli.digitorandroid.editor.processing.hasResumablePersonCutoutGenerationV66
import com.tajuli.digitorandroid.editor.processing.markPersonCutoutGenerationV47Ready
import com.tajuli.digitorandroid.editor.processing.markPersonCutoutGenerationV69Partial
import com.tajuli.digitorandroid.editor.processing.personCutoutSavedFrameCountV66
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val personCutoutAnalysisInFlightV43 = ConcurrentHashMap.newKeySet<String>()

// Analysis must not be a child of an Activity/ViewModel lifecycle. A notification, configuration
// recreation or editor navigation can destroy a ViewModel while the GPU job is still valid. The
// process-wide scope is paired with the existing wake/preview leases; durable frame PNGs recover a
// real process/native crash on the next launch.
private val personCutoutAnalysisScopeV66 = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** V69 bridge retains historical symbols so existing project/editor code remains source-compatible. */
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
        label = "cutout-v69",
        project = snapshot.project.copy(tracks = tracks),
        status = status,
        coalesce = coalesce,
    )
    // Once paused/failed/cancelled, changing analysis-time settings makes the on-disk signature
    // mismatch. Drop only in-memory terminal UI state; the next Analyze atomically starts cleanly.
    CutoutAnalysisRuntimeV66.clearIfClip(id)
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
    when {
        hasPersonCutoutCoverageV43(app.applicationContext, clip) -> {
            setEditorStatusV19("Pro Cutout ready · $label · cached matte")
            PreviewExportCoordinator.refreshActivePreviews()
        }
        hasResumablePersonCutoutGenerationV66(app.applicationContext, clip) -> {
            val saved = personCutoutSavedFrameCountV66(app.applicationContext, clip)
            setEditorStatusV19("Pro Cutout interrupted · $label · $saved saved frame(s) · Resume available")
        }
    }
}

fun EditorViewModelV4.pauseSelectedPersonCutoutV66() {
    val clip = state.value.project.clip(state.value.selectedClipId) ?: return
    if (CutoutAnalysisRuntimeV66.requestPause(clip.id)) {
        setEditorStatusV19("Pro Cutout · pausing after current frame…")
    }
}

/**
 * Cancel terminates the current analysis run without deleting durable matte PNGs. Unlike Pause it
 * removes resumability: the saved partial matte remains usable for preview/export, and a later
 * Analyze starts fresh with the current settings.
 */
fun EditorViewModelV4.cancelSelectedPersonCutoutV69() {
    val clip = state.value.project.clip(state.value.selectedClipId) ?: return
    val appContext = getApplication<Application>().applicationContext
    val runtime = CutoutAnalysisRuntimeV66.state.value

    if (runtime.clipId == clip.id && runtime.busy) {
        if (CutoutAnalysisRuntimeV66.requestCancel(clip.id)) {
            setEditorStatusV19("Pro Cutout · cancelling safely… · saved frames stay exportable")
        }
        return
    }

    val savedBefore = personCutoutSavedFrameCountV66(appContext, clip)
    markPersonCutoutGenerationV69Partial(appContext, clip)
    val saved = maxOf(savedBefore, personCutoutSavedFrameCountV66(appContext, clip))
    CutoutAnalysisRuntimeV66.markCancelled(saved)
    PreviewExportCoordinator.refreshActivePreviews(120L)
    setEditorStatusV19("Pro Cutout cancelled · $saved saved frame(s) · Export allowed")
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

    val alreadyRunning = CutoutAnalysisRuntimeV66.state.value
    if (alreadyRunning.busy) {
        if (alreadyRunning.clipId == clip.id) {
            pauseSelectedPersonCutoutV66()
        } else {
            setEditorStatusV19("Another Pro Cutout analysis is already running")
        }
        return
    }

    val settings = clip.resolvedCutoutV43()
    val quality = settings.analysisQualityV47
    val label = quality.uiLabelV47()
    val analysisKey = buildString {
        append("v69-checkpoint-resume|")
        append(clip.uri); append('|'); append(clip.sourceInUs); append('|'); append(clip.sourceOutUs)
        append('|'); append(quality.name)
        append('|'); append(settings.mattingSizeV69)
        append('|'); append(settings.hairDetailV44); append('|'); append(settings.temporalStabilityV44)
    }
    if (!personCutoutAnalysisInFlightV43.add(analysisKey)) {
        setEditorStatusV19("Pro Cutout · $label · analysis already running")
        return
    }

    val app = getApplication<Application>()
    val appContext = app.applicationContext
    val resumeAvailable = hasResumablePersonCutoutGenerationV66(appContext, clip)
    val initialSaved = if (resumeAvailable) personCutoutSavedFrameCountV66(appContext, clip) else 0
    if (!CutoutAnalysisRuntimeV66.begin(clip.id, resumeAvailable, initialSaved)) {
        personCutoutAnalysisInFlightV43.remove(analysisKey)
        setEditorStatusV19("Another Pro Cutout analysis is already running")
        return
    }

    val clockLocalUs = PreviewTransformClock.snapshotFor(clip.id)?.localUs
    val prioritySourceUs = if (resumeAvailable) {
        null
    } else {
        clockLocalUs?.let { localUs ->
            (clip.sourceInUs + localUs).coerceIn(
                clip.sourceInUs.coerceAtLeast(0L),
                (clip.sourceOutUs - 1L).coerceAtLeast(clip.sourceInUs.coerceAtLeast(0L)),
            )
        }
    }

    CutoutBackendStatusV50.clear()
    setEditorStatusV19(
        if (resumeAvailable) {
            "Pro Cutout · $label · resuming after $initialSaved saved frame(s)…"
        } else {
            "Pro Cutout · $label · selecting matting backend…"
        },
    )
    val progressStride = when (quality) {
        CutoutAnalysisQualityV47.LOW -> 8
        CutoutAnalysisQualityV47.MEDIUM -> 24
        CutoutAnalysisQualityV47.HIGH -> 30
    }

    personCutoutAnalysisScopeV66.launch {
        try {
            val result = runCatching {
                CutoutAnalysisPowerGuardV48.acquire(appContext).use {
                    PreviewExportCoordinator.acquireAnalysisLease().use {
                        GpuPersonCutoutAnalyzerV47(appContext).analyzeAndStore(
                            clip = clip,
                            prioritySourceUs = prioritySourceUs,
                            onBackendResolved = { backend ->
                                CutoutBackendStatusV50.update(backend)
                                personCutoutAnalysisScopeV66.launch(Dispatchers.Main) {
                                    setEditorStatusV19("Pro Cutout · $label · $backend")
                                }
                            },
                            onAnchorStored = { completed ->
                                CutoutAnalysisRuntimeV66.updateSavedFrames(completed)
                                if (completed == 1 || completed % progressStride == 0) {
                                    personCutoutAnalysisScopeV66.launch(Dispatchers.Main) {
                                        setEditorStatusV19(
                                            "Pro Cutout · $label · $completed processed frame(s) · checkpointing",
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
                    val ready = hasPersonCutoutCoverageV43(appContext, currentClip)
                    val durable = track.frames.count { it.file.isFile }
                    CutoutAnalysisRuntimeV66.markCompleted(durable)
                    PreviewExportCoordinator.refreshActivePreviews(220L)
                    if (ready) {
                        setEditorStatusV19(
                            "Pro Cutout ready · $label · $durable refined matte frame(s)",
                        )
                    } else {
                        val saved = personCutoutSavedFrameCountV66(appContext, clip)
                        CutoutAnalysisRuntimeV66.markFailed(saved, "Coverage incomplete")
                        setEditorStatusV19(
                            "Pro Cutout incomplete · $label · $saved saved frame(s) · Resume available · Export allowed",
                        )
                    }
                }.onFailure { error ->
                    val phase = CutoutAnalysisRuntimeV66.state.value.phase
                    val cancelRequested =
                        error is CutoutAnalysisCancelledV69 ||
                            phase == CutoutAnalysisPhaseV66.CANCEL_REQUESTED
                    val pauseRequested = !cancelRequested && (
                        error is CutoutAnalysisPausedV66 ||
                            phase == CutoutAnalysisPhaseV66.PAUSE_REQUESTED
                        )
                    PreviewExportCoordinator.refreshActivePreviews(220L)

                    if (cancelRequested) {
                        markPersonCutoutGenerationV69Partial(appContext, clip)
                        val saved = personCutoutSavedFrameCountV66(appContext, clip)
                        CutoutAnalysisRuntimeV66.markCancelled(saved)
                        setEditorStatusV19(
                            "Pro Cutout cancelled · $label · $saved saved frame(s) · Export allowed",
                        )
                    } else if (pauseRequested) {
                        val saved = personCutoutSavedFrameCountV66(appContext, clip)
                        CutoutAnalysisRuntimeV66.markPaused(saved)
                        setEditorStatusV19(
                            "Pro Cutout paused · $label · $saved saved frame(s) · tap Resume · Export allowed",
                        )
                    } else {
                        val saved = personCutoutSavedFrameCountV66(appContext, clip)
                        val currentClip = state.value.project.clip(clip.id) ?: clip
                        val recoveredHigh =
                            quality == CutoutAnalysisQualityV47.HIGH &&
                                hasPersonCutoutCoverageV43(appContext, currentClip)
                        if (recoveredHigh) {
                            markPersonCutoutGenerationV47Ready(appContext, currentClip)
                            CutoutAnalysisRuntimeV66.markCompleted(saved)
                            setEditorStatusV19(
                                "Pro Cutout ready · $label · recovered complete matte after decoder tail stop",
                            )
                        } else {
                            val detail = error.message?.takeIf { it.isNotBlank() }
                                ?: error::class.java.simpleName
                            val resumable = hasResumablePersonCutoutGenerationV66(appContext, clip)
                            CutoutAnalysisRuntimeV66.markFailed(saved, detail)
                            setEditorStatusV19(
                                if (resumable) {
                                    "Pro Cutout interrupted · $label · $saved saved frame(s) · Resume available · Export allowed · $detail"
                                } else {
                                    "Pro Cutout failed · $label · Export still allowed · $detail"
                                },
                            )
                        }
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
