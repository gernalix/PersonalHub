package com.gernalix.personalhub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gernalix.personalhub.contracts.database.HubTimestamp
import com.gernalix.personalhub.core.database.capsules.gitdata.GitDataSettings
import com.gernalix.personalhub.core.database.capsules.gitdata.GitHistory
import com.gernalix.personalhub.core.database.capsules.gitdata.GitHistoryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HubAuditLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val gitEnabled = remember(context) {
        runCatching { GitDataSettings.configuration(context).enabled }.getOrDefault(false)
    }
    if (!gitEnabled) {
        HubHistorySearchScreen(onBack = onBack)
        return
    }

    var query by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<GitHistoryItem>>(emptyList()) }
    LaunchedEffect(Unit) {
        entries = withContext(Dispatchers.IO) {
            GitHistory.recent(context.applicationContext, limit = 1000)
                .sortedWith(compareByDescending<GitHistoryItem> { it.occurredAt }.thenByDescending { it.id })
        }
    }
    val needle = query.trim().lowercase()
    val visible = remember(entries, needle) {
        if (needle.isBlank()) entries else entries.filter { item ->
            listOf(item.table, item.operation, item.rowKey, item.changedColumns, item.author, item.source, item.reason.orEmpty())
                .any { it.lowercase().contains(needle) }
        }
    }

    Column(
        Modifier.fillMaxSize().windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.settings_back)) }
            Text(stringResource(R.string.audit_title), style = MaterialTheme.typography.headlineMedium)
        }
        Text(stringResource(R.string.audit_git_source), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.audit_search_hint)) },
            singleLine = true,
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(visible, key = { it.id }) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${item.operation} · ${item.table}", style = MaterialTheme.typography.titleSmall)
                        Text(HubTimestamp.format(item.occurredAt), style = MaterialTheme.typography.bodySmall)
                        Text("row ${item.rowKey}", style = MaterialTheme.typography.bodySmall)
                        if (item.changedColumns.isNotBlank()) {
                            Text(item.changedColumns, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("${item.author} · ${item.source}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
