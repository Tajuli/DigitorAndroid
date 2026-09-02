package com.tajuli.digitorandroid.editor.model

import kotlin.math.abs

/**
 * V36 filter persistence contract.
 *
 * Creator filters are stored as lightweight [NodeEffect] markers on one already-existing editable
 * color node. Changing a filter therefore changes only effect metadata: no node/edge topology is
 * created or destroyed, so the realtime MediaCodec/GL preview session can stay alive while a user
 * taps filters or drags intensity. The renderer resolves the actual recipe from this catalog.
 *
 * Old V28 filter-node projects remain readable through [appliedCreatorFiltersV36].
 */
const val CREATOR_FILTER_MARKER_PREFIX_V36 = "__digitor_filter_v36__:"

enum class CreatorFilterGroupV36 { LOOKS, BEAUTY }

data class CreatorFilterPresetV36(
    val id: String,
    val name: String,
    val description: String,
    val group: CreatorFilterGroupV36,
    val corrections: NodeCorrections = NodeCorrections(),
    val log: LogWheels = LogWheels(),
    val beautyWeights: Map<String, Float> = emptyMap(),
    val defaultIntensity: Float = 1f,
)

val CREATOR_FILTERS_V36: List<CreatorFilterPresetV36> = listOf(
    CreatorFilterPresetV36(
        id = "fresh_lime", name = "Fresh Lime", description = "Clean fresh greens", group = CreatorFilterGroupV36.LOOKS,
        corrections = NodeCorrections(exposure = .08f, contrast = 7f, saturation = 13f, temperature = -4f, tint = -3f, highlights = -5f, shadows = 7f),
        log = LogWheels(
            shadows = ColorWheelValue(red = -.015f, green = .035f, blue = .005f),
            highlights = ColorWheelValue(red = .012f, green = .030f, blue = -.012f),
        ),
    ),
    CreatorFilterPresetV36(
        id = "vivid_verse", name = "Vivid Verse", description = "Punchy social color", group = CreatorFilterGroupV36.LOOKS,
        corrections = NodeCorrections(exposure = .04f, contrast = 16f, saturation = 23f, temperature = 3f, tint = 2f, highlights = -6f, shadows = -3f),
        log = LogWheels(
            shadows = ColorWheelValue(red = -.020f, blue = .025f),
            highlights = ColorWheelValue(red = .025f, green = .006f, blue = -.014f),
        ),
    ),
    CreatorFilterPresetV36(
        id = "soft_light", name = "Soft Light", description = "Airy bright portrait", group = CreatorFilterGroupV36.LOOKS,
        corrections = NodeCorrections(exposure = .20f, contrast = -13f, saturation = -4f, temperature = 5f, tint = 2f, highlights = -22f, shadows = 18f),
        log = LogWheels(
            midtones = ColorWheelValue(luma = .030f),
            highlights = ColorWheelValue(red = .018f, green = .010f, blue = .004f, luma = .018f),
        ),
    ),
    CreatorFilterPresetV36(
        id = "vhs", name = "VHS", description = "Muted retro camcorder", group = CreatorFilterGroupV36.LOOKS,
        corrections = NodeCorrections(exposure = -.03f, contrast = -7f, saturation = -17f, temperature = 7f, tint = 7f, highlights = -12f, shadows = 11f, hue = -2f),
        log = LogWheels(
            shadows = ColorWheelValue(red = -.020f, green = .002f, blue = .028f),
            highlights = ColorWheelValue(red = .030f, green = -.005f, blue = -.020f),
        ),
    ),
    CreatorFilterPresetV36(
        id = "teal_orange", name = "Teal & Orange", description = "Cool shadow + warm skin", group = CreatorFilterGroupV36.LOOKS,
        corrections = NodeCorrections(exposure = .01f, contrast = 14f, saturation = 9f, temperature = 2f, highlights = -8f, shadows = -7f),
        log = LogWheels(
            shadows = ColorWheelValue(red = -.075f, green = .025f, blue = .090f),
            midtones = ColorWheelValue(red = .018f, green = .003f, blue = -.014f),
            highlights = ColorWheelValue(red = .085f, green = .022f, blue = -.065f),
        ),
    ),
    CreatorFilterPresetV36(
        id = "warm_film", name = "Warm Film", description = "Warm cinematic film", group = CreatorFilterGroupV36.LOOKS,
        corrections = NodeCorrections(exposure = .03f, contrast = -4f, saturation = -5f, temperature = 17f, tint = 2f, highlights = -10f, shadows = 9f),
        log = LogWheels(
            shadows = ColorWheelValue(red = .018f, green = .006f, blue = -.020f),
            highlights = ColorWheelValue(red = .060f, green = .020f, blue = -.045f),
        ),
    ),
    CreatorFilterPresetV36(
        id = "golden_hour", name = "Golden Hour", description = "Sunset warmth + glow", group = CreatorFilterGroupV36.LOOKS,
        corrections = NodeCorrections(exposure = .10f, contrast = 6f, saturation = 14f, temperature = 29f, tint = 4f, highlights = -9f, shadows = 9f),
        log = LogWheels(
            midtones = ColorWheelValue(red = .035f, green = .010f, blue = -.030f),
            highlights = ColorWheelValue(red = .090f, green = .035f, blue = -.070f, luma = .012f),
        ),
    ),
    CreatorFilterPresetV36(
        id = "moody_cinema", name = "Moody Cinema", description = "Deep cinematic contrast", group = CreatorFilterGroupV36.LOOKS,
        corrections = NodeCorrections(exposure = -.16f, contrast = 21f, saturation = -12f, temperature = -5f, tint = 1f, highlights = -23f, shadows = -13f),
        log = LogWheels(
            shadows = ColorWheelValue(red = -.035f, green = .006f, blue = .060f, luma = -.025f),
            highlights = ColorWheelValue(red = .030f, green = .006f, blue = -.022f),
        ),
    ),
    CreatorFilterPresetV36(
        id = "natural_portrait", name = "Natural Portrait", description = "Bright clean natural skin", group = CreatorFilterGroupV36.LOOKS,
        corrections = NodeCorrections(exposure = .15f, contrast = -5f, saturation = 4f, temperature = 7f, tint = 2f, highlights = -17f, shadows = 16f),
        log = LogWheels(
            midtones = ColorWheelValue(red = .018f, green = .005f, blue = -.010f, luma = .022f),
            highlights = ColorWheelValue(red = .020f, green = .007f, blue = -.012f),
        ),
    ),
    CreatorFilterPresetV36(
        id = "fade_film", name = "Fade Film", description = "Lifted blacks + matte", group = CreatorFilterGroupV36.LOOKS,
        corrections = NodeCorrections(exposure = .03f, contrast = -19f, saturation = -10f, temperature = 6f, tint = 1f, highlights = -11f, shadows = 22f),
        log = LogWheels(
            shadows = ColorWheelValue(red = .012f, green = .006f, luma = .080f),
            highlights = ColorWheelValue(red = .025f, green = .008f, blue = -.016f, luma = -.010f),
        ),
    ),
    CreatorFilterPresetV36(
        id = "skin_bright", name = "Skin Bright", description = "Instant bright natural skin", group = CreatorFilterGroupV36.BEAUTY,
        beautyWeights = mapOf(BEAUTY_SKIN_BRIGHT_V28 to 1f), defaultIntensity = .80f,
    ),
    CreatorFilterPresetV36(
        id = "skin_smooth", name = "Skin Smooth", description = "Texture-safe skin smoothing", group = CreatorFilterGroupV36.BEAUTY,
        beautyWeights = mapOf(BEAUTY_SKIN_SMOOTH_V28 to 1f), defaultIntensity = .50f,
    ),
    CreatorFilterPresetV36(
        id = "pink_lips", name = "Pink Lips", description = "Natural rose lips", group = CreatorFilterGroupV36.BEAUTY,
        beautyWeights = mapOf(BEAUTY_PINK_LIP_V28 to 1f), defaultIntensity = .50f,
    ),
    CreatorFilterPresetV36(
        id = "hair_brows", name = "Hair & Brows", description = "Defined hair + brows", group = CreatorFilterGroupV36.BEAUTY,
        beautyWeights = mapOf(BEAUTY_HAIR_BROW_DARK_V28 to 1f), defaultIntensity = .42f,
    ),
    CreatorFilterPresetV36(
        id = "eye_pop", name = "Eye Pop", description = "Natural eye clarity", group = CreatorFilterGroupV36.BEAUTY,
        beautyWeights = mapOf(BEAUTY_EYE_POP_V28 to 1f), defaultIntensity = .42f,
    ),
    CreatorFilterPresetV36(
        id = "portrait_glow", name = "Portrait Glow", description = "Bright balanced beauty", group = CreatorFilterGroupV36.BEAUTY,
        beautyWeights = mapOf(
            BEAUTY_SKIN_BRIGHT_V28 to 1.00f,
            BEAUTY_SKIN_SMOOTH_V28 to .34f,
            BEAUTY_PINK_LIP_V28 to .38f,
            BEAUTY_HAIR_BROW_DARK_V28 to .28f,
            BEAUTY_EYE_POP_V28 to .34f,
        ),
        defaultIntensity = .75f,
    ),
)

