// v343
package com.example.multitimetracker.capsules.auditlog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.multitimetracker.R
import com.example.multitimetracker.capsules.auditlog.controller.AuditLogCapsuleViewModel
import com.example.multitimetracker.persistence.UiPrefsStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun AuditLogCapsuleUi(
    vm: AuditLogCapsuleViewModel,
    onOpenDiagnostics: () -> Unit,
    onOpenDevTools: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val events by vm.events.collectAsState()
    val filterTasks by vm.filterTasks.collectAsState()
    val filterTags by vm.filterTags.collectAsState()
    val filterAlerts by vm.filterAlerts.collectAsState()
    val filterChains by vm.filterChains.collectAsState()
    val filterSystem by vm.filterSystemEvents.collectAsState()
    val filterUndone by vm.filterUndone.collectAsState()
    val undoEnabled by vm.undoEnabled.collectAsState()
    val developerSurfaceEnabled = UiPrefsStore.isDeveloperSurfaceAvailable()

    // NOTE: Filtering happens in the access layer; here we only show the toggles.
    val zoneId = ZoneId.systemDefault()
    val tsFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zoneId)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
    ) {
        // Filter row(s)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 10.dp, bottom = 10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                FilterToggle(
                    label = stringResource(R.string.audit_filter_tasks),
                    checked = filterTasks,
                    onCheckedChange = vm::setFilterTasks
                )
                FilterToggle(
                    label = stringResource(R.string.audit_filter_tags),
                    checked = filterTags,
                    onCheckedChange = vm::setFilterTags
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                FilterToggle(
                    label = stringResource(R.string.audit_filter_alerts),
                    checked = filterAlerts,
                    onCheckedChange = vm::setFilterAlerts
                )
                FilterToggle(
                    label = stringResource(R.string.audit_filter_chains),
                    checked = filterChains,
                    onCheckedChange = vm::setFilterChains
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                FilterToggle(
                    label = stringResource(R.string.audit_filter_system),
                    checked = filterSystem,
                    onCheckedChange = vm::setFilterSystemEvents
                )
                FilterToggle(
                    label = stringResource(R.string.audit_filter_undone),
                    checked = filterUndone,
                    onCheckedChange = vm::setFilterUndone
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                FilterToggle(
                    label = stringResource(R.string.enable_undo),
                    checked = undoEnabled,
                    onCheckedChange = vm::setUndoEnabled
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Keep callbacks: they open existing dialogs/screens.
                    if (developerSurfaceEnabled) {
                        IconButton(onClick = onOpenDiagnostics) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.diagnostics_title))
                        }
                    }
                    if (developerSurfaceEnabled && onOpenDevTools != null) {
                        IconButton(onClick = onOpenDevTools) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.dev_tools_title))
                        }
                    }
                    IconButton(onClick = { vm.clearLog() }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.elimina))
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (events.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.placeholder_dash),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            } else {
                items(events, key = { it.id }) { e ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = e.summary,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${tsFmt.format(Instant.ofEpochMilli(e.tsMs))} • ${e.action}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (e.undoable && undoEnabled && !e.isUndone) {
                                    IconButton(onClick = { vm.undoEvent(e.id) }) {
                                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.undo))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(0.48f),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
