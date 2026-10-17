package com.tajuli.digitorandroid.editor.assets

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tajuli.digitorandroid.editor.model.ColorWheelValue
import com.tajuli.digitorandroid.editor.model.CreatorEffectPresetV25
import com.tajuli.digitorandroid.editor.model.CreatorEffectVectorV25
import com.tajuli.digitorandroid.editor.model.CreatorFilterGroupV36
import com.tajuli.digitorandroid.editor.model.CreatorFilterPresetV36
import com.tajuli.digitorandroid.editor.model.LogWheels
import com.tajuli.digitorandroid.editor.model.NodeCorrections
import com.tajuli.digitorandroid.editor.model.TransitionStyleV22
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Data-only GitHub preset catalog. Remote JSON cannot load code, shaders or arbitrary URLs.
 * Compiled Android render families remain the source of preview/export behavior.
 */
data class RemoteTransitionV1(
    val id: String,
    val label: String,
    val category: String,
    val engineStyle: TransitionStyleV22,
)

data class RemoteAssetSnapshotV1(
    val transitions: List<RemoteTransitionV1> = emptyList(),
    val effects: List<CreatorEffectPresetV25> = emptyList(),
    val filters: List<CreatorFilterPresetV36> = emptyList(),
    val revision: Long = 0L,
)

object RemoteAssetCatalogV1 {
    private val updates = MutableStateFlow(RemoteAssetSnapshotV1())
    val state = updates.asStateFlow()

    /** Validate before publishing: malformed files never replace the last valid snapshot. */
    @Synchronized
    fun install(kind: String, json: String): Boolean {
        val next = runCatching {
            val current = updates.value
            when (kind) {
                "transitions" -> current.copy(transitions = parseTransitionsV1(json))
                "effects" -> current.copy(effects = parseEffectsV1(json))
                "filters" -> current.copy(filters = parseFiltersV1(json))
                else -> error("Unsupported catalog")
            }.copy(revision = current.revision + 1L)
        }.getOrNull() ?: return false
        updates.value = next
        return true
    }

    /** Used by downloader to reject invalid network data before it replaces the disk cache. */
    fun isValid(kind: String, json: String): Boolean = runCatching {
        when (kind) {
            "transitions" -> parseTransitionsV1(json)
            "effects" -> parseEffectsV1(json)
            "filters" -> parseFiltersV1(json)
            else -> error("Unsupported catalog")
        }
    }.isSuccess
}

private val remoteIdPatternV1 = Regex("[a-z0-9][a-z0-9_-]{2,63}")
private val allowedEffectCategoriesV1 = setOf("Basic", "Glitch", "Retro", "Lens", "Motion")
private val allowedTransitionCategoriesV1 = setOf("BASIC", "CAMERA", "EFFECT")

private fun checkedPresetsV1(raw: String): List<JsonObject> {
    require(raw.toByteArray(Charsets.UTF_8).size <= 256 * 1024) { "Catalog too large" }
    val document = JsonParser.parseString(raw).asJsonObject
    require(document.get("schemaVersion")?.asInt == 1) { "Unknown catalog schema" }
    val items = requireNotNull(document.getAsJsonArray("presets")) { "Missing presets" }
    require(items.size() <= 200) { "Too many presets" }
    return items.map { it.asJsonObject }
}

private fun JsonObject.requiredTextV1(key: String, limit: Int = 64): String {
    val element = requireNotNull(get(key)) { "Missing " + key }
    require(element.isJsonPrimitive && element.asJsonPrimitive.isString) { "Invalid " + key }
    val value = element.asString.trim()
    require(value.isNotEmpty() && value.length <= limit && value.none { it.isISOControl() }) {
        "Invalid " + key
    }
    return value
}

private fun JsonObject.optionalObjectV1(key: String): JsonObject =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()

private fun JsonObject.numberV1(key: String, min: Float, max: Float, default: Float = 0f): Float {
    val element = get(key) ?: return default
    require(element.isJsonPrimitive && element.asJsonPrimitive.isNumber) { "Invalid " + key }
    val value = element.asFloat
    require(value.isFinite() && value in min..max) { "Out-of-range " + key }
    return value
}

