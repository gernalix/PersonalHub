package com.gernalix.personalhub

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gernalix.personalhub.core.hubcontext.*
import kotlinx.coroutines.launch
import java.time.Instant

@Composable
fun HubTemporalSearchScreen(onBack: () -> Unit) {
    val providers = remember { HubContextRuntime.temporalProviders() }
    var selected by rememberSaveable { mutableStateOf(providers.map { it.moduleId }.toSet()) }
    var fromText by rememberSaveable { mutableStateOf((System.currentTimeMillis() - 24 * 60 * 60 * 1000L).toString()) }
    var toText by rememberSaveable { mutableStateOf((System.currentTimeMillis() + 1).toString()) }
    var records by remember { mutableStateOf<List<HubTemporalRecord>>(emptyList()) }
    var cursors by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun search(append: Boolean) {
        val from = fromText.toLong()
        val to = toText.toLong()
        val pages = providers.filter { it.moduleId in selected }.map { provider ->
            provider.moduleId to provider.queryTemporal(HubTemporalQuery(from, to, 50, if (append) cursors[provider.moduleId] else null))
        }
        cursors = pages.associate { it.first to it.second.nextCursor }
        val incoming = mergeTemporalSlices(pages.map { it.second.records }, from, to, selected)
        records = if (append) mergeTemporalSlices(listOf(records, incoming), from, to, selected).distinctBy { Triple(it.moduleId, it.source, it.stableId) } else incoming
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.temporal_back)) }
            Text(stringResource(R.string.temporal_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(64.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(fromText, { fromText = it.filter(Char::isDigit) }, Modifier.weight(1f).testTag("temporal-from"), label = { Text(stringResource(R.string.temporal_from)) })
            OutlinedTextField(toText, { toText = it.filter(Char::isDigit) }, Modifier.weight(1f).testTag("temporal-to"), label = { Text(stringResource(R.string.temporal_to)) })
        }
        runCatching { Text("${Instant.ofEpochMilli(fromText.toLong())} — ${Instant.ofEpochMilli(toText.toLong())}", style = MaterialTheme.typography.bodySmall) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { providers.distinctBy { it.moduleId }.forEach { provider ->
            FilterChip(provider.moduleId in selected, { selected = if (provider.moduleId in selected) selected - provider.moduleId else selected + provider.moduleId }, label = { Text(provider.moduleId.replaceFirstChar { it.uppercase() }) })
        } }
        Button(onClick = { scope.launch { runCatching { search(false) }.onFailure { error = it.message } } }, modifier = Modifier.testTag("temporal-search")) { Text(stringResource(R.string.temporal_search)) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(records, key = { "${it.moduleId}/${it.source}/${it.stableId}" }) { record ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text(record.title, style = MaterialTheme.typography.titleMedium)
                    Text("${record.moduleId} · ${Instant.ofEpochMilli(record.startMs)}", style = MaterialTheme.typography.bodySmall)
                    record.subtitle?.let { Text(it) }
                } }
            }
            if (cursors.values.any { it != null }) item { TextButton(onClick = { scope.launch { runCatching { search(true) }.onFailure { error = it.message } } }) { Text(stringResource(R.string.temporal_more)) } }
        }
    }
}
