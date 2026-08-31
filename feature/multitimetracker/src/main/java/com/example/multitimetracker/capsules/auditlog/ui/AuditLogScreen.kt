// v351
package com.example.multitimetracker.capsules.auditlog.ui
import androidx.compose.foundation.ExperimentalFoundationApi

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import com.example.multitimetracker.ui.components.SingleSubmitButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.example.multitimetracker.ui.components.AppTopBar
import com.example.multitimetracker.ui.components.InlineHelpAction
import com.example.multitimetracker.ui.components.ScreenEmptyStateCard
import com.example.multitimetracker.ui.components.ScreenHelpAction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.example.multitimetracker.R
import com.example.multitimetracker.model.AuditEventUi
import com.example.multitimetracker.persistence.UiPrefsStore
import com.example.multitimetracker.ui.theme.Dimens
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.compose.runtime.collectAsState
import com.example.multitimetracker.capsules.auditlog.controller.AuditLogCapsuleViewModel
import com.example.multitimetracker.capsules.auditlog.state.AuditLogUiState

/**
 * Audit log screen using the v339 UI, backed by the AuditLog feature capsule.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun AuditLogScreen(
    modifier: Modifier = Modifier,
    capsule: AuditLogCapsuleViewModel,
    state: AuditLogUiState,
    onOpenDiagnostics: (() -> Unit)? = null,
    onOpenDevTools: (() -> Unit)? = null
) {
    val events by capsule.events.collectAsState()
    val filterTasks by capsule.filterTasks.collectAsState()
    val filterTags by capsule.filterTags.collectAsState()
    val filterAlerts by capsule.filterAlerts.collectAsState()
    val filterChains by capsule.filterChains.collectAsState()
    val filterSystemEvents by capsule.filterSystemEvents.collectAsState()
    val filterUndone by capsule.filterUndone.collectAsState()
    val undoEnabled by capsule.undoEnabled.collectAsState()

    AuditLogScreenContent(
        modifier = modifier,
        events = events,
        filterSessions = filterTasks,
        onFilterTasksChange = capsule::setFilterTasks,
        filterTags = filterTags,
        onFilterTagsChange = capsule::setFilterTags,
        filterAlerts = filterAlerts,
        onFilterAlertsChange = capsule::setFilterAlerts,
        filterChains = filterChains,
        onFilterChainsChange = capsule::setFilterChains,
        filterSystemEvents = filterSystemEvents,
        onFilterSystemEventsChange = capsule::setFilterSystemEvents,
        filterUndone = filterUndone,
        onFilterUndoneChange = capsule::setFilterUndone,
        undoEnabled = undoEnabled,
        onUndoEnabledChange = capsule::setUndoEnabled,
        isReadOnly = state.isReadOnly,
        onUndoEvent = capsule::undoEvent,
        onOpenDiagnostics = onOpenDiagnostics,
        onOpenDevTools = onOpenDevTools,
        onClearLog = capsule::clearLog
    )
}


@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun AuditLogScreenContent(
    modifier: Modifier = Modifier,
    events: List<AuditEventUi>,
    filterSessions: Boolean,
    onFilterTasksChange: (Boolean) -> Unit,
    filterTags: Boolean,
    onFilterTagsChange: (Boolean) -> Unit,
    filterAlerts: Boolean,
    onFilterAlertsChange: (Boolean) -> Unit,
    filterChains: Boolean,
    onFilterChainsChange: (Boolean) -> Unit,
    filterSystemEvents: Boolean,
    onFilterSystemEventsChange: (Boolean) -> Unit,
    filterUndone: Boolean,
    onFilterUndoneChange: (Boolean) -> Unit,
    undoEnabled: Boolean,
    onUndoEnabledChange: (Boolean) -> Unit,
    isReadOnly: Boolean,
    onUndoEvent: (Long) -> Unit,
    onOpenDiagnostics: (() -> Unit)?,
    onOpenDevTools: (() -> Unit)? = null,
    onClearLog: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val clearedMsg = stringResource(R.string.log_cleared)
    var prevCount by remember { mutableIntStateOf(events.size) }
    var showClearDialog by remember { mutableStateOf(false) }
    var filterMenuExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val developerSurfaceEnabled = UiPrefsStore.isDeveloperSurfaceAvailable()
    val activeFilterCount = listOf(
        filterSessions,
        filterTags,
        filterAlerts,
        filterChains,
        filterSystemEvents,
        filterUndone
    ).count { it }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.clear_log)) },
            text = { Text(stringResource(R.string.clear_log_confirm_body)) },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.clear_log_cancel))
                }
            },
            confirmButton = {
                SingleSubmitButton(
                    onClick = {
                        showClearDialog = false
                        onClearLog()
                    }
                ) {
                    Text(stringResource(R.string.clear_log_confirm))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.audit_log),
                titleContent = {
                    val titleModifier = if (developerSurfaceEnabled) {
                        Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = {
                                if (isReadOnly) return@combinedClickable
                                val enabled = UiPrefsStore.toggleDevMode(context)
                                Toast.makeText(
                                    context,
                                    context.getString(
                                        if (enabled) R.string.dev_mode_enabled else R.string.dev_mode_disabled
                                    ),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                    } else {
                        Modifier
                    }
                    Text(
                        text = stringResource(R.string.audit_log),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = titleModifier
                    )
                },
                actions = {
                    ScreenHelpAction(
                        title = stringResource(R.string.audit_intro_title),
                        body = stringResource(R.string.audit_intro_body)
                    )
                    IconButton(onClick = { filterMenuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Filled.FilterList,
                            contentDescription = stringResource(R.string.audit_filters)
                        )
                    }
                    if (developerSurfaceEnabled && onOpenDiagnostics != null) {
                        IconButton(onClick = onOpenDiagnostics) {
                            Icon(
                                imageVector = Icons.Filled.BugReport,
                                contentDescription = stringResource(R.string.diagnostics_title)
                            )
                        }
                    }
                    if (developerSurfaceEnabled && UiPrefsStore.isDevModeEnabled(context) && onOpenDevTools != null) {
                        IconButton(onClick = onOpenDevTools) {
                            Icon(
                                imageVector = Icons.Filled.Build,
                                contentDescription = stringResource(R.string.dev_tools_title)
                            )
                        }
                    }
                    IconButton(onClick = { showClearDialog = true }, enabled = !isReadOnly) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.clear_log),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { inner ->
        Column(modifier = modifier.fillMaxSize().padding(inner)) {

            DropdownMenu(
                expanded = filterMenuExpanded,
                onDismissRequest = { filterMenuExpanded = false }
            ) {
                FilterItem(
                    text = stringResource(R.string.audit_filter_tasks),
                    checked = filterSessions,
                    onCheckedChange = onFilterTasksChange
                )
                FilterItem(
                    text = stringResource(R.string.audit_filter_tags),
                    checked = filterTags,
                    onCheckedChange = onFilterTagsChange
                )
                FilterItem(
                    text = stringResource(R.string.audit_filter_alerts),
                    checked = filterAlerts,
                    onCheckedChange = onFilterAlertsChange
                )
                FilterItem(
                    text = stringResource(R.string.audit_filter_chains),
                    checked = filterChains,
                    onCheckedChange = onFilterChainsChange
                )
                com.example.multitimetracker.ui.components.AppDivider()
                FilterItem(
                    text = stringResource(R.string.audit_filter_system),
                    checked = filterSystemEvents,
                    onCheckedChange = onFilterSystemEventsChange
                )
                FilterItem(
                    text = stringResource(R.string.audit_filter_undone),
                    checked = filterUndone,
                    onCheckedChange = onFilterUndoneChange
                )
            }

            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = undoEnabled,
                        onCheckedChange = if (isReadOnly) null else onUndoEnabledChange
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.enable_undo),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            InlineHelpAction(
                                title = stringResource(R.string.enable_undo),
                                body = stringResource(R.string.audit_undo_help)
                            )
                        }
                        Text(
                            text = stringResource(R.string.audit_intro_counts, activeFilterCount, events.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            LaunchedEffect(events.size) {
                if (prevCount > 0 && events.isEmpty()) {
                    snackbarHostState.showSnackbar(clearedMsg)
                }
                prevCount = events.size
            }

            if (events.isEmpty()) {
                ScreenEmptyStateCard(
                    title = stringResource(R.string.audit_empty_title),
                    body = stringResource(R.string.audit_empty_body),
                    modifier = Modifier.padding(12.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(events, key = { it.id }) { ev ->
                        AuditRow(
                            ev = ev,
                            undoEnabled = undoEnabled && !isReadOnly,
                            onUndo = { onUndoEvent(ev.id) }
                        )
                        com.example.multitimetracker.ui.components.AppDivider()
                    }
                    item { Spacer(modifier = Modifier.height(48.dp)) }
                }
            }
        }
    }
}

@Composable
private fun FilterItem(text: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked, onCheckedChange = onCheckedChange)
                Text(text = text, style = MaterialTheme.typography.bodyMedium)
            }
        },
        onClick = { onCheckedChange(!checked) }
    )
}



internal fun isAuditUndoActionSupported(action: String): Boolean {
    return when (action) {
        "TASK_CREATE",
        "TAG_CREATE",
        "TAG_PARENTS_CHANGE",
        "TASK_SESSION_EDIT",
        "TASK_START_NEW",
        "TASK_START_RESUME",
        "SESSION_STOP" -> true
        else -> false
    }
}

private fun category(action: String, isSystemRow: Boolean): String {
    if (isSystemRow) return "system"
    return when {
        action.startsWith("TASK") || action.startsWith("SESSION") -> "tasks" // legacy label: this bucket is "Sessions" in UI
        action.startsWith("TAG") -> "tags"
        action.startsWith("ALERT") || action.startsWith("TIME_FENCE") -> "alerts"
        action.startsWith("CHAIN") -> "chains"
        else -> "system"
    }
}

@Composable
private fun AuditRow(ev: AuditEventUi, undoEnabled: Boolean, onUndo: () -> Unit) {
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val ts = fmt.format(Date(ev.tsMs))
    val textDecoration = if (ev.isUndone) TextDecoration.LineThrough else TextDecoration.None

    val cat = category(ev.action, ev.isSystem)

    val dotColor = when (cat) {
        "tasks" -> MaterialTheme.colorScheme.primary
        "tags" -> MaterialTheme.colorScheme.secondary
        "alerts" -> MaterialTheme.colorScheme.tertiary
        "chains" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding, vertical = 6.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (ev.isUndone) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.RowPaddingH, vertical = Dimens.RowPaddingV),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(10.dp)
                    .height(10.dp)
                    .background(dotColor.copy(alpha = if (ev.isUndone) 0.35f else 0.9f), RoundedCornerShape(10.dp))
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = ev.summary,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = textDecoration
                )
                Text(
                    text = ts + if (ev.isSystem) "  ·  " + stringResource(R.string.system) else "",
                    style = MaterialTheme.typography.bodySmall,
                    textDecoration = textDecoration
                )
            }

            if (undoEnabled && ev.undoable && !ev.isUndone && isAuditUndoActionSupported(ev.action)) {
                IconButton(onClick = onUndo) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.undo))
                }
            }
        }
    }
}
