package com.gernalix.personalhub.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PersonalHubColors = lightColorScheme(
    primary = Color(0xFF146C75),
    onPrimary = Color.White,
    secondary = Color(0xFF675C00),
    onSecondary = Color.White,
    tertiary = Color(0xFF7A3E4F),
    background = Color(0xFFFAFBF8),
    surface = Color(0xFFFAFBF8),
    surfaceContainer = Color(0xFFEFF4F1),
    onSurface = Color(0xFF1A1C1B),
    onSurfaceVariant = Color(0xFF4B5552),
)

@Composable
fun PersonalHubTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PersonalHubColors,
        content = content,
    )
}
