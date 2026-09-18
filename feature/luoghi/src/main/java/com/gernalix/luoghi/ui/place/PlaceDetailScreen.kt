package com.gernalix.luoghi.ui.place

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gernalix.luoghi.R
import com.gernalix.luoghi.capsules.checkin.CheckInPolicy
import com.gernalix.luoghi.capsules.places.PlaceListUiModel
import com.gernalix.luoghi.capsules.stats.PlaceStatsUi
import com.gernalix.luoghi.ui.common.lastVisitLabel
import com.gernalix.luoghi.ui.common.localizedDuration
import com.gernalix.luoghi.ui.common.localizedPercent
import com.gernalix.luoghi.ui.common.localizedTime
import com.gernalix.luoghi.ui.common.rememberMinuteNow
import com.gernalix.luoghi.ui.common.visitCount
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.core.hubcontext.HubContextLinks

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceDetailScreen(
    item: PlaceListUiModel,
    stats: PlaceStatsUi?,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onMap: () -> Unit,
    onHistory: () -> Unit,
    onCheckInNow: () -> Unit,
    onAddManualVisit: () -> Unit,
    onGeofenceSettings: () -> Unit,
    onAlerts: () -> Unit = {},
) {
    BackHandler(onBack = onBack)
    val place = item.place
    val name = place.nickname.ifBlank { stringResource(R.string.unnamed_place) }
    val nowMs = rememberMinuteNow(item.activeStartedAt != null)
    Scaffold(
        modifier = Modifier.semantics { contentDescription = "hub-detail-places/place/${place.uuid}" },
        topBar = {
            TopAppBar(
                title = {
                    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "identity") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    place.address?.takeIf { it.isNotBlank() }?.let { address ->
                        Text(
                            address,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } ?: Text(
                        stringResource(R.string.address_not_set),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    item.activeStartedAt?.let { startedAt ->
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Text(
                                stringResource(
                                    R.string.place_active_detail_format,
                                    localizedTime(startedAt),
                                    localizedDuration((nowMs - startedAt).coerceAtLeast(0L)),
                                ),
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
            item(key = "summary") {
                SectionTitle(stringResource(R.string.place_overview))
                AdaptiveMetrics(
                    metrics = listOf(
                        stringResource(R.string.total_time) to localizedDuration(item.totalTimeMs),
                        stringResource(R.string.visits_label) to visitCount(item.visitCount),
                        stringResource(R.string.last_visit) to (lastVisitLabel(item.lastVisitAt) ?: stringResource(R.string.never)),
                        stringResource(R.string.radius) to stringResource(
                            R.string.radius_value_plain_format,
                            CheckInPolicy.effectiveRadiusM(place),
                        ),
                    )
                )
            }
            item(key = "hub-context") {
                HubContextLinks(HubEntityRef("places", "place", place.uuid))
            }
            item(key = "actions") {
                SectionTitle(stringResource(R.string.actions))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Edit, contentDescription = null)
                        Text(stringResource(R.string.edit_place), modifier = Modifier.padding(start = 8.dp))
                    }
                    FilledTonalButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.History, contentDescription = null)
                        Text(stringResource(R.string.place_history), modifier = Modifier.padding(start = 8.dp))
                    }
                    FilledTonalButton(onClick = onCheckInNow, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                        Text(stringResource(R.string.place_check_in_now), modifier = Modifier.padding(start = 8.dp))
                    }
                    FilledTonalButton(onClick = onAddManualVisit, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.History, contentDescription = null)
                        Text(stringResource(R.string.place_add_past_visit), modifier = Modifier.padding(start = 8.dp))
                    }
                    FilledTonalButton(onClick = onMap, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Map, contentDescription = null)
                        Text(stringResource(R.string.show_on_map), modifier = Modifier.padding(start = 8.dp))
                    }
                    FilledTonalButton(onClick = onAlerts, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Settings, contentDescription = null)
                        Text(stringResource(R.string.place_alerts), modifier = Modifier.padding(start = 8.dp))
                    }
                    FilledTonalButton(onClick = onGeofenceSettings, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Settings, contentDescription = null)
                        Text(stringResource(R.string.geofence_settings), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            item(key = "statistics") {
                SectionTitle(stringResource(R.string.place_statistics))
                AdaptiveMetrics(
                    metrics = listOf(
                        stringResource(R.string.stat_global_share) to localizedPercent(stats?.ratioGlobalPct),
                        stringResource(R.string.stat_place_lifetime_share) to localizedPercent(stats?.ratioPlaceLifetimePct),
                    )
                )
            }
            item(key = "notes") {
                SectionTitle(stringResource(R.string.notes))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Text(
                        text = place.notes?.takeIf { it.isNotBlank() } ?: stringResource(R.string.notes_empty),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (place.notes.isNullOrBlank()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(bottom = 8.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun AdaptiveMetrics(metrics: List<Pair<String, String>>) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints {
        val useTwoColumns = maxWidth >= 320.dp && fontScale < 1.5f
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (useTwoColumns) {
                metrics.chunked(2).forEach { rowMetrics ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowMetrics.forEach { (label, value) ->
                            MetricCard(label, value, Modifier.weight(1f))
                        }
                        if (rowMetrics.size == 1) {
                            Column(modifier = Modifier.weight(1f)) {}
                        }
                    }
                }
            } else {
                metrics.forEach { (label, value) -> MetricCard(label, value, Modifier.fillMaxWidth()) }
            }
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