fun creatorFilterPresetV36(id: String): CreatorFilterPresetV36? =
    CREATOR_FILTERS_V36.firstOrNull { it.id == id }

fun creatorFilterMarkerNameV36(id: String): String = CREATOR_FILTER_MARKER_PREFIX_V36 + id

fun NodeEffect.creatorFilterPresetIdV36(): String? =
    name.takeIf { it.startsWith(CREATOR_FILTER_MARKER_PREFIX_V36) }
        ?.removePrefix(CREATOR_FILTER_MARKER_PREFIX_V36)
        ?.takeIf { creatorFilterPresetV36(it) != null }

private const val LEGACY_FILTER_PREFIX_V28 = "FilterV28 · "
private const val LEGACY_FILTER_PREFIX_V27 = "Filter · "

fun ColorNode.legacyCreatorFilterPresetIdV36(): String? {
    if (label.startsWith(LEGACY_FILTER_PREFIX_V28)) {
        return label.removePrefix(LEGACY_FILTER_PREFIX_V28).substringBefore(" · ")
            .takeIf { creatorFilterPresetV36(it) != null }
    }
    if (label.startsWith(LEGACY_FILTER_PREFIX_V27)) {
        val oldName = label.removePrefix(LEGACY_FILTER_PREFIX_V27)
        return CREATOR_FILTERS_V36.firstOrNull { it.name == oldName }?.id
    }
    return null
}

