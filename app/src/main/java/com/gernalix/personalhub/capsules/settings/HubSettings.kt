package com.gernalix.personalhub.capsules.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.gernalix.personalhub.DatabaseActivity
import com.gernalix.personalhub.DatabaseRestartActivity
import com.gernalix.personalhub.R
import com.gernalix.personalhub.ProfileRuntimeCoordinator
import com.gernalix.personalhub.capsules.shortcuts.HomeShortcutsSettings
import com.gernalix.personalhub.core.database.ImportRolledBack
import com.gernalix.personalhub.core.database.DatabaseProfileInitMode
import com.gernalix.personalhub.core.database.DatabaseProfiles
import com.gernalix.personalhub.core.database.DatabaseVault
import com.gernalix.personalhub.core.database.capsules.sync.DatasetteSettings
import com.gernalix.personalhub.core.database.capsules.sync.DatasetteSync
import com.gernalix.personalhub.core.database.capsules.gitdata.GitDataSettings
import com.gernalix.personalhub.core.database.capsules.gitdata.GitDataSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HubSettings(onBack: () -> Unit) {
    var page by rememberSaveable { mutableStateOf("root") }
    fun back() { if (page == "root") onBack() else page = "root" }
    BackHandler { back() }
    when (page) {
        "shortcuts" -> HomeShortcutsSettings { page = "root" }
        "profiles" -> DatabaseProfilesSettings { page = "root" }
        "data-guide" -> {
            val context = LocalContext.current
            DataSetupGuide(
                onBack = { page = "root" },
                onOpenDatabase = {
                    context.startActivity(Intent(context, DatabaseActivity::class.java))
                },
                onOpenDatasette = { page = "sync" },
                onOpenGit = { page = "git-data" },
            )
        }
        "sync" -> SyncSettings { page = "root" }
        "git-data" -> GitDataSyncSettings(onBack = { page = "root" })
        "git-history" -> GitHistorySettings { page = "root" }
        "workflowy-days" -> WorkflowyDaysSettings { page = "root" }
        else -> {
            val context = LocalContext.current
            val privacyPolicyUrl = stringResource(R.string.privacy_policy_url)
            SettingsPage(R.string.settings_title, ::back) {
                Text(stringResource(R.string.settings_data_model_summary))
                Button(onClick = { page = "data-guide" }) {
                    Text(stringResource(R.string.settings_guided_data_setup))
                }
                HorizontalDivider()
                Text(stringResource(R.string.settings_local_data_section), style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { context.startActivity(Intent(context, DatabaseActivity::class.java)) }) {
                    Text(stringResource(R.string.database_title))
                }
                OutlinedButton(onClick = { page = "profiles" }) { Text(stringResource(R.string.database_profiles_title)) }
                HorizontalDivider()
                Text(stringResource(R.string.settings_optional_services_section), style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { page = "sync" }) { Text(stringResource(R.string.datasette_sync_title)) }
                OutlinedButton(onClick = { page = "git-data" }) { Text(stringResource(R.string.git_data_sync_title)) }
                OutlinedButton(onClick = { page = "git-history" }) { Text(stringResource(R.string.git_history_title)) }
                OutlinedButton(onClick = { page = "workflowy-days" }) { Text(stringResource(R.string.workflowy_days_title)) }
                HorizontalDivider()
                OutlinedButton(onClick = { page = "shortcuts" }) { Text(stringResource(R.string.home_shortcuts_title)) }
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(privacyPolicyUrl),
                            ),
                        )
                    },
                ) { Text(stringResource(R.string.privacy_policy_title)) }
            }
        }
    }
}

