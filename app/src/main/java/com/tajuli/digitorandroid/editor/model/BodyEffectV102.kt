package com.tajuli.digitorandroid.editor.model

/**
 * Semantic body-effect controls.
 *
 * Body effects intentionally stay separate from the V25 full-frame effect vector. They consume the
 * same temporally-stabilized PP-MattingV2 portrait matte as Pro Cutout, but never modify output
 * alpha. That lets preview/export keep the whole scene while effects stay locked to the person.
 */
data class BodyEffectVectorV102(
    val outline: Float = 0f,
    val glow: Float = 0f,
    val aura: Float = 0f,
    val rgbSplit: Float = 0f,
    val silhouette: Float = 0f,
    val pulse: Float = 0f,
    val clone: Float = 0f,
    val cloneTriple: Float = 0f,
    val cloneEcho: Float = 0f,
    val cloneMirror: Float = 0f,
) {
    val isIdentity: Boolean
        get() = outline == 0f && glow == 0f && aura == 0f && rgbSplit == 0f &&
            silhouette == 0f && pulse == 0f && clone == 0f && cloneTriple == 0f &&
            cloneEcho == 0f && cloneMirror == 0f
}

object BodyEffectCatalogV102 {
    const val CATEGORY = "Body"

    private val vectors: Map<String, BodyEffectVectorV102> = mapOf(
        "Body Glow" to BodyEffectVectorV102(outline = .22f, glow = 1f),
        "Neon Outline" to BodyEffectVectorV102(outline = 1f, glow = .42f),
        "Body Aura" to BodyEffectVectorV102(outline = .18f, glow = .38f, aura = 1f),
        "Body RGB Split" to BodyEffectVectorV102(outline = .16f, rgbSplit = 1f),
        "Body Silhouette" to BodyEffectVectorV102(outline = .32f, glow = .16f, silhouette = 1f),
        "Body Pulse" to BodyEffectVectorV102(outline = .72f, glow = .82f, aura = .36f, pulse = 1f),
        "Body Clone" to BodyEffectVectorV102(clone = 1f),
        "Triple Clone" to BodyEffectVectorV102(clone = 1f, cloneTriple = 1f),
        "Clone Echo" to BodyEffectVectorV102(cloneEcho = 1f),
        "Mirror Clone" to BodyEffectVectorV102(cloneMirror = 1f),
    )

    val names: List<String> get() = vectors.keys.toList()

    fun find(name: String): BodyEffectVectorV102? =
        vectors.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    fun isBodyEffect(name: String): Boolean = find(name) != null
}

fun resolveBodyEffectsV102(effects: List<NodeEffect>): BodyEffectVectorV102 {
    var out = BodyEffectVectorV102()
    effects.asSequence().filter { it.enabled && it.amount > 0f }.forEach { effect ->
        val preset = BodyEffectCatalogV102.find(effect.name) ?: return@forEach
        val amount = effect.amount.coerceIn(0f, 1f)
        out = out.copy(
            outline = (out.outline + preset.outline * amount).coerceIn(0f, 1.5f),
            glow = (out.glow + preset.glow * amount).coerceIn(0f, 1.5f),
            aura = (out.aura + preset.aura * amount).coerceIn(0f, 1.5f),
            rgbSplit = (out.rgbSplit + preset.rgbSplit * amount).coerceIn(0f, 1.5f),
            silhouette = (out.silhouette + preset.silhouette * amount).coerceIn(0f, 1.5f),
            pulse = (out.pulse + preset.pulse * amount).coerceIn(0f, 1.5f),
            clone = (out.clone + preset.clone * amount).coerceIn(0f, 1.5f),
            cloneTriple = (out.cloneTriple + preset.cloneTriple * amount).coerceIn(0f, 1.5f),
            cloneEcho = (out.cloneEcho + preset.cloneEcho * amount).coerceIn(0f, 1.5f),
            cloneMirror = (out.cloneMirror + preset.cloneMirror * amount).coerceIn(0f, 1.5f),
        )
    }
    return out
}

fun resolveTimedBodyEffectsV102(
    effects: List<NodeEffect>,
    clip: TimelineClip,
    sourceTimeUs: Long,
): BodyEffectVectorV102 =
    resolveBodyEffectsV102(effects.filter { it.activeAtSourceTimeV26(clip, sourceTimeUs) })
