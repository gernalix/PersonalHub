package com.gernalix.luoghi.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gernalix.personalhub.contracts.database.DataExplorerContract
import com.gernalix.luoghi.CHECKIN_BUTTON_TAG
import com.gernalix.luoghi.CheckInHomeState
import com.gernalix.luoghi.CheckInMessage
import com.gernalix.luoghi.HomeUiState
import com.gernalix.luoghi.R
import com.gernalix.luoghi.capsules.checkin.CheckInCandidate
import com.gernalix.luoghi.capsules.places.PlaceListUiModel
import com.gernalix.luoghi.capsules.places.PlaceSortCriterion
import com.gernalix.luoghi.capsules.places.PlaceSortDirection
import com.gernalix.luoghi.capsules.places.PlaceSortState
import com.gernalix.luoghi.capsules.visits.VisitUiModel
import com.gernalix.luoghi.data.CheckInAttemptCandidateEntity
import com.gernalix.luoghi.data.CheckInAttemptDiagnostic
import com.gernalix.luoghi.ui.history.VisitTimelineItem
import com.gernalix.luoghi.ui.places.PlaceListItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onCheckAction: () -> Unit,
    onAmbiguousCheckIn: (String) -> Unit,
    onOpenPlace: (PlaceListUiModel) -> Unit,
    onEditPlace: (PlaceListUiModel) -> Unit,
    onPlaceHistory: (PlaceListUiModel) -> Unit,
    onPlaceMap: (PlaceListUiModel) -> Unit,
    onDeletePlace: (PlaceListUiModel) -> Unit,
    onSortPlaces: (PlaceSortCriterion, PlaceSortDirection) -> Unit,
    onRefreshLocation: () -> Unit,
    onGlobalMap: () -> Unit,
    onGlobalStats: () -> Unit,
    onOpenHistory: (String?) -> Unit,
    onNewPlace: () -> Unit,
) {
    val context = LocalContext.current
    val recentVisits = state.visits.take(3)
    var diagnosticsOpen by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    if (state.checkIn.recentAttempts.isNotEmpty()) {
                        IconButton(onClick = { diagnosticsOpen = true }) {
                            Icon(
                                Icons.Outlined.BugReport,
                                contentDescription = stringResource(R.string.checkin_diagnostics_open),
                            )
                        }
                    }
                    IconButton(
                        onClick = { context.startActivity(DataExplorerContract.intent(context.packageName, "places")) },
                    ) {
                        Icon(Icons.Outlined.Storage, contentDescription = "Datasette")
                    }
                    IconButton(onClick = onNewPlace) {
                        Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.new_place))
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "check-in") {
                CheckInPanel(
                    checkIn = state.checkIn,
                    onCheckAction = onCheckAction,
                    onAmbiguousCheckIn = onAmbiguousCheckIn,
                )
            }
            item(key = "global-actions") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        enabled = state.places.isNotEmpty(),
                        onClick = onGlobalMap,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Map, contentDescription = null)
                        Text(stringResource(R.string.map), modifier = Modifier.padding(start = 8.dp))
                    }
                    FilledTonalButton(onClick = onGlobalStats, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.BarChart, contentDescription = null)
                        Text(stringResource(R.string.statistics), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            item(key = "places-title") {
                SectionHeader(
                    title = stringResource(R.string.places),
                    action = stringResource(R.string.places_count_format, state.placeItems.size),
                )
            }
            item(key = "places-sort") {
                PlacesSortBar(
                    sort = state.placeSort,
                    locationUnavailable = state.listLocationUnavailable,
                    onSortPlaces = onSortPlaces,
                    onRefreshLocation = onRefreshLocation,
                )
            }
            if (state.placeItems.isEmpty()) {
                item(key = "places-empty") {
                    Text(
                        stringResource(R.string.saved_places_empty),
                        modifier = Modifier.padding(vertical = 20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(state.placeItems, key = { it.place.uuid }) { item ->
                    PlaceListItem(
                        item = item,
                        onOpen = { onOpenPlace(item) },
                        onEdit = { onEditPlace(item) },
                        onHistory = { onPlaceHistory(item) },
                        onMap = { onPlaceMap(item) },
                        onDelete = { onDeletePlace(item) },
                    )
                }
            }
            item(key = "history-title") {
                SectionHeader(
                    title = stringResource(R.string.recent_visits),
                    action = null,
                )
            }
            if (recentVisits.isEmpty()) {
                item(key = "history-empty") {
                    Text(
                        stringResource(R.string.history_empty_visits),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(recentVisits, key = { "recent-${it.stableId}" }) { visit: VisitUiModel ->
                    VisitTimelineItem(
                        visit = visit,
                        onClick = { onOpenHistory(visit.stableId) },
                        showAddress = false,
                    )
                }
            }
            item(key = "history-all") {
                TextButton(onClick = { onOpenHistory(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.view_all_history))
                }
            }
        }
    }
    if (diagnosticsOpen) {
        AlertDialog(
            onDismissRequest = { diagnosticsOpen = false },
            title = { Text(stringResource(R.string.checkin_diagnostics_title)) },
            text = { CheckInDiagnosticsContent(state.checkIn.recentAttempts) },
            confirmButton = {
                TextButton(onClick = { diagnosticsOpen = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
}

@Composable
private fun CheckInDiagnosticsContent(attempts: List<CheckInAttemptDiagnostic>) {
    val clipboard = LocalClipboardManager.current
    var outcomeFilter by remember { mutableStateOf("") }
    var placeFilter by remember { mutableStateOf("") }
    val filteredAttempts = filteredDiagnosticAttempts(attempts, outcomeFilter, placeFilter)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = outcomeFilter,
                    onValueChange = { outcomeFilter = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text(stringResource(R.string.checkin_diagnostics_outcome_filter)) },
                )
                OutlinedTextField(
                    value = placeFilter,
                    onValueChange = { placeFilter = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text(stringResource(R.string.checkin_diagnostics_place_filter)) },
                )
            }
        filteredAttempts.take(5).forEach { diagnostic ->
                val attempt = diagnostic.attempt
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(
                                R.string.checkin_diagnostics_row_format,
                                attempt.outcome,
                                attempt.placeName ?: attempt.errorCode ?: stringResource(R.string.checkin_diagnostics_no_place),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            diagnosticDetail(attempt, diagnostic.candidates),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { clipboard.setText(AnnotatedString(diagnosticReport(diagnostic))) }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.checkin_diagnostics_copy))
                    }
                }
        }
    }
}

@Composable
private fun diagnosticDetail(
    attempt: com.gernalix.luoghi.data.CheckInAttemptWithPlaceName,
    candidates: List<CheckInAttemptCandidateEntity>,
): String {
    val accuracy = attempt.accuracyM?.let { stringResource(R.string.checkin_diagnostics_accuracy_format, it) }
    return listOfNotNull(
        com.gernalix.luoghi.ui.common.localizedTime(attempt.startedAt),
        accuracy,
        attempt.errorMessage,
        candidateSummary(candidates).takeIf { it.isNotBlank() },
    ).joinToString(" - ")
}

fun filteredDiagnosticAttempts(
    attempts: List<CheckInAttemptDiagnostic>,
    outcomeFilter: String,
    placeFilter: String,
): List<CheckInAttemptDiagnostic> {
    val outcomeNeedle = outcomeFilter.trim().lowercase()
    val placeNeedle = placeFilter.trim().lowercase()
    return attempts.filter { diagnostic ->
        val attempt = diagnostic.attempt
        val outcomeMatches = outcomeNeedle.isBlank() || attempt.outcome.lowercase().contains(outcomeNeedle)
        val placeValues = buildList {
            add(attempt.placeName.orEmpty())
            add(attempt.selectedPlaceId.orEmpty())
            add(attempt.matchedPlaceId.orEmpty())
            diagnostic.candidates.forEach { candidate ->
                add(candidate.placeNameSnapshot.orEmpty())
                add(candidate.placeId)
            }
        }
        val placeMatches = placeNeedle.isBlank() || placeValues.any { it.lowercase().contains(placeNeedle) }
        outcomeMatches && placeMatches
    }
}

fun diagnosticReport(diagnostic: CheckInAttemptDiagnostic): String {
    val attempt = diagnostic.attempt
    return buildString {
        appendLine("attempt=${attempt.id}")
        appendLine("outcome=${attempt.outcome}")
        appendLine("stage=${attempt.stage}")
        appendLine("startedAt=${attempt.startedAt}")
        appendLine("finishedAt=${attempt.finishedAt}")
        appendLine("place=${attempt.placeName ?: attempt.selectedPlaceId ?: attempt.matchedPlaceId ?: ""}")
        appendLine("lat=${attempt.lat ?: ""} lon=${attempt.lon ?: ""} accuracyM=${attempt.accuracyM ?: ""}")
        appendLine("error=${attempt.errorCode ?: ""} ${attempt.errorMessage ?: ""}".trim())
        appendLine("candidates=${candidateSummary(diagnostic.candidates)}")
    }
}

fun candidateSummary(candidates: List<CheckInAttemptCandidateEntity>): String =
    candidates.sortedWith(compareBy<CheckInAttemptCandidateEntity> { it.rank }.thenBy { it.distanceM }).joinToString("; ") { candidate ->
        val name = candidate.placeNameSnapshot?.takeIf { it.isNotBlank() } ?: candidate.placeId
        "$name distanceM=${candidate.distanceM} thresholdM=${candidate.thresholdM} rank=${candidate.rank} result=${candidate.result}"
    }

@Composable
private fun PlacesSortBar(
    sort: PlaceSortState,
    locationUnavailable: Boolean,
    onSortPlaces: (PlaceSortCriterion, PlaceSortDirection) -> Unit,
    onRefreshLocation: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            SortChip(R.string.sort_distance, sort, PlaceSortCriterion.DISTANCE, onSortPlaces, Modifier.weight(1f))
            SortChip(R.string.sort_last_visit, sort, PlaceSortCriterion.LAST_VISIT, onSortPlaces, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            SortChip(R.string.sort_total_time, sort, PlaceSortCriterion.TOTAL_TIME, onSortPlaces, Modifier.weight(1f))
            SortChip(R.string.sort_visit_count, sort, PlaceSortCriterion.VISIT_COUNT, onSortPlaces, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                onSortPlaces(sort.criterion, sort.direction.toggle())
            }) {
                Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = null)
                Text(
                    stringResource(if (sort.direction == PlaceSortDirection.ASC) R.string.sort_ascending else R.string.sort_descending),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            TextButton(onClick = onRefreshLocation) {
                Text(stringResource(R.string.sort_refresh_location))
            }
        }
        if (locationUnavailable && sort.criterion == PlaceSortCriterion.DISTANCE) {
            Text(
                stringResource(R.string.sort_distance_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SortChip(
    labelRes: Int,
    sort: PlaceSortState,
    criterion: PlaceSortCriterion,
    onSortPlaces: (PlaceSortCriterion, PlaceSortDirection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = sort.criterion == criterion
    val onClick = {
        val direction = if (selected) sort.direction.toggle() else defaultDirection(criterion)
        onSortPlaces(criterion, direction)
    }
    if (selected) {
        FilledTonalButton(onClick = onClick, modifier = modifier) { Text(stringResource(labelRes)) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(stringResource(labelRes)) }
    }
}

private fun PlaceSortDirection.toggle(): PlaceSortDirection =
    if (this == PlaceSortDirection.ASC) PlaceSortDirection.DESC else PlaceSortDirection.ASC

private fun defaultDirection(criterion: PlaceSortCriterion): PlaceSortDirection =
    if (criterion == PlaceSortCriterion.DISTANCE) PlaceSortDirection.ASC else PlaceSortDirection.DESC

@Composable
private fun SectionHeader(title: String, action: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        action?.let {
            Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun CheckInPanel(
    checkIn: CheckInHomeState,
    onCheckAction: () -> Unit,
    onAmbiguousCheckIn: (String) -> Unit,
) {
    val active = checkIn.activeVisit
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (active == null) {
                    stringResource(R.string.checkin_not_checked_in)
                } else {
                    stringResource(R.string.checkin_checked_in_format, active.placeName, com.gernalix.luoghi.ui.common.localizedTime(active.sinceMs))
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            CheckInMessageText(checkIn)
            Button(
                enabled = !checkIn.working,
                onClick = onCheckAction,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(CHECKIN_BUTTON_TAG),
                colors = if (active == null) {
                    ButtonDefaults.buttonColors()
                } else {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                },
            ) {
                Text(
                    when {
                        checkIn.working -> stringResource(R.string.checkin_working)
                        active == null -> stringResource(R.string.checkin_button)
                        else -> stringResource(R.string.checkout_button)
                    }
                )
            }
            if (checkIn.ambiguousCandidates.isNotEmpty()) {
                Text(stringResource(R.string.checkin_choose_place), fontWeight = FontWeight.SemiBold)
                checkIn.ambiguousCandidates.forEach { candidate: CheckInCandidate ->
                    OutlinedButton(
                        onClick = { onAmbiguousCheckIn(candidate.place.uuid) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.checkin_candidate_format, candidate.place.nickname, candidate.distanceM))
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckInMessageText(checkIn: CheckInHomeState) {
    val message = checkIn.message ?: return
    val placeName = checkIn.messagePlaceName.orEmpty()
    Text(
        when (message) {
            CheckInMessage.LOCATION_PERMISSION_DENIED -> stringResource(R.string.checkin_permission_denied)
            CheckInMessage.LOCATION_UNAVAILABLE -> stringResource(R.string.checkin_location_unavailable)
            CheckInMessage.CHECKED_IN -> stringResource(R.string.checkin_message_checked_in_format, placeName)
            CheckInMessage.CHECKED_OUT -> stringResource(R.string.checkin_message_checked_out_format, placeName)
            CheckInMessage.UNKNOWN_PLACE -> stringResource(R.string.checkin_unknown_place)
            CheckInMessage.AMBIGUOUS_PLACE -> stringResource(R.string.checkin_ambiguous_place)
        },
        style = MaterialTheme.typography.bodyMedium,
    )
}
