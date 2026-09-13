package com.gernalix.personalhub.soldi

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val SoldiLightColors = lightColorScheme(
    primary = Color(0xFF176B3A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC0F1CE),
    onPrimaryContainer = Color(0xFF05210F),
    secondary = Color(0xFF2E6750),
    background = Color(0xFFF7FAF7),
    surface = Color(0xFFF7FAF7),
    surfaceVariant = Color(0xFFE2E9E3),
    error = Color(0xFFBA1A1A),
)

private val SoldiDarkColors = darkColorScheme(
    primary = Color(0xFF55DB82),
    onPrimary = Color(0xFF00391A),
    primaryContainer = Color(0xFF0B512A),
    onPrimaryContainer = Color(0xFFB8F3C9),
    secondary = Color(0xFF9BD5B3),
    background = Color(0xFF0D1110),
    surface = Color(0xFF0D1110),
    surfaceVariant = Color(0xFF1B211E),
    onSurfaceVariant = Color(0xFFC0C9C2),
    error = Color(0xFFFF6B6B),
)

@Composable
internal fun SoldiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) SoldiDarkColors else SoldiLightColors,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(6.dp),
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(14.dp),
            large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}
