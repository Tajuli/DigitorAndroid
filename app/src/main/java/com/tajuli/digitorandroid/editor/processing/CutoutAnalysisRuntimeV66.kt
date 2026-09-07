package com.tajuli.digitorandroid.editor.processing

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CutoutAnalysisPhaseV66 {
    IDLE,
    RUNNING,
    PAUSE_REQUESTED,
    PAUSED,
    FAILED,
    COMPLETED,
}

data class CutoutAnalysisRuntimeStateV66(
    val phase: CutoutAnalysisPhaseV66 = CutoutAnalysisPhaseV66.IDLE,
    val clipId: String? = null,
    val savedFrames: Int = 0,
    val resumed: Boolean = false,
    val detail: String? = null,
) {
    val busy: Boolean
        get() = phase == CutoutAnalysisPhaseV66.RUNNING ||
            phase == CutoutAnalysisPhaseV66.PAUSE_REQUESTED
}

/**
 * Process-wide cooperative state for a long PP-MattingV2 analysis.
 *
 * This state intentionally lives outside an Activity/ViewModel. A configuration/UI recreation must
 * not cancel an expensive analysis. Durable PNG mattes plus the matching generation marker provide
 * the cross-process/crash checkpoint; this object only coordinates the currently alive process and
 * the Pause button.
 */
object CutoutAnalysisRuntimeV66 {
    private val pauseRequested = AtomicBoolean(false)
    private val lock = Any()
    private val _state = MutableStateFlow(CutoutAnalysisRuntimeStateV66())
    val state: StateFlow<CutoutAnalysisRuntimeStateV66> = _state.asStateFlow()

    fun begin(clipId: String, resumed: Boolean, savedFrames: Int): Boolean = synchronized(lock) {
        if (_state.value.busy) return@synchronized false
        pauseRequested.set(false)
        _state.value = CutoutAnalysisRuntimeStateV66(
            phase = CutoutAnalysisPhaseV66.RUNNING,
            clipId = clipId,
            savedFrames = savedFrames.coerceAtLeast(0),
            resumed = resumed,
            detail = if (resumed) "Resuming durable matte checkpoint" else "Starting matte analysis",
        )
        true
    }

    fun updateSavedFrames(savedFrames: Int) {
        synchronized(lock) {
            val current = _state.value
            if (current.phase == CutoutAnalysisPhaseV66.IDLE) return
            if (savedFrames <= current.savedFrames) return
            _state.value = current.copy(savedFrames = savedFrames)
        }
    }

    fun requestPause(clipId: String): Boolean = synchronized(lock) {
        val current = _state.value
        if (current.clipId != clipId || current.phase != CutoutAnalysisPhaseV66.RUNNING) {
            return@synchronized false
        }
        pauseRequested.set(true)
        _state.value = current.copy(
            phase = CutoutAnalysisPhaseV66.PAUSE_REQUESTED,
            detail = "Pausing after the current durable frame",
        )
        true
    }

    fun isPauseRequested(): Boolean = pauseRequested.get()

    fun throwIfPauseRequested() {
        if (pauseRequested.get()) throw CutoutAnalysisPausedV66()
    }

    fun markPaused(savedFrames: Int) = synchronized(lock) {
        pauseRequested.set(false)
        val current = _state.value
        _state.value = current.copy(
            phase = CutoutAnalysisPhaseV66.PAUSED,
            savedFrames = maxOf(current.savedFrames, savedFrames),
            detail = "Paused safely; Resume continues from the next missing frame",
        )
    }

    fun markFailed(savedFrames: Int, detail: String?) = synchronized(lock) {
        pauseRequested.set(false)
        val current = _state.value
        _state.value = current.copy(
            phase = CutoutAnalysisPhaseV66.FAILED,
            savedFrames = maxOf(current.savedFrames, savedFrames),
            detail = detail,
        )
    }

    fun markCompleted(savedFrames: Int) = synchronized(lock) {
        pauseRequested.set(false)
        val current = _state.value
        _state.value = current.copy(
            phase = CutoutAnalysisPhaseV66.COMPLETED,
            savedFrames = maxOf(current.savedFrames, savedFrames),
            detail = "Matte analysis complete",
        )
    }

    fun clearIfClip(clipId: String) = synchronized(lock) {
        val current = _state.value
        if (current.clipId == clipId && !current.busy) {
            pauseRequested.set(false)
            _state.value = CutoutAnalysisRuntimeStateV66()
        }
    }
}

internal class CutoutAnalysisPausedV66 : RuntimeException("Pro Cutout paused")
