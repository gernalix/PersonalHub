package com.gernalix.personalhub

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gernalix.personalhub.contracts.database.SinceWhenSourceDescriptor
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.ui.SinceWhenCreationControl
import com.example.multitimetracker.api.TimerStartupApi
import kotlinx.coroutines.launch

@Composable
internal fun SinceWhenCreateScreen(source: SinceWhenSourceDescriptor, onBack: () -> Unit, startEnabled: Boolean = false) {
    val context = LocalContext.current
    val saveErrorMessage = stringResource(R.string.since_when_save_error)
    val dao = remember { PersonalHubDatabase.get(context).sinceWhenCounterDao() }
    val scope = rememberCoroutineScope()
    var title by remember(source) { mutableStateOf(source.defaultCounterTitle) }
    var enabled by remember(source, startEnabled) { mutableStateOf(startEnabled) }
    var selectedSourceId by remember(source) {
        mutableStateOf(source.timestampSources.firstOrNull { it.isDefault }?.id ?: source.timestampSources.firstOrNull()?.id)
    }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var duplicateId by remember { mutableStateOf<Long?>(null) }
    var autoCreateAttempted by remember(source, startEnabled) { mutableStateOf(false) }
    var migrationReady by remember { mutableStateOf(false) }
    var migrationFailed by remember { mutableStateOf(false) }
    val selected = source.timestampSources.firstOrNull { it.id == selectedSourceId }

    LaunchedEffect(context) {
        try {
            TimerStartupApi.ensureLegacySinceWhenMigrated(context)
            migrationReady = true
        } catch (_: Exception) {
            migrationFailed = true
        }
    }

    LaunchedEffect(source, enabled, selectedSourceId) {
        duplicateId = if (enabled && selected != null) {
            dao.findSourceSnapshot(source.entityType, source.entityId, selected.timestamp)?.id
        } else null
    }
    LaunchedEffect(source, startEnabled, migrationReady, selectedSourceId) {
        val choice = source.timestampSources.firstOrNull { it.id == selectedSourceId }
        if (!startEnabled || !migrationReady || !enabled || choice == null || autoCreateAttempted) return@LaunchedEffect
        autoCreateAttempted = true
        saving = true
        error = null
        try {
            val existing = dao.findSourceSnapshot(source.entityType, source.entityId, choice.timestamp)
            if (existing != null) duplicateId = existing.id
            else {
                dao.insert(
                    com.gernalix.personalhub.contracts.database.SinceWhenCounterEntity(
                        title = title.trim(),
                        initialTimestamp = choice.timestamp,
                        sourceEntityType = source.entityType,
                        sourceEntityId = source.entityId,
                        sourceTimestampField = choice.id,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                onBack()
            }
        } catch (_: Exception) {
            val existing = dao.findSourceSnapshot(source.entityType, source.entityId, choice.timestamp)
            if (existing != null) duplicateId = existing.id
            else error = saveErrorMessage
        } finally {
            saving = false
        }
    }
    AlertDialog(
        onDismissRequest = { if (!saving) onBack() },
        title = { Text(stringResource(R.string.since_when_create_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (migrationFailed) Text(stringResource(R.string.since_when_migration_error))
                else if (!migrationReady) Text(stringResource(R.string.since_when_loading))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.since_when_counter_name)) },
                    singleLine = true,
                    enabled = migrationReady && !saving,
                )
                SinceWhenCreationControl(
                    timestampSources = source.timestampSources,
                    enabled = enabled,
                    selectedSourceId = selectedSourceId,
                    saving = saving || !migrationReady,
                    onEnabledChange = { enabled = it },
                    onSourceSelected = { selectedSourceId = it },
                )
                error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
                if (duplicateId != null) {
                    Text(stringResource(R.string.since_when_already_created))
                }
            }
        },
        confirmButton = {
            if (duplicateId != null) {
                TextButton(enabled = migrationReady && !saving, onClick = {
                    context.startActivity(Intent(context, SinceWhenActivity::class.java).setData(android.net.Uri.parse("personalhub://sincewhen/v1/counter/$duplicateId")))
                    onBack()
                }) { Text(stringResource(R.string.since_when_open_counter)) }
            } else {
                TextButton(enabled = migrationReady && !saving && enabled && title.isNotBlank() && selected != null, onClick = {
                    val choice = selected ?: return@TextButton
                    saving = true
                    error = null
                    scope.launch {
                        try {
                            dao.insert(
                                com.gernalix.personalhub.contracts.database.SinceWhenCounterEntity(
                                    title = title.trim(),
                                    initialTimestamp = choice.timestamp,
                                    sourceEntityType = source.entityType,
                                    sourceEntityId = source.entityId,
                                    sourceTimestampField = choice.id,
                                    createdAt = System.currentTimeMillis(),
                                ),
                            )
                            onBack()
                        } catch (_: Exception) {
                            val existing = dao.findSourceSnapshot(source.entityType, source.entityId, choice.timestamp)
                            if (existing != null) duplicateId = existing.id
                            else error = saveErrorMessage
                            saving = false
                        }
                    }
                }) { Text(stringResource(R.string.since_when_create_action)) }
            }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onBack) { Text(stringResource(R.string.since_when_cancel)) } },
    )
}