@Composable
private fun DatabaseProfilesSettings(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf(DatabaseProfiles.list(context)) }
    var activeId by remember { mutableStateOf(DatabaseProfiles.activeProfileId(context)) }
    var newName by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var renamingId by remember { mutableStateOf<String?>(null) }
    var renameName by rememberSaveable { mutableStateOf("") }

    fun create(mode: DatabaseProfileInitMode) {
        if (newName.isBlank()) return
        busy = true
        failed = false
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching { DatabaseProfiles.create(context, newName, mode) }.exceptionOrNull()
            }
            busy = false
            failed = error != null
            if (error == null) {
                newName = ""
                profiles = DatabaseProfiles.list(context)
            }
        }
    }

    SettingsPage(R.string.database_profiles_title, onBack) {
        Text(stringResource(R.string.database_profiles_description))
        profiles.forEach { profile ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium)
                    if (profile.id == activeId) {
                        Text(stringResource(R.string.database_profile_active))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            renamingId = profile.id
                            renameName = profile.name
                        },
                    ) { Text(stringResource(R.string.database_profile_rename)) }
                    if (profile.id != activeId) {
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                busy = true
                                failed = false
                                scope.launch {
                                    val switched = withContext(Dispatchers.IO) {
                                        runCatching {
                                            ProfileRuntimeCoordinator.switchActiveProfile(context, profile.id)
                                        }
                                    }
                                    busy = false
                                    if (switched.isSuccess && switched.getOrDefault(false)) {
                                        activeId = profile.id
                                        restartDatabaseGraph(context, false)
                                    } else {
                                        failed = switched.isFailure
                                    }
                                }
                            },
                        ) { Text(stringResource(R.string.database_profile_switch)) }
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                runCatching { DatabaseProfiles.delete(context, profile.id) }
                                    .onSuccess { profiles = DatabaseProfiles.list(context) }
                                    .onFailure { failed = true }
                            },
                        ) { Text(stringResource(R.string.database_profile_delete)) }
                    }
                }
            }
            HorizontalDivider()
        }

        OutlinedTextField(
            value = newName,
            onValueChange = { newName = it },
            label = { Text(stringResource(R.string.database_profile_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && newName.isNotBlank(), onClick = { create(DatabaseProfileInitMode.EMPTY) }) {
                Text(stringResource(R.string.database_profile_create_empty))
            }
            OutlinedButton(enabled = !busy && newName.isNotBlank(), onClick = { create(DatabaseProfileInitMode.CLONE_CURRENT) }) {
                Text(stringResource(R.string.database_profile_clone))
            }
        }
        if (busy) CircularProgressIndicator()
        if (failed) Text(stringResource(R.string.database_profile_error), color = MaterialTheme.colorScheme.error)
    }

    val renameId = renamingId
    if (renameId != null) {
        AlertDialog(
            onDismissRequest = { renamingId = null },
            title = { Text(stringResource(R.string.database_profile_rename)) },
            text = {
                OutlinedTextField(
                    value = renameName,
                    onValueChange = { renameName = it },
                    label = { Text(stringResource(R.string.database_profile_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameName.isNotBlank(),
                    onClick = {
                        runCatching { DatabaseProfiles.rename(context, renameId, renameName) }
                            .onSuccess {
                                profiles = DatabaseProfiles.list(context)
                                renamingId = null
                            }
                            .onFailure { failed = true }
                    },
                ) { Text(stringResource(R.string.database_profile_rename)) }
            },
            dismissButton = {
                TextButton(onClick = { renamingId = null }) {
                    Text(stringResource(R.string.settings_back))
                }
            },
        )
    }
}

@Composable
private fun SettingsPage(title: Int, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.settings_back)) }
        Text(stringResource(title), style = MaterialTheme.typography.headlineMedium)
        content()
    }
}

