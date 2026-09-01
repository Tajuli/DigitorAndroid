package com.tajuli.digitorandroid.editor.model

/**
 * Creator-facing V25 effect library inspired by the effect families commonly used in short-form
 * editors. Names are intentionally generic and the renderer is Digitor's own implementation.
 */
data class CreatorEffectPresetV25(
    val name: String,
    val category: String,
    val vector: CreatorEffectVectorV25,
)

data class CreatorEffectVectorV25(
    val blur: Float = 0f,
    val sharpen: Float = 0f,
    val glow: Float = 0f,
    val grain: Float = 0f,
    val vignette: Float = 0f,
    val rgbSplit: Float = 0f,
    val scanlines: Float = 0f,
    val pixelate: Float = 0f,
    val wave: Float = 0f,
    val lens: Float = 0f,
    val zoomBlur: Float = 0f,
    val ghost: Float = 0f,
    val flicker: Float = 0f,
    val warm: Float = 0f,
) {
    val isIdentity: Boolean
        get() = blur == 0f && sharpen == 0f && glow == 0f && grain == 0f && vignette == 0f &&
            rgbSplit == 0f && scanlines == 0f && pixelate == 0f && wave == 0f && lens == 0f &&
            zoomBlur == 0f && ghost == 0f && flicker == 0f && warm == 0f
}

object CreatorEffectCatalogV25 {
    val categories: List<String> = listOf("Basic", "Glitch", "Retro", "Lens", "Motion")

    val presets: List<CreatorEffectPresetV25> = listOf(
        // Basic
        p("Blur", "Basic", blur = 1.00f),
        p("Sharpen", "Basic", sharpen = 1.00f),
        p("Glow", "Basic", glow = 1.00f, blur = .18f),
        p("Film Grain", "Basic", grain = 1.00f),
        p("Vignette", "Basic", vignette = 1.00f),
        p("Soft Focus", "Basic", blur = .52f, glow = .22f),
        p("Dreamy", "Basic", blur = .28f, glow = .72f, warm = .12f),
        p("HDR Pop", "Basic", sharpen = .72f, glow = .18f),
        p("Fade Film", "Basic", grain = .24f, vignette = .28f, warm = .18f),
        p("Black Mist", "Basic", blur = .18f, glow = .42f, vignette = .52f),

        // Glitch
        p("RGB Split", "Glitch", rgbSplit = 1.00f),
        p("Digital Glitch", "Glitch", rgbSplit = .78f, pixelate = .38f, flicker = .42f),
        p("VHS", "Glitch", rgbSplit = .34f, scanlines = .72f, grain = .58f, wave = .16f),
        p("TV Static", "Glitch", grain = 1.00f, scanlines = .62f, flicker = .50f),
        p("Scan Lines", "Glitch", scanlines = 1.00f),
        p("Chromatic", "Glitch", rgbSplit = .62f, glow = .20f),
        p("Data Moshing", "Glitch", pixelate = .64f, wave = .72f, ghost = .38f),
        p("Flicker", "Glitch", flicker = 1.00f),
        p("Strobe", "Glitch", flicker = 1.00f, glow = .46f),
        p("Bad Signal", "Glitch", wave = .86f, scanlines = .58f, grain = .50f, rgbSplit = .44f),

        // Retro
        p("Old Film", "Retro", grain = .72f, vignette = .62f, flicker = .18f, warm = .30f),
        p("90s Cam", "Retro", grain = .54f, scanlines = .22f, warm = .32f, vignette = .20f),
        p("Camcorder", "Retro", sharpen = .24f, scanlines = .38f, grain = .28f, rgbSplit = .14f),
        p("Dust", "Retro", grain = .78f, flicker = .14f),
        p("Retro Noise", "Retro", grain = .84f, warm = .18f, vignette = .22f),
        p("Sepia Fade", "Retro", warm = .72f, vignette = .26f, blur = .08f),
        p("Film Burn", "Retro", glow = .82f, warm = .82f, flicker = .26f),
        p("Light Leak", "Retro", glow = .72f, warm = .52f, vignette = -.20f),
        p("Vintage Lens", "Retro", vignette = .68f, blur = .12f, warm = .34f, lens = .18f),
        p("Super 8", "Retro", grain = .66f, vignette = .58f, flicker = .22f, warm = .28f, scanlines = .12f),

        // Lens
        p("Fisheye", "Lens", lens = 1.00f),
        p("Wide Lens", "Lens", lens = .52f),
        p("Bulge", "Lens", lens = .78f, zoomBlur = .10f),
        p("Pinch", "Lens", lens = -.72f),
        p("Ripple", "Lens", wave = .78f),
        p("Wave", "Lens", wave = 1.00f),
        p("Prism", "Lens", rgbSplit = .58f, lens = .28f, glow = .22f),
        p("Kaleidoscope", "Lens", lens = .62f, wave = .54f, rgbSplit = .34f),
        p("Mirror", "Lens", lens = -.34f, sharpen = .16f),
        p("Lens Distortion", "Lens", lens = .68f, vignette = .42f),

        // Motion
        p("Motion Blur", "Motion", blur = .54f, ghost = .54f),
        p("Zoom Blur", "Motion", zoomBlur = 1.00f),
        p("Radial Blur", "Motion", zoomBlur = .72f, blur = .26f),
        p("Shake", "Motion", wave = .58f, flicker = .12f),
        p("Jitter", "Motion", wave = .82f, rgbSplit = .18f),
        p("Ghost Trail", "Motion", ghost = 1.00f),
        p("Echo", "Motion", ghost = .72f, rgbSplit = .20f),
        p("Pulse Zoom", "Motion", zoomBlur = .62f, flicker = .24f),
        p("Spin Blur", "Motion", zoomBlur = .70f, wave = .36f),
        p("Flash", "Motion", glow = .82f, flicker = .82f),
    )

