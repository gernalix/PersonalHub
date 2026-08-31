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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gernalix.luoghi.CHECKIN_BUTTON_TAG
import com.gernalix.luoghi.CheckInHomeState
import com.gernalix.luoghi.CheckInMessage
import com.gernalix.luoghi.HomeUiState
import com.gernalix.luoghi.R
import com.gernalix.luoghi.capsules.checkin.CheckInCandidate
import com.gernalix.luoghi.capsules.places.PlaceListUiModel
import com.gernalix.luoghi.capsules.visits.VisitUiModel
import com.gernalix.luoghi.ui.history.VisitTimelineItem
import com.gernalix.luoghi.ui.places.PlaceListItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    appVersion: String,
    onCheckAction: () -> Unit,
    onAmbiguousCheckIn: (String) -> Unit,
    onOpenPlace: (PlaceListUiModel) -> Unit,
    onEditPlace: (PlaceListUiModel) -> Unit,
    onPlaceHistory: (PlaceListUiModel) -> Unit,
    onPlaceMap: (PlaceListUiModel) -> Unit,
    onDeletePlace: (PlaceListUiModel) -> Unit,
    onGlobalMap: () -> Unit,
    onGlobalStats: () -> Unit,
    onOpenHistory: (String?) -> Unit,
    onNewPlace: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val recentVisits = state.visits.take(3)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings_title))
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
            item(key = "footer") {
                Text(
                    text = stringResource(
                        R.string.home_footer_format,
                        appVersion,
                        state.safGate.folderLabel ?: stringResource(R.string.saf_folder_unknown),
                    ),
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

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
