package com.gernalix.luoghi.ui.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gernalix.luoghi.R
import com.gernalix.luoghi.RestoreFlowPhase
import com.gernalix.luoghi.RestoreFlowOrigin
import com.gernalix.luoghi.RestoreUiError
import com.gernalix.luoghi.RestoreUiState
import com.gernalix.luoghi.backup.BackupPreview
import com.gernalix.luoghi.backup.BackupValidationCode
import java.text.DateFormat
import java.util.Date

@Composable
fun RestoreBackupDialog(
    state: RestoreUiState,
    onRestore: () -> Unit,
    onNotNow: () -> Unit,
    onChooseAnother: () -> Unit,
    onCloseStatus: () -> Unit,
) {
    if (!state.dialogVisible) return
    val blockingFirstRunError = (state.phase == RestoreFlowPhase.ERROR) &&
        (state.origin == RestoreFlowOrigin.FIRST_RUN) &&
        (state.candidate == null)
    val dismiss = when (state.phase) {
        RestoreFlowPhase.READY -> onNotNow
        RestoreFlowPhase.SUCCESS -> onCloseStatus

        RestoreFlowPhase.ERROR -> {
            if (blockingFirstRunError) ({}) else onCloseStatus
        }

        else -> ({})
    }
    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(
            dismissOnBackPress = state.phase in setOf(
                RestoreFlowPhase.READY,
                RestoreFlowPhase.SUCCESS,
                RestoreFlowPhase.ERROR,
            ),
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.restore_places_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                when (state.phase) {
                    RestoreFlowPhase.DISCOVERING -> RestoreProgress(R.string.restore_searching_backups)
                    RestoreFlowPhase.INSPECTING -> RestoreProgress(R.string.restore_inspecting_backup)
                    RestoreFlowPhase.RESTORING -> RestoreProgress(R.string.restore_in_progress)
                    RestoreFlowPhase.DEFERRING -> RestoreProgress(R.string.restore_preserving_backup)
                    RestoreFlowPhase.READY -> {
                        Text(
                            text = stringResource(R.string.restore_compatible_found),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        state.candidate?.preview?.let { RestorePreview(it) }
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = onRestore,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.restore_action))
                            }
                            OutlinedButton(
                                onClick = onChooseAnother,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.restore_choose_another))
                            }
                            TextButton(
                                onClick = onNotNow,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.restore_not_now))
                            }
                        }
                    }
                    RestoreFlowPhase.SUCCESS -> {
                        val summary = state.summary
                        Text(
                            text = if (summary == null) {
                                stringResource(R.string.restore_success)
                            } else {
                                stringResource(
                                    R.string.restore_success_summary,
                                    summary.places,
                                    summary.events,
                                    summary.activeCheckIns,
                                )
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (summary?.finalExportSucceeded == false) {
                            Text(
                                text = stringResource(R.string.restore_final_export_warning),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (!blockingFirstRunError) {
                            Button(onClick = onCloseStatus, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.close))
                            }
                        }
                    }
                    RestoreFlowPhase.ERROR -> {
                        Text(
                            text = restoreErrorText(state.error, state.validationCode),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        OutlinedButton(onClick = onChooseAnother, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.restore_choose_another))
                        }
                        Button(onClick = onCloseStatus, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.close))
                        }
                    }
                    RestoreFlowPhase.IDLE,
                    RestoreFlowPhase.DEFERRED,
                    -> Unit
                }
            }
        }
    }
}

@Composable
private fun RestoreProgress(messageRes: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        Text(stringResource(messageRes), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun RestorePreview(preview: BackupPreview) {
    val date = if (preview.exportedAtMs > 0L) {
        remember(preview.exportedAtMs) {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(preview.exportedAtMs))
        }
    } else {
        stringResource(R.string.restore_backup_date_unknown)
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PreviewRow(stringResource(R.string.restore_backup_date), date)
        PreviewRow(
            stringResource(R.string.restore_backup_version),
            stringResource(R.string.restore_backup_version_value, preview.appVersion, preview.formatVersion),
        )
        PreviewRow(stringResource(R.string.restore_backup_places), preview.placeCount.toString())
        PreviewRow(stringResource(R.string.restore_backup_events), preview.eventCount.toString())
    }
}

@Composable
private fun PreviewRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1.4f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun restoreErrorText(error: RestoreUiError?, code: BackupValidationCode?): String {
    if (error == RestoreUiError.DISCOVERY_FAILED) return stringResource(R.string.restore_discovery_failed)
    if (error == RestoreUiError.NO_COMPATIBLE_BACKUP) return stringResource(R.string.restore_no_compatible_backup)
    if (error == RestoreUiError.RESTORE_FAILED) return stringResource(R.string.restore_failed)
    if (error == RestoreUiError.PRESERVE_FAILED) return stringResource(R.string.restore_preserve_failed)
    return when (code) {
        BackupValidationCode.FILE_EMPTY,
        BackupValidationCode.EMPTY_BACKUP -> stringResource(R.string.restore_error_empty)
        BackupValidationCode.FILE_TOO_LARGE -> stringResource(R.string.restore_error_too_large)
        BackupValidationCode.UNSUPPORTED_FUTURE_FORMAT,
        BackupValidationCode.UNSUPPORTED_FUTURE_SCHEMA -> stringResource(R.string.restore_error_future_version)
        BackupValidationCode.UNSUPPORTED_OLD_SCHEMA -> stringResource(R.string.restore_error_old_version)
        BackupValidationCode.WRONG_APPLICATION -> stringResource(R.string.restore_error_wrong_app)
        BackupValidationCode.CHECKSUM_MISMATCH,
        BackupValidationCode.MANIFEST_MISMATCH -> stringResource(R.string.restore_error_checksum)
        BackupValidationCode.NOT_SQLITE,
        BackupValidationCode.CORRUPT_DATABASE -> stringResource(R.string.restore_error_corrupt)
        BackupValidationCode.INVALID_UUID,
        BackupValidationCode.FOREIGN_KEY_VIOLATION,
        BackupValidationCode.REQUIRED_DATA_MISSING -> stringResource(R.string.restore_error_inconsistent)
        BackupValidationCode.CANNOT_READ -> stringResource(R.string.restore_error_cannot_read)
        null -> stringResource(R.string.restore_error_invalid)
    }
}
