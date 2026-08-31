// v434
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.multitimetracker.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.multitimetracker.BuildConfig
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.multitimetracker.R
import com.example.multitimetracker.persistence.MultiDbVaults
import com.example.multitimetracker.util.AppRestarter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun MultiDbVaultsDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    var newName by remember { mutableStateOf("") }
    val active = MultiDbVaults.getActiveVaultName(context)
    var vaults by remember { mutableStateOf(MultiDbVaults.listVaultNames(context)) }

    fun refreshVaults() {
        vaults = MultiDbVaults.listVaultNames(context)
    }
    val scope = rememberCoroutineScope()
    var isBusy by remember { mutableStateOf(false) }
    var busyLabel by remember { mutableStateOf<String?>(null) }

    // v430: if the user tries to switch to a vault that exists in the list but has no DB yet,
    // we must ask intent: EMPTY (new life) vs CLONE (branch/backup).
    var pendingInitVault by remember { mutableStateOf<String?>(null) }
    var pendingRenameVault by remember { mutableStateOf<String?>(null) }
    var renameVaultText by remember { mutableStateOf("") }
    var pendingDeleteVault by remember { mutableStateOf<String?>(null) }

    fun releaseSafeError(message: String?): String =
        if (BuildConfig.DEBUG) {
            message ?: context.getString(R.string.multidb_switch_failed_unknown)
        } else {
            context.getString(R.string.multidb_switch_failed_unknown)
        }

    LaunchedEffect(Unit) {
        refreshVaults()
    }

    fun relaunchAppOrExplain(targetVault: String) {
        onDismiss()
        if (!AppRestarter.restart(context)) {
            Toast.makeText(
                context,
                context.getString(R.string.multidb_switch_pending_restart_fmt, targetVault),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    if (pendingRenameVault != null) {
        val target = pendingRenameVault!!
        AlertDialog(
            onDismissRequest = { if (!isBusy) pendingRenameVault = null },
            title = { Text(stringResource(R.string.multidb_rename_vault)) },
            text = {
                OutlinedTextField(
                    value = renameVaultText,
                    onValueChange = { renameVaultText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.multidb_new_name_hint)) }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (isBusy) return@TextButton
                        isBusy = true
                        busyLabel = context.getString(R.string.multidb_rename_vault)
                        scope.launch {
                            try {
                                val renamed = withContext(Dispatchers.IO) {
                                    MultiDbVaults.renameVault(context, target, renameVaultText)
                                }
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.multidb_renamed_toast_fmt, renamed),
                                    Toast.LENGTH_SHORT
                                ).show()
                                refreshVaults()
                                pendingRenameVault = null
                            } catch (e: Throwable) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.multidb_create_failed_toast_fmt, e.message ?: "?"),
                                    Toast.LENGTH_LONG
                                ).show()
                            } finally {
                                isBusy = false
                                busyLabel = null
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.salva))
                }
            },
            dismissButton = {
                TextButton(onClick = { if (!isBusy) pendingRenameVault = null }) {
                    Text(stringResource(R.string.annulla))
                }
            }
        )
    }

    if (pendingDeleteVault != null) {
        val target = pendingDeleteVault!!
        AlertDialog(
            onDismissRequest = { if (!isBusy) pendingDeleteVault = null },
            title = { Text(stringResource(R.string.multidb_delete_vault)) },
            text = { Text(stringResource(R.string.multidb_delete_vault_confirm_fmt, target)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (isBusy) return@TextButton
                        isBusy = true
                        busyLabel = context.getString(R.string.multidb_delete_vault)
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    MultiDbVaults.deleteVault(context, target)
                                }
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.multidb_deleted_toast_fmt, target),
                                    Toast.LENGTH_SHORT
                                ).show()
                                refreshVaults()
                                pendingDeleteVault = null
                            } catch (e: Throwable) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.multidb_create_failed_toast_fmt, e.message ?: "?"),
                                    Toast.LENGTH_LONG
                                ).show()
                            } finally {
                                isBusy = false
                                busyLabel = null
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.elimina))
                }
            },
            dismissButton = {
                TextButton(onClick = { if (!isBusy) pendingDeleteVault = null }) {
                    Text(stringResource(R.string.annulla))
                }
            }
        )
    }

    if (pendingInitVault != null) {
        val target = pendingInitVault!!
        AlertDialog(
            onDismissRequest = { if (!isBusy) pendingInitVault = null },
            title = { Text(stringResource(R.string.multidb_missing_db_title)) },
            text = {
                Text(stringResource(R.string.multidb_missing_db_message_fmt, target))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (isBusy) return@TextButton
                        isBusy = true
                        busyLabel = context.getString(R.string.multidb_initializing_vault)
                        scope.launch {
                            try {
                                val created = withContext(Dispatchers.IO) {
                                    MultiDbVaults.createVault(context, target)
                                }

                                // v433: default confirm button is "Create EMPTY DB".
                                withContext(Dispatchers.IO) {
                                    MultiDbVaults.initVaultDb(context, created, MultiDbVaults.InitMode.EMPTY)
                                }

                                val res = withContext(Dispatchers.IO) { MultiDbVaults.switchToVault(context, created) }
                                if (!res.importedOk) {
                                    val msg = releaseSafeError(res.errorMessage)
                                    Toast.makeText(context, context.getString(R.string.multidb_switch_failed_toast_fmt, msg), Toast.LENGTH_LONG).show()
                                    refreshVaults()
                                    pendingInitVault = null
                                    return@launch
                                }

                                refreshVaults()
                                pendingInitVault = null
                                relaunchAppOrExplain(res.toVault)
                            } catch (e: Throwable) {
                                if (e is CancellationException) return@launch
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.multidb_create_failed_toast_fmt, e.message ?: "?"),
                                    Toast.LENGTH_LONG
                                ).show()
                                pendingInitVault = null
                            } finally {
                                isBusy = false
                                busyLabel = null
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.multidb_missing_db_create_empty_button))
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            if (isBusy) return@TextButton
                            isBusy = true
                            busyLabel = context.getString(R.string.multidb_initializing_vault)
                            scope.launch {
                                try {
                                    val created = withContext(Dispatchers.IO) { MultiDbVaults.createVault(context, target) }
                                    withContext(Dispatchers.IO) {
                                        MultiDbVaults.initVaultDb(context, created, MultiDbVaults.InitMode.CLONE_CURRENT)
                                    }
                                    val res = withContext(Dispatchers.IO) { MultiDbVaults.switchToVault(context, created) }
                                    if (!res.importedOk) {
                                        val msg = releaseSafeError(res.errorMessage)
                                        Toast.makeText(context, context.getString(R.string.multidb_switch_failed_toast_fmt, msg), Toast.LENGTH_LONG).show()
                                        refreshVaults()
                                        pendingInitVault = null
                                        return@launch
                                    }
                                    refreshVaults()
                                    pendingInitVault = null
                                    relaunchAppOrExplain(res.toVault)
                                } catch (e: Throwable) {
                                    if (e is CancellationException) return@launch
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.multidb_create_failed_toast_fmt, e.message ?: "?"),
                                        Toast.LENGTH_LONG
                                    ).show()
                                    pendingInitVault = null
                                } finally {
                                    isBusy = false
                                    busyLabel = null
                                }
                            }
                        }
                    ) {
                        Text(stringResource(R.string.multidb_missing_db_clone_button))
                    }
                    TextButton(onClick = { if (!isBusy) pendingInitVault = null }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        title = { Text(stringResource(R.string.multidb_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (!MultiDbVaults.isConfigured(context)) {
                    Text(stringResource(R.string.multidb_requires_backup_folder))
                    return@Column
                }

                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.multidb_current_vault_fmt, active),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = stringResource(R.string.multidb_switch_to),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (isBusy) {
                    Text(stringResource(R.string.multidb_busy_working_fmt, busyLabel ?: "…"))
                }
                com.example.multitimetracker.ui.components.AppDivider()

                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.52f)
                        .verticalScroll(rememberScrollState())
                ) {
                    vaults.forEach { v ->
                        val isActiveVault = v.equals(active, ignoreCase = true)

                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.elevatedCardColors(
                                containerColor = if (isActiveVault) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                } else {
                                    MaterialTheme.colorScheme.surface
                                }
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = v,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (isActiveVault) {
                                        AssistChip(
                                            onClick = {},
                                            enabled = false,
                                            label = { Text(stringResource(R.string.multidb_active_badge)) }
                                        )
                                    }
                                }

                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    TextButton(
                                        onClick = {
                                            if (isActiveVault) return@TextButton
                                            if (isBusy) return@TextButton
                                            isBusy = true
                                            busyLabel = v
                                            scope.launch {
                                                try {
                                                    val res = withContext(Dispatchers.IO) {
                                                        MultiDbVaults.switchToVault(context, v)
                                                    }
                                                    if (res.importedOk) {
                                                        relaunchAppOrExplain(res.toVault)
                                                    } else if (res.errorCode == MultiDbVaults.SwitchErrorCode.MISSING_TARGET_DB) {
                                                        pendingInitVault = res.toVault
                                                    } else {
                                                        val msg = releaseSafeError(res.errorMessage)
                                                        Toast.makeText(
                                                            context,
                                                            context.getString(R.string.multidb_switch_failed_toast_fmt, msg),
                                                            Toast.LENGTH_LONG
                                                        ).show()
                                                    }
                                                } catch (e: Throwable) {
                                                    if (e is CancellationException) return@launch
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(R.string.multidb_switch_failed_toast_fmt, e.message ?: "?"),
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                } finally {
                                                    isBusy = false
                                                    busyLabel = null
                                                }
                                            }
                                        },
                                        enabled = !isActiveVault && !isBusy
                                    ) {
                                        Text(stringResource(R.string.multidb_switch_action))
                                    }
                                    TextButton(
                                        onClick = {
                                            pendingRenameVault = v
                                            renameVaultText = v
                                        },
                                        enabled = !isBusy && !v.equals("default", ignoreCase = true)
                                    ) {
                                        Text(stringResource(R.string.modifica))
                                    }
                                    TextButton(
                                        onClick = { pendingDeleteVault = v },
                                        enabled = !isBusy && !isActiveVault && !v.equals("default", ignoreCase = true)
                                    ) {
                                        Text(stringResource(R.string.elimina))
                                    }
                                }
                            }
                        }
                    }
                }

                com.example.multitimetracker.ui.components.AppDivider()

                Text(stringResource(R.string.multidb_create_new))
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.multidb_new_name_hint)) }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            if (isBusy) return@TextButton
                            isBusy = true
                            busyLabel = context.getString(R.string.multidb_creating_vault)
                            scope.launch {
                                try {
                                    val created = withContext(Dispatchers.IO) {
                                        MultiDbVaults.createVault(context, newName)
                                    }
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.multidb_created_toast_fmt, created),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    refreshVaults()
                                    newName = ""
                                } catch (e: Throwable) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.multidb_create_failed_toast_fmt, e.message ?: "?"),
                                        Toast.LENGTH_LONG
                                    ).show()
                                } finally {
                                    isBusy = false
                                    busyLabel = null
                                }
                            }
                        },
                        enabled = !isBusy
                    ) {
                        Text(stringResource(R.string.create))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        }
    )
}
