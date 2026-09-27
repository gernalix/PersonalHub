package com.example.multitimetracker.capsules.alerts.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.multitimetracker.R
import com.example.multitimetracker.capsules.alerts.core.RandomAlertWindow
import com.example.multitimetracker.capsules.alerts.state.AlertsUiState
import com.example.multitimetracker.capsules.system.ui.ConfirmEmptyTrashDialog
import com.example.multitimetracker.capsules.system.ui.TrashListDialog
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.TimeFenceDelivery
import com.example.multitimetracker.model.TimeFenceMatchMode
import com.example.multitimetracker.model.TimeFenceRule
import com.example.multitimetracker.model.TimeFenceScope
import com.example.multitimetracker.model.TimeFenceTrigger
import com.example.multitimetracker.ui.util.TagSelectionOrder
import com.example.multitimetracker.ui.util.formatDuration
import com.gernalix.personalhub.alerts.AlertRuleEntity
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.core.alerts.AlertText
import com.gernalix.personalhub.core.alerts.AlertDomain
import com.gernalix.personalhub.core.alerts.AlertMatchMode
import com.gernalix.personalhub.core.alerts.AlertScope
import com.gernalix.personalhub.core.alerts.AlertTargetKind
import com.gernalix.personalhub.core.alerts.AlertTrigger
import com.gernalix.personalhub.core.alerts.PlaceAlertDraft
import com.gernalix.personalhub.core.alerts.UnifiedAlertsRoute
import com.gernalix.personalhub.core.alerts.UnifiedPlaceAlertsProvider
import com.gernalix.personalhub.core.alerts.UnifiedPlaceAlertsSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface EditingAlert {
    data class Timer(val rule: TimeFenceRule?) : EditingAlert
    data class Places(val rule: AlertRuleEntity?) : EditingAlert
}