@Composable
private fun DataSetupGuide(
    onBack: () -> Unit,
    onOpenDatabase: () -> Unit,
    onOpenDatasette: () -> Unit,
    onOpenGit: () -> Unit,
) {
    val context = LocalContext.current
    val activeProfile = remember { DatabaseProfiles.active(context) }
    val safConfigured = remember { DatabaseVault.folder(context) != null }
    val datasette = remember {
        runCatching { DatasetteSettings.configuration(context) }.getOrNull()
    }
    val git = remember {
        runCatching { GitDataSettings.configuration(context) }.getOrNull()
    }

    SettingsPage(R.string.settings_guided_data_setup, onBack) {
        Text(stringResource(R.string.data_setup_intro))
        DataRoleCard(
            number = 1,
            title = stringResource(R.string.data_setup_local_title),
            role = stringResource(R.string.data_setup_authoritative),
            body = stringResource(R.string.data_setup_local_body),
            status = stringResource(R.string.data_setup_active_profile, activeProfile.name),
            action = stringResource(R.string.data_setup_open_database),
            onAction = onOpenDatabase,
        )
        DataRoleCard(
            number = 2,
            title = stringResource(R.string.data_setup_saf_title),
            role = stringResource(R.string.data_setup_backup_role),
            body = stringResource(R.string.data_setup_saf_body),
            status = stringResource(
                if (safConfigured) R.string.data_setup_configured else R.string.data_setup_not_configured,
            ),
            action = stringResource(R.string.data_setup_open_database),
            onAction = onOpenDatabase,
        )
        DataRoleCard(
            number = 3,
            title = stringResource(R.string.data_setup_datasette_title),
            role = stringResource(R.string.data_setup_replica_role),
            body = stringResource(R.string.data_setup_datasette_body),
            status = stringResource(
                if (datasette?.enabled == true) R.string.data_setup_enabled
                else if (datasette?.hasToken == true) R.string.data_setup_configured
                else R.string.data_setup_not_configured,
            ),
            action = stringResource(R.string.data_setup_configure),
            onAction = onOpenDatasette,
        )
        DataRoleCard(
            number = 4,
            title = stringResource(R.string.data_setup_git_title),
            role = stringResource(R.string.data_setup_history_role),
            body = stringResource(R.string.data_setup_git_body),
            status = stringResource(
                if (git?.enabled == true) R.string.data_setup_enabled
                else if (git?.configured == true) R.string.data_setup_configured
                else R.string.data_setup_not_configured,
            ),
            action = stringResource(R.string.data_setup_configure),
            onAction = onOpenGit,
        )
        HorizontalDivider()
        Text(
            stringResource(R.string.data_setup_security),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DataRoleCard(
    number: Int,
    title: String,
    role: String,
    body: String,
    status: String,
    action: String,
    onAction: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("$number. $title", style = MaterialTheme.typography.titleMedium)
            Text(role, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(body)
            Text(status, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun SyncSettings(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(runCatching { DatasetteSettings.configuration(context) }.getOrNull()) }
    var connection by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(config == null) }
    var state by remember { mutableStateOf("idle") }
    LaunchedEffect(Unit) { while (true) { state = DatasetteSync.status(context); delay(1000) } }
    BackHandler { if (connection) connection = false else onBack() }
    if (connection) {
        ConnectionSettings(onBack = { connection = false }, onSaved = {
            config = DatasetteSettings.configuration(context)
            failed = false
            connection = false
        })
        return
    }
    SettingsPage(R.string.datasette_sync_title, onBack) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.datasette_enable), Modifier.weight(1f))
            Switch(checked = config?.enabled == true, enabled = !busy && config != null && (config?.hasToken == true), onCheckedChange = { enabled ->
                busy = true
                scope.launch {
                    failed = withContext(Dispatchers.IO) { runCatching { DatasetteSync.setEnabled(context, enabled) }.isFailure }
                    config = runCatching { DatasetteSettings.configuration(context) }.getOrNull()
                    busy = false
                }
            })
        }
        Text(stringResource(R.string.datasette_description))
        Text(
            stringResource(R.string.datasette_direction_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { connection = true }, enabled = !busy) { Text(stringResource(R.string.datasette_connection)) }
        Text(stringResource(if (config?.enabled != true) R.string.datasette_off else when (state) {
            "sending" -> R.string.datasette_sending
            "complete" -> R.string.datasette_complete
            "retry" -> R.string.datasette_retry
            else -> R.string.datasette_waiting
        }))
        if (busy) CircularProgressIndicator()
        if (failed) Text(stringResource(R.string.datasette_error), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ConnectionSettings(onBack: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initial = remember { runCatching { DatasetteSettings.configuration(context) }.getOrNull() }
    var url by remember { mutableStateOf(initial?.baseUrl ?: "") }
    var database by remember { mutableStateOf(initial?.database ?: "") }
    var table by remember { mutableStateOf(initial?.table ?: "") }
    // Credentials never enter saved-instance state, preferences, logs or database exports.
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    SettingsPage(R.string.datasette_connection, onBack) {
        Text(stringResource(R.string.datasette_connection_help))
        Text(
            stringResource(R.string.datasette_token_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.datasette_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(database, { database = it }, label = { Text(stringResource(R.string.datasette_database)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(table, { table = it }, label = { Text(stringResource(R.string.datasette_table)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(token, { token = it }, label = { Text(stringResource(R.string.datasette_token)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        if (initial?.hasToken == true) Text(stringResource(R.string.datasette_token_saved))
        Button(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                failed = withContext(Dispatchers.IO) { runCatching { DatasetteSync.save(context, url, database.trim(), table.trim(), token) }.isFailure }
                busy = false
                if (!failed) { token = ""; onSaved() }
            }
        }) { Text(stringResource(R.string.datasette_save)) }
        if (failed) Text(stringResource(R.string.datasette_error), color = MaterialTheme.colorScheme.error)
    }
}


@Composable
private fun GitDataSyncSettings(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initial = remember {
        runCatching { GitDataSettings.configuration(context) }.getOrNull()
    }
    var repository by remember { mutableStateOf(initial?.repositoryUrl ?: "") }
    var token by remember { mutableStateOf("") }
    var revision by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    var status by remember { mutableStateOf(GitDataSync.status(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            status = GitDataSync.status(context)
            delay(1000)
        }
    }

    fun runOperation(block: () -> Unit, onSuccess: () -> Unit = {}) {
        busy = true
        failed = false
        message = null
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching(block).exceptionOrNull()
            }
            busy = false
            if (error == null) {
                onSuccess()
                message = R.string.git_data_sync_complete
            } else {
                failed = true
                if (error is ImportRolledBack) restartDatabaseGraph(context, true)
            }
        }
    }

    SettingsPage(R.string.git_data_sync_title, onBack) {
        Text(stringResource(R.string.git_data_sync_description))
        Text(
            stringResource(R.string.git_data_sync_direction_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.git_data_sync_enable), Modifier.weight(1f))
            Switch(
                checked = status.enabled,
                enabled = !busy && status.configured,
                onCheckedChange = { enabled ->
                    runOperation(
                        block = { GitDataSync.setEnabled(context, enabled) },
                        onSuccess = { status = GitDataSync.status(context) },
                    )
                },
            )
        }
        if (!status.configured) {
            Text(
                stringResource(R.string.git_data_sync_enable_after_configure),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            stringResource(R.string.git_data_sync_token_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            repository,
            { repository = it },
            label = { Text(stringResource(R.string.git_data_sync_repository)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            token,
            { token = it },
            label = { Text(stringResource(R.string.git_data_sync_token)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (initial?.hasToken == true) {
            Text(stringResource(R.string.git_data_sync_token_saved))
        }
        Button(
            enabled = !busy,
            onClick = {
                runOperation(
                    block = {
                        GitDataSync.save(context, repository, token)
                    },
                    onSuccess = {
                        token = ""
                        status = GitDataSync.status(context)
                    },
                )
            },
        ) {
            Text(stringResource(R.string.git_data_sync_save))
        }

        HorizontalDivider()
        Text(stringResource(R.string.git_data_sync_actions), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(
            enabled = !busy && status.enabled && status.configured,
            onClick = { runOperation(block = { GitDataSync.syncNow(context) }) },
        ) {
            Text(stringResource(R.string.git_data_sync_push_pull_now))
        }
        if (status.runtimeState == "attention") {
            Button(
                enabled = !busy && status.enabled && status.configured,
                onClick = { runOperation(block = { GitDataSync.forcePushNow(context) }) },
            ) {
                Text(stringResource(R.string.git_data_sync_force_push))
            }
        }
        OutlinedButton(
            enabled = !busy && status.enabled && status.configured,
            onClick = { runOperation(block = { GitDataSync.pullNow(context) }) },
        ) {
            Text(stringResource(R.string.git_data_sync_pull_now))
        }
        if (status.pendingPatchIds.isNotEmpty()) {
            Text(
                stringResource(
                    R.string.git_data_sync_pending_patches,
                    status.pendingPatchIds.joinToString(", "),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        HorizontalDivider()
        Text(stringResource(R.string.git_data_sync_restore_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.git_data_sync_restore_description))
        OutlinedTextField(
            revision,
            { revision = it },
            label = { Text(stringResource(R.string.git_data_sync_revision)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = !busy && status.enabled && status.configured && revision.isNotBlank(),
            onClick = {
                busy = true
                failed = false
                message = null
                scope.launch {
                    val error = withContext(Dispatchers.IO) {
                        runCatching {
                            GitDataSync.restoreRevision(context, revision)
                        }.exceptionOrNull()
                    }
                    busy = false
                    if (error == null) {
                        restartDatabaseGraph(context, false)
                    } else {
                        failed = true
                        if (error is ImportRolledBack) restartDatabaseGraph(context, true)
                    }
                }
            },
        ) {
            Text(stringResource(R.string.git_data_sync_restore))
        }

        Text(
            stringResource(
                when {
                    !status.enabled -> R.string.git_data_sync_off
                    status.runtimeState == "syncing" || status.runtimeState == "pulling" ||
                        status.runtimeState == "restoring" -> R.string.git_data_sync_working
                    status.runtimeState == "retry" -> R.string.git_data_sync_retry
                    status.runtimeState == "attention" -> R.string.git_data_sync_attention
                    else -> R.string.git_data_sync_complete
                },
            ),
        )
        status.lastError?.takeIf { it.isNotBlank() && status.runtimeState != "attention" }?.let {
            Text(stringResource(R.string.git_data_sync_error), color = MaterialTheme.colorScheme.error)
        }
        message?.let { Text(stringResource(it)) }
        if (busy) CircularProgressIndicator()
    }
}

private fun restartDatabaseGraph(context: android.content.Context, rolledBack: Boolean) {
    val activity = context as? Activity ?: return
    activity.startActivity(
        Intent(activity, DatabaseRestartActivity::class.java)
            .putExtra("rolled_back", rolledBack)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    activity.finishAffinity()
    android.os.Process.killProcess(android.os.Process.myPid())
}
