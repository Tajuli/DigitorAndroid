package com.tajuli.digitorandroid.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val DigitorAccent = Color(0xFF30E0C3)
val DigitorBackground = Color(0xFF09090B)
val DigitorShell = Color(0xFF08080A)
val DigitorSurface = Color(0xFF101014)
val DigitorRaised = Color(0xFF17171C)
val DigitorDivider = Color(0xFF24242A)

private val DigitorDark = darkColorScheme(
    primary = DigitorAccent,
    onPrimary = Color(0xFF001F1A),
    secondary = Color(0xFF76DCCB),
    background = DigitorBackground,
    surface = DigitorSurface,
    surfaceVariant = DigitorRaised,
    outline = DigitorDivider,
    onBackground = Color(0xFFF4F4F5),
    onSurface = Color(0xFFF4F4F5),
    // Transport icons are explicitly tinted white in the editor; keep the fallback content color
    // pure white as well so Material3 never dims them because of theme inheritance.
    onSurfaceVariant = Color.White,
)

@Composable
fun DigitorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DigitorDark, content = content)
}
