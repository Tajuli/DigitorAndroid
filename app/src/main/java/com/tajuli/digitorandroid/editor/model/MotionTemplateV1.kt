package com.tajuli.digitorandroid.editor.model

/**
 * Single-clip motion presets. These are not baked videos: the same timeline-timed
 * effect is evaluated by the Media3 creator shader for preview and GPU export.
 *
 * Styles 1..7 are for video, and 11..17 for still photos. Keeping them in
 * NodeEffect means their amount, timeline bar, undo and saved-project lifecycle
 * use the existing effects infrastructure.
 */
data class MotionTemplateV1(
    val name: String,
    val category: String,
    val style: Int,
    val description: String,
)

data class MotionTemplateFrameV1(
    val style: Int = 0,
    val amount: Float = 0f,
    val progress: Float = 0f,
    val elapsedSeconds: Float = 0f,
) {
    val active: Boolean get() = style != 0 && amount > 0f
}

object MotionTemplateCatalogV1 {
    const val VIDEO = "Video Effect"
    const val IMAGE = "Image Effect"

    val presets: List<MotionTemplateV1> = listOf(
        MotionTemplateV1("Beat Zoom", VIDEO, 1, "Rhythmic zoom-in pulses"),
        MotionTemplateV1("Whip Pan", VIDEO, 2, "Fast side-to-side camera motion"),
        MotionTemplateV1("Camera Shake", VIDEO, 3, "Controlled handheld beats"),
        MotionTemplateV1("Flash Cut", VIDEO, 4, "Zoom with a beat-synced flash"),
        MotionTemplateV1("Ghost Echo", VIDEO, 5, "Moving double-exposure echoes"),
        MotionTemplateV1("Mono Reveal", VIDEO, 6, "Black-and-white to colour"),
        MotionTemplateV1("Grid Montage", VIDEO, 7, "Animated two-by-two picture collage"),
        MotionTemplateV1("Ken Burns", IMAGE, 11, "Slow cinematic pan and zoom"),
        MotionTemplateV1("Photo Push", IMAGE, 12, "Smooth push-in on a photo"),
        MotionTemplateV1("Photo Drift", IMAGE, 13, "Gentle floating camera"),
        MotionTemplateV1("Photo Rotate", IMAGE, 14, "Slow subtle rotation and zoom"),
        MotionTemplateV1("Photo Bounce", IMAGE, 15, "Rhythmic image bounce"),
        MotionTemplateV1("Photo Flash", IMAGE, 16, "Soft flash and zoom pulse"),
        MotionTemplateV1("Photo Collage", IMAGE, 17, "Animated tiled photo layout"),
    )

    private val byName = presets.associateBy { it.name.lowercase() }

    fun find(name: String): MotionTemplateV1? = byName[name.lowercase()]

    fun forCategory(category: String): List<MotionTemplateV1> =
        presets.filter { it.category == category }

    /** Last active motion preset wins inside one node, avoiding double camera transforms. */
    fun atSourceTime(
        effects: List<NodeEffect>,
        clip: TimelineClip,
        sourceTimeUs: Long,
    ): MotionTemplateFrameV1 {
        val chosen = effects.lastOrNull { effect ->
            find(effect.name) != null && effect.activeAtSourceTimeV26(clip, sourceTimeUs)
        } ?: return MotionTemplateFrameV1()
        val preset = find(chosen.name) ?: return MotionTemplateFrameV1()
        val start = chosen.resolvedSourceStartUsV26(clip)
        val end = chosen.resolvedSourceEndUsV26(clip)
        val elapsedUs = (sourceTimeUs - start).coerceAtLeast(0L)
        return MotionTemplateFrameV1(
            style = preset.style,
            amount = chosen.amount.coerceIn(0f, 1f),
            progress = (elapsedUs.toDouble() / (end - start).coerceAtLeast(1L).toDouble())
                .toFloat().coerceIn(0f, 1f),
            elapsedSeconds = (elapsedUs.toDouble() / 1_000_000.0).toFloat(),
        )
    }
}
