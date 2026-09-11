// v352
package com.example.multitimetracker.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    // v352: main accent must be purple (not teal).
    primary = Tertiary,
    onPrimary = OnTertiary,
    primaryContainer = TertiaryContainer,
    onPrimaryContainer = OnTertiaryContainer,
    secondary = BlueSecondary,
    onSecondary = BlueOnSecondary,
    secondaryContainer = BlueSecondaryContainer,
    onSecondaryContainer = BlueOnSecondaryContainer,
    tertiary = TealPrimary,
    onTertiary = TealOnPrimary,
    tertiaryContainer = TealPrimaryContainer,
    onTertiaryContainer = TealOnPrimaryContainer,
)

private val DarkColors = darkColorScheme(
    primary = TertiaryDark,
    onPrimary = OnTertiaryDark,
    primaryContainer = TertiaryContainerDark,
    onPrimaryContainer = OnTertiaryContainerDark,
    secondary = BlueSecondaryDark,
    onSecondary = BlueOnSecondaryDark,
    secondaryContainer = BlueSecondaryContainerDark,
    onSecondaryContainer = BlueOnSecondaryContainerDark,
    tertiary = TealPrimaryDark,
    onTertiary = TealOnPrimaryDark,
    tertiaryContainer = TealPrimaryContainerDark,
    onTertiaryContainer = TealOnPrimaryContainerDark,
)

@Composable
fun MultiTimeTrackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    useDynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = MttShapes,
    ) {
        // Ensure a consistent background on every screen.
        Surface(color = MaterialTheme.colorScheme.background) {
            content()
        }
    }
}
