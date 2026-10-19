package com.gernalix.luoghi.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.saveable.rememberSaveable
import com.gernalix.luoghi.ui.visits.WhereWasIDialog
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import android.content.ClipData
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gernalix.luoghi.CHECKIN_BUTTON_TAG
import com.gernalix.luoghi.CheckInHomeState
import com.gernalix.luoghi.CheckInMessage
import com.gernalix.luoghi.HomeUiState
import com.gernalix.luoghi.R
import kotlinx.coroutines.launch
import com.gernalix.luoghi.capsules.checkin.CheckInCandidate
import com.gernalix.luoghi.capsules.places.PlaceListUiModel
import com.gernalix.luoghi.capsules.places.PlaceSortCriterion
import com.gernalix.luoghi.capsules.places.PlaceSortDirection
import com.gernalix.luoghi.capsules.places.PlaceSortState
import com.gernalix.luoghi.capsules.visits.VisitUiModel
import com.gernalix.luoghi.data.CheckInAttemptCandidateEntity
import com.gernalix.luoghi.data.CheckInAttemptDiagnostic
import com.gernalix.luoghi.ui.visits.VisitTimelineItem
import com.gernalix.luoghi.ui.places.PlaceListItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onCheckAction: () -> Unit,
    onAmbiguousCheckIn: (String) -> Unit,
    onCancelAmbiguousCheckIn: () -> Unit,
    onOpenPlace: (PlaceListUiModel) -> Unit,
    onEditPlace: (PlaceListUiModel) -> Unit,
    onPlaceVisits: (PlaceListUiModel) -> Unit,
    onPlaceMap: (PlaceListUiModel) -> Unit,
    onDeletePlace: (PlaceListUiModel) -> Unit,
    onSortPlaces: (PlaceSortCriterion, PlaceSortDirection) -> Unit,
    onPlaceQueryChange: (String) -> Unit,
    onRefreshLocation: () -> Unit,
    onToggleTagFilter: (String) -> Unit,
    onExplore: () -> Unit,
    onGlobalMap: () -> Unit,
    onGlobalStats: () -> Unit,
    onHistorySearch: () -> Unit,
    onOpenVisits: (String?) -> Unit,
    onNewPlace: () -> Unit,
) {
    val context = LocalContext.current
    val recentVisits = state.visits.take(3)
    // Echo edits synchronously. The derived Room/statistics flow can acknowledge
    // them later; feeding its older value back to the IME can discard an edit.
    // Keep this outside the lazy item so scrolling cannot discard the draft.
    var placeQueryDraft by rememberSaveable { mutableStateOf(state.placeQuery) }
    LaunchedEffect(Unit) { onPlaceQueryChange(placeQueryDraft) }
    var diagnosticsOpen by remember { mutableStateOf(false) }
    var whereWasIOpen by rememberSaveable { mutableStateOf(false) }
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
                    IconButton(onClick = onHistorySearch) {
                        Icon(
                            Icons.Outlined.Search,
                            contentDescription = stringResource(R.string.history_search),
                        )
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
                .testTag("places-home-list")
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
                    onCancelAmbiguousCheckIn = onCancelAmbiguousCheckIn,
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
            item(key = "where-was-i") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onExplore, modifier = Modifier.weight(1f).testTag("places-explore")) {
                    Text(stringResource(R.string.places_explore_action))
                }
                OutlinedButton(
                    onClick = { whereWasIOpen = true },
                    modifier = Modifier.weight(1f).testTag("places-where-was-i"),
                ) { Text(stringResource(R.string.where_was_i)) }
                }
            }
            item(key = "places-query") {
                com.gernalix.personalhub.core.ui.HubSearchField(
                    value = placeQueryDraft,
                    onValueChange = { value ->
                        placeQueryDraft = value
                        onPlaceQueryChange(value)
                    },
                    label = stringResource(R.string.places_search_hint),
                    modifier = Modifier.fillMaxWidth().testTag("places-live-search"),
                )
            }
            item(key = "places-title") {
                SectionHeader(
                    title = stringResource(R.string.places),
                    action = stringResource(R.string.places_count_format, state.placeItems.size),
                    actionTestTag = "places-count",
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
            if (state.placeTags.isNotEmpty()) item(key = "places-tag-filters") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.placeTags.filterNot { it.archived }.forEach { tag ->
                        FilterChip(
                            selected = tag.id in state.selectedPlaceTagIds,
                            onClick = { onToggleTagFilter(tag.id) },
                            label = { Text(tag.name) },
                        )
                    }
                }
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
                        showDistance = state.placeSort.criterion == PlaceSortCriterion.DISTANCE,
                        onOpen = { onOpenPlace(item) },
                        onEdit = { onEditPlace(item) },
                        onHistory = { onPlaceVisits(item) },
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
                        onClick = { onOpenVisits(visit.stableId) },
                        showAddress = false,
                    )
                }
            }
            item(key = "history-all") {
                TextButton(onClick = { onOpenVisits(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.view_all_history))
                }
            }
        }
    }
    if (whereWasIOpen) {
        WhereWasIDialog(state.visits, state.places, onDismiss = { whereWasIOpen = false })
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
    val clipboard = LocalClipboard.current
    val clipboardScope = androidx.compose.runtime.rememberCoroutineScope()
    var outcomeFilter by remember { mutableStateOf("") }
    var placeFilter by remember { mutableStateOf("") }
    val filteredAttempts = filteredDiagnosticAttempts(attempts, outcomeFilter, placeFilter)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                com.gernalix.personalhub.core.ui.HubSearchField(
                    value = outcomeFilter,
                    onValueChange = { outcomeFilter = it },
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.checkin_diagnostics_outcome_filter),
                )
                com.gernalix.personalhub.core.ui.HubSearchField(
                    value = placeFilter,
                    onValueChange = { placeFilter = it },
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.checkin_diagnostics_place_filter),
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
                    IconButton(onClick = { clipboardScope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("plain text", diagnosticReport(diagnostic)))) } }) {
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
    var helpOpen by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.places_sort_help), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { helpOpen = true }) { Text(stringResource(R.string.places_sort_about)) }
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
    if (helpOpen) AlertDialog(
        onDismissRequest = { helpOpen = false },
        title = { Text(stringResource(R.string.places_sort_about)) },
        text = { Text(stringResource(R.string.places_sort_explanation)) },
        confirmButton = { TextButton(onClick = { helpOpen = false }) { Text(stringResource(android.R.string.ok)) } },
    )
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
private fun SectionHeader(title: String, action: String?, actionTestTag: String? = null) {
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
            Text(it, modifier = actionTestTag?.let { tag -> Modifier.testTag(tag) } ?: Modifier,
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun CheckInPanel(
    checkIn: CheckInHomeState,
    onCheckAction: () -> Unit,
    onAmbiguousCheckIn: (String) -> Unit,
    onCancelAmbiguousCheckIn: () -> Unit,
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
        }
    }
    if (checkIn.ambiguousCandidates.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { if (!checkIn.working) onCancelAmbiguousCheckIn() },
            title = { Text(stringResource(R.string.checkin_choose_place)) },
            text = {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp).testTag("places-checkin-choices"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(checkIn.ambiguousCandidates, key = { it.place.uuid }) { candidate ->
                        OutlinedButton(
                            enabled = !checkIn.working,
                            onClick = { onAmbiguousCheckIn(candidate.place.uuid) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.checkin_candidate_format, candidate.place.nickname, candidate.distanceM))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(enabled = !checkIn.working, onClick = onCancelAmbiguousCheckIn) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
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
