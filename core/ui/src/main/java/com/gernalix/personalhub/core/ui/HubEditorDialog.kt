package com.gernalix.personalhub.core.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/** One owner for status/navigation bars and IME insets; the platform must not pan the form. */
@Composable
fun HubFullscreenDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = light
                    isAppearanceLightNavigationBars = light
                }
            }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), propagateMinConstraints = true) {
                content()
            }
        }
    }
}

/** A bounded, scrollable form viewport and actions that stay reachable with the IME open. */
@Composable
fun HubEditorDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable () -> Unit = {},
) {
    HubFullscreenDialog(onDismissRequest = onDismissRequest) {
        BoxWithConstraints(modifier.fillMaxSize()) {
            val compact = maxHeight < 320.dp
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = if (compact) 4.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 12.dp)) {
                if (compact) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { ProvideTextStyle(MaterialTheme.typography.titleMedium) { title() } }
                        dismissButton()
                        Spacer(Modifier.width(8.dp))
                        confirmButton()
                    }
                } else {
                    ProvideTextStyle(MaterialTheme.typography.titleLarge) { title() }
                    HorizontalDivider()
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { text() }
                if (!compact) {
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        dismissButton()
                        Spacer(Modifier.width(12.dp))
                        confirmButton()
                    }
                }
            }
        }
    }
}
