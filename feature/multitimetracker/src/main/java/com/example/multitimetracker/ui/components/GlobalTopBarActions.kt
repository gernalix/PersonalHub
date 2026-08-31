// v375
package com.example.multitimetracker.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Global top-bar actions provided by [AppRoot].
 *
 * Why this exists:
 * - AppRoot owns the navigation drawer.
 * - We don't want to thread `onOpenAppMenu` through every single screen call.
 */
val LocalOpenAppMenu = staticCompositionLocalOf<(() -> Unit)?> { null }