@Composable
fun AlertsScreen(
    modifier: Modifier = Modifier,
    state: AlertsUiState,
    initialFilter: AlertDomain? = AlertDomain.TIMER,
    initialPlaceId: String? = null,
    onAddTimeFenceRule: (String, TimeFenceTrigger, TimeFenceScope, TimeFenceMatchMode, Set<Long>, Long, TimeFenceDelivery, Boolean, Int, RandomAlertWindow, Boolean) -> Boolean,
    onUpdateTimeFenceRule: (Long, String, TimeFenceTrigger, TimeFenceScope, TimeFenceMatchMode, Set<Long>, Long, TimeFenceDelivery, Boolean, Int, RandomAlertWindow) -> Boolean,
    onDeleteTimeFenceRule: (Long) -> Unit,
    onRestoreTimeFenceRule: (Long) -> Unit,
    onPurgeTimeFenceRule: (Long) -> Unit,
    onPurgeAllDeletedTimeFenceRules: () -> Unit,
    onSetTimeFenceRuleEnabled: (Long, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val provider = remember(context) { UnifiedPlaceAlertsProvider(context) }
    val scope = rememberCoroutineScope()
    var places by remember { mutableStateOf(UnifiedPlaceAlertsSnapshot()) }
    var filter by remember { mutableStateOf(initialFilter) }
    var editing by remember { mutableStateOf<EditingAlert?>(null) }
    var showDomainPicker by remember { mutableStateOf(false) }
    var showTrash by remember { mutableStateOf(false) }
    var trashQuery by rememberSaveable { mutableStateOf("") }
    var confirmEmptyTrash by remember { mutableStateOf(false) }

    suspend fun refreshPlaces() {
        places = withContext(Dispatchers.IO) { provider.snapshot() }
    }
    LaunchedEffect(initialFilter, initialPlaceId) {
        filter = initialFilter
        refreshPlaces()
    }

    val timerRules = state.timeFenceRules.filterNot { it.isDeleted }
    val placeRules = places.rules
    Scaffold(
        modifier = modifier,
        topBar = {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(alertText(R.string.alerts_unified_title, R.string.alerts_unified_title_it), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = { showTrash = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.show_trash))
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                when (filter) {
                    null -> showDomainPicker = true
                    AlertDomain.TIMER -> editing = EditingAlert.Timer(null)
                    AlertDomain.PLACE -> editing = EditingAlert.Places(null)
                }
            }, modifier = Modifier.testTag("alerts-add")) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.aggiungi))
            }
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf<AlertDomain?>(null) + AlertDomain.entries).forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        label = { Text(filterLabel(option)) },
                        modifier = Modifier.testTag("alerts-filter-" + (option?.moduleId ?: "all")),
                    )
                }
            }
            if (when (filter) {
                    null -> timerRules.isEmpty() && placeRules.isEmpty()
                    AlertDomain.TIMER -> timerRules.isEmpty()
                    AlertDomain.PLACE -> placeRules.isEmpty()
                }) {
                Text(stringResource(R.string.alerts_empty_title), Modifier.padding(16.dp))
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (filter != AlertDomain.PLACE) {
                    items(timerRules, key = { "timer-${it.id}" }) { rule ->
                        val tagNames = rule.tagIds.mapNotNull { id -> state.tags.firstOrNull { it.id == id }?.name }
                        UnifiedAlertCard(
                            entityRef = HubEntityRef("timer", "alert", rule.id.toString()),
                            domain = alertText(R.string.alerts_domain_timer, R.string.alerts_domain_timer_it),
                            message = rule.message,
                            details = listOf(
                                if (rule.trigger == TimeFenceTrigger.ON_START) stringResource(R.string.alert_trigger_start) else stringResource(R.string.alert_trigger_end),
                                tagNames.joinToString(", ").ifBlank { stringResource(R.string.alert_tags_prefix) },
                                if (rule.matchMode == TimeFenceMatchMode.AND) stringResource(R.string.alert_match_all) else stringResource(R.string.alert_match_any),
                                if (rule.scope == TimeFenceScope.ONE_TIME) stringResource(R.string.alert_scope_one_time) else stringResource(R.string.alert_scope_always),
                            ).joinToString(" · "),
                            enabled = rule.isEnabled,
                            onEdit = { editing = EditingAlert.Timer(rule) },
                            onDelete = { onDeleteTimeFenceRule(rule.id) },
                            onSetEnabled = { onSetTimeFenceRuleEnabled(rule.id, it) },
                        )
                    }
                }
                if (filter != AlertDomain.TIMER) {
                    items(placeRules, key = { "places-${it.id}" }) { rule ->
                        val target = if (rule.targetKind == AlertTargetKind.ENTITY.name) {
                            places.places.firstOrNull { it.uuid == rule.entityId }?.let { it.nickname.ifBlank { it.address.orEmpty() } }
                                ?: alertText(R.string.alerts_missing_place, R.string.alerts_missing_place_it)
                        } else {
                            places.targets[rule.id].orEmpty().mapNotNull { id -> places.tags.firstOrNull { it.id == id }?.name }
                                .joinToString(", ").ifBlank { alertText(R.string.alerts_places_tags, R.string.alerts_places_tags_it) }
                        }
                        UnifiedAlertCard(
                            entityRef = HubEntityRef("places", "alert", rule.id),
                            domain = alertText(R.string.alerts_domain_places, R.string.alerts_domain_places_it),
                            message = rule.message,
                            details = listOf(
                                placeTriggerLabel(rule.trigger), target,
                                if (rule.targetKind == AlertTargetKind.ENTITY.name) alertText(R.string.alerts_specific_place, R.string.alerts_specific_place_it)
                                else if (rule.matchMode == AlertMatchMode.ANY.name) stringResource(R.string.alert_match_any)
                                else stringResource(R.string.alert_match_all),
                                if (rule.scope == AlertScope.ONE_TIME.name) stringResource(R.string.alert_scope_one_time) else stringResource(R.string.alert_scope_always),
                            ).joinToString(" · "),
                            enabled = rule.enabled,
                            onEdit = { editing = EditingAlert.Places(rule) },
                            onDelete = { scope.launch { withContext(Dispatchers.IO) { provider.delete(rule.id) }; refreshPlaces() } },
                            onSetEnabled = { enabled -> scope.launch { withContext(Dispatchers.IO) { provider.setEnabled(rule.id, enabled) }; refreshPlaces() } },
                        )
                    }
                }
            }
        }
    }

    if (showDomainPicker) {
        AlertDialog(
            onDismissRequest = { showDomainPicker = false },
            title = { Text(alertText(R.string.alerts_choose_domain, R.string.alerts_choose_domain_it)) },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AlertDomain.entries.forEach { domain ->
                        TextButton(onClick = {
                            showDomainPicker = false
                            editing = when (domain) {
                                AlertDomain.TIMER -> EditingAlert.Timer(null)
                                AlertDomain.PLACE -> EditingAlert.Places(null)
                            }
                        }) {
                            Text(domain.displayName)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDomainPicker = false }) { Text(stringResource(R.string.annulla)) } },
        )
    }

    editing?.let { selected ->
        UnifiedAlertEditor(
            key = when (selected) {
                is EditingAlert.Timer -> "timer-${selected.rule?.id ?: "new"}"
                is EditingAlert.Places -> "places-${selected.rule?.id ?: "new"}"
            },
            editing = selected,
            state = state,
            places = places,
            initialPlaceId = initialPlaceId,
            onDismiss = { editing = null },
            onSaveTimer = { msg, trigger, scopeValue, match, tags, cooldown, random, count, window, enabled ->
                val rule = (selected as EditingAlert.Timer).rule
                val saved = if (rule == null) {
                    onAddTimeFenceRule(msg, trigger, scopeValue, match, tags, cooldown, TimeFenceDelivery.NOTIFICATION, random, count, window, enabled)
                } else {
                    onUpdateTimeFenceRule(rule.id, msg, trigger, scopeValue, match, tags, cooldown, TimeFenceDelivery.NOTIFICATION, random, count, window)
                }
                if (saved && rule != null && enabled != rule.isEnabled) onSetTimeFenceRuleEnabled(rule.id, enabled)
                if (saved) editing = null
                saved
            },
            onSavePlaces = { draft, enabled ->
                val rule = (selected as EditingAlert.Places).rule
                val saved = withContext(Dispatchers.IO) { provider.save(rule?.id, draft, enabled) }
                if (saved) {
                    refreshPlaces()
                    editing = null
                }
                saved
            },
        )
    }

    if (showTrash) {
        val trashed = state.timeFenceRules.filter { it.isDeleted }
        val filtered = trashed.filter { it.message.contains(trashQuery, ignoreCase = true) }
        TrashListDialog(
            title = { Text(stringResource(R.string.alert_trash_title)) },
            query = trashQuery,
            onQueryChange = { trashQuery = it },
            items = filtered,
            key = { it.id },
            itemTitle = { it.message },
            onRestore = { onRestoreTimeFenceRule(it.id) },
            onPurge = { onPurgeTimeFenceRule(it.id) },
            onRequestEmpty = if (trashed.isNotEmpty()) ({ confirmEmptyTrash = true }) else null,
            onDismiss = { showTrash = false },
        )
    }
    if (confirmEmptyTrash) {
        ConfirmEmptyTrashDialog(
            onConfirm = { confirmEmptyTrash = false; onPurgeAllDeletedTimeFenceRules(); showTrash = false },
            onDismiss = { confirmEmptyTrash = false },
        )
    }
}