fun ColorNode.isLegacyCreatorFilterNodeV36(): Boolean = legacyCreatorFilterPresetIdV36() != null

/**
 * Returns applied preset intensities. V36 markers win over legacy filter nodes if both exist.
 */
fun TimelineClip.appliedCreatorFiltersV36(): LinkedHashMap<String, Float> {
    val result = linkedMapOf<String, Float>()
    nodeGraph.nodes
        .filter { it.kind == NodeKind.SERIAL || it.kind == NodeKind.PARALLEL }
        .forEach { node ->
            val legacyId = node.legacyCreatorFilterPresetIdV36()
            if (legacyId != null && legacyId !in result) {
                creatorFilterPresetV36(legacyId)?.let { preset ->
                    result[legacyId] = inferLegacyFilterIntensityV36(node, preset)
                }
            }
            node.effects.asSequence()
                .filter { it.enabled && it.amount > 0f }
                .forEach { effect ->
                    effect.creatorFilterPresetIdV36()?.let { id -> result[id] = effect.amount.coerceIn(0f, 1f) }
                }
        }
    return result
}

fun TimelineClip.creatorFilterHostNodeV36(): ColorNode? =
    nodeGraph.nodes.firstOrNull { node ->
        (node.kind == NodeKind.SERIAL || node.kind == NodeKind.PARALLEL) && !node.isLegacyCreatorFilterNodeV36()
    }

