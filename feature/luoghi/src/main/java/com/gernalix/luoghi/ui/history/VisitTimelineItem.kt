package com.gernalix.luoghi.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gernalix.luoghi.R
import com.gernalix.luoghi.capsules.visits.VisitPairingStatus
import com.gernalix.luoghi.capsules.visits.VisitUiModel
import com.gernalix.luoghi.ui.common.localizedDuration
import com.gernalix.luoghi.ui.common.localizedTime
import com.gernalix.luoghi.ui.common.rememberMinuteNow

@Composable
fun VisitTimelineItem(
    visit: VisitUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showAddress: Boolean = true,
) {
    val placeName = visit.placeName.ifBlank { stringResource(R.string.place_unavailable) }
    val nowMs = rememberMinuteNow(visit.isActive)
    val duration = localizedDuration(
        if (visit.isActive && visit.startedAt != null) {
            (nowMs - visit.startedAt).coerceAtLeast(0L)
        } else {
            visit.durationMs
        }
    )
    val interval = visitInterval(visit)
    val status = visitStatus(visit)
    val accessibility = stringResource(
        R.string.visit_accessibility_format,
        placeName,
        interval,
        duration,
        status,
    )
    val largeText = LocalDensity.current.fontScale >= 1.5f

    Row(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.width(28.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .heightIn(min = 104.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            Box(
                modifier = Modifier
                    .padding(top = 22.dp)
                    .size(if (visit.isActive) 12.dp else 9.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            visit.isActive -> MaterialTheme.colorScheme.primary
                            visit.isAnomalous -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.outline
                        }
                    )
            )
        }
        Card(
            onClick = onClick,
            modifier = Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) { contentDescription = accessibility },
            colors = CardDefaults.cardColors(
                containerColor = if (visit.isActive) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                }
            ),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (largeText) {
                    Text(
                        text = placeName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    VisitStatusBadge(visit = visit, duration = duration)
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = placeName,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        VisitStatusBadge(visit = visit, duration = duration)
                    }
                }
                Text(
                    text = interval,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (visit.isActive) {
                    Text(
                        text = stringResource(R.string.active_duration_format, duration),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (visit.isAnomalous && !visit.isActive) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.Medium,
                    )
                }
                if (showAddress) {
                    visit.address?.let { address ->
                        Text(
                            text = address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VisitStatusBadge(visit: VisitUiModel, duration: String) {
    Surface(
        color = when {
            visit.isActive -> MaterialTheme.colorScheme.primary
            visit.isAnomalous -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = when {
            visit.isActive -> MaterialTheme.colorScheme.onPrimary
            visit.isAnomalous -> MaterialTheme.colorScheme.onTertiaryContainer
            else -> MaterialTheme.colorScheme.onSecondaryContainer
        },
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = if (visit.isActive) stringResource(R.string.in_progress) else duration,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun visitInterval(visit: VisitUiModel): String = when {
    visit.startedAt != null && visit.endedAt != null -> stringResource(
        R.string.visit_interval_format,
        localizedTime(visit.startedAt),
        localizedTime(visit.endedAt),
    )
    visit.startedAt != null -> stringResource(
        R.string.visit_interval_active_format,
        localizedTime(visit.startedAt),
    )
    visit.endedAt != null -> stringResource(
        R.string.visit_orphan_checkout_format,
        localizedTime(visit.endedAt),
    )
    else -> stringResource(R.string.visit_time_unavailable)
}

@Composable
private fun visitStatus(visit: VisitUiModel): String = when (visit.pairingStatus) {
    VisitPairingStatus.PAIRED -> stringResource(R.string.visit_complete)
    VisitPairingStatus.ACTIVE -> stringResource(R.string.in_progress)
    VisitPairingStatus.INCOMPLETE -> stringResource(R.string.visit_missing_checkout)
    VisitPairingStatus.ORPHAN_CHECK_OUT -> stringResource(R.string.visit_orphan_checkout)
    VisitPairingStatus.ANOMALOUS -> stringResource(R.string.visit_data_to_review)
}
