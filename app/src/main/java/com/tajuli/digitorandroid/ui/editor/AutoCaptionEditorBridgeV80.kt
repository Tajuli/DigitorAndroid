package com.tajuli.digitorandroid.ui.editor

import android.widget.Toast
import androidx.lifecycle.viewModelScope
import com.tajuli.digitorandroid.editor.model.AutoCaptionLanguageV80
import com.tajuli.digitorandroid.editor.model.ProjectStore
import com.tajuli.digitorandroid.editor.model.TextOverlayClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.resolvedVideoTrackIdV3
import com.tajuli.digitorandroid.editor.model.visualOverlaysForVideoTrackV19
import com.tajuli.digitorandroid.editor.processing.AutoCaptionGpuUnavailableException
import com.tajuli.digitorandroid.editor.processing.WhisperAutoCaptionV80
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val autoCaptionRunningV80 = AtomicBoolean(false)
private const val AUTO_CAPTION_TRACK_V80 = "Captions"

internal data class AutoCaptionRunStateV80(
    val running: Boolean = false,
    val message: String = "Choose a language, then generate captions.",
    val progressPercent: Int? = null,
    val completedCount: Int? = null,
    val failed: Boolean = false,
    val cpuFallbackAvailable: Boolean = false,
)

/** Shared UI state so progress remains visible even when the Auto Caption dialog is closed. */
internal object AutoCaptionStatusV80 {
    private val _state = MutableStateFlow(AutoCaptionRunStateV80())
    val state: StateFlow<AutoCaptionRunStateV80> = _state.asStateFlow()

    fun start(allowCpuFallback: Boolean) {
        _state.value = AutoCaptionRunStateV80(
            running = true,
            message = if (allowCpuFallback) {
                "Auto Caption · trying GPUs, then approved CPU mode"
            } else {
                "Auto Caption · preparing GPU"
            },
        )
    }

    fun update(message: String) {
        val percent = Regex("(\\d{1,3})%").find(message)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.coerceIn(0, 100)
        _state.value = _state.value.copy(
            running = true,
            message = message,
            progressPercent = if (message.contains("model", ignoreCase = true)) percent else null,
            completedCount = null,
            failed = false,
            cpuFallbackAvailable = false,
        )
    }

    fun success(count: Int, language: AutoCaptionLanguageV80) {
        _state.value = AutoCaptionRunStateV80(
            running = false,
            message = "Created $count captions · ${language.label}",
            completedCount = count,
        )
    }

    fun failure(message: String, cpuFallbackAvailable: Boolean = false) {
        _state.value = AutoCaptionRunStateV80(
            running = false,
            message = message,
            failed = true,
            cpuFallbackAvailable = cpuFallbackAvailable,
        )
    }
}

/** Generate editable TextOverlayClip captions with the existing project/timeline system. */
fun EditorViewModelV4.generateAutoCaptionsV80(
    language: AutoCaptionLanguageV80,
    allowCpuFallback: Boolean = false,
) {
    if (!autoCaptionRunningV80.compareAndSet(false, true)) {
        val message = "Auto Caption is already running"
        setEditorStatusV19(message)
        AutoCaptionStatusV80.update(message)
        return
    }

    val sourceProject = state.value.project
    AutoCaptionStatusV80.start(allowCpuFallback)
    setEditorStatusV19(
        if (allowCpuFallback) "Auto Caption · GPU first, CPU approved" else "Auto Caption · preparing GPU",
    )

    fun publishStatus(message: String) {
        setEditorStatusV19(message)
        AutoCaptionStatusV80.update(message)
    }

    viewModelScope.launch(Dispatchers.Default) {
        try {
            val segments = WhisperAutoCaptionV80(getApplication()).transcribeProject(
                project = sourceProject,
                language = language,
                allowCpuFallback = allowCpuFallback,
                onStatus = ::publishStatus,
            )
            require(segments.isNotEmpty()) { "No speech was detected in the project audio" }

            withContext(Dispatchers.Main) {
                // Do not overwrite edits the user made while a long transcription was running.
                if (state.value.project != sourceProject) {
                    val message = "Auto Caption stopped · project changed during transcription"
                    setEditorStatusV19(message)
                    AutoCaptionStatusV80.failure(message)
                    Toast.makeText(getApplication(), message, Toast.LENGTH_LONG).show()
                    return@withContext
                }

                val (baseProject, captionTrack) = sourceProject.projectWithCaptionTrackV80()
                val overlays = segments.map { segment ->
                    TextOverlayClip(
                        text = segment.text,
                        timelineStartUs = segment.startUs,
                        timelineEndUs = segment.endUs,
                        positionY = .72f,
                        sizeScale = .78f,
                        bold = true,
                        background = true,
                        videoTrackIdV3 = captionTrack.id,
                    )
                }
                val nextProject = baseProject.copy(textOverlays = baseProject.textOverlays + overlays)

                commitProjectV19(
                    label = "auto-caption",
                    project = nextProject,
                    status = "Auto Caption · ${overlays.size} captions · ${language.label}",
                )
                ProjectStore(getApplication()).autoSave(state.value.project)

                selectTrack(captionTrack.id)
                overlays.firstOrNull()?.let { first ->
                    selectTextOverlay(first.id)
                    TimelineTextSelectionBusV10.select(first.id)
                }

                val message = "Auto Caption · ${overlays.size} captions · ${language.label}"
                setEditorStatusV19(message)
                AutoCaptionStatusV80.success(overlays.size, language)
                Toast.makeText(getApplication(), "Created ${overlays.size} captions", Toast.LENGTH_SHORT).show()
            }
        } catch (error: Throwable) {
            withContext(Dispatchers.Main) {
                val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                val message = "Auto Caption failed · $detail"
                val canOfferCpu = error is AutoCaptionGpuUnavailableException && !allowCpuFallback
                setEditorStatusV19(message)
                AutoCaptionStatusV80.failure(message, cpuFallbackAvailable = canOfferCpu)
                Toast.makeText(getApplication(), message, Toast.LENGTH_LONG).show()
            }
        } finally {
            autoCaptionRunningV80.set(false)
        }
    }
}

/**
 * Reuse the dedicated caption V-track when it remains a text-only lane. Regeneration replaces only
 * that lane's previous captions; normal titles on V1/V2/etc. are never touched.
 */
private fun TimelineProject.projectWithCaptionTrackV80(): Pair<TimelineProject, TimelineTrack> {
    val reusable = tracks.firstOrNull { track ->
        track.kind == TrackKind.VIDEO &&
            track.name.startsWith(AUTO_CAPTION_TRACK_V80) &&
            track.clips.isEmpty() &&
            visualOverlaysForVideoTrackV19(track.id).isEmpty()
    }
    if (reusable != null) {
        val keptText = textOverlays.filterNot { it.resolvedVideoTrackIdV3(this) == reusable.id }
        return copy(textOverlays = keptText) to reusable
    }

    val usedNames = tracks.mapTo(hashSetOf()) { it.name }
    var name = AUTO_CAPTION_TRACK_V80
    var suffix = 2
    while (name in usedNames) name = "$AUTO_CAPTION_TRACK_V80 ${suffix++}"
    val track = TimelineTrack(name = name, kind = TrackKind.VIDEO)
    return copy(tracks = listOf(track) + tracks) to track
}
