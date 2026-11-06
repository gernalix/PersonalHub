package com.gernalix.personalhub.core.ui

import android.content.Context
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource

data class HubModuleDestination(val id: String, val labelRes: Int, val intent: (Context) -> Intent)

object ModuleNavigationUi {
    var destinations: List<HubModuleDestination> = emptyList()
}

/** The app supplies its one shared History screen; features depend only on this UI boundary. */
object ModuleHistoryUi {
    var screen: (@Composable (String, () -> Unit) -> Unit)? = null
}

@Composable
fun ModuleHistoryHost(
    moduleId: String,
    navigationKey: Any? = null,
    content: @Composable () -> Unit,
) {
    var historyOpen by rememberSaveable(moduleId, navigationKey) { mutableStateOf(false) }
    val savedState = rememberSaveableStateHolder()
    BackHandler(enabled = historyOpen) { historyOpen = false }
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val destinations = ModuleNavigationUi.destinations
    val current = destinations.firstOrNull { it.id == moduleId }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                        context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    }
                }, modifier = Modifier.testTag("module-home")) {
                    Icon(Icons.Filled.Home, contentDescription = "PersonalHub")
                }
                Box(Modifier.weight(1f)) {
                    TextButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("module-switcher")) {
                        Text(current?.let { stringResource(it.labelRes) } ?: stringResource(R.string.module_content_tab),
                            maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                        Icon(Icons.Filled.ExpandMore, contentDescription = null)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        destinations.forEach { destination ->
                            DropdownMenuItem(text = { Text(stringResource(destination.labelRes)) }, onClick = {
                                menuOpen = false
                                if (destination.id == moduleId) historyOpen = false
                                else context.startActivity(destination.intent(context).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                            })
                        }
                    }
                }
                TextButton(onClick = { historyOpen = false }, modifier = Modifier.testTag("module-content-tab")) {
                    Text(stringResource(R.string.module_content_tab))
                }
                TextButton(onClick = { historyOpen = true }, modifier = Modifier.testTag("module-history-tab")) {
                    Icon(Icons.Filled.History, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.module_history_tab))
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (historyOpen) savedState.SaveableStateProvider("history:$moduleId") {
                val screen = ModuleHistoryUi.screen
                if (screen == null) Text(stringResource(R.string.module_history_unavailable))
                else screen(moduleId) { historyOpen = false }
            } else savedState.SaveableStateProvider("content:$moduleId") { content() }
        }
    }
}
