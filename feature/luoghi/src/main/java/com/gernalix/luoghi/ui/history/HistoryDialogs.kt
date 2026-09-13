package com.gernalix.luoghi.ui.history

import android.icu.text.ListFormatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gernalix.luoghi.HistoryMessage
import com.gernalix.luoghi.R
import com.gernalix.luoghi.capsules.checkin.PlaceEventTypes
import com.gernalix.luoghi.capsules.visits.VisitAnomaly
import com.gernalix.luoghi.capsules.visits.VisitUiModel
import com.gernalix.luoghi.data.PlaceEventEntity
import com.gernalix.luoghi.ui.common.localizedDateTime
import com.gernalix.luoghi.ui.common.localizedDuration
import com.gernalix.luoghi.ui.common.rememberMinuteNow
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

@Composable
fun VisitDetailDialog(
    visit: VisitUiModel,
    events: List<PlaceEventEntity>,
    onEditEvent: (PlaceEventEntity) -> Unit,
    onDeleteEvent: (PlaceEventEntity) -> Unit,
    onDeleteVisit: (VisitUiModel) -> Unit,
    onDismiss: () -> Unit,
) {
    val name = visit.placeName.ifBlank { stringResource(R.string.place_unavailable) }
    val nowMs = rememberMinuteNow(visit.isActive)
    val displayedDurationMs = if (visit.isActive && visit.startedAt != null) {
        (nowMs - visit.startedAt).coerceAtLeast(0L)
    } else {
        visit.durationMs
    }
    val visibleAnomalies = if (visit.isActive) {
        visit.anomalies - VisitAnomaly.MISSING_CHECK_OUT
    } else {
        visit.anomalies
    }
    val relatedEvents = remember(visit.underlyingEventIds, events) {
        val ids = visit.underlyingEventIds.toSet()
        events.filter { it.id in ids }.sortedWith(compareBy<PlaceEventEntity> { it.timestamp }.thenBy { it.id })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = visitDetailInterval(visit),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.visit_duration_format, localizedDuration(displayedDurationMs)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                visit.address?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (visibleAnomalies.isNotEmpty()) {
                    Text(
                        text = anomalyList(visibleAnomalies),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.Medium,
                    )
                }
                HorizontalDivider()
                Text(
                    stringResource(R.string.underlying_events),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                relatedEvents.forEach { event ->
                    EventActionRow(
                        event = event,
                        onEdit = { onEditEvent(event) },
                        onDelete = { onDeleteEvent(event) },
                    )
                }
                if (relatedEvents.isEmpty()) {
                    Text(stringResource(R.string.underlying_events_unavailable))
                }
                if (visit.sessionUuid != null) {
                    TextButton(onClick = { onDeleteVisit(visit) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Text(
                            stringResource(R.string.history_delete_visit),
                            modifier = Modifier.padding(start = 8.dp),
                            color = MaterialTheme.colorScheme.error,
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
private fun EventActionRow(
    event: PlaceEventEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val label = if (event.eventType == PlaceEventTypes.CHECK_IN) {
        stringResource(R.string.history_check_in)
    } else {
        stringResource(R.string.history_check_out)
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(localizedDateTime(event.timestamp), style = MaterialTheme.typography.bodyMedium)
            event.notes?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.edit_event_format, label))
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.delete_event_format, label),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
fun HistoryEventEditDialog(
    event: PlaceEventEntity,
    onDismiss: () -> Unit,
    onSave: (Long, String?) -> Unit,
) {
    var timestampText by rememberSaveable(event.id) { mutableStateOf(formatHistoryTimestampForInput(event.timestamp)) }
    var notesText by rememberSaveable(event.id) { mutableStateOf(event.notes.orEmpty()) }
    var parseFailed by rememberSaveable(event.id) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_edit_event_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = timestampText,
                    onValueChange = {
                        timestampText = it
                        parseFailed = false
                    },
                    label = { Text(stringResource(R.string.history_timestamp)) },
                    supportingText = {
                        Text(
                            if (parseFailed) stringResource(R.string.history_timestamp_invalid)
                            else stringResource(R.string.history_timestamp_hint)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    isError = parseFailed,
                )
                OutlinedTextField(
                    value = notesText,
                    onValueChange = { notesText = it },
                    label = { Text(stringResource(R.string.notes)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val timestamp = parseHistoryTimestampInput(timestampText)
                if (timestamp == null) parseFailed = true else onSave(timestamp, notesText)
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
fun DeleteEventConfirmation(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_delete_event_title)) },
        text = { Text(stringResource(R.string.history_delete_event_confirm)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun DeleteVisitConfirmation(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_delete_visit_title)) },
        text = { Text(stringResource(R.string.history_delete_visit_confirm)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun historyMessageText(message: HistoryMessage): String = when (message) {
    HistoryMessage.EVENT_UPDATED -> stringResource(R.string.history_message_event_updated)
    HistoryMessage.EVENT_DELETED -> stringResource(R.string.history_message_event_deleted)
    HistoryMessage.SESSION_DELETED -> stringResource(R.string.history_message_session_deleted)
    HistoryMessage.CHECKED_IN -> stringResource(R.string.history_message_checked_in)
    HistoryMessage.VISIT_CREATED -> stringResource(R.string.history_message_visit_created)
    HistoryMessage.UNDONE -> stringResource(R.string.history_message_undone)
    HistoryMessage.REDONE -> stringResource(R.string.history_message_redone)
    HistoryMessage.EVENT_NOT_FOUND -> stringResource(R.string.history_error_event_not_found)
    HistoryMessage.INVALID_TIMESTAMP -> stringResource(R.string.history_error_invalid_timestamp)
    HistoryMessage.CHECKOUT_BEFORE_CHECKIN -> stringResource(R.string.history_error_checkout_before_checkin)
    HistoryMessage.OVERLAP -> stringResource(R.string.history_error_overlap)
    HistoryMessage.DUPLICATE -> stringResource(R.string.history_error_duplicate)
    HistoryMessage.ORPHAN_CHECKOUT -> stringResource(R.string.history_error_orphan_checkout)
    HistoryMessage.SESSION_NOT_FOUND -> stringResource(R.string.history_error_session_not_found)
    HistoryMessage.NOTHING_TO_UNDO -> stringResource(R.string.history_error_nothing_to_undo)
    HistoryMessage.NOTHING_TO_REDO -> stringResource(R.string.history_error_nothing_to_redo)
}

@Composable
private fun visitDetailInterval(visit: VisitUiModel): String = when {
    visit.startedAt != null && visit.endedAt != null -> stringResource(
        R.string.visit_detail_interval_format,
        localizedDateTime(visit.startedAt),
        localizedDateTime(visit.endedAt),
    )
    visit.startedAt != null -> stringResource(
        R.string.visit_detail_active_format,
        localizedDateTime(visit.startedAt),
    )
    visit.endedAt != null -> stringResource(
        R.string.visit_detail_orphan_format,
        localizedDateTime(visit.endedAt),
    )
    else -> stringResource(R.string.visit_time_unavailable)
}

@Composable
private fun anomalyList(anomalies: Set<VisitAnomaly>): String {
    val labels = anomalies.map { anomaly ->
        stringResource(
            when (anomaly) {
                VisitAnomaly.MISSING_CHECK_IN -> R.string.history_anomaly_missing_check_in
                VisitAnomaly.MISSING_CHECK_OUT -> R.string.history_anomaly_open
                VisitAnomaly.MULTIPLE_CHECK_INS -> R.string.history_anomaly_multiple_check_ins
                VisitAnomaly.MULTIPLE_CHECK_OUTS -> R.string.history_anomaly_multiple_check_outs
                VisitAnomaly.NON_POSITIVE_DURATION -> R.string.history_anomaly_negative_duration
                VisitAnomaly.OVERLAP -> R.string.history_anomaly_overlap
                VisitAnomaly.MULTIPLE_OPEN_VISITS -> R.string.history_anomaly_multiple_open
                VisitAnomaly.PLACE_MISMATCH -> R.string.history_anomaly_place_mismatch
                VisitAnomaly.UNUSUAL_DURATION -> R.string.history_anomaly_unusual_duration
                VisitAnomaly.UNKNOWN_EVENT_TYPE -> R.string.history_anomaly_unknown_event
            }
        )
    }
    val locale = LocalConfiguration.current.locales[0]
    return remember(labels, locale) { ListFormatter.getInstance(locale).format(labels) }
}

fun formatHistoryTimestampForInput(timestamp: Long): String =
    HISTORY_EDIT_FORMATTER.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))

fun parseHistoryTimestampInput(value: String): Long? = try {
    LocalDateTime.parse(value.trim(), HISTORY_EDIT_FORMATTER)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
} catch (_: DateTimeParseException) {
    null
}

private val HISTORY_EDIT_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d/M/yy HH:mm")
