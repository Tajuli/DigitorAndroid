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
    onPrimary = Color.White,
    primaryContainer = Color(0xFF163E38),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF76DCCB),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF24443F),
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFFB7A7FF),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF393153),
    onTertiaryContainer = Color.White,
    background = DigitorBackground,
    onBackground = Color.White,
    surface = DigitorSurface,
    onSurface = Color.White,
    surfaceVariant = DigitorRaised,
    onSurfaceVariant = Color.White,
    outline = DigitorDivider,
    error = Color(0xFFFF7474),
    onError = Color.White,
    errorContainer = Color(0xFF5A2020),
    onErrorContainer = Color.White,
    inverseOnSurface = Color.White,
)

@Composable
fun DigitorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DigitorDark, content = content)
}
