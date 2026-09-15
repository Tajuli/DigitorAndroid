package com.tajuli.digitorandroid.editor.render

import android.content.Context
import android.graphics.Bitmap
import androidx.media3.common.Effect
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextureOverlay
import com.tajuli.digitorandroid.editor.model.TextOverlayClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.VisualOverlayClipV19
import com.tajuli.digitorandroid.editor.model.resolvedVideoTrackIdV19
import com.tajuli.digitorandroid.editor.model.resolvedVisualOverlaysV19
import com.tajuli.digitorandroid.editor.model.resolvedVideoTrackIdV3

private const val AUTO_CC_EXPORT_PREFIX_V80 = "auto-cc-v77-"

@UnstableApi
internal class TimedDigitorVisualOverlayV19(
    context: Context,
    private val projectWidth: Int,
    private val spec: VisualOverlayClipV19,
) : BitmapOverlay() {
    private val bitmap: Bitmap = VisualOverlayBitmapCacheV19.get(context, spec)

    override fun getBitmap(presentationTimeUs: Long): Bitmap = bitmap

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        if (!spec.activeAt(presentationTimeUs)) {
            return StaticOverlaySettings.Builder().setAlphaScale(0f).build()
        }
        val desiredWidthPx = projectWidth.coerceAtLeast(2) * spec.scale.coerceIn(.03f, 1.5f)
        val scale = (desiredWidthPx / bitmap.width.coerceAtLeast(1).toFloat()).coerceAtLeast(.0001f)
        return StaticOverlaySettings.Builder()
            .setScale(scale, scale)
            .setAlphaScale(spec.opacity.coerceIn(0f, 1f))
            .setOverlayFrameAnchor(0f, 0f)
            .setBackgroundFrameAnchor(
                spec.positionX.coerceIn(-1f, 1f),
                -spec.positionY.coerceIn(-1f, 1f),
            )
            // Media3 positive rotation is counter-clockwise; editor screen coordinates are +clockwise.
            .setRotationDegrees(-spec.rotationDegrees)
            .build()
    }
}

/**
 * Builds one composition-level overlay stack so titles, images, stickers and shapes share identical
 * timeline timing and V-track z-order during GPU export.
 *
 * V80 collapses each generated Auto CC lane into one dynamic TextOverlay. Media3 allocates one GPU
 * texture/sampler per TextureOverlay, so a 20-100 caption project must not allocate 20-100 samplers
 * when only one caption on that CC lane is visible at a time. Manual text remains one layer per item.
 */
@UnstableApi
internal fun buildProjectOverlayEffectsV19(
    context: Context?,
    project: TimelineProject,
): List<Effect> {
    if (project.textOverlays.isEmpty() && project.resolvedVisualOverlaysV19().isEmpty()) return emptyList()

    data class Layer(val trackIndex: Int, val stableIndex: Int, val overlay: TextureOverlay)
    var stableIndex = 0
    val layers = mutableListOf<Layer>()
    val emittedAutoTracks = mutableSetOf<String?>()

    project.textOverlays.forEach { text ->
        val trackId = text.resolvedVideoTrackIdV3(project)
        val trackIndex = project.tracks.indexOfFirst { it.id == trackId }.let { if (it < 0) Int.MAX_VALUE else it }
        if (!text.id.startsWith(AUTO_CC_EXPORT_PREFIX_V80)) {
            layers += Layer(trackIndex, stableIndex++, TimedDigitorTextOverlay(text))
            return@forEach
        }

        if (!emittedAutoTracks.add(trackId)) return@forEach
        val lane = project.textOverlays
            .asSequence()
            .filter { it.id.startsWith(AUTO_CC_EXPORT_PREFIX_V80) }
            .filter { it.resolvedVideoTrackIdV3(project) == trackId }
            .sortedBy { it.timelineStartUs }
            .toList()
        val canCollapse = lane.zipWithNext().all { (left, right) ->
            left.timelineEndUs <= right.timelineStartUs
        }
        if (canCollapse && lane.isNotEmpty()) {
            layers += Layer(trackIndex, stableIndex++, TimedAutoCaptionSequenceOverlayV80(lane))
        } else {
            // Defensive compatibility: malformed/hand-edited overlapping generated captions retain
            // the old independent-layer semantics instead of failing the whole export.
            lane.forEach { caption ->
                layers += Layer(trackIndex, stableIndex++, TimedDigitorTextOverlay(caption))
            }
        }
    }

    val visualContext = if (project.resolvedVisualOverlaysV19().isNotEmpty()) {
        requireNotNull(context) { "Visual overlay export requires an Android Context" }
    } else null
    project.resolvedVisualOverlaysV19().forEach { visual ->
        val trackId = visual.resolvedVideoTrackIdV19(project)
        val trackIndex = project.tracks.indexOfFirst { it.id == trackId }.let { if (it < 0) Int.MAX_VALUE else it }
        layers += Layer(
            trackIndex,
            stableIndex++,
            TimedDigitorVisualOverlayV19(
                context = requireNotNull(visualContext),
                projectWidth = project.width,
                spec = visual,
            ),
        )
    }

    // New VIDEO tracks are inserted above old ones, so lower/older tracks have larger list indices.
    // Draw those first and upper tracks last.
    val ordered = layers
        .sortedWith(compareByDescending<Layer> { it.trackIndex }.thenBy { it.stableIndex })
        .map { it.overlay }
    return listOf(OverlayEffect(ordered))
}

/** Pure helper used by JVM tests: generated non-overlapping captions consume one export texture. */
internal fun autoCaptionExportTextureCountV80(captions: List<TextOverlayClip>): Int {
    val generated = captions.filter { it.id.startsWith(AUTO_CC_EXPORT_PREFIX_V80) }.sortedBy { it.timelineStartUs }
    if (generated.isEmpty()) return 0
    return if (generated.zipWithNext().all { (left, right) -> left.timelineEndUs <= right.timelineStartUs }) {
        1
    } else {
        generated.size
    }
}
