package com.tajuli.digitorandroid.editor.audio

import android.content.Context
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.outputTimeForSource
import com.tajuli.digitorandroid.editor.model.sourceTimeForOutput
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Audio-following motion uses REAL decoded PCM waveform peaks, not simulated BPM.
 * We select only salient onsets separated by at least 280ms. As with most lightweight
 * waveform-only beat detectors, strong non-bass transients can also count as beats.
 */
object MusicBeatPulseV1 {
    @Volatile private var activeProject: TimelineProject? = null
    private val beatsByUri = ConcurrentHashMap<String, LongArray>()
    private val sourceLocks = ConcurrentHashMap<String, Any>()

    fun useProject(project: TimelineProject) {
        activeProject = project
    }

    fun project(): TimelineProject? = activeProject

    /** Never decode from CPU fallback loops; only use beat data already cached. */
    fun cachedPulse(visualClip: TimelineClip, sourceUs: Long): Float =
        pulseAtInternal(null, visualClip, sourceUs, activeProject)

    /** Loads/decode-caches a track once when an animated GPU effect first needs its beats. */
    fun pulseAt(context: Context, visualClip: TimelineClip, sourceUs: Long, project: TimelineProject?): Float =
        pulseAtInternal(context, visualClip, sourceUs, project ?: activeProject)

    private fun pulseAtInternal(
        context: Context?,
        visualClip: TimelineClip,
        sourceUs: Long,
        project: TimelineProject?,
    ): Float {
        val timelineUs = visualClip.timelineStartUs + visualClip.outputTimeForSource(sourceUs)
        // Separate music on A tracks takes precedence over incidental audio on a video.
        val audioClips = project?.tracks.orEmpty()
            .filter { it.kind == TrackKind.AUDIO && !it.muted }
            .flatMap { it.clips }
            .filter { timelineUs in it.timelineStartUs until it.timelineEndUs && it.audioMix.volume > 0f }
        val sources = if (audioClips.isNotEmpty()) audioClips else listOf(visualClip)
        var largest = 0f
        for (audioClip in sources) {
            val beats = loadedBeats(context, audioClip.uri)
            if (beats.isEmpty()) continue
            val offset = (timelineUs - audioClip.timelineStartUs).coerceIn(0L, audioClip.durationUs)
            val audioSourceUs = audioClip.sourceTimeForOutput(offset)
            val beatIndex = beats.binarySearch(audioSourceUs)
            // Check neighbours around the insertion point; impulses peak at the actual onset.
            val first = if (beatIndex >= 0) beatIndex else -beatIndex - 1
            for (index in (first - 1).coerceAtLeast(0)..(first + 1).coerceAtMost(beats.lastIndex)) {
                val delta = abs(audioSourceUs - beats[index])
                if (delta < 240_000L) {
                    val t = delta / 105_000.0
                    largest = max(largest, exp(-t * t).toFloat())
                }
            }
        }
        return largest.coerceIn(0f, 1f)
    }

    private fun loadedBeats(context: Context?, uri: String): LongArray {
        beatsByUri[uri]?.let { return it }
        if (context == null) return LongArray(0)
        val lock = sourceLocks.getOrPut(uri) { Any() }
        synchronized(lock) {
            beatsByUri[uri]?.let { return it }
            val waveform = runCatching {
                runBlocking(Dispatchers.IO) { AudioWaveformRepository.get(context).load(uri) }
            }.getOrNull()
            return (waveform?.let(::detectHeavyOnsetsV1) ?: LongArray(0)).also {
                beatsByUri[uri] = it
                sourceLocks.remove(uri, lock)
            }
        }
    }
}

/**
 * Lightweight adaptive strong-onset detector over the already decoded 20ms (or coarser) peaks.
 * Silence produces no beat; threshold follows the recent mean rather than a fixed BPM.
 */
internal fun detectHeavyOnsetsV1(waveform: AudioWaveform): LongArray {
    val peaks = waveform.peaks
    if (peaks.size < 8 || peaks.maxOrNull() == 0f) return LongArray(0)
    val bucketUs = waveform.durationUs.toDouble() / peaks.size
    val lookback = (480_000.0 / bucketUs).toInt().coerceIn(3, 48)
    val minGap = (280_000.0 / bucketUs).toInt().coerceAtLeast(1)
    val hits = ArrayList<Long>()
    var previous = -minGap
    for (i in lookback until peaks.size - 2) {
        if (i - previous < minGap) continue
        val value = peaks[i]
        if (value < .44f || value < peaks[i - 1] || value < peaks[i + 1]) continue
        var sum = 0f
        for (j in (i - lookback) until i) sum += peaks[j]
        val base = sum / lookback
        if (value < base * 1.34f || value - base < .16f) continue
        hits += (i * bucketUs).toLong()
        previous = i
    }
    return hits.toLongArray()
}
