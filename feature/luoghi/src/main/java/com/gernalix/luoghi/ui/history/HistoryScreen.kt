package com.gernalix.luoghi.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gernalix.luoghi.HistoryMessage
import com.gernalix.luoghi.HistoryUiState
import com.gernalix.luoghi.R
import com.gernalix.luoghi.capsules.checkin.WhereWasIQuery
import com.gernalix.luoghi.capsules.checkin.WhereWasIResult
import com.gernalix.luoghi.capsules.visits.VisitUiModel
import com.gernalix.luoghi.data.PlaceEntity
import com.gernalix.luoghi.data.PlaceEventEntity
import com.gernalix.luoghi.ui.common.localizedDateTime
import com.gernalix.luoghi.ui.common.localizedDayHeading
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private data class VisitDayGroup(val day: LocalDate, val visits: List<VisitUiModel>)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    visits: List<VisitUiModel>,
    events: List<PlaceEventEntity>,
    historyState: HistoryUiState,
    isLoading: Boolean,
    filterPlaceId: String?,
    filterPlaceName: String?,
    places: List<PlaceEntity>,
    initialVisitId: String?,
    listState: LazyListState,
    onBack: () -> Unit,
    onEditEvent: (Long, Long, String?) -> Unit,
    onDeleteEvent: (Long) -> Unit,
    onDeleteVisit: (String) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onClearMessage: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val filteredVisits = remember(visits, filterPlaceId) {
        filterPlaceId?.let { id -> visits.filter { it.placeId == id } } ?: visits
    }
    val activeVisits = remember(filteredVisits) { filteredVisits.filter { it.isActive } }
    val dayGroups = remember(filteredVisits) {
        val zone = ZoneId.systemDefault()
        filteredVisits
            .filterNot { it.isActive }
            .groupBy { Instant.ofEpochMilli(it.sortTimestamp).atZone(zone).toLocalDate() }
            .entries
            .sortedByDescending { it.key }
            .map { VisitDayGroup(it.key, it.value) }
    }
    var selectedVisitId by rememberSaveable { mutableStateOf(initialVisitId) }
    var editingEventId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deletingEventId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deletingVisitId by rememberSaveable { mutableStateOf<String?>(null) }
    var whereWasIOpen by rememberSaveable { mutableStateOf(false) }
    val selectedVisit = selectedVisitId?.let { id -> filteredVisits.firstOrNull { it.stableId == id } }
    val editingEvent = editingEventId?.let { id -> events.firstOrNull { it.id == id } }
    val deletingEvent = deletingEventId?.let { id -> events.firstOrNull { it.id == id } }
    val deletingVisit = deletingVisitId?.let { id -> filteredVisits.firstOrNull { it.stableId == id } }

    LaunchedEffect(initialVisitId) {
        if (initialVisitId != null) selectedVisitId = initialVisitId
    }
    LaunchedEffect(selectedVisitId, selectedVisit) {
        if (selectedVisitId != null && selectedVisit == null) selectedVisitId = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.history_title))
                        filterPlaceName?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            isLoading -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Text(
                        stringResource(R.string.history_loading),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "history-actions") {
                        HistoryActions(
                            state = historyState,
                            onUndo = onUndo,
                            onRedo = onRedo,
                            onWhereWasI = { whereWasIOpen = true },
                            onClearMessage = onClearMessage,
                        )
                    }
                    if (filteredVisits.isEmpty()) {
                        item(key = "history-empty") {
                            EmptyHistory(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    if (activeVisits.isNotEmpty()) {
                        stickyHeader(key = "active-header") {
                            DayHeader(stringResource(R.string.in_progress))
                        }
                        items(activeVisits, key = { it.stableId }) { visit ->
                            VisitTimelineItem(visit = visit, onClick = { selectedVisitId = visit.stableId })
                        }
                    }
                    dayGroups.forEach { group ->
                        stickyHeader(key = "day-${group.day}") {
                            val firstTimestamp = group.visits.first().sortTimestamp
                            DayHeader(localizedDayHeading(firstTimestamp))
                        }
                        items(group.visits, key = { it.stableId }) { visit ->
                            VisitTimelineItem(visit = visit, onClick = { selectedVisitId = visit.stableId })
                        }
                    }
                }
            }
        }
    }

    selectedVisit?.let { visit ->
        VisitDetailDialog(
            visit = visit,
            events = events,
            onEditEvent = { event ->
                selectedVisitId = null
                editingEventId = event.id
            },
            onDeleteEvent = { event ->
                selectedVisitId = null
                deletingEventId = event.id
            },
            onDeleteVisit = {
                selectedVisitId = null
                deletingVisitId = it.stableId
            },
            onDismiss = { selectedVisitId = null },
        )
    }
    editingEvent?.let { event ->
        HistoryEventEditDialog(
            event = event,
            onDismiss = { editingEventId = null },
            onSave = { timestamp, notes ->
                onEditEvent(event.id, timestamp, notes)
                editingEventId = null
            },
        )
    }
    deletingEvent?.let { event ->
        DeleteEventConfirmation(
            onConfirm = {
                onDeleteEvent(event.id)
                deletingEventId = null
            },
            onDismiss = { deletingEventId = null },
        )
    }
    deletingVisit?.let { visit ->
        DeleteVisitConfirmation(
            onConfirm = {
                visit.sessionUuid?.let(onDeleteVisit)
                deletingVisitId = null
            },
            onDismiss = { deletingVisitId = null },
        )
    }
    if (whereWasIOpen) {
        WhereWasIDialog(
            visits = visits,
            places = places,
            onDismiss = { whereWasIOpen = false },
        )
    }
}

