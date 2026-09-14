package com.tajuli.digitorandroid.editor.render

import android.text.SpannableString
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import com.tajuli.digitorandroid.editor.model.TextOverlayClip

/**
 * Renders a non-overlapping Auto CC lane through one Media3 texture.
 *
 * Media3's OverlayEffect creates one sampler/texture slot per TextureOverlay. Auto captions can
 * easily contain tens or hundreds of TextOverlayClip items; representing every caption as a
 * separate TextureOverlay can exceed a phone GPU's fragment-texture limit during export even
 * though only one caption is visible at a time. This wrapper keeps the existing text renderer and
 * switches the active caption by presentation timestamp, so one CC lane always consumes one slot.
 */
@UnstableApi
internal class TimedAutoCaptionSequenceOverlayV80(
    captions: List<TextOverlayClip>,
) : TextOverlay() {
    private data class Entry(
        val spec: TextOverlayClip,
        val renderer: TimedDigitorTextOverlay,
    )

    private val entries = captions
        .sortedBy { it.timelineStartUs }
        .map { Entry(it, TimedDigitorTextOverlay(it)) }

    private val hiddenText = SpannableString(" ")
    private val hiddenSettings: OverlaySettings =
        StaticOverlaySettings.Builder().setAlphaScale(0f).build()

    init {
        require(entries.isNotEmpty()) { "Auto caption sequence requires at least one caption" }
        entries.zipWithNext().forEach { (left, right) ->
            require(left.spec.timelineEndUs <= right.spec.timelineStartUs) {
                "Auto caption sequence contains overlapping captions"
            }
        }
    }

    override fun getText(presentationTimeUs: Long): SpannableString {
        val active = activeEntry(presentationTimeUs) ?: return hiddenText
        return active.renderer.getText(presentationTimeUs)
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val active = activeEntry(presentationTimeUs) ?: return hiddenSettings
        return active.renderer.getOverlaySettings(presentationTimeUs)
    }

    private fun activeEntry(timeUs: Long): Entry? {
        // Captions are sorted and non-overlapping. Binary search avoids scanning a long caption lane
        // for every output frame on projects containing hundreds of generated captions.
        var low = 0
        var high = entries.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val entry = entries[mid]
            when {
                timeUs < entry.spec.timelineStartUs -> high = mid - 1
                timeUs >= entry.spec.timelineEndUs -> low = mid + 1
                else -> return entry
            }
        }
        return null
    }
}
