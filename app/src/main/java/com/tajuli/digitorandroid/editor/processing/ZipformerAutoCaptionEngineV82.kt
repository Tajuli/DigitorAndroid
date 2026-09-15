package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TrackKind
import kotlin.math.ceil

private const val ACCURATE_ZIPFORMER_MAX_CHUNK_US_V82 = 6_500_000L

/**
 * Zipformer-only V82 Auto CC.
 *
 * There is currently one official Bengali Zipformer2 distribution in sherpa-onnx. Instead of
 * introducing a second multilingual model, Accurate mode gives that Bengali model a cleaner search
 * problem: long clips are divided into balanced sentence-sized recognition windows and decoded with
 * V79's modified-beam-search path. Fast mode remains unchanged.
 *
 * The split exists only in a temporary project passed to the recognizer. Source and timeline offsets
 * are preserved exactly, so generated captions map back to the user's original timeline.
 */
class ZipformerAutoCaptionEngineV82(private val context: Context) {
    private val delegate = ZipformerAutoCaptionEngineV79(context)

    companion object {
        fun supportedOnThisDevice(): Boolean = ZipformerAutoCaptionEngineV79.supportedOnThisDevice()
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

        val tuned = project.withZipformerAccurateChunksV82(audioTrackId)
        val originalCount = project.track(audioTrackId)?.clips?.size ?: 0
        val tunedCount = tuned.track(audioTrackId)?.clips?.size ?: 0
        if (tunedCount > originalCount) {
            onProgress(
                AutoCaptionProgressV77(
                    .17f,
                    "Accurate Zipformer · $tunedCount speech windows",
                ),
            )
        }
        return delegate.generate(
            project = tuned,
            audioTrackId = audioTrackId,
            quality = AutoCaptionQualityV77.ACCURATE,
            language = language,
            onProgress = onProgress,
        ).let { result ->
            result.copy(
                backend = "${result.backend} · Zipformer tuned",
                model = result.model,
            )
        }
    }
}

internal fun TimelineProject.withZipformerAccurateChunksV82(
    audioTrackId: String,
    maxChunkUs: Long = ACCURATE_ZIPFORMER_MAX_CHUNK_US_V82,
): TimelineProject {
    require(maxChunkUs >= 2_000_000L)
    val selected = track(audioTrackId)
        ?.takeIf { it.kind == TrackKind.AUDIO && !it.muted }
        ?: error("Select an unmuted audio track")

    val chunked = selected.sortedClips().flatMap { it.balancedZipformerChunksV82(maxChunkUs) }
    if (chunked == selected.sortedClips()) return this

    return copy(
        tracks = tracks.map { track ->
            if (track.id == audioTrackId) track.copy(clips = chunked) else track
        },
    )
}

private fun TimelineClip.balancedZipformerChunksV82(maxChunkUs: Long): List<TimelineClip> {
    val totalUs = durationUs
    if (totalUs <= maxChunkUs) return listOf(this)

    val count = ceil(totalUs.toDouble() / maxChunkUs.toDouble()).toInt().coerceAtLeast(2)
    val baseUs = totalUs / count
    val remainderUs = totalUs % count
    var offsetUs = 0L

    return List(count) { index ->
        val duration = baseUs + if (index.toLong() < remainderUs) 1L else 0L
        val sourceStart = sourceInUs + offsetUs
        val sourceEnd = (sourceStart + duration).coerceAtMost(sourceOutUs)
        val timelineStart = timelineStartUs + offsetUs
        val part = copy(
            id = "$id-auto-cc-v82-$index",
            label = "$label · Zipformer ${index + 1}/$count",
            timelineStartUs = timelineStart,
            sourceInUs = sourceStart,
            sourceOutUs = sourceEnd,
        )
        offsetUs += duration
        part
    }
}
