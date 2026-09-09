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
    CANCEL_REQUESTED,
    CANCELLED,
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
            phase == CutoutAnalysisPhaseV66.PAUSE_REQUESTED ||
            phase == CutoutAnalysisPhaseV66.CANCEL_REQUESTED
}

/**
 * Process-wide cooperative state for a long PP-MattingV2 analysis.
 *
 * This state intentionally lives outside an Activity/ViewModel. A configuration/UI recreation must
 * not cancel an expensive analysis. Durable PNG mattes plus the matching generation marker provide
 * the cross-process/crash checkpoint; this object coordinates Pause/Resume and cooperative Cancel.
 */
object CutoutAnalysisRuntimeV66 {
    private val pauseRequested = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)
    private val lock = Any()
    private val _state = MutableStateFlow(CutoutAnalysisRuntimeStateV66())
    val state: StateFlow<CutoutAnalysisRuntimeStateV66> = _state.asStateFlow()

    fun begin(clipId: String, resumed: Boolean, savedFrames: Int): Boolean = synchronized(lock) {
        if (_state.value.busy) return@synchronized false
        pauseRequested.set(false)
        cancelRequested.set(false)
        _state.value = CutoutAnalysisRuntimeStateV66(
            phase = CutoutAnalysisPhaseV66.RUNNING,
            clipId = clipId,
            savedFrames = savedFrames.coerceAtLeast(0),
            resumed = resumed,
            detail = if (resumed) "Resuming durable matte checkpoint" else "Starting matte analysis",
        )
        true
    }

    /** During RUNNING this is processed progress; terminal states replace it with durable file count. */
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
        cancelRequested.set(false)
        pauseRequested.set(true)
        _state.value = current.copy(
            phase = CutoutAnalysisPhaseV66.PAUSE_REQUESTED,
            detail = "Pausing after the current durable frame",
        )
        true
    }

    /** Export uses this to release the analysis decoder/GPU lease without discarding resumable work. */
    fun requestPauseForExport(): Boolean = synchronized(lock) {
        val current = _state.value
        when (current.phase) {
            CutoutAnalysisPhaseV66.RUNNING -> {
                cancelRequested.set(false)
                pauseRequested.set(true)
                _state.value = current.copy(
                    phase = CutoutAnalysisPhaseV66.PAUSE_REQUESTED,
                    detail = "Pausing Cutout so export can use the decoder/GPU safely",
                )
                true
            }
            CutoutAnalysisPhaseV66.PAUSE_REQUESTED,
            CutoutAnalysisPhaseV66.CANCEL_REQUESTED -> true
            else -> false
        }
    }

    fun requestCancel(clipId: String): Boolean = synchronized(lock) {
        val current = _state.value
        if (current.clipId != clipId ||
            (current.phase != CutoutAnalysisPhaseV66.RUNNING &&
                current.phase != CutoutAnalysisPhaseV66.PAUSE_REQUESTED)
        ) {
            return@synchronized false
        }
        pauseRequested.set(false)
        cancelRequested.set(true)
        _state.value = current.copy(
            phase = CutoutAnalysisPhaseV66.CANCEL_REQUESTED,
            detail = "Cancelling after pending durable work is flushed",
        )
        true
    }

    fun isPauseRequested(): Boolean = pauseRequested.get()
    fun isCancelRequested(): Boolean = cancelRequested.get()

    /** Historical name retained for callers; Cancel is checked first and has stronger semantics. */
    fun throwIfPauseRequested() {
        if (cancelRequested.get()) throw CutoutAnalysisCancelledV69()
        if (pauseRequested.get()) throw CutoutAnalysisPausedV66()
    }

    fun markPaused(savedFrames: Int) = synchronized(lock) {
        pauseRequested.set(false)
        cancelRequested.set(false)
        val current = _state.value
        _state.value = current.copy(
            phase = CutoutAnalysisPhaseV66.PAUSED,
            savedFrames = savedFrames.coerceAtLeast(0),
            detail = "Paused safely; Resume continues from the next missing frame",
        )
    }

    fun markCancelled(savedFrames: Int) = synchronized(lock) {
        pauseRequested.set(false)
        cancelRequested.set(false)
        val current = _state.value
        _state.value = current.copy(
            phase = CutoutAnalysisPhaseV66.CANCELLED,
            savedFrames = savedFrames.coerceAtLeast(0),
            resumed = false,
            detail = "Cutout analysis cancelled; saved partial matte remains exportable",
        )
    }

    fun markFailed(savedFrames: Int, detail: String?) = synchronized(lock) {
        pauseRequested.set(false)
        cancelRequested.set(false)
        val current = _state.value
        _state.value = current.copy(
            phase = CutoutAnalysisPhaseV66.FAILED,
            savedFrames = savedFrames.coerceAtLeast(0),
            detail = detail,
        )
    }

    fun markCompleted(savedFrames: Int) = synchronized(lock) {
        pauseRequested.set(false)
        cancelRequested.set(false)
        val current = _state.value
        _state.value = current.copy(
            phase = CutoutAnalysisPhaseV66.COMPLETED,
            savedFrames = savedFrames.coerceAtLeast(0),
            detail = "Matte analysis complete",
        )
    }

    fun clearIfClip(clipId: String) = synchronized(lock) {
        val current = _state.value
        if (current.clipId == clipId && !current.busy) {
            pauseRequested.set(false)
            cancelRequested.set(false)
            _state.value = CutoutAnalysisRuntimeStateV66()
        }
    }
}

internal open class CutoutAnalysisPausedV66 : RuntimeException("Pro Cutout paused")
internal class CutoutAnalysisCancelledV69 : CutoutAnalysisPausedV66()
