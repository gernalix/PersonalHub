package com.gernalix.luoghi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gernalix.luoghi.capsules.routedistance.RouteDistanceMethod
import com.gernalix.luoghi.capsules.routedistance.RouteDistancePeriodUi
import com.gernalix.luoghi.capsules.routedistance.RouteDistanceStatsUi
import com.gernalix.luoghi.ui.common.localizedDuration
import com.gernalix.luoghi.ui.common.localizedPercent
import java.text.NumberFormat
import java.util.Locale

@Composable
fun GlobalStatsDialog(
    state: HomeUiState,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.global_stats_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.global_total_tracked_format, localizedDuration(state.stats.global.totalTrackedTimeMs)))
                Text(stringResource(R.string.global_ratio_tracked_format, localizedPercent(state.stats.global.ratioTrackedGlobalPct)))
                Text(stringResource(R.string.global_checkins_format, state.stats.global.totalCheckIns))
                RouteDistanceStatsSection(state.routeDistances)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
fun RouteDistanceStatsSection(routeDistances: RouteDistanceStatsUi) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.distance_traveled_title),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag(DISTANCE_TITLE_TAG),
        )
        if (routeDistances.loading) {
            Text(stringResource(R.string.distance_loading))
            return
        }
        RouteDistancePeriodRow(
            label = stringResource(R.string.distance_today_label),
            period = routeDistances.today,
            modifier = Modifier.testTag(DISTANCE_TODAY_TAG),
        )
        RouteDistancePeriodRow(stringResource(R.string.distance_week_label), routeDistances.week)
        RouteDistancePeriodRow(stringResource(R.string.distance_month_label), routeDistances.month)
        Text(
            stringResource(R.string.distance_method_format, routeDistanceMethodText(routeDistances.today.method)),
            modifier = Modifier.testTag(DISTANCE_METHOD_TAG),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RouteDistancePeriodRow(
    label: String,
    period: RouteDistancePeriodUi,
    modifier: Modifier = Modifier,
) {
    Text(stringResource(R.string.distance_period_format, label, localizedDistance(period.distanceMeters)), modifier = modifier)
}

@Composable
private fun localizedDistance(distanceMeters: Long): String {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val formatter = remember(locale) { NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 } }
    return if (distanceMeters >= 1_000L) {
        stringResource(R.string.distance_kilometers_format, formatter.format(distanceMeters / 1_000.0))
    } else {
        stringResource(R.string.distance_meters_format, formatter.format(distanceMeters.coerceAtLeast(0L)))
    }
}

@Composable
private fun routeDistanceMethodText(method: RouteDistanceMethod): String = when (method) {
    RouteDistanceMethod.GOOGLE_BICYCLE,
    RouteDistanceMethod.INVERSE_GOOGLE_BICYCLE -> stringResource(R.string.distance_method_google_bicycle)
    RouteDistanceMethod.MIXED -> stringResource(R.string.distance_method_mixed)
    RouteDistanceMethod.HAVERSINE_FALLBACK,
    RouteDistanceMethod.ZERO,
    RouteDistanceMethod.UNAVAILABLE -> stringResource(R.string.distance_method_haversine_fallback)
}
