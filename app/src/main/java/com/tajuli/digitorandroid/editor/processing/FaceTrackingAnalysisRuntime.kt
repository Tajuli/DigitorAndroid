package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import com.tajuli.digitorandroid.editor.model.TimelineClip
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FaceTrackingAnalysisState(
    val running: Boolean = false,
    val progress: Int = 0,
    val ready: Boolean = false,
    val gpuAccelerated: Boolean? = null,
    val gpuFailureReason: String? = null,
    val message: String = "Analyze face tracking before applying these effects.",
)

/**
 * Process-lifetime tracking runtime. Jobs are intentionally independent from the Effects composable
 * lifecycle, so analysis keeps running when the panel/category is no longer on screen.
 */
object FaceTrackingAnalysisRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val _states = MutableStateFlow<Map<String, FaceTrackingAnalysisState>>(emptyMap())
    val states = _states.asStateFlow()

    fun key(clip: TimelineClip): String =
        "${clip.id}|${clip.uri}|${clip.sourceInUs}|${clip.sourceOutUs}"

    fun refresh(context: Context, clip: TimelineClip) {
        val key = key(clip)
        if (_states.value[key]?.running == true) return
        scope.launch(Dispatchers.IO) {
            val ready = EyeTrackStore.load(context.applicationContext, clip)?.covers(clip) == true
            _states.update { states ->
                val current = states[key] ?: FaceTrackingAnalysisState()
                if (current.running) states else states + (
                    key to current.copy(
                        progress = if (ready) 100 else current.progress.coerceAtMost(99),
                        ready = ready,
                        message = if (ready) {
                            "Done · Face tracking ready"
                        } else {
                            "Face tracking must be analyzed before these effects are available."
                        },
                    )
                )
            }
        }
    }

    fun start(context: Context, clip: TimelineClip) {
        val key = key(clip)
        if (jobs[key]?.isActive == true || _states.value[key]?.ready == true) return

        _states.update { states ->
            states + (
                key to FaceTrackingAnalysisState(
                    running = true,
                    progress = 0,
                    ready = false,
                    message = "Analyzing face motion in the background…",
                )
            )
        }

        val job = scope.launch {
            try {
                EyeTrackingAnalyzer(context.applicationContext).analyze(
                    clip = clip,
                    onProgress = { progress ->
                        _states.update { states ->
                            val current = states[key] ?: FaceTrackingAnalysisState()
                            states + (
                                key to current.copy(
                                    running = true,
                                    progress = progress.coerceIn(0, 100),
                                    ready = false,
                                )
                            )
                        }
                    },
                    onBackend = { gpu ->
                        _states.update { states ->
                            val current = states[key] ?: FaceTrackingAnalysisState()
                            states + (
                                key to current.copy(
                                    gpuAccelerated = gpu,
                                    gpuFailureReason = if (gpu) null else current.gpuFailureReason,
                                    message = if (gpu) {
                                        "Analyzing face motion · GPU accelerated"
                                    } else {
                                        "Analyzing face motion · CPU compatibility fallback"
                                    },
                                )
                            )
                        }
                    },
                    onBackendFailure = { reason ->
                        _states.update { states ->
                            val current = states[key] ?: FaceTrackingAnalysisState()
                            states + (
                                key to current.copy(
                                    gpuAccelerated = false,
                                    gpuFailureReason = reason?.take(180),
                                )
                            )
                        }
                    },
                )
                _states.update { states ->
                    val current = states[key] ?: FaceTrackingAnalysisState()
                    states + (
                        key to current.copy(
                            running = false,
                            progress = 100,
                            ready = true,
                            message = "Done · Face tracking ready",
                        )
                    )
                }
            } catch (cancelled: CancellationException) {
                _states.update { states ->
                    val current = states[key] ?: FaceTrackingAnalysisState()
                    states + (
                        key to current.copy(
                            running = false,
                            ready = false,
                            message = "Face tracking cancelled. Analyze again to unlock tracked effects.",
                        )
                    )
                }
            } catch (error: Exception) {
                _states.update { states ->
                    val current = states[key] ?: FaceTrackingAnalysisState()
                    states + (
                        key to current.copy(
                            running = false,
                            ready = false,
                            message = error.message?.take(180)
                                ?: "Face tracking failed. Please retry.",
                        )
                    )
                }
            } finally {
                currentCoroutineContext()[Job]?.let { current ->
                    jobs.remove(key, current)
                }
            }
        }
        jobs[key] = job
    }

    fun cancel(clip: TimelineClip) {
        val key = key(clip)
        jobs.remove(key)?.cancel()
    }
}
