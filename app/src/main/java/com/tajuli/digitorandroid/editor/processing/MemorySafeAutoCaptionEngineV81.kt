package com.tajuli.digitorandroid.editor.processing

import android.app.ActivityManager
import android.content.Context
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TrackKind
import kotlin.math.ceil

private const val ACCURATE_MAX_CHUNK_US_V81 = 8_000_000L
private const val ACCURATE_MIN_AVAILABLE_RAM_BYTES_V81 = 768L * 1024L * 1024L

/**
 * V81 protects the large Omnilingual Accurate model from long-utterance native-memory spikes.
 *
 * The V80 recognizer intentionally stays responsible for model download, checksum verification,
 * one-time model construction and token/timestamp mapping. Before Accurate inference starts, V81
 * replaces only the selected *temporary recognition track* with contiguous balanced sub-clips whose
 * duration never exceeds eight seconds. V80 therefore reuses one OfflineRecognizer/model session
 * while each OfflineStream sees a small bounded waveform and is released before the next chunk.
 *
 * The user's real project is never modified by this split. Generated caption timestamps still map to
 * the original timeline because every temporary clip keeps the correct sourceIn/sourceOut and
 * timelineStart values.
 */
class MemorySafeHybridAutoCaptionEngineV81(private val context: Context) {
    private val delegate = HybridAutoCaptionEngineV80(context)
    private val compactFallback = ZipformerAutoCaptionEngineV79(context)

    companion object {
        fun supportedOnThisDevice(): Boolean = HybridAutoCaptionEngineV80.supportedOnThisDevice()
    }

    suspend fun generate(
        project: TimelineProject,
        audioTrackId: String,
        quality: AutoCaptionQualityV77,
        language: AutoCaptionLanguageV79 = AutoCaptionLanguageV79.BANGLA,
        onProgress: (AutoCaptionProgressV77) -> Unit = {},
    ): AutoCaptionResultV77 {
        if (quality == AutoCaptionQualityV77.FAST) {
            return delegate.generate(project, audioTrackId, quality, language, onProgress)
        }

        if (!context.hasOmnilingualMemoryHeadroomV81()) {
            onProgress(
                AutoCaptionProgressV77(
                    .08f,
                    "Low free RAM · using compact Accurate fallback",
                ),
            )
            return compactFallback.generate(
                project = project,
                audioTrackId = audioTrackId,
                quality = AutoCaptionQualityV77.ACCURATE,
                language = language,
                onProgress = onProgress,
            )
        }

        val safeProject = project.withMemorySafeAccurateAudioChunksV81(audioTrackId)
        val chunkCount = safeProject.track(audioTrackId)?.clips?.size ?: 0
        val originalCount = project.track(audioTrackId)?.clips?.size ?: 0
        if (chunkCount > originalCount) {
            onProgress(
                AutoCaptionProgressV77(
                    .18f,
                    "Accurate mode · memory-safe ${chunkCount} chunks",
                ),
            )
        }

        return delegate.generate(
            project = safeProject,
            audioTrackId = audioTrackId,
            quality = quality,
            language = language,
            onProgress = onProgress,
        ).let { result ->
            if (chunkCount > originalCount && result.backend.contains("offline", ignoreCase = true)) {
                result.copy(backend = "${result.backend} · V81 chunked")
            } else {
                result
            }
        }
    }
}

private fun Context.hasOmnilingualMemoryHeadroomV81(): Boolean {
    val manager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return true
    if (manager.isLowRamDevice) return false
    val info = ActivityManager.MemoryInfo()
    manager.getMemoryInfo(info)
    if (info.lowMemory) return false
    return info.availMem >= ACCURATE_MIN_AVAILABLE_RAM_BYTES_V81
}

/**
 * Pure timeline transformation used only for speech recognition. Chunks are balanced rather than
 * making fixed 8 s pieces plus a tiny tail: a 17 s source becomes ~5.67 + 5.67 + 5.66 s, while a
 * 60 s source becomes eight 7.5 s pieces. This keeps peak ASR memory bounded and avoids pathological
 * sub-second final chunks that hurt recognition quality.
 */
internal fun TimelineProject.withMemorySafeAccurateAudioChunksV81(
    audioTrackId: String,
    maxChunkUs: Long = ACCURATE_MAX_CHUNK_US_V81,
): TimelineProject {
    require(maxChunkUs >= 1_000_000L) { "Accurate Auto CC chunk size is too small" }
    val selected = track(audioTrackId)
        ?.takeIf { it.kind == TrackKind.AUDIO && !it.muted }
        ?: error("Select an unmuted audio track")

    val chunked = selected.sortedClips().flatMap { clip ->
        clip.balancedRecognitionChunksV81(maxChunkUs)
    }
    if (chunked.size == selected.clips.size && chunked.zip(selected.sortedClips()).all { (a, b) -> a == b }) {
        return this
    }

    return copy(
        tracks = tracks.map { track ->
            if (track.id == audioTrackId) track.copy(clips = chunked) else track
        },
    )
}

private fun TimelineClip.balancedRecognitionChunksV81(maxChunkUs: Long): List<TimelineClip> {
    val totalUs = durationUs
    if (totalUs <= maxChunkUs) return listOf(this)

    val count = ceil(totalUs.toDouble() / maxChunkUs.toDouble()).toInt().coerceAtLeast(2)
    val baseSizeUs = totalUs / count
    val remainderUs = totalUs % count
    var offsetUs = 0L

    return List(count) { index ->
        // Spread remainder microseconds over the first chunks so all chunks differ by at most 1 us.
        val chunkDurationUs = baseSizeUs + if (index.toLong() < remainderUs) 1L else 0L
        val sourceStartUs = sourceInUs + offsetUs
        val sourceEndUs = (sourceStartUs + chunkDurationUs).coerceAtMost(sourceOutUs)
        val timelineStart = timelineStartUs + offsetUs
        val chunk = copy(
            id = "$id-auto-cc-v81-$index",
            label = "$label · Accurate ${index + 1}/$count",
            timelineStartUs = timelineStart,
            sourceInUs = sourceStartUs,
            sourceOutUs = sourceEndUs,
        )
        offsetUs += chunkDurationUs
        chunk
    }
}
