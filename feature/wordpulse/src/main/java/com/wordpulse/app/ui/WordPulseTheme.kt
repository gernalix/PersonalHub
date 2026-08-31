package com.wordpulse.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4DB6AC),
    secondary = Color(0xFFF2C94C),
    tertiary = Color(0xFF90CAF9),
    background = Color(0xFF101418),
    surface = Color(0xFF141A1F),
    surfaceVariant = Color(0xFF263238),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00796B),
    secondary = Color(0xFF7A5C00),
    tertiary = Color(0xFF1565C0),
    background = Color(0xFFFAFCFC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE0ECEA),
)

@Composable
fun WordPulseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
