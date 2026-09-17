package com.tajuli.digitorandroid.ui.editor

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tajuli.digitorandroid.editor.model.PreviewTransformClock
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.preview.DavinciFramePreviewEngine
import com.tajuli.digitorandroid.editor.preview.MultitrackAudioPreviewEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal class EditorPlaybackController(initialDurationUs: Long) {
    var isPlaying by mutableStateOf(false)
    var cursorUs by mutableStateOf(0L)
    var previewStatus by mutableStateOf<String?>(null)

    internal var previousProjectDurationUs = initialDurationUs
    internal var playAnchorCursorUs = 0L
    internal var playAnchorRealtimeMs = 0L

    fun stopForEdit(audioPreview: MultitrackAudioPreviewEngine) {
        isPlaying = false
        runCatching { audioPreview.pause() }
    }

    fun seek(
        requestUs: Long,
        project: TimelineProject,
        hasAudio: Boolean,
        audioPreviewReady: Boolean,
        audioPreview: MultitrackAudioPreviewEngine,
    ): Long {
        val target = requestUs.coerceIn(0L, project.durationUs.coerceAtLeast(0L))
        cursorUs = target
        if (hasAudio && audioPreviewReady) {
            runCatching { audioPreview.seekTo(target / 1000L) }
        }
        if (isPlaying && !(hasAudio && audioPreviewReady)) {
            playAnchorCursorUs = target
            playAnchorRealtimeMs = SystemClock.elapsedRealtime()
        }
        return target
    }

    fun togglePlayback(
        hasMedia: Boolean,
        durationUs: Long,
        hasAudio: Boolean,
        audioPreviewReady: Boolean,
        audioPreview: MultitrackAudioPreviewEngine,
        onSeek: (Long) -> Unit,
    ) {
        if (!hasMedia) return
        if (isPlaying) {
            stopForEdit(audioPreview)
            return
        }

        if (cursorUs >= durationUs && durationUs > 0L) {
            onSeek(0L)
        }
        playAnchorCursorUs = cursorUs
        playAnchorRealtimeMs = SystemClock.elapsedRealtime()
        if (hasAudio && audioPreviewReady) {
            runCatching { audioPreview.play() }
        }
        isPlaying = true
    }
}

@Composable
internal fun rememberEditorPlaybackController(initialDurationUs: Long): EditorPlaybackController =
    remember { EditorPlaybackController(initialDurationUs) }

@Composable
internal fun EditorPlaybackEffects(
    project: TimelineProject,
    selectedClip: TimelineClip?,
    previewClip: TimelineClip?,
    hasVideo: Boolean,
    hasAudio: Boolean,
    audioPreviewKey: Int,
    previewEngine: DavinciFramePreviewEngine,
    previewFrame: DavinciFramePreviewEngine.Frame?,
    audioPreview: MultitrackAudioPreviewEngine,
    audioPreviewReady: Boolean,
    audioPreviewError: String?,
    controller: EditorPlaybackController,
) {
    LaunchedEffect(project, controller.cursorUs, hasVideo, controller.isPlaying) {
        if (hasVideo) {
            previewEngine.submit(project, controller.cursorUs, controller.isPlaying)
        }
    }

    LaunchedEffect(previewFrame?.timelineUs, previewFrame?.renderTimeMs, hasVideo) {
        val frame = previewFrame
        controller.previewStatus = when {
            !hasVideo -> null
            frame == null -> "Preview: GPU preparing…"
            frame.bitmap != null -> "Preview: CPU fallback · ${frame.renderTimeMs}ms"
            else -> "GPU ${timeV7(frame.timelineUs)} · ${frame.activeLayerCount}L"
        }
    }

    LaunchedEffect(audioPreviewError) {
        audioPreviewError?.let { controller.previewStatus = "Audio preview: $it" }
    }

    LaunchedEffect(project.durationUs) {
        val durationUs = project.durationUs.coerceAtLeast(0L)
        if (durationUs < controller.previousProjectDurationUs && controller.cursorUs >= durationUs) {
            controller.cursorUs = if (durationUs > 0L) durationUs - 1L else 0L
        }
        controller.previousProjectDurationUs = durationUs
    }

    LaunchedEffect(audioPreviewKey, hasAudio) {
        if (!hasAudio) {
            audioPreview.clear()
            return@LaunchedEffect
        }
        val snapshot = project
        val resume = controller.isPlaying
        delay(100)
        try {
            val maxStartUs = (snapshot.durationUs - 1L).coerceAtLeast(0L)
            audioPreview.rebuild(
                snapshot,
                controller.cursorUs.coerceIn(0L, maxStartUs) / 1000L,
                resume,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            controller.previewStatus = "Audio preview: ${error.message ?: "unavailable"}"
        }
    }

    LaunchedEffect(controller.isPlaying, audioPreviewReady, hasAudio) {
        while (controller.isPlaying) {
            val durationUs = project.durationUs.coerceAtLeast(0L)
            val nextUs = if (hasAudio && audioPreviewReady) {
                audioPreview.syncFollowers()
                audioPreview.currentPositionMs().coerceAtLeast(0L) * 1000L
            } else {
                controller.playAnchorCursorUs +
                    (SystemClock.elapsedRealtime() - controller.playAnchorRealtimeMs) * 1000L
            }.coerceIn(0L, durationUs)

            controller.cursorUs = nextUs
            if (durationUs > 0L && nextUs >= durationUs) {
                runCatching { audioPreview.pause() }
                controller.isPlaying = false
                break
            }
            delay(33)
        }
    }

    LaunchedEffect(controller.cursorUs, selectedClip?.id, previewClip?.id) {
        val clockClip = selectedClip?.takeIf { clip ->
            project.trackContaining(clip.id)?.kind == TrackKind.VIDEO &&
                controller.cursorUs in clip.timelineStartUs until clip.timelineEndUs
        } ?: previewClip
        if (clockClip == null) {
            PreviewTransformClock.clear()
        } else {
            PreviewTransformClock.update(clockClip, controller.cursorUs)
        }
    }
}
