// v355
package com.example.multitimetracker.capsules.alerts.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import com.example.multitimetracker.ui.components.SingleSubmitButton
import com.example.multitimetracker.ui.components.ScreenEmptyStateCard
import com.example.multitimetracker.ui.components.ScreenIntroCard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.TimeFenceDelivery
import com.example.multitimetracker.model.TimeFenceMatchMode
import com.example.multitimetracker.model.TimeFenceRule
import com.example.multitimetracker.model.TimeFenceScope
import com.example.multitimetracker.model.TimeFenceTrigger
import com.example.multitimetracker.capsules.alerts.state.AlertsUiState
import com.example.multitimetracker.ui.util.formatDuration
import com.example.multitimetracker.ui.util.TagSelectionOrder
import com.example.multitimetracker.ui.components.AppTopBar
import com.example.multitimetracker.ui.components.InlineHelpAction
import com.example.multitimetracker.ui.components.ScreenHelpAction
import com.example.multitimetracker.ui.theme.Dimens
import com.example.multitimetracker.R
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Scaffold
import com.example.multitimetracker.capsules.system.ui.ConfirmEmptyTrashDialog
import com.example.multitimetracker.capsules.system.ui.TrashListDialog

@Composable
fun AlertsScreen(
    modifier: Modifier = Modifier,
    state: AlertsUiState,
    onAddTimeFenceRule: (String, TimeFenceTrigger, TimeFenceScope, TimeFenceMatchMode, Set<Long>, Long, Int, TimeFenceDelivery) -> Unit,
    onUpdateTimeFenceRule: (Long, String, TimeFenceTrigger, TimeFenceScope, TimeFenceMatchMode, Set<Long>, Long, Int, TimeFenceDelivery) -> Unit,
    onDeleteTimeFenceRule: (Long) -> Unit,
    onRestoreTimeFenceRule: (Long) -> Unit,
    onPurgeTimeFenceRule: (Long) -> Unit,
    onPurgeAllDeletedTimeFenceRules: () -> Unit,
    onSetTimeFenceRuleEnabled: (Long, Boolean) -> Unit
) {
    var showTrash by remember { mutableStateOf(false) }
    var trashQuery by rememberSaveable { mutableStateOf("") }
    var confirmEmptyTrash by remember { mutableStateOf(false) }


    // === FEATURE CAPSULE: AlertsScreen (UI) START ===
    var showAdd by remember { mutableStateOf(false) }
    var editRule by remember { mutableStateOf<TimeFenceRule?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.alert),
                actions = {
                    ScreenHelpAction(
                        title = stringResource(R.string.alerts_intro_title),
                        body = stringResource(R.string.alerts_intro_body)
                    )
                    IconButton(onClick = { showTrash = true }) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.show_trash),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.aggiungi))
            }
        }
    ) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            val visibleRules = remember(state.timeFenceRules) { state.timeFenceRules.filter { !it.isDeleted } }

            Spacer(Modifier.height(Dimens.SectionSpacing))

            if (visibleRules.isEmpty()) {
                ScreenEmptyStateCard(
                    title = stringResource(R.string.alerts_empty_title),
                    body = stringResource(R.string.alerts_empty_body),
                    modifier = Modifier.padding(12.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(visibleRules, key = { it.id }) { rule ->
                        AlertRuleCard(
                            rule = rule,
                            tags = state.tags.filter { !it.isDeleted && !it.isArchived },
                            onEdit = { editRule = rule },
                            onDelete = { onDeleteTimeFenceRule(rule.id) },
                            onToggleEnabled = { onSetTimeFenceRuleEnabled(rule.id, it) }
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        AlertRuleDialog(
            title = stringResource(R.string.new_alert_title),
            tags = state.tags.filter { !it.isDeleted && !it.isArchived },
            tagLastUsedMsByTagId = state.tagLastUsedMsByTagId,
            initial = null,
            onDismiss = { showAdd = false },
            onConfirm = { msg, trigger, scope, matchMode, tagIds, cooldownMs, timerMinutes, delivery ->
                onAddTimeFenceRule(msg, trigger, scope, matchMode, tagIds, cooldownMs, timerMinutes, delivery)
                showAdd = false
            }
        )
    }

    if (editRule != null) {
        AlertRuleDialog(
            title = stringResource(R.string.edit_alert_title),
            tags = state.tags.filter { !it.isDeleted && !it.isArchived },
            tagLastUsedMsByTagId = state.tagLastUsedMsByTagId,
            initial = editRule,
            onDismiss = { editRule = null },
            onConfirm = { msg, trigger, scope, matchMode, tagIds, cooldownMs, timerMinutes, delivery ->
                val r = editRule ?: return@AlertRuleDialog
                onUpdateTimeFenceRule(r.id, msg, trigger, scope, matchMode, tagIds, cooldownMs, timerMinutes, delivery)
                editRule = null
            }
        )
    }


    // --- Alerts Trash dialog (capsule-hardened, shared component) ---
    if (showTrash) {
        val trashed = remember(state.timeFenceRules) { state.timeFenceRules.filter { it.isDeleted } }
        val filtered = remember(trashed, trashQuery) {
            val q = trashQuery.trim().lowercase()
            if (q.isBlank()) trashed else trashed.filter { it.message.lowercase().contains(q) || it.id.toString().contains(q) }
        }

        TrashListDialog(
            title = { Text(stringResource(R.string.alert_trash_title)) },
            query = trashQuery,
            onQueryChange = { trashQuery = it },
            items = filtered,
            key = { it.id },
            itemTitle = { r -> r.message.ifBlank { "(${r.id})" } },
            onRestore = { r -> onRestoreTimeFenceRule(r.id) },
            onPurge = { r -> onPurgeTimeFenceRule(r.id) },
            onRequestEmpty = if (trashed.isNotEmpty()) ({ confirmEmptyTrash = true }) else null,
            onDismiss = { showTrash = false }
        )
    }

    if (confirmEmptyTrash) {
        ConfirmEmptyTrashDialog(
            onConfirm = {
                confirmEmptyTrash = false
                onPurgeAllDeletedTimeFenceRules()
                showTrash = false
            },
            onDismiss = { confirmEmptyTrash = false }
        )
    }

    // === FEATURE CAPSULE: AlertsScreen (UI) END ===
}

@Composable
private fun AlertRuleCard(
    rule: TimeFenceRule,
    tags: List<Tag>,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(Dimens.CardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        rule.message,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    val subtitle = buildString {
                        append(
                            if (rule.trigger == TimeFenceTrigger.ON_START)
                                stringResource(R.string.alert_trigger_start)
                            else
                                stringResource(R.string.alert_trigger_end)
                        )
                        append(" · ")
                        append(
                            if (rule.scope == TimeFenceScope.ONE_TIME)
                                stringResource(R.string.alert_scope_one_time)
                            else
                                stringResource(R.string.alert_scope_always)
                        )
                        append(" · ")
                        append(
                            if (rule.matchMode == TimeFenceMatchMode.AND)
                                stringResource(R.string.alert_match_all)
                            else
                                stringResource(R.string.alert_match_any)
                        )
                        if (rule.delivery == TimeFenceDelivery.NOTIFICATION &&
                            rule.trigger == TimeFenceTrigger.ON_START &&
                            rule.timerMinutes > 0
                        ) {
                            append(" · ")
                            append(stringResource(R.string.alert_timer_short, rule.timerMinutes))
                        }
                        if (rule.cooldownMs > 0) {
                            append(" · ")
                            append(stringResource(R.string.alert_cooldown_short))
                            append(" ")
                            append(formatDuration(rule.cooldownMs))
                        }
                    }
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = rule.isEnabled, onCheckedChange = onToggleEnabled)
            }

            Text(
                text = if (rule.isEnabled) {
                    stringResource(R.string.alert_status_enabled)
                } else {
                    stringResource(R.string.alert_status_disabled)
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (rule.isEnabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            val tagNames = rule.tagIds.mapNotNull { id -> tags.firstOrNull { it.id == id }?.name }
            if (tagNames.isNotEmpty()) {
                Text(
                    stringResource(R.string.alert_tags_prefix) + tagNames.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onEdit) { Text(stringResource(R.string.modifica)) }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.elimina)) }
            }
        }
    }
}