internal fun parseTransitionsV1(json: String): List<RemoteTransitionV1> =
    checkedPresetsV1(json).map { entry ->
        val id = entry.requiredTextV1("id")
        require(remoteIdPatternV1.matches(id)) { "Invalid transition ID" }
        val category = entry.requiredTextV1("category")
        require(category in allowedTransitionCategoriesV1)
        val style = TransitionStyleV22.valueOf(entry.requiredTextV1("engineStyle"))
        require(style != TransitionStyleV22.NONE)
        RemoteTransitionV1(id, entry.requiredTextV1("label"), category, style)
    }.also { require(it.map(RemoteTransitionV1::id).distinct().size == it.size) }

internal fun parseEffectsV1(json: String): List<CreatorEffectPresetV25> =
    checkedPresetsV1(json).map { entry ->
        val name = entry.requiredTextV1("name")
        val category = entry.requiredTextV1("category")
        require(category in allowedEffectCategoriesV1)
        val vector = entry.optionalObjectV1("vector")
        CreatorEffectPresetV25(
            name = name,
            category = category,
            vector = CreatorEffectVectorV25(
                blur = vector.numberV1("blur", 0f, 1.5f),
                sharpen = vector.numberV1("sharpen", 0f, 1.5f),
                glow = vector.numberV1("glow", 0f, 1.5f),
                grain = vector.numberV1("grain", 0f, 1.5f),
                vignette = vector.numberV1("vignette", -1f, 1.5f),
                rgbSplit = vector.numberV1("rgbSplit", 0f, 1.5f),
                scanlines = vector.numberV1("scanlines", 0f, 1.5f),
                pixelate = vector.numberV1("pixelate", 0f, 1.5f),
                wave = vector.numberV1("wave", 0f, 1.5f),
                lens = vector.numberV1("lens", -1.5f, 1.5f),
                zoomBlur = vector.numberV1("zoomBlur", 0f, 1.5f),
                ghost = vector.numberV1("ghost", 0f, 1.5f),
                flicker = vector.numberV1("flicker", 0f, 1.5f),
                warm = vector.numberV1("warm", -1f, 1.5f),
                denoise = vector.numberV1("denoise", 0f, 1.5f),
            ),
        ).also { require(!it.vector.isIdentity) { "Effect recipe must change the image" } }
    }.also { require(it.map { p -> p.name.lowercase() }.distinct().size == it.size) }

private fun JsonObject.wheelV1(): ColorWheelValue = ColorWheelValue(
    red = numberV1("red", -1f, 1f),
    green = numberV1("green", -1f, 1f),
    blue = numberV1("blue", -1f, 1f),
    luma = numberV1("luma", -1f, 1f),
)

internal fun parseFiltersV1(json: String): List<CreatorFilterPresetV36> =
    checkedPresetsV1(json).map { entry ->
        val id = entry.requiredTextV1("id")
        require(remoteIdPatternV1.matches(id)) { "Invalid filter ID" }
        require(entry.requiredTextV1("group") == "LOOKS") { "Only native LOOKS recipes are supported" }
        val corrections = entry.optionalObjectV1("corrections")
        val log = entry.optionalObjectV1("log")
        CreatorFilterPresetV36(
            id = id,
            name = entry.requiredTextV1("name"),
            description = entry.requiredTextV1("description", 120),
            group = CreatorFilterGroupV36.LOOKS,
            corrections = NodeCorrections(
                exposure = corrections.numberV1("exposure", -2f, 2f),
                contrast = corrections.numberV1("contrast", -100f, 100f),
                saturation = corrections.numberV1("saturation", -100f, 100f),
                temperature = corrections.numberV1("temperature", -100f, 100f),
                tint = corrections.numberV1("tint", -100f, 100f),
                highlights = corrections.numberV1("highlights", -100f, 100f),
                shadows = corrections.numberV1("shadows", -100f, 100f),
                hue = corrections.numberV1("hue", -180f, 180f),
                colorBoost = corrections.numberV1("colorBoost", -100f, 100f),
            ),
            log = LogWheels(
                shadows = log.optionalObjectV1("shadows").wheelV1(),
                midtones = log.optionalObjectV1("midtones").wheelV1(),
                highlights = log.optionalObjectV1("highlights").wheelV1(),
                global = log.optionalObjectV1("global").wheelV1(),
            ),
            defaultIntensity = entry.numberV1("defaultIntensity", 0.01f, 1f, 1f),
        )
    }.also { require(it.map(CreatorFilterPresetV36::id).distinct().size == it.size) }