@Composable
private fun DayHeader(text: String) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun HistoryActions(
    state: HistoryUiState,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onWhereWasI: () -> Unit,
    onClearMessage: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = state.canUndo,
                onClick = onUndo,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = null)
                Text(stringResource(R.string.undo), modifier = Modifier.padding(start = 8.dp))
            }
            OutlinedButton(
                enabled = state.canRedo,
                onClick = onRedo,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = null)
                Text(stringResource(R.string.redo), modifier = Modifier.padding(start = 8.dp))
            }
        }
        OutlinedButton(onClick = onWhereWasI, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Place, contentDescription = null)
            Text(stringResource(R.string.where_was_i), modifier = Modifier.padding(start = 8.dp))
        }
        state.message?.let { message: HistoryMessage ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(historyMessageText(message), modifier = Modifier.weight(1f))
                    TextButton(onClick = onClearMessage) { Text(stringResource(R.string.close)) }
                }
            }
        }
    }
}

@Composable
private fun WhereWasIDialog(
    visits: List<VisitUiModel>,
    places: List<PlaceEntity>,
    onDismiss: () -> Unit,
) {
    var timestampText by rememberSaveable {
        mutableStateOf(formatHistoryTimestampForInput(System.currentTimeMillis()))
    }
    var parseFailed by rememberSaveable { mutableStateOf(false) }
    val instant = parseHistoryTimestampInput(timestampText)
    val result = instant?.let { WhereWasIQuery.at(visits, places, it) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.where_was_i)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = timestampText,
                    onValueChange = {
                        timestampText = it
                        parseFailed = false
                    },
                    label = { Text(stringResource(R.string.history_timestamp)) },
                    supportingText = { Text(stringResource(R.string.history_timestamp_hint)) },
                    isError = parseFailed,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(whereWasIText(result))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (instant == null) parseFailed = true else onDismiss()
            }) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
private fun whereWasIText(result: WhereWasIResult?): String = when (result) {
    null -> stringResource(R.string.where_was_i_invalid)
    is WhereWasIResult.Inside -> stringResource(R.string.where_was_i_inside_format, result.place.nickname)
    is WhereWasIResult.Between -> stringResource(
        R.string.where_was_i_between_format,
        result.previous.nickname,
        result.next.nickname,
    )
    is WhereWasIResult.OnlyPrevious -> stringResource(R.string.where_was_i_only_previous_format, result.previous.nickname)
    is WhereWasIResult.OnlyNext -> stringResource(R.string.where_was_i_only_next_format, result.next.nickname)
    WhereWasIResult.NoData -> stringResource(R.string.where_was_i_no_data)
}

@Composable
private fun EmptyHistory(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.history_empty_visits),
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.history_empty_visits_support),
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
