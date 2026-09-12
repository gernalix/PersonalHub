package com.gernalix.personalhub.capsules.settings

import android.content.Intent
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
import com.gernalix.personalhub.R
import com.gernalix.personalhub.capsules.shortcuts.HomeShortcutsSettings
import com.gernalix.personalhub.core.database.capsules.sync.DatasetteSettings
import com.gernalix.personalhub.core.database.capsules.sync.DatasetteSync
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
        "sync" -> SyncSettings { page = "root" }
        "workflowy-days" -> WorkflowyDaysSettings { page = "root" }
        else -> {
            val context = LocalContext.current
            SettingsPage(R.string.settings_title, ::back) {
                OutlinedButton(onClick = { page = "shortcuts" }) { Text(stringResource(R.string.home_shortcuts_title)) }
                OutlinedButton(onClick = { context.startActivity(Intent(context, DatabaseActivity::class.java)) }) { Text(stringResource(R.string.database_title)) }
                OutlinedButton(onClick = { page = "sync" }) { Text(stringResource(R.string.datasette_sync_title)) }
                OutlinedButton(onClick = { page = "workflowy-days" }) { Text(stringResource(R.string.workflowy_days_title)) }
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
