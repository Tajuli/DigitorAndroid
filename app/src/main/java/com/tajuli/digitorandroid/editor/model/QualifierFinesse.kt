package com.tajuli.digitorandroid.editor.model

/**
 * Extra Resolve-style qualifier controls are stored as internal node effects so old projects and
 * the public HslQualifier model remain source compatible. These entries are renderer metadata,
 * not user-facing OFX effects.
 */
object QualifierFinesseKeys {
    const val PREFIX = "__qualifier_"
    const val HUE_SYMMETRY = "${PREFIX}hue_symmetry"
    const val SAT_LOW_SOFT = "${PREFIX}sat_low_soft"
    const val SAT_HIGH_SOFT = "${PREFIX}sat_high_soft"
    const val LUM_LOW_SOFT = "${PREFIX}lum_low_soft"
    const val LUM_HIGH_SOFT = "${PREFIX}lum_high_soft"
    const val PRE_FILTER = "${PREFIX}pre_filter"
    const val CLEAN_BLACK = "${PREFIX}clean_black"
    const val CLEAN_WHITE = "${PREFIX}clean_white"
    const val BLACK_CLIP = "${PREFIX}black_clip"
    const val WHITE_CLIP = "${PREFIX}white_clip"
    const val BLUR_RADIUS = "${PREFIX}blur_radius"
    const val IN_OUT_RATIO = "${PREFIX}in_out_ratio"
}

fun ColorNode.qualifierFinesse(key: String, defaultValue: Float): Float =
    effects.firstOrNull { it.name == key }?.amount ?: defaultValue

fun ColorNode.visibleEffects(): List<NodeEffect> =
    effects.filterNot { it.name.startsWith(QualifierFinesseKeys.PREFIX) }
