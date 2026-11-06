package com.gernalix.personalhub.soldi

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.gernalix.personalhub.core.ui.HubTheme

@Suppress("UNUSED_PARAMETER")
@Composable
fun SoldiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),

    content: @Composable () -> Unit,
) {
    HubTheme(darkTheme = darkTheme, content = content)
}