@Composable
private fun filterLabel(filter: AlertDomain?): String =
    filter?.displayName ?: alertText(R.string.alerts_filter_all, R.string.alerts_filter_all_it)

@Composable
private fun placeTriggerLabel(trigger: String): String = when (trigger) {
    AlertTrigger.PLACE_CHECK_IN.name -> alertText(R.string.alerts_check_in, R.string.alerts_check_in_it)
    AlertTrigger.PLACE_CHECK_OUT.name -> alertText(R.string.alerts_check_out, R.string.alerts_check_out_it)
    AlertTrigger.PLACE_BOTH.name -> alertText(R.string.alerts_both, R.string.alerts_both_it)
    else -> trigger
}

@Composable
private fun UnifiedAlertCard(
    entityRef: HubEntityRef,
    domain: String,
    message: String,
    details: String,
    enabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
) {
    var linksOpen by remember(entityRef) { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(domain, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(message, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(details, style = MaterialTheme.typography.bodySmall)
                }
                Switch(enabled, onSetEnabled)
            }
            Text(stringResource(if (enabled) R.string.alert_status_enabled else R.string.alert_status_disabled))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onEdit) { Text(stringResource(R.string.modifica)) }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.elimina)) }
                TextButton(onClick = { linksOpen = !linksOpen }) {
                    Text(stringResource(com.gernalix.personalhub.core.hubcontext.R.string.hub_context_links_title))
                }
            }
            if (linksOpen) com.gernalix.personalhub.core.hubcontext.HubContextLinks(entityRef)
        }
    }
}

