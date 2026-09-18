package com.gernalix.luoghi.ui.place

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gernalix.luoghi.R
import com.gernalix.luoghi.data.PlaceTagEntity
import com.gernalix.personalhub.alerts.AlertRuleEntity
import com.gernalix.personalhub.core.alerts.AlertMatchMode
import com.gernalix.personalhub.core.alerts.AlertScope
import com.gernalix.personalhub.core.alerts.AlertTargetKind
import com.gernalix.personalhub.core.alerts.AlertTrigger
import com.gernalix.personalhub.core.alerts.PlaceAlertDraft

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaceAlertsDialog(
    placeId: String,
    placeName: String,
    tags: List<PlaceTagEntity>,
    rules: List<AlertRuleEntity>,
    tagTargets: Map<String, Set<Long>>,
    onCreate: (PlaceAlertDraft) -> Unit,
    onDelete: (String) -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var message by remember { mutableStateOf("") }
    var trigger by remember { mutableStateOf(AlertTrigger.PLACE_CHECK_IN) }
    var targetKind by remember { mutableStateOf(AlertTargetKind.ENTITY) }
    var selectedTagIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var matchMode by remember { mutableStateOf(AlertMatchMode.ALL) }
    var scope by remember { mutableStateOf(AlertScope.ALWAYS) }

    val visibleRules = rules.filter { it.deletedAt == null }
    val canSave = message.trim().isNotEmpty() &&
        (targetKind == AlertTargetKind.ENTITY || selectedTagIds.isNotEmpty())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.place_alerts_title, placeName)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text(stringResource(R.string.place_alert_message)) },
                    supportingText = { Text(stringResource(R.string.place_alert_link_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(stringResource(R.string.place_alert_when), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = trigger == AlertTrigger.PLACE_CHECK_IN,
                        onClick = { trigger = AlertTrigger.PLACE_CHECK_IN },
                        label = { Text(stringResource(R.string.checkin_button)) },
                    )
                    FilterChip(
                        selected = trigger == AlertTrigger.PLACE_CHECK_OUT,
                        onClick = { trigger = AlertTrigger.PLACE_CHECK_OUT },
                        label = { Text(stringResource(R.string.checkout_button)) },
                    )
                    FilterChip(
                        selected = trigger == AlertTrigger.PLACE_BOTH,
                        onClick = { trigger = AlertTrigger.PLACE_BOTH },
                        label = { Text(stringResource(R.string.place_alert_both)) },
                    )
                }

                Text(stringResource(R.string.place_alert_where), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = targetKind == AlertTargetKind.ENTITY,
                        onClick = { targetKind = AlertTargetKind.ENTITY },
                        label = { Text(stringResource(R.string.place_alert_this_place)) },
                    )
                    FilterChip(
                        selected = targetKind == AlertTargetKind.TAGS,
                        onClick = { targetKind = AlertTargetKind.TAGS },
                        label = { Text(stringResource(R.string.place_tags)) },
                    )
                }

                if (targetKind == AlertTargetKind.TAGS) {
                    if (tags.isEmpty()) {
                        Text(
                            stringResource(R.string.place_alert_no_tags),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            tags.forEach { tag ->
                                FilterChip(
                                    selected = tag.id in selectedTagIds,
                                    onClick = {
                                        selectedTagIds = if (tag.id in selectedTagIds) {
                                            selectedTagIds - tag.id
                                        } else {
                                            selectedTagIds + tag.id
                                        }
                                    },
                                    label = { Text(tag.name) },
                                )
                            }
                        }
                        if (selectedTagIds.size > 1) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(
                                    selected = matchMode == AlertMatchMode.ALL,
                                    onClick = { matchMode = AlertMatchMode.ALL },
                                    label = { Text(stringResource(R.string.place_alert_all_tags)) },
                                )
                                FilterChip(
                                    selected = matchMode == AlertMatchMode.ANY,
                                    onClick = { matchMode = AlertMatchMode.ANY },
                                    label = { Text(stringResource(R.string.place_alert_any_tag)) },
                                )
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = scope == AlertScope.ALWAYS,
                        onClick = { scope = AlertScope.ALWAYS },
                        label = { Text(stringResource(R.string.place_alert_every_time)) },
                    )
                    FilterChip(
                        selected = scope == AlertScope.ONE_TIME,
                        onClick = { scope = AlertScope.ONE_TIME },
                        label = { Text(stringResource(R.string.place_alert_once)) },
                    )
                }

                TextButton(
                    enabled = canSave,
                    onClick = {
                        onCreate(
                            PlaceAlertDraft(
                                message = message,
                                trigger = trigger,
                                targetKind = targetKind,
                                placeId = placeId.takeIf { targetKind == AlertTargetKind.ENTITY },
                                placeTagIds = selectedTagIds.takeIf { targetKind == AlertTargetKind.TAGS }.orEmpty(),
                                matchMode = matchMode,
                                scope = scope,
                            )
                        )
                        message = ""
                    },
                ) {
                    Text(stringResource(R.string.place_alert_add))
                }

                if (visibleRules.isNotEmpty()) {
                    Text(stringResource(R.string.place_alert_existing), style = MaterialTheme.typography.titleSmall)
                    visibleRules.forEach { rule ->
                        PlaceAlertRuleCard(
                            rule = rule,
                            tags = tags,
                            tagIds = tagTargets[rule.id].orEmpty(),
                            onDelete = { onDelete(rule.id) },
                            onSetEnabled = { onSetEnabled(rule.id, it) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
private fun PlaceAlertRuleCard(
    rule: AlertRuleEntity,
    tags: List<PlaceTagEntity>,
    tagIds: Set<Long>,
    onDelete: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
) {
    val tagNames = tagIds.mapNotNull { id -> tags.firstOrNull { it.id == id }?.name }
    val target = if (rule.targetKind == AlertTargetKind.ENTITY.name) {
        stringResource(R.string.place_alert_specific_place)
    } else {
        tagNames.joinToString(", ").ifBlank { stringResource(R.string.place_tags) }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(rule.message, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${rule.trigger} · $target",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = rule.enabled, onCheckedChange = onSetEnabled)
            }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
        }
    }
}
