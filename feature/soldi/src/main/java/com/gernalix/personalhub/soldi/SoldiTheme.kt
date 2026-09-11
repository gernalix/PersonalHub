package com.gernalix.personalhub.soldi

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val SoldiLightColors = lightColorScheme()
private val SoldiDarkColors = darkColorScheme()

@Composable
internal fun SoldiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) SoldiDarkColors else SoldiLightColors,
        content = content,
    )
}