@Composable
private fun UnifiedAlertEditor(
    key: String,
    editing: EditingAlert,
    state: AlertsUiState,
    places: UnifiedPlaceAlertsSnapshot,
    initialPlaceId: String?,
    onDismiss: () -> Unit,
    onSaveTimer: (String, TimeFenceTrigger, TimeFenceScope, TimeFenceMatchMode, Set<Long>, Long, Boolean, Int, RandomAlertWindow, Boolean) -> Boolean,
    onSavePlaces: suspend (PlaceAlertDraft, Boolean) -> Boolean,
) {
    val timer = (editing as? EditingAlert.Timer)?.rule
    val place = (editing as? EditingAlert.Places)?.rule
    val isTimer = editing is EditingAlert.Timer
    var message by remember(key) { mutableStateOf(timer?.message ?: place?.message.orEmpty()) }
    var enabled by remember(key) { mutableStateOf(timer?.isEnabled ?: place?.enabled ?: true) }
    var scopeValue by remember(key) { mutableStateOf(if (timer?.scope == TimeFenceScope.ONE_TIME || place?.scope == AlertScope.ONE_TIME.name) AlertScope.ONE_TIME else AlertScope.ALWAYS) }
    var trigger by remember(key) { mutableStateOf(timer?.trigger ?: TimeFenceTrigger.ON_START) }
    var placeTrigger by remember(key) { mutableStateOf(runCatching { AlertTrigger.valueOf(place?.trigger.orEmpty()) }.getOrDefault(AlertTrigger.PLACE_CHECK_IN)) }
    var match by remember(key) { mutableStateOf(if (timer?.matchMode == TimeFenceMatchMode.OR || place?.matchMode == AlertMatchMode.ANY.name) AlertMatchMode.ANY else AlertMatchMode.ALL) }
    var timerTags by remember(key) { mutableStateOf(timer?.tagIds ?: emptySet<Long>()) }
    var placeTags by remember(key) { mutableStateOf(places.targets[place?.id].orEmpty()) }
    var targetKind by remember(key) { mutableStateOf(runCatching { AlertTargetKind.valueOf(place?.targetKind.orEmpty()) }.getOrDefault(AlertTargetKind.ENTITY)) }
    var placeId by remember(key) { mutableStateOf(place?.entityId ?: initialPlaceId.orEmpty()) }
    var cooldown by remember(key) { mutableStateOf(timer?.cooldownMs ?: 0L) }
    var randomEnabled by remember(key) { mutableStateOf(timer?.randomAlertsEnabled ?: false) }
    var randomCount by remember(key) { mutableStateOf((timer?.randomAlertsCount ?: 1).coerceAtLeast(1).toString()) }
    var randomWindow by remember(key) { mutableStateOf(RandomAlertWindow.parse(timer?.randomAlertsWindow ?: RandomAlertWindow.DAY.name)) }
    var tagQuery by remember(key) { mutableStateOf("") }
    var saveError by remember(key) { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val canSave = message.isNotBlank() && if (isTimer) timerTags.isNotEmpty() else
        if (targetKind == AlertTargetKind.ENTITY) placeId.isNotBlank() else placeTags.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (timer == null && place == null) R.string.new_alert_title else R.string.edit_alert_title)) },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    saveError = false
                    if (isTimer) {
                        if (!onSaveTimer(message, trigger,
                                if (scopeValue == AlertScope.ALWAYS) TimeFenceScope.ALWAYS else TimeFenceScope.ONE_TIME,
                                if (match == AlertMatchMode.ALL) TimeFenceMatchMode.AND else TimeFenceMatchMode.OR,
                                timerTags, cooldown, randomEnabled, randomCount.toIntOrNull() ?: 0, randomWindow, enabled)) {
                            saveError = true
                        }
                    } else {
                        val draft = PlaceAlertDraft(
                            message = message,
                            trigger = placeTrigger,
                            targetKind = targetKind,
                            placeId = placeId.takeIf { targetKind == AlertTargetKind.ENTITY },
                            placeTagIds = placeTags.takeIf { targetKind == AlertTargetKind.TAGS }.orEmpty(),
                            matchMode = match,
                            scope = scopeValue,
                            cooldownMs = place?.cooldownMs ?: 0L,
                        )
                        coroutineScope.launch { if (!onSavePlaces(draft, enabled)) saveError = true }
                    }
                },
                modifier = Modifier.testTag("alerts-save"),
            ) { Text(stringResource(R.string.salva)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("alerts-cancel")) { Text(stringResource(R.string.annulla)) } },
        text = {
            Column(
                Modifier.heightIn(max = 530.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(if (isTimer) alertText(R.string.alerts_domain_timer, R.string.alerts_domain_timer_it) else alertText(R.string.alerts_domain_places, R.string.alerts_domain_places_it))
                OutlinedTextField(message, { message = it }, label = { Text(stringResource(R.string.messaggio)) }, modifier = Modifier.fillMaxWidth().testTag("alerts-message"))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.alert_status_enabled), Modifier.weight(1f))
                    Switch(enabled, { enabled = it }, modifier = Modifier.testTag("alerts-editor-enabled"))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(scopeValue == AlertScope.ALWAYS, { scopeValue = AlertScope.ALWAYS }, label = { Text(stringResource(R.string.alert_scope_always)) })
                    FilterChip(scopeValue == AlertScope.ONE_TIME, { scopeValue = AlertScope.ONE_TIME }, label = { Text(stringResource(R.string.alert_scope_one_time)) })
                }
                if (isTimer) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(trigger == TimeFenceTrigger.ON_START, { trigger = TimeFenceTrigger.ON_START }, label = { Text(stringResource(R.string.alert_trigger_start)) })
                        FilterChip(trigger == TimeFenceTrigger.ON_STOP, { trigger = TimeFenceTrigger.ON_STOP }, label = { Text(stringResource(R.string.alert_trigger_end)) })
                    }
                    MatchModeChips(match, { match = it })
                    OutlinedTextField(tagQuery, { tagQuery = it }, label = { Text(stringResource(R.string.cerca_tag)) }, modifier = Modifier.fillMaxWidth())
                    TimerTagPicker(
                        tags = TagSelectionOrder.sortForPicker(
                            if (tagQuery.isBlank()) state.tags.filter { !it.isDeleted && !it.isArchived }
                            else state.tags.filter { !it.isDeleted && !it.isArchived && it.name.contains(tagQuery, true) },
                            timerTags, state.tagLastUsedMsByTagId,
                        ),
                        selected = timerTags,
                        onToggle = { id -> timerTags = if (id in timerTags) timerTags - id else timerTags + id },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(cooldown > 0, { cooldown = if (it) 30_000 else 0 })
                        Text(stringResource(R.string.cooldown_30s_anti_spam))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(randomEnabled, { randomEnabled = it })
                        Text(stringResource(R.string.random_alerts_enabled))
                    }
                    if (randomEnabled) {
                        OutlinedTextField(randomCount, { randomCount = it.filter(Char::isDigit).take(2) }, label = { Text(stringResource(R.string.random_alerts_count)) }, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(randomWindow == RandomAlertWindow.HOUR, { randomWindow = RandomAlertWindow.HOUR }, label = { Text(stringResource(R.string.random_alerts_per_hour)) })
                            FilterChip(randomWindow == RandomAlertWindow.DAY, { randomWindow = RandomAlertWindow.DAY }, label = { Text(stringResource(R.string.random_alerts_per_day)) })
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(AlertTrigger.PLACE_CHECK_IN, AlertTrigger.PLACE_CHECK_OUT, AlertTrigger.PLACE_BOTH).forEach { option ->
                            FilterChip(placeTrigger == option, { placeTrigger = option }, label = { Text(placeTriggerLabel(option.name)) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(targetKind == AlertTargetKind.ENTITY, { targetKind = AlertTargetKind.ENTITY }, label = { Text(alertText(R.string.alerts_specific_place, R.string.alerts_specific_place_it)) })
                        FilterChip(targetKind == AlertTargetKind.TAGS, { targetKind = AlertTargetKind.TAGS }, label = { Text(alertText(R.string.alerts_places_tags, R.string.alerts_places_tags_it)) })
                    }
                    if (targetKind == AlertTargetKind.ENTITY) {
                        places.places.forEach { target ->
                            FilterChip(placeId == target.uuid, { placeId = target.uuid }, label = { Text(target.nickname.ifBlank { target.address.orEmpty() }) })
                        }
                    } else {
                        MatchModeChips(match, { match = it })
                        places.tags.forEach { tag ->
                            FilterChip(tag.id in placeTags, { placeTags = if (tag.id in placeTags) placeTags - tag.id else placeTags + tag.id }, label = { Text(tag.name) })
                        }
                    }
                }
                if (saveError) Text(alertText(R.string.alerts_save_failed, R.string.alerts_save_failed_it), color = MaterialTheme.colorScheme.error)
            }
        },
    )
}

@Composable
private fun MatchModeChips(mode: AlertMatchMode, onChange: (AlertMatchMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(mode == AlertMatchMode.ALL, { onChange(AlertMatchMode.ALL) }, label = { Text(stringResource(R.string.alert_match_all)) })
        FilterChip(mode == AlertMatchMode.ANY, { onChange(AlertMatchMode.ANY) }, label = { Text(stringResource(R.string.alert_match_any)) })
    }
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun TimerTagPicker(tags: List<Tag>, selected: Set<Long>, onToggle: (Long) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tags.forEach { tag ->
            val color = MaterialTheme.colorScheme.secondaryContainer
            FilterChip(
                selected = tag.id in selected,
                onClick = { onToggle(tag.id) },
                label = { Text(tag.name) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = color, containerColor = color.copy(alpha = 0.6f)),
            )
        }
    }
}

@Composable
private fun alertText(english: Int, italian: Int): String = AlertText.get(LocalContext.current, english, italian)