private fun inferLegacyFilterIntensityV36(node: ColorNode, preset: CreatorFilterPresetV36): Float {
    if (preset.beautyWeights.isNotEmpty()) {
        val base = preset.beautyWeights.entries.firstOrNull { abs(it.value) > .0001f } ?: return 1f
        val current = node.effects.firstOrNull { it.name == base.key }?.amount ?: 0f
        return (current / base.value).coerceIn(0f, 1f)
    }
    val base = preset.corrections
    val current = node.corrections
    val pairs = listOf(
        current.exposure to base.exposure,
        current.contrast to base.contrast,
        current.saturation to base.saturation,
        current.temperature to base.temperature,
        current.highlights to base.highlights,
        current.shadows to base.shadows,
    )
    val pair = pairs.firstOrNull { (_, reference) -> abs(reference) > .0001f } ?: return 1f
    return (pair.first / pair.second).coerceIn(0f, 1f)
}

data class CombinedCreatorLookV36(
    val corrections: NodeCorrections = NodeCorrections(),
    val log: LogWheels = LogWheels(),
    val strength: Float = 0f,
)

/**
 * Combines all active creator looks into one persistent high-precision GPU pass. Exposure/hue are
 * naturally additive, while contrast/saturation/temperature/log-wheel deltas are summed then capped
 * to sane creator ranges. One active filter is represented exactly by its catalog recipe.
 */
fun TimelineClip.combinedCreatorLookV36(): CombinedCreatorLookV36 {
    val applied = appliedCreatorFiltersV36()
    var c = NodeCorrections()
    var shadows = ColorWheelValue()
    var midtones = ColorWheelValue()
    var highlights = ColorWheelValue()
    var global = ColorWheelValue()
    var strength = 0f

    applied.forEach { (id, amount) ->
        val preset = creatorFilterPresetV36(id) ?: return@forEach
        if (preset.group != CreatorFilterGroupV36.LOOKS || amount <= 0f) return@forEach
        val a = amount.coerceIn(0f, 1f)
        c = c.copy(
            exposure = c.exposure + preset.corrections.exposure * a,
            contrast = c.contrast + preset.corrections.contrast * a,
            saturation = c.saturation + preset.corrections.saturation * a,
            temperature = c.temperature + preset.corrections.temperature * a,
            tint = c.tint + preset.corrections.tint * a,
            highlights = c.highlights + preset.corrections.highlights * a,
            shadows = c.shadows + preset.corrections.shadows * a,
            hue = c.hue + preset.corrections.hue * a,
            colorBoost = c.colorBoost + preset.corrections.colorBoost * a,
        )
        shadows = shadows.plusScaledV36(preset.log.shadows, a)
        midtones = midtones.plusScaledV36(preset.log.midtones, a)
        highlights = highlights.plusScaledV36(preset.log.highlights, a)
        global = global.plusScaledV36(preset.log.global, a)
        strength = maxOf(strength, a)
    }

    return CombinedCreatorLookV36(
        corrections = c.copy(
            exposure = c.exposure.coerceIn(-1.2f, 1.2f),
            contrast = c.contrast.coerceIn(-80f, 100f),
            saturation = c.saturation.coerceIn(-80f, 120f),
            temperature = c.temperature.coerceIn(-100f, 100f),
            tint = c.tint.coerceIn(-100f, 100f),
            highlights = c.highlights.coerceIn(-100f, 100f),
            shadows = c.shadows.coerceIn(-100f, 100f),
            hue = c.hue.coerceIn(-180f, 180f),
            colorBoost = c.colorBoost.coerceIn(-100f, 100f),
        ),
        log = LogWheels(
            shadows = shadows.clampedV36(),
            midtones = midtones.clampedV36(),
            highlights = highlights.clampedV36(),
            global = global.clampedV36(),
        ),
        strength = strength,
    )
}

private fun ColorWheelValue.plusScaledV36(other: ColorWheelValue, amount: Float): ColorWheelValue = copy(
    red = red + other.red * amount,
    green = green + other.green * amount,
    blue = blue + other.blue * amount,
    luma = luma + other.luma * amount,
)

private fun ColorWheelValue.clampedV36(): ColorWheelValue = copy(
    red = red.coerceIn(-.35f, .35f),
    green = green.coerceIn(-.35f, .35f),
    blue = blue.coerceIn(-.35f, .35f),
    luma = luma.coerceIn(-.35f, .35f),
)
