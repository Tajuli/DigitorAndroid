package com.tajuli.digitorandroid.editor.assets

import com.google.gson.JsonParser

/** Matches the editor's actual tab/category names; all other GitHub paths are ignored. */
object RemoteAssetFoldersV2 {
    val effects = listOf("Video Effect", "Image Effect", "Basic", "Glitch", "Retro",
        "Lens", "Motion", "Body", "Eyes", "Funny Faces", "Glowing Lines", "Stroke", "Clone")
    val filters = listOf("Looks", "Beauty")
    val transitions = listOf("Basic", "Camera", "Effect")

    fun folders(kind: String): List<String> = when (kind) {
        "effects" -> effects
        "filters" -> filters
        "transitions" -> transitions
        else -> emptyList()
    }

    fun key(kind: String, folder: String): String {
        require(folder in folders(kind)) { "Unknown remote preset category" }
        return "$kind/$folder"
    }

    /** One small public Git tree request discovers new/updated folders without downloading assets. */
    fun discover(treeJson: String): Map<String, String> {
        require(treeJson.toByteArray(Charsets.UTF_8).size <= 512 * 1024)
        val root = JsonParser.parseString(treeJson).asJsonObject
        require(root.get("truncated")?.asBoolean != true) { "GitHub tree is incomplete" }
        val tree = requireNotNull(root.getAsJsonArray("tree"))
        require(tree.size() <= 2000)
        val found = linkedMapOf<String, String>()
        tree.forEach { entry ->
            val node = entry.asJsonObject
            if (node.get("type")?.asString != "blob") return@forEach
            val path = node.get("path")?.asString ?: return@forEach
            val split = path.split("/")
            if (split.size != 3 || split[2] != "presets.json") return@forEach
            val kind = split[0]
            val folder = split[1]
            if (folder !in folders(kind)) return@forEach
            val sha = node.get("sha")?.asString ?: return@forEach
            require(sha.matches(Regex("[0-9a-f]{40}")))
            found[key(kind, folder)] = sha
        }
        return found
    }
}
