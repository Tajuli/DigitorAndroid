package com.tajuli.digitorandroid.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DigitorDark = darkColorScheme(
    primary = Color(0xFF9CC8FF),
    secondary = Color(0xFFBBC7DB),
    background = Color(0xFF0C0D10),
    surface = Color(0xFF15171C),
    surfaceVariant = Color(0xFF20232A),
    onBackground = Color(0xFFF1F3F7),
    onSurface = Color(0xFFF1F3F7),
)

@Composable
fun DigitorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DigitorDark, content = content)
}
