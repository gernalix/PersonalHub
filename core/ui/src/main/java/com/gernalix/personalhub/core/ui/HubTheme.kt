package com.gernalix.personalhub.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val Light = lightColorScheme(
    primary = Color(0xFF146C75), onPrimary = Color.White,
    primaryContainer = Color(0xFFD1F0F0), onPrimaryContainer = Color(0xFF002F34),
    secondary = Color(0xFF52656A), secondaryContainer = Color(0xFFD5E8ED),
    tertiary = Color(0xFF635B83), tertiaryContainer = Color(0xFFE9DDFF),
    background = Color(0xFFF8FAFA), onBackground = Color(0xFF182224),
    surface = Color(0xFFF8FAFA), onSurface = Color(0xFF182224),
    surfaceContainer = Color(0xFFEDF2F3), surfaceContainerLow = Color(0xFFF2F6F6),
    surfaceContainerHigh = Color(0xFFE6EDEF), surfaceContainerHighest = Color(0xFFE1E9EB),
    surfaceContainerLowest = Color.White, surfaceVariant = Color(0xFFE1E9EB),
    onSurfaceVariant = Color(0xFF48585B), outline = Color(0xFF718184), outlineVariant = Color(0xFFC0CED1),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF82D1DB), onPrimary = Color(0xFF00363D),
    primaryContainer = Color(0xFF004F57), onPrimaryContainer = Color(0xFFA4EFF5),
    secondary = Color(0xFFB5CAD0), secondaryContainer = Color(0xFF354A50),
    tertiary = Color(0xFFCBC0EA), tertiaryContainer = Color(0xFF4B4369),
    background = Color(0xFF101719), onBackground = Color(0xFFE0E9EA),
    surface = Color(0xFF101719), onSurface = Color(0xFFE0E9EA),
    surfaceContainer = Color(0xFF1D272A), surfaceContainerLow = Color(0xFF182124),
    surfaceContainerHigh = Color(0xFF273235), surfaceContainerHighest = Color(0xFF323D40),
    surfaceContainerLowest = Color(0xFF0B1214), surfaceVariant = Color(0xFF323D40),
    onSurfaceVariant = Color(0xFFBDCDD0), outline = Color(0xFF889B9F), outlineVariant = Color(0xFF405155),
)

@Composable
fun HubTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) Dark else Light,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().imePadding()) { content() }
        }
    }
}
