package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.tajuli.digitorandroid.editor.audio.AudioWaveform
import com.tajuli.digitorandroid.editor.audio.AudioWaveformRepository
import com.tajuli.digitorandroid.editor.model.TimelineClip
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Timeline-only waveform layer. Decode/cache work stays off the UI thread in the repository. */
@Composable
internal fun TimelineAudioWaveformV15(
    clip: TimelineClip,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember(context.applicationContext) { AudioWaveformRepository.get(context.applicationContext) }
    var waveform by remember(clip.uri) { mutableStateOf<AudioWaveform?>(null) }

    LaunchedEffect(clip.uri) {
        waveform = repository.load(clip.uri)
    }

    Canvas(modifier) {
        val source = waveform
        val centerY = size.height * .5f
        if (source == null) {
            drawLine(
                color = Color.White.copy(alpha = .16f),
                start = Offset(0f, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = 1f,
            )
            return@Canvas
        }

        val startUs = clip.sourceInUs.coerceIn(0L, source.durationUs - 1L)
        val endUs = clip.sourceOutUs.coerceIn(startUs + 1L, source.durationUs)
        val sourceSpanUs = (endUs - startUs).coerceAtLeast(1L)
        val columns = min(MAX_DRAW_COLUMNS, max(1, (size.width / MIN_COLUMN_PX).roundToInt()))
        val columnWidth = size.width / columns
        val strokeWidth = max(1f, columnWidth * .58f)
        val maxHalfHeight = size.height * .43f

        for (column in 0 until columns) {
            val segmentStartUs = startUs + sourceSpanUs * column / columns
            val segmentEndUs = startUs + sourceSpanUs * (column + 1L) / columns
            val amplitude = source.peakBetween(segmentStartUs, segmentEndUs)
            val halfHeight = max(1f, amplitude * maxHalfHeight)
            val x = (column + .5f) * columnWidth
            drawLine(
                color = Color.White.copy(alpha = .72f),
                start = Offset(x, centerY - halfHeight),
                end = Offset(x, centerY + halfHeight),
                strokeWidth = strokeWidth,
            )
        }
    }
}

private const val MAX_DRAW_COLUMNS = 1024
private const val MIN_COLUMN_PX = 2.25f
