package com.tajuli.digitorandroid.editor.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.Spannable
import android.text.SpannableString
import android.text.style.AlignmentSpan
import android.text.style.ReplacementSpan
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
import com.tajuli.digitorandroid.editor.model.TextAlignmentV2
import com.tajuli.digitorandroid.editor.model.TextFontV2
import com.tajuli.digitorandroid.editor.model.TextOverlayClip
import com.tajuli.digitorandroid.editor.model.TextStyleV2
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.resolvedTextStyleV2
import com.tajuli.digitorandroid.editor.model.textAnimationFrameV2
import com.tajuli.digitorandroid.editor.model.textManualFrameV2
import kotlin.math.ceil
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
    private val staticVisibleText: SpannableString = styled(spec, spec.sizeScale)

    // Media3 TextOverlay rasterizes getText() into a Bitmap. Returning an empty SpannableString
    // makes its measured width zero and can reach Bitmap.createBitmap(0, ...), which aborts
    // Transformer before the first encoded frame. Keep a positive-width placeholder while hidden;
    // getOverlaySettings() makes it fully transparent outside the clip's active interval.
    private val hiddenText = SpannableString(" ")

    override fun getText(presentationTimeUs: Long): SpannableString {
        if (!spec.activeAt(presentationTimeUs)) return hiddenText
        val manual = spec.textManualFrameV2(presentationTimeUs)
        return if (spec.manualAnimationV2?.keyframes.isNullOrEmpty()) {
            staticVisibleText
        } else {
            // Media3 TextOverlay lays out a Spannable per frame. Rebuilding only when manual size
            // keyframes exist keeps normal text cheap while preserving preview/export parity.
            styled(spec, manual.sizeScale)
        }
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        if (!spec.activeAt(presentationTimeUs)) {
            return StaticOverlaySettings.Builder().setAlphaScale(0f).build()
        }
        val preset = spec.textAnimationFrameV2(presentationTimeUs)
        val manual = spec.textManualFrameV2(presentationTimeUs)
        return StaticOverlaySettings.Builder()
            .setAlphaScale((preset.alpha * manual.alpha).coerceIn(0f, 1f))
            .setOverlayFrameAnchor(0f, 0f)
            .setBackgroundFrameAnchor(
                (manual.positionX + preset.offsetX).coerceIn(-1f, 1f),
                -(manual.positionY + preset.offsetY).coerceIn(-1f, 1f),
            )
            .setRotationDegrees(manual.rotationDegrees)
            .build()
    }

    private companion object {
        fun styled(spec: TextOverlayClip, sizeScale: Float): SpannableString {
            val content = spec.text.ifBlank { " " }
            val text = SpannableString(content)
            val flags = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            val style = spec.resolvedTextStyleV2()
            val alignment = when (style.alignment) {
                TextAlignmentV2.LEFT -> Layout.Alignment.ALIGN_NORMAL
                TextAlignmentV2.CENTER -> Layout.Alignment.ALIGN_CENTER
                TextAlignmentV2.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
            }
            text.setSpan(AlignmentSpan.Standard(alignment), 0, text.length, flags)

            var lineStart = 0
            while (lineStart <= content.length) {
                val newline = content.indexOf('\n', lineStart).let { if (it < 0) content.length else it }
                if (newline > lineStart) {
                    text.setSpan(
                        StyledTextReplacementSpan(style, sizeScale, spec.bold),
                        lineStart,
                        newline,
                        flags,
                    )
                }
                if (newline >= content.length) break
                lineStart = newline + 1
            }
            return text
        }
    }
}

private class StyledTextReplacementSpan(
    private val style: TextStyleV2,
    private val sizeScale: Float,
    private val bold: Boolean,
) : ReplacementSpan() {
    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int {
        val work = configuredPaint(paint)
        if (fm != null) {
            val metrics = work.fontMetricsInt
            fm.top = metrics.top
            fm.ascent = metrics.ascent
            fm.descent = metrics.descent
            fm.bottom = metrics.bottom
            fm.leading = metrics.leading
        }
        return ceil(work.measureText(text, start, end) + horizontalPadding(work) * 2f).toInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val work = configuredPaint(paint)
        val padding = horizontalPadding(work)
        val baselineX = x + padding
        val measured = work.measureText(text, start, end)

        if (style.backgroundEnabled) {
            val backgroundColor = style.backgroundArgb.toInt()
            val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = backgroundColor
                this.style = Paint.Style.FILL
            }
            val radius = (work.textSize * .16f).coerceAtLeast(2f)
            canvas.drawRoundRect(
                x,
                top.toFloat(),
                x + measured + padding * 2f,
                bottom.toFloat(),
                radius,
                radius,
                background,
            )
        }

        if (style.shadowEnabled) {
            work.setShadowLayer(
                style.shadowRadius,
                style.shadowDx,
                style.shadowDy,
                style.shadowArgb.toInt(),
            )
        }

        if (style.strokeWidth > 0f) {
            work.style = Paint.Style.STROKE
            work.strokeJoin = Paint.Join.ROUND
            work.strokeWidth = style.strokeWidth * sizeScale.coerceIn(.35f, 4f)
            work.color = style.strokeArgb.toInt()
            canvas.drawText(text, start, end, baselineX, y.toFloat(), work)
        }

        work.style = Paint.Style.FILL
        work.color = style.colorArgb.toInt()
        canvas.drawText(text, start, end, baselineX, y.toFloat(), work)
    }

    private fun configuredPaint(source: Paint): Paint {
        val font = style.font
        return Paint(source).apply {
            textSize = source.textSize * sizeScale.coerceIn(.35f, 4f)
            typeface = Typeface.create(
                when (font) {
                    TextFontV2.SANS -> "sans-serif"
                    TextFontV2.SERIF -> "serif"
                    TextFontV2.MONO -> "monospace"
                    TextFontV2.CURSIVE -> "cursive"
                },
                if (bold) Typeface.BOLD else Typeface.NORMAL,
            )
            isAntiAlias = true
        }
    }

    private fun horizontalPadding(paint: Paint): Float =
        if (style.backgroundEnabled) (paint.textSize * .18f).coerceAtLeast(3f) else 0f
}
