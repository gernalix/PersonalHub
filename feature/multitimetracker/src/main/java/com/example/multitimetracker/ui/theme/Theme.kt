package com.example.multitimetracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.gernalix.personalhub.core.ui.HubTheme

@Suppress("UNUSED_PARAMETER")
@Composable
fun MultiTimeTrackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    useDynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    HubTheme(darkTheme = darkTheme, content = content)
}
