package com.tajuli.digitorandroid.editor.model

/** Original procedural variants of the reference families; indices are renderer-local only. */
object BodyDecorationCatalog {
    val names = listOf("Mechanical", "Firefly", "Musical Notes", "Butterfly Wings",
        "Shape Trails 2", "Club Hearts", "Neon Motion", "Ghost", "Star Trails", "Halo 2",
        "Whirlpool", "Technology 3", "Hellfire", "Faulty Screen", "Angel Wings", "Starlight",
        "Totem", "Gas Waves", "Electric Shock", "Luminescence", "Optical Scan", "Light Dissolve",
        "Hand-drawn", "Psychedelic Halo", "Target Flicker", "Sparkle Edge", "Rotate Clone",
        "Absorb Clone", "Leave Clone", "Light Trails", "Speed Streaks")
    fun index(name: String) = names.indexOfFirst { it.equals(name,true) }
    fun category(index: Int) = when(index) { in 19..25 -> "Stroke"; in 26..29 -> "Clone"; else -> "Glowing Lines" }
}
