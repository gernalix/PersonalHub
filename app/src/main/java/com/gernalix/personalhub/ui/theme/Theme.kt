package com.gernalix.personalhub.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PersonalHubLightColors = lightColorScheme(
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

private val PersonalHubDarkColors = darkColorScheme(
    primary = Color(0xFF82D1DB),
    onPrimary = Color(0xFF00363D),
    secondary = Color(0xFFD6C65D),
    onSecondary = Color(0xFF373100),
    tertiary = Color(0xFFF0B8C6),
    onTertiary = Color(0xFF48202D),
    background = Color(0xFF111413),
    surface = Color(0xFF111413),
    surfaceContainer = Color(0xFF1D2321),
    onSurface = Color(0xFFE1E3E0),
    onSurfaceVariant = Color(0xFFBEC9C5),
)

@Composable
fun PersonalHubTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) PersonalHubDarkColors else PersonalHubLightColors,
        content = content,
    )
}
