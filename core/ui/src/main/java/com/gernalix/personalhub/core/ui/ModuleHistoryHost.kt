package com.gernalix.personalhub.core.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource

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
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        PrimaryTabRow(selectedTabIndex = if (historyOpen) 1 else 0) {
            Tab(selected = !historyOpen, onClick = { historyOpen = false },
                modifier = Modifier.testTag("module-content-tab"),
                text = { Text(stringResource(R.string.module_content_tab)) })
            Tab(selected = historyOpen, onClick = { historyOpen = true },
                modifier = Modifier.testTag("module-history-tab"),
                text = { Text(stringResource(R.string.module_history_tab)) })
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