    private val byName = presets.associateBy { it.name.lowercase() }

    fun find(name: String): CreatorEffectPresetV25? = byName[name.lowercase()]
    fun inCategory(category: String): List<CreatorEffectPresetV25> = presets.filter { it.category == category }
}

fun resolveCreatorEffectsV25(effects: List<NodeEffect>): CreatorEffectVectorV25 {
    var out = CreatorEffectVectorV25()
    effects.asSequence().filter { it.enabled && it.amount > 0f }.forEach { effect ->
        val preset = CreatorEffectCatalogV25.find(effect.name) ?: return@forEach
        val a = effect.amount.coerceIn(0f, 1f)
        val v = preset.vector
        out = out.copy(
            blur = (out.blur + v.blur * a).coerceIn(0f, 1.5f),
            sharpen = (out.sharpen + v.sharpen * a).coerceIn(0f, 1.5f),
            glow = (out.glow + v.glow * a).coerceIn(0f, 1.5f),
            grain = (out.grain + v.grain * a).coerceIn(0f, 1.5f),
            vignette = (out.vignette + v.vignette * a).coerceIn(-1f, 1.5f),
            rgbSplit = (out.rgbSplit + v.rgbSplit * a).coerceIn(0f, 1.5f),
            scanlines = (out.scanlines + v.scanlines * a).coerceIn(0f, 1.5f),
            pixelate = (out.pixelate + v.pixelate * a).coerceIn(0f, 1.5f),
            wave = (out.wave + v.wave * a).coerceIn(0f, 1.5f),
            lens = (out.lens + v.lens * a).coerceIn(-1.5f, 1.5f),
            zoomBlur = (out.zoomBlur + v.zoomBlur * a).coerceIn(0f, 1.5f),
            ghost = (out.ghost + v.ghost * a).coerceIn(0f, 1.5f),
            flicker = (out.flicker + v.flicker * a).coerceIn(0f, 1.5f),
            warm = (out.warm + v.warm * a).coerceIn(-1f, 1.5f),
        )
    }
    return out
}

@Suppress("LongParameterList")
private fun p(
    name: String,
    category: String,
    blur: Float = 0f,
    sharpen: Float = 0f,
    glow: Float = 0f,
    grain: Float = 0f,
    vignette: Float = 0f,
    rgbSplit: Float = 0f,
    scanlines: Float = 0f,
    pixelate: Float = 0f,
    wave: Float = 0f,
    lens: Float = 0f,
    zoomBlur: Float = 0f,
    ghost: Float = 0f,
    flicker: Float = 0f,
    warm: Float = 0f,
): CreatorEffectPresetV25 = CreatorEffectPresetV25(
    name = name,
    category = category,
    vector = CreatorEffectVectorV25(
        blur = blur,
        sharpen = sharpen,
        glow = glow,
        grain = grain,
        vignette = vignette,
        rgbSplit = rgbSplit,
        scanlines = scanlines,
        pixelate = pixelate,
        wave = wave,
        lens = lens,
        zoomBlur = zoomBlur,
        ghost = ghost,
        flicker = flicker,
        warm = warm,
    ),
)
