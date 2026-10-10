package com.gernalix.personalhub

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.contracts.database.HubOpenTarget
import com.gernalix.personalhub.contracts.database.SinceWhenCounterEntity
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.hubcontext.HubContextRuntime
import com.example.multitimetracker.api.TimerStartupApi
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SinceWhenScreen(onBack: () -> Unit, initialCounterId: Long? = null) {
    val context = LocalContext.current
    val dao = remember { PersonalHubDatabase.get(context).sinceWhenCounterDao() }
    val counters by dao.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<SinceWhenCounterEntity?>(null) }
    var initialEditorOpened by remember(initialCounterId) { mutableStateOf(false) }
    var linkedCounterId by remember { mutableStateOf<Long?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var migrationReady by remember { mutableStateOf(false) }
    var migrationFailed by remember { mutableStateOf(false) }
    var sourceLabels by remember { mutableStateOf(emptyMap<Long, Pair<String, HubOpenTarget>>()) }
    LaunchedEffect(context) {
        try {
            TimerStartupApi.ensureLegacySinceWhenMigrated(context)
            migrationReady = true
        } catch (_: Exception) {
            migrationFailed = true
        }
    }
    LaunchedEffect(initialCounterId, counters) {
        val initial = initialCounterId?.let { id -> counters.firstOrNull { it.id == id } }
        if (initial != null && !initialEditorOpened) {
            initialEditorOpened = true
            editing = initial
            showCreate = true
        }
    }
    LaunchedEffect(counters) {
        val resolved = mutableMapOf<Long, Pair<String, HubOpenTarget>>()
        for (counter in counters) {
            val moduleKind = counter.sourceEntityType?.split('/')
            val sourceId = counter.sourceEntityId
            if (moduleKind?.size != 2 || sourceId == null) continue
            val ref = HubEntityRef(moduleKind[0], moduleKind[1], sourceId)
            try {
                val summary = HubContextRuntime.adapter(ref.moduleId, ref.entityKind).summaries(setOf(sourceId))[sourceId]
                    ?: continue
                val target = HubContextRuntime.adapter(ref.moduleId, ref.entityKind).openTarget(sourceId)
                    ?: continue
                resolved[counter.id] = summary.label to target
            } catch (_: Exception) {
                // A deleted or unavailable source has no tappable provenance row.
            }
        }
        sourceLabels = resolved.toMap()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.since_when_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.since_when_back)) } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(modifier = Modifier.testTag("sincewhen-create"), onClick = { if (migrationReady) { editing = null; showCreate = true } }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.since_when_create))
            }
        },
    ) { padding ->
        if (migrationFailed) {
            Text(stringResource(R.string.since_when_migration_error), modifier = Modifier.padding(padding).padding(20.dp))
        } else if (!migrationReady) {
            androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.padding(padding).padding(20.dp))
        } else if (counters.isEmpty()) {
            Text(stringResource(R.string.since_when_empty), modifier = Modifier.padding(padding).padding(20.dp))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(counters, key = SinceWhenCounterEntity::id) { counter ->
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row {
                                Column(Modifier.weight(1f)) {
                                    Text(counter.title, style = MaterialTheme.typography.titleMedium)
                                    Text(formatSinceWhenDate(counter.initialTimestamp), style = MaterialTheme.typography.bodyMedium)
                                }
                                IconButton(modifier = Modifier.testTag("sincewhen-edit-${counter.id}"), onClick = { editing = counter; showCreate = true }) {
                                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.since_when_edit_action))
                                }
                                IconButton(onClick = { scope.launch { dao.delete(counter) } }) {
                                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.since_when_delete))
                                }
                            }
                            counter.description.takeIf(String::isNotBlank)?.let { Text(it) }
                            TextButton(onClick = { linkedCounterId = if (linkedCounterId == counter.id) null else counter.id }) {
                                Text(stringResource(com.gernalix.personalhub.core.hubcontext.R.string.hub_context_links_title))
                            }
                            if (linkedCounterId == counter.id) {
                                com.gernalix.personalhub.core.hubcontext.HubContextLinks(
                                    HubEntityRef("since_when", "counter", counter.id.toString()),
                                )
                            }
                            sourceLabels[counter.id]?.let { (label, target) ->
                                Text(
                                    "↳ ${stringResource(sourceTypeRes(counter.sourceEntityType))}: $label",
                                    modifier = Modifier.clickable {
                                        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(target.uri))
                                        target.activityClassName?.let { intent.setClassName(context.packageName, it) }
                                        context.startActivity(intent)
                                    },
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (showCreate) {
        com.gernalix.personalhub.sincewhen.SinceWhenEditorDialog(
            initial = editing,
            onDismiss = { showCreate = false },
            onSaved = { showCreate = false },
        )
    }
}

private fun formatSinceWhenDate(timestamp: Long): String = com.gernalix.personalhub.sincewhen.formatSinceWhenTimestamp(timestamp)

private fun sourceTypeRes(entityType: String?): Int = when (entityType) {
    "timer/quick_event_entry" -> R.string.since_when_source_event
    "timer/session" -> R.string.since_when_source_session
    "soldi/transaction" -> R.string.since_when_source_transaction
    "places/place" -> R.string.since_when_source_place
    "substances/substance" -> R.string.since_when_source_substance
    "tags/tag" -> R.string.since_when_source_tag
    else -> R.string.since_when_source_personalhub
}
