// v435
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.multitimetracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.multitimetracker.R

/**
 * Global Settings dialog, opened from the navigation drawer.
 */
@Composable
fun AppSettingsDialog(
    keepScreenOn: Boolean,
    onKeepScreenOnChange: (Boolean) -> Unit,
    showSeconds: Boolean,
    onShowSecondsChange: (Boolean) -> Unit,
    hideHoursIfZero: Boolean,
    onHideHoursIfZeroChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cd_settings)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingRow(
                    title = stringResource(R.string.keep_screen_on),
                    checked = keepScreenOn,
                    onCheckedChange = onKeepScreenOnChange
                )
                SettingRow(
                    title = stringResource(R.string.show_seconds),
                    checked = showSeconds,
                    onCheckedChange = onShowSecondsChange
                )
                SettingRow(
                    title = stringResource(R.string.hide_hours_if_zero),
                    checked = hideHoursIfZero,
                    onCheckedChange = onHideHoursIfZeroChange
                )

            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ok))
            }
        }
    )
}

@Composable
private fun SettingRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
