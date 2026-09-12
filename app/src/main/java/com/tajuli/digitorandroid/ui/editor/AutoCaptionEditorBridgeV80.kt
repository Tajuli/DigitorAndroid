package com.tajuli.digitorandroid.ui.editor

import androidx.lifecycle.viewModelScope
import com.tajuli.digitorandroid.editor.model.AutoCaptionLanguageV80
import com.tajuli.digitorandroid.editor.model.ProjectStore
import com.tajuli.digitorandroid.editor.model.TextOverlayClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.resolvedVideoTrackIdV3
import com.tajuli.digitorandroid.editor.model.visualOverlaysForVideoTrackV19
import com.tajuli.digitorandroid.editor.processing.WhisperAutoCaptionV80
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val autoCaptionRunningV80 = AtomicBoolean(false)
private const val AUTO_CAPTION_TRACK_V80 = "Captions"

/** Generate editable TextOverlayClip captions with the existing project/timeline system. */
fun EditorViewModelV4.generateAutoCaptionsV80(language: AutoCaptionLanguageV80) {
    if (!autoCaptionRunningV80.compareAndSet(false, true)) {
        setEditorStatusV19("Auto Caption is already running")
        return
    }

    val sourceProject = state.value.project
    setEditorStatusV19("Auto Caption · preparing audio")
    viewModelScope.launch(Dispatchers.Default) {
        try {
            val segments = WhisperAutoCaptionV80(getApplication()).transcribeProject(
                project = sourceProject,
                language = language,
                onStatus = ::setEditorStatusV19,
            )
            require(segments.isNotEmpty()) { "No speech was detected" }

            withContext(Dispatchers.Main) {
                // Do not overwrite edits the user made while a long transcription was running.
                if (state.value.project != sourceProject) {
                    setEditorStatusV19("Auto Caption stopped · project changed during transcription")
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
                ProjectStore(getApplication()).autoSave(nextProject)
                loadProject()
                selectTrack(captionTrack.id)
                overlays.firstOrNull()?.let { first ->
                    selectTextOverlay(first.id)
                    TimelineTextSelectionBusV10.select(first.id)
                }
                setEditorStatusV19("Auto Caption · ${overlays.size} captions · ${language.label}")
            }
        } catch (error: Throwable) {
            setEditorStatusV19(error.message ?: "Auto Caption failed")
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
