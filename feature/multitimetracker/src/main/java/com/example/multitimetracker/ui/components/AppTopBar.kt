package com.example.multitimetracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.multitimetracker.R
import com.example.multitimetracker.persistence.MultiDbVaults
import com.example.multitimetracker.persistence.SyncStatusStore
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    titleContent: (@Composable (() -> Unit))? = null,
    navigationIcon: (@Composable (() -> Unit))? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val openAppMenu = LocalOpenAppMenu.current
    CenterAlignedTopAppBar(
        title = {
            if (titleContent != null) {
                titleContent()
            } else {
                TitleWithVaultIndicator(title = title)
            }
        },
        navigationIcon = {
            when {
                navigationIcon != null -> navigationIcon()
                openAppMenu != null -> {
                    IconButton(onClick = openAppMenu) {
                        Icon(
                            imageVector = Icons.Filled.Menu,
                            contentDescription = stringResource(R.string.cd_open_app_menu)
                        )
                    }
                }
            }
        },
        actions = {
            SyncStatusIndicator()
            actions()
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = modifier
    )
}

@Composable
private fun SyncStatusIndicator() {
    val context = LocalContext.current
    val status by produceState(initialValue = SyncStatusStore.read(context), context) {
        while (true) {
            value = SyncStatusStore.read(context)
            delay(1_000)
        }
    }
    var showDialog by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .padding(end = 4.dp)
            .clickable { showDialog = true }
    ) {
        Text(
            text = status.visualState.symbol,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
    if (showDialog) {
        SyncStatusDialog(
            status = status,
            onDismiss = { showDialog = false }
        )
    }
}

@Composable
private fun SyncStatusDialog(
    status: SyncStatusStore.Snapshot,
    onDismiss: () -> Unit,
) {
    val formatter = remember {
        DateTimeFormatter.ofPattern("d/M/yy - HH:mm").withZone(ZoneId.systemDefault())
    }
    val dash = stringResource(R.string.placeholder_dash)
    fun format(ms: Long): String {
        if (ms <= 0L) return dash
        return formatter.format(Instant.ofEpochMilli(ms))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_status_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SyncStatusRow(stringResource(R.string.sync_status_db_mutation), format(status.lastDatabaseMutationAtMs))
                SyncStatusRow(stringResource(R.string.sync_status_successful_export), format(status.lastSuccessfulExportAtMs))
                SyncStatusRow(stringResource(R.string.sync_status_export_attempt), format(status.lastExportAttemptAtMs))
                SyncStatusRow(stringResource(R.string.sync_status_export_state), status.lastExportStatus.name)
                SyncStatusRow(stringResource(R.string.sync_status_last_error), status.lastExportError ?: stringResource(R.string.placeholder_dash))
                SyncStatusRow(stringResource(R.string.sync_status_saf_file), status.lastExportFile ?: stringResource(R.string.placeholder_dash))
                SyncStatusRow(stringResource(R.string.sync_status_integrity), status.lastIntegrityCheck ?: stringResource(R.string.placeholder_dash))
                SyncStatusRow(stringResource(R.string.sync_status_tolerance), stringResource(R.string.sync_status_tolerance_value))
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
private fun SyncStatusRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(0.42f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.58f)
        )
    }
}

@Composable
fun TitleWithVaultIndicator(
    title: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activeVault = MultiDbVaults.getActiveVaultName(context)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = MaterialTheme.shapes.medium
        ) {
            Text(
                text = stringResource(R.string.multidb_vault_badge_fmt, activeVault),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
