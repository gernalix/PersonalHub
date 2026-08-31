package com.supercontacts.app.ui.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.supercontacts.app.R
import com.supercontacts.app.data.backup.BackupState
import com.supercontacts.app.ui.contacts.ContactTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsScreen(
    snackbarHostState: SnackbarHostState,
    backupState: BackupState,
    errorMessage: String?,
    onBack: () -> Unit,
    onErrorDismiss: () -> Unit,
    onPickFolder: () -> Unit,
    onToggleAutoExport: (Boolean) -> Unit,
    onManualExport: () -> Unit,
    onImportBackup: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_settings)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BackupErrorCard(errorMessage, onErrorDismiss)

            BackupFolderCard(
                backupState = backupState,
                onPickFolder = onPickFolder,
            )

            BackupActionsCard(
                backupState = backupState,
                onToggleAutoExport = onToggleAutoExport,
                onManualExport = onManualExport,
                onImportBackup = onImportBackup,
            )
        }
    }
}

@Composable
private fun BackupErrorCard(
    message: String?,
    onDismiss: () -> Unit,
) {
    if (message == null) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dismiss))
            }
        }
    }
}

@Composable
private fun BackupFolderCard(
    backupState: BackupState,
    onPickFolder: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.backup_folder),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = backupState.folderLabel ?: stringResource(R.string.backup_status_missing),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = if (backupState.isConfigured && backupState.isAccessible) {
                    stringResource(R.string.backup_status_ready)
                } else {
                    stringResource(R.string.backup_status_missing)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.backup_single_file_hint, backupState.backupFileName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onPickFolder) {
                Text(stringResource(R.string.backup_pick_folder))
            }
        }
    }
}

@Composable
private fun BackupActionsCard(
    backupState: BackupState,
    onToggleAutoExport: (Boolean) -> Unit,
    onManualExport: () -> Unit,
    onImportBackup: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.backup_auto_export),
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = backupState.autoExportEnabled,
                    onCheckedChange = onToggleAutoExport,
                    enabled = !backupState.isBusy,
                )
            }

            backupState.lastExportAt?.let { lastExportAt ->
                Text(
                    text = stringResource(
                        R.string.backup_last_export,
                        ContactTimeFormatter.formatDateTime(lastExportAt),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            backupState.lastError?.let { lastError ->
                Text(
                    text = stringResource(R.string.backup_last_error, lastError),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (backupState.isBusy) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 4.dp))
                }
                Button(
                    onClick = onManualExport,
                    enabled = !backupState.isBusy,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(stringResource(R.string.backup_manual_export))
                }
                Button(
                    onClick = onImportBackup,
                    enabled = !backupState.isBusy,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(stringResource(R.string.backup_import))
                }
            }
        }
    }
}