@Composable
private fun AlertRuleDialog(
    title: String,
    tags: List<Tag>,
    tagLastUsedMsByTagId: Map<Long, Long>,
    initial: TimeFenceRule?,
    onDismiss: () -> Unit,
    onConfirm: (String, TimeFenceTrigger, TimeFenceScope, TimeFenceMatchMode, Set<Long>, Long, Int, TimeFenceDelivery) -> Unit
) {
    var message by remember { mutableStateOf(initial?.message ?: "") }
    var trigger by remember { mutableStateOf(initial?.trigger ?: TimeFenceTrigger.ON_START) }
    var scope by remember { mutableStateOf(initial?.scope ?: TimeFenceScope.ALWAYS) }
    var matchMode by remember { mutableStateOf(initial?.matchMode ?: TimeFenceMatchMode.AND) }
    var cooldownMs by remember { mutableStateOf(initial?.cooldownMs ?: 0L) }
    var timerMinutesText by remember { mutableStateOf((initial?.timerMinutes ?: 1).coerceAtLeast(0).toString()) }
    var selectedTagIds by remember { mutableStateOf(initial?.tagIds ?: emptySet()) }
    var tagQuery by remember { mutableStateOf("") }
    val timerMinutes = timerMinutesText.toIntOrNull()?.coerceIn(0, 24 * 60)
    val normalizedTimerMinutes = if (trigger == TimeFenceTrigger.ON_START) timerMinutes else 0

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            SingleSubmitButton(
                enabled = message.trim().isNotEmpty() && selectedTagIds.isNotEmpty() && normalizedTimerMinutes != null,
                onClick = {
                    onConfirm(
                        message,
                        trigger,
                        scope,
                        matchMode,
                        selectedTagIds,
                        cooldownMs,
                        normalizedTimerMinutes ?: 0,
                        TimeFenceDelivery.NOTIFICATION
                    )
                }
            ) { Text(stringResource(R.string.salva)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.annulla)) } },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text(stringResource(R.string.messaggio)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.alert_notification_delivery_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.trigger), style = MaterialTheme.typography.labelLarge)
                    FilterChip(
                        selected = trigger == TimeFenceTrigger.ON_START,
                        onClick = { trigger = TimeFenceTrigger.ON_START },
                        label = { Text(stringResource(R.string.inizio)) }
                    )
                    FilterChip(
                        selected = trigger == TimeFenceTrigger.ON_STOP,
                        onClick = { trigger = TimeFenceTrigger.ON_STOP },
                        label = { Text(stringResource(R.string.fine)) }
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.frequenza), style = MaterialTheme.typography.labelLarge)
                    FilterChip(
                        selected = scope == TimeFenceScope.ALWAYS,
                        onClick = { scope = TimeFenceScope.ALWAYS },
                        label = { Text(stringResource(R.string.sempre)) }
                    )
                    FilterChip(
                        selected = scope == TimeFenceScope.ONE_TIME,
                        onClick = { scope = TimeFenceScope.ONE_TIME },
                        label = { Text(stringResource(R.string.one_time)) }
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.match), style = MaterialTheme.typography.labelLarge)
                    FilterChip(
                        selected = matchMode == TimeFenceMatchMode.AND,
                        onClick = { matchMode = TimeFenceMatchMode.AND },
                        label = { Text(stringResource(R.string.tutti)) }
                    )
                    FilterChip(
                        selected = matchMode == TimeFenceMatchMode.OR,
                        onClick = { matchMode = TimeFenceMatchMode.OR },
                        label = { Text(stringResource(R.string.almeno_uno)) }
                    )
                }

                if (trigger == TimeFenceTrigger.ON_START) {
                    OutlinedTextField(
                        value = timerMinutesText,
                        onValueChange = { value -> timerMinutesText = value.filter { it.isDigit() }.take(4) },
                        label = { Text(stringResource(R.string.alert_timer_minutes_label)) },
                        supportingText = { Text(stringResource(R.string.alert_timer_help)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = timerMinutes == null
                    )
                }

                Text(stringResource(R.string.tag_seleziona), style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = tagQuery,
                    onValueChange = { tagQuery = it },
                    label = { Text(stringResource(R.string.cerca_tag)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                FlowRowTags(
                    tags = TagSelectionOrder.sortForPicker(
                        tags = if (tagQuery.isBlank()) tags else tags.filter { it.name.contains(tagQuery, ignoreCase = true) },
                        selectedIds = selectedTagIds,
                        lastUsedMsByTagId = tagLastUsedMsByTagId
                    ),
                    selected = selectedTagIds,
                    onToggle = { id ->
                        selectedTagIds = if (selectedTagIds.contains(id)) selectedTagIds - id else selectedTagIds + id
                    }
                )

                // Cooldown kept but hidden-ish: only show numeric if non-zero
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(
                        checked = cooldownMs > 0,
                        onCheckedChange = { checked -> cooldownMs = if (checked) 30_000L else 0L }
                    )
                    Text(stringResource(R.string.cooldown_30s_anti_spam), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun FlowRowTags(
    tags: List<Tag>,
    selected: Set<Long>,
    onToggle: (Long) -> Unit
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val uniform = MaterialTheme.colorScheme.secondaryContainer
        tags.forEach { tag ->
            val isSel = selected.contains(tag.id)
            FilterChip(
                selected = isSel,
                onClick = { onToggle(tag.id) },
                label = { Text(tag.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = uniform,
                    containerColor = uniform.copy(alpha = 0.6f)
                )
            )
        }
    }
}
