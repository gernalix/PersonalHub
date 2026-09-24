package com.gernalix.personalhub.core.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gernalix.personalhub.contracts.database.SinceWhenTimestampSource
import com.gernalix.personalhub.contracts.database.SinceWhenSourceDescriptor
import com.gernalix.personalhub.contracts.database.HubDeepLinkContract
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Shared opt-in and source selector. The parent owns persistence and save/retry behavior. */
@Composable
fun SinceWhenCreationControl(
    timestampSources: List<SinceWhenTimestampSource>,
    enabled: Boolean,
    selectedSourceId: String?,
    saving: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onSourceSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (timestampSources.isEmpty()) return
    val selected = timestampSources.firstOrNull { it.id == selectedSourceId }
        ?: timestampSources.firstOrNull { it.isDefault }
        ?: timestampSources.first()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().toggleable(
                value = enabled,
                enabled = !saving,
                role = Role.Checkbox,
                onValueChange = onEnabledChange,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = enabled, onCheckedChange = null, enabled = !saving)
            Text(stringResource(R.string.since_when_create_counter), style = MaterialTheme.typography.bodyLarge)
        }
        if (enabled && timestampSources.size == 1) {
            Text(
                stringResource(R.string.since_when_starts_from, formatDate(selected.timestamp)),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else if (enabled) {
            Text(stringResource(R.string.since_when_start_from), style = MaterialTheme.typography.labelLarge)
            timestampSources.forEach { source ->
                val isSelected = source.id == selected.id
                Row(
                    modifier = Modifier.fillMaxWidth().selectable(
                        selected = isSelected,
                        enabled = !saving,
                        role = Role.RadioButton,
                        onClick = { onSourceSelected(source.id) },
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = isSelected, onClick = null, enabled = !saving)
                    Text(
                        text = "${source.label}: ${formatDate(source.timestamp)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

private fun formatDate(timestamp: Long): String = DateTimeFormatter
    .ofLocalizedDate(FormatStyle.MEDIUM)
    .withLocale(Locale.getDefault())
    .format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))

fun Context.launchSinceWhenCreate(source: SinceWhenSourceDescriptor, selectedSourceId: String?) {
    val selectedSource = source.copy(
        timestampSources = source.timestampSources.map { it.copy(isDefault = it.id == selectedSourceId) },
    )
    startActivity(Intent(Intent.ACTION_VIEW, HubDeepLinkContract.sinceWhenCreateUri(selectedSource, startEnabled = true)).apply {
        if (this@launchSinceWhenCreate !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}
