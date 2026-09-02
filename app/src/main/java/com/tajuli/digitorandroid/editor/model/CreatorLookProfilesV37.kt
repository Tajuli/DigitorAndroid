package com.tajuli.digitorandroid.editor.model

/**
 * V37 look-selection contract.
 *
 * A creator LOOK is deliberately different from BEAUTY. LOOKS are full-frame color transforms and
 * never depend on face boxes, skin masks, ML segmentation, or pixel location. Beauty may remain
 * semantic/spatial, but it is a separate stage and a separate group.
 *
 * CapCut-style filter UX is single-look: when legacy projects contain several LOOK markers, the last
 * enabled marker wins. This also gives old V36 projects deterministic behaviour without summing
 * unrelated looks into an unpredictable grade.
 */
enum class CreatorLookKernelV37 {
    /** Existing Digitor-owned analytic full-frame grade for generic looks. */
    GENERIC_GLOBAL,

    /**
     * Global tone/chroma response calibrated from the user-supplied Normal/Cinematic-Dark pair.
     * This is an independently fitted approximation; no third-party LUT/code/assets are stored.
     */
    CINEMATIC_DARK_REFERENCE,
}

data class ActiveCreatorLookV37(
    val preset: CreatorFilterPresetV36,
    val intensity: Float,
    val kernel: CreatorLookKernelV37,
)

fun creatorLookKernelV37(presetId: String): CreatorLookKernelV37 = when (presetId) {
    "moody_cinema" -> CreatorLookKernelV37.CINEMATIC_DARK_REFERENCE
    else -> CreatorLookKernelV37.GENERIC_GLOBAL
}

fun TimelineClip.activeCreatorLookV37(): ActiveCreatorLookV37? {
    val applied = appliedCreatorFiltersV36()
    val selected = applied.entries
        .mapNotNull { (id, amount) ->
            val preset = creatorFilterPresetV36(id) ?: return@mapNotNull null
            if (preset.group != CreatorFilterGroupV36.LOOKS || amount <= .001f) return@mapNotNull null
            preset to amount.coerceIn(0f, 1f)
        }
        .lastOrNull()
        ?: return null

    return ActiveCreatorLookV37(
        preset = selected.first,
        intensity = selected.second,
        kernel = creatorLookKernelV37(selected.first.id),
    )
}
