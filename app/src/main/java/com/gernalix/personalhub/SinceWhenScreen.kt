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
import com.gernalix.personalhub.contracts.database.CounterModes
import com.gernalix.personalhub.sincewhen.CounterElapsed
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import org.json.JSONArray
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.hubcontext.HubContextRuntime
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SinceWhenScreen(onBack: () -> Unit, initialCounterId: Long? = null, mode: String = CounterModes.SINCE_WHEN) {
    val context = LocalContext.current
    val dao = remember { PersonalHubDatabase.get(context).sinceWhenCounterDao() }
    val allCounters by dao.observeAll().collectAsState(initial = emptyList())
    val counters = remember(allCounters, mode) { allCounters.filter { it.mode == mode } }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(mode, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { now = System.currentTimeMillis(); delay(1_000) }
        }
    }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<SinceWhenCounterEntity?>(null) }
    var initialEditorOpened by remember(initialCounterId) { mutableStateOf(false) }
    var linkedCounterId by remember { mutableStateOf<Long?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var sourceLabels by remember { mutableStateOf(emptyMap<Long, Pair<String, HubOpenTarget>>()) }
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
                title = { Text(stringResource(if (mode == CounterModes.COUNTDOWN) com.gernalix.personalhub.core.ui.R.string.countdown_title else R.string.since_when_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.since_when_back)) } },
                actions = { TextButton(modifier = Modifier.testTag("sincewhen-history"), onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW,
                        com.gernalix.personalhub.contracts.database.HubDeepLinkContract.moduleHistoryUri(CounterModes.module(mode))).setPackage(context.packageName))
                }) { Text(stringResource(R.string.activity_title)) } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(modifier = Modifier.testTag("sincewhen-create"), onClick = { editing = null; showCreate = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.since_when_create))
            }
        },
    ) { padding ->
        if (counters.isEmpty()) {
            Text(stringResource(if (mode == CounterModes.COUNTDOWN) com.gernalix.personalhub.core.ui.R.string.countdown_empty else R.string.since_when_empty), modifier = Modifier.padding(padding).padding(20.dp))
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
                                    Text(counterDurationText(counter, now), style = MaterialTheme.typography.headlineSmall,
                                        color = androidx.compose.ui.graphics.Color(counter.colorArgb))
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
                                    HubEntityRef("since_when", "counter", counter.canonicalId),
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
            mode = mode,
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

@Composable
private fun counterDurationText(counter: SinceWhenCounterEntity, now: Long): String {
    val array = remember(counter.displayUnitsJson) { JSONArray(counter.displayUnitsJson) }
    val selected = remember(array) { (0 until array.length()).map { array.getString(it) }.toSet() }
    if (counter.mode == CounterModes.SINCE_WHEN && now < counter.initialTimestamp) {
        return stringResource(com.gernalix.personalhub.core.ui.R.string.counter_not_started)
    }
    val reference = counter.endTimestamp?.let { minOf(now, it) } ?: now
    val duration = CounterElapsed.between(counter.initialTimestamp, reference, selected, ZoneId.systemDefault())
    val resources = LocalContext.current.resources
    val text = duration.parts.joinToString(" ") { (unit, amount) ->
        val plural = when (unit) {
            ChronoUnit.YEARS -> com.gernalix.personalhub.core.ui.R.plurals.counter_years
            ChronoUnit.MONTHS -> com.gernalix.personalhub.core.ui.R.plurals.counter_months
            ChronoUnit.WEEKS -> com.gernalix.personalhub.core.ui.R.plurals.counter_weeks
            ChronoUnit.DAYS -> com.gernalix.personalhub.core.ui.R.plurals.counter_days
            ChronoUnit.HOURS -> com.gernalix.personalhub.core.ui.R.plurals.counter_hours
            ChronoUnit.MINUTES -> com.gernalix.personalhub.core.ui.R.plurals.counter_minutes
            else -> com.gernalix.personalhub.core.ui.R.plurals.counter_seconds
        }
        resources.getQuantityString(plural, amount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), amount)
    }
    return (if (duration.negative) "−" else "") + text
}
