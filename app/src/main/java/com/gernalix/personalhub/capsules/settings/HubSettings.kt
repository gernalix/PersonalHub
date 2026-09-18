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
import com.gernalix.personalhub.capsules.shortcuts.HomeShortcutsSettings
import com.gernalix.personalhub.core.database.ImportRolledBack
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
    var enableGitAfterConfigure by rememberSaveable { mutableStateOf(false) }
    fun back() { if (page == "root") onBack() else page = "root" }
    BackHandler { back() }
    when (page) {
        "shortcuts" -> HomeShortcutsSettings { page = "root" }
        "sync" -> SyncSettings { page = "root" }
        "git-data" -> GitDataSyncSettings(
            onBack = { page = "root"; enableGitAfterConfigure = false },
            enableAfterSave = enableGitAfterConfigure,
            onConfigured = { enableGitAfterConfigure = false },
        )
        "workflowy-days" -> WorkflowyDaysSettings { page = "root" }
        else -> {
            val context = LocalContext.current
            val privacyPolicyUrl = stringResource(R.string.privacy_policy_url)
            SettingsPage(R.string.settings_title, ::back) {
                OutlinedButton(onClick = { page = "shortcuts" }) { Text(stringResource(R.string.home_shortcuts_title)) }
                OutlinedButton(onClick = { context.startActivity(Intent(context, DatabaseActivity::class.java)) }) { Text(stringResource(R.string.database_title)) }
                OutlinedButton(onClick = { page = "sync" }) { Text(stringResource(R.string.datasette_sync_title)) }
                GitDataSyncToggle(
                    onConfigure = { requestedEnable ->
                        enableGitAfterConfigure = requestedEnable
                        page = "git-data"
                    },
                )
                OutlinedButton(onClick = { page = "workflowy-days" }) { Text(stringResource(R.string.workflowy_days_title)) }
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
private fun SettingsPage(title: Int, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.settings_back)) }
        Text(stringResource(title), style = MaterialTheme.typography.headlineMedium)
        content()
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
private fun GitDataSyncToggle(onConfigure: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember {
        mutableStateOf(runCatching { GitDataSettings.configuration(context) }.getOrNull())
    }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.git_data_sync_title), Modifier.weight(1f))
            Switch(
                checked = config?.enabled == true,
                enabled = !busy,
                onCheckedChange = { enabled ->
                    if (enabled && config?.configured != true) {
                        onConfigure(true)
                    } else {
                        busy = true
                        scope.launch {
                            failed = withContext(Dispatchers.IO) {
                                runCatching { GitDataSync.setEnabled(context, enabled) }.isFailure
                            }
                            config = runCatching {
                                GitDataSettings.configuration(context)
                            }.getOrNull()
                            busy = false
                        }
                    }
                },
            )
        }
        Text(stringResource(R.string.git_data_sync_description))
        TextButton(onClick = { onConfigure(false) }, enabled = !busy) {
            Text(stringResource(R.string.git_data_sync_configure))
        }
        if (failed) {
            Text(
                stringResource(R.string.git_data_sync_error),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun GitDataSyncSettings(
    onBack: () -> Unit,
    enableAfterSave: Boolean,
    onConfigured: () -> Unit,
) {
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
                        if (enableAfterSave) GitDataSync.setEnabled(context, true)
                    },
                    onSuccess = {
                        token = ""
                        onConfigured()
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
            onClick = { runOperation { GitDataSync.syncNow(context) } },
        ) {
            Text(stringResource(R.string.git_data_sync_push_pull_now))
        }
        OutlinedButton(
            enabled = !busy && status.enabled && status.configured,
            onClick = { runOperation { GitDataSync.pullNow(context) } },
        ) {
            Text(stringResource(R.string.git_data_sync_pull_now))
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
                    else -> R.string.git_data_sync_complete
                },
            ),
        )
        status.lastError?.takeIf { it.isNotBlank() }?.let {
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
