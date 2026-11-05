package com.supercontacts.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.gernalix.personalhub.core.ui.HubTheme

@Suppress("UNUSED_PARAMETER")
@Composable
fun SuperContactsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    HubTheme(darkTheme = darkTheme, content = content)
}
