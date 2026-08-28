package com.tajuli.digitorandroid.editor.render

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.OverlaySettings
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.GainProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import com.tajuli.digitorandroid.editor.model.AudioMix
import com.tajuli.digitorandroid.editor.model.TextOverlayClip
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import kotlin.math.min

@UnstableApi
internal fun projectTextEffects(project: TimelineProject): List<Effect> {
    if (project.textOverlays.isEmpty()) return emptyList()
    return listOf(OverlayEffect(project.textOverlays.map(::TimedDigitorTextOverlay)))
}

@UnstableApi
internal fun audioProcessorsFor(clip: TimelineClip): List<AudioProcessor> {
    val mix = clip.audioMix.normalizedFor(clip.durationUs)
    if (mix.volume == 1f && mix.fadeInUs == 0L && mix.fadeOutUs == 0L) return emptyList()
    return listOf(GainProcessor(ClipGainProvider(mix, clip.durationUs)))
}

@UnstableApi
private class ClipGainProvider(
    private val mix: AudioMix,
    private val durationUs: Long,
) : GainProcessor.GainProvider {
    override fun getGainFactorAtSamplePosition(samplePosition: Long, sampleRate: Int): Float {
        val safeRate = sampleRate.coerceAtLeast(1)
        val timeUs = samplePosition.coerceAtLeast(0L) * 1_000_000L / safeRate
        var envelope = 1f
        if (mix.fadeInUs > 0L) {
            envelope = min(envelope, timeUs.toFloat() / mix.fadeInUs.toFloat())
        }
        if (mix.fadeOutUs > 0L) {
            val remainingUs = (durationUs - timeUs).coerceAtLeast(0L)
            envelope = min(envelope, remainingUs.toFloat() / mix.fadeOutUs.toFloat())
        }
        return (mix.volume * envelope.coerceIn(0f, 1f)).coerceIn(0f, 1f)
    }

    override fun isUnityUntil(samplePosition: Long, sampleRate: Int): Long {
        if (mix.volume != 1f) return C.TIME_UNSET
        val safeRate = sampleRate.coerceAtLeast(1)
        val timeUs = samplePosition.coerceAtLeast(0L) * 1_000_000L / safeRate
        if (mix.fadeInUs > 0L && timeUs < mix.fadeInUs) return C.TIME_UNSET
        val fadeOutStartUs = (durationUs - mix.fadeOutUs).coerceAtLeast(0L)
        if (mix.fadeOutUs > 0L && timeUs >= fadeOutStartUs) return C.TIME_UNSET
        return if (mix.fadeOutUs <= 0L) {
            C.TIME_END_OF_SOURCE
        } else {
            (fadeOutStartUs * safeRate / 1_000_000L).coerceAtLeast(samplePosition)
        }
    }
}

@UnstableApi
private class TimedDigitorTextOverlay(
    private val spec: TextOverlayClip,
) : TextOverlay() {
    private val visibleText: SpannableString = styled(spec)
    private val hiddenText = SpannableString("")

    override fun getText(presentationTimeUs: Long): SpannableString =
        if (spec.activeAt(presentationTimeUs)) visibleText else hiddenText

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        if (!spec.activeAt(presentationTimeUs)) {
            return StaticOverlaySettings.Builder().setAlphaScale(0f).build()
        }
        return StaticOverlaySettings.Builder()
            .setAlphaScale(1f)
            .setOverlayFrameAnchor(0f, 0f)
            .setBackgroundFrameAnchor(
                spec.positionX.coerceIn(-1f, 1f),
                -spec.positionY.coerceIn(-1f, 1f),
            )
            .build()
    }

    private companion object {
        fun styled(spec: TextOverlayClip): SpannableString {
            val text = SpannableString(spec.text.ifBlank { " " })
            val flags = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            text.setSpan(ForegroundColorSpan(spec.argb.toInt()), 0, text.length, flags)
            text.setSpan(RelativeSizeSpan(spec.sizeScale.coerceIn(.35f, 4f)), 0, text.length, flags)
            if (spec.bold) text.setSpan(StyleSpan(Typeface.BOLD), 0, text.length, flags)
            if (spec.background) text.setSpan(BackgroundColorSpan(0xB0000000.toInt()), 0, text.length, flags)
            return text
        }
    }
}
