package com.gernalix.luoghi.ui.places

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gernalix.luoghi.R
import com.gernalix.luoghi.capsules.checkin.CheckInPolicy
import com.gernalix.luoghi.capsules.places.PlaceListUiModel
import com.gernalix.luoghi.ui.common.localizedDuration
import com.gernalix.luoghi.ui.common.localizedTime
import com.gernalix.luoghi.ui.common.rememberMinuteNow
import com.gernalix.luoghi.ui.common.visitCount

@Composable
fun PlaceListItem(
    item: PlaceListUiModel,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onHistory: () -> Unit,
    onMap: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val place = item.place
    val displayName = place.nickname.ifBlank { stringResource(R.string.unnamed_place) }
    val address = place.address?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.address_not_set)
    val activeNow = rememberMinuteNow(item.activeStartedAt != null)
    val activeDuration = item.activeStartedAt?.let { localizedDuration((activeNow - it).coerceAtLeast(0L)) }
    val totalDuration = localizedDuration(item.totalTimeMs)
    val visits = visitCount(item.visitCount)
    val radius = stringResource(R.string.radius_compact_format, CheckInPolicy.effectiveRadiusM(place))
    val distance = item.distanceMeters?.let {
        stringResource(R.string.place_distance_format, com.gernalix.luoghi.ui.common.localizedDistance(it))
    }
    val metadata = if (item.activeStartedAt != null) {
        stringResource(
            R.string.place_active_meta_format,
            localizedTime(item.activeStartedAt),
            activeDuration.orEmpty(),
        )
    } else {
        listOf(stringResource(R.string.place_meta_format, totalDuration, visits, radius), distance)
            .filterNotNull()
            .joinToString(" · ")
    }
    val accessibility = if (item.activeStartedAt != null) {
        stringResource(R.string.place_accessibility_active_format, displayName, address, metadata)
    } else {
        stringResource(R.string.place_accessibility_format, displayName, address, metadata)
    }
    Card(
        onClick = onOpen,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .semantics(mergeDescendants = true) { contentDescription = accessibility },
        colors = CardDefaults.cardColors(
            containerColor = if (item.activeStartedAt != null) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = displayName,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.activeStartedAt != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                text = stringResource(R.string.here_now),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                Text(
                    text = address,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = metadata,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (item.activeStartedAt != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PlaceOverflowMenu(
                displayName = displayName,
                onEdit = onEdit,
                onHistory = onHistory,
                onMap = onMap,
                onDelete = onDelete,
            )
        }
    }
}

@Composable
private fun PlaceOverflowMenu(
    displayName: String,
    onEdit: () -> Unit,
    onHistory: () -> Unit,
    onMap: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = stringResource(R.string.place_actions_format, displayName),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.edit_place)) },
                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                onClick = {
                    expanded = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.place_history)) },
                leadingIcon = { Icon(Icons.Outlined.History, contentDescription = null) },
                onClick = {
                    expanded = false
                    onHistory()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.map)) },
                leadingIcon = { Icon(Icons.Outlined.Map, contentDescription = null) },
                onClick = {
                    expanded = false
                    onMap()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}
