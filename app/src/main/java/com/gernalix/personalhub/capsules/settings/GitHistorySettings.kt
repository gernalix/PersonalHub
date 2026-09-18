package com.gernalix.personalhub.capsules.settings

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.WindowInsets
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
import com.gernalix.personalhub.DatabaseRestartActivity
import com.gernalix.personalhub.R
import com.gernalix.personalhub.core.database.ImportRolledBack
import com.gernalix.personalhub.core.database.capsules.gitdata.GitHistory
import com.gernalix.personalhub.core.database.capsules.gitdata.GitHistoryItem
import com.gernalix.personalhub.core.database.capsules.gitdata.GitHistoryStats
import com.gernalix.personalhub.core.database.capsules.gitdata.GitMilestone
import com.gernalix.personalhub.core.database.capsules.gitdata.GitRevision
import com.gernalix.personalhub.core.database.capsules.gitdata.GitStateDiff
import com.gernalix.personalhub.core.database.capsules.gitdata.GitDataSettings
import com.gernalix.personalhub.core.database.capsules.gitdata.GitDataSync
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun GitHistorySettings(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var author by remember { mutableStateOf<String?>(null) }
    var table by remember { mutableStateOf("") }
    var rowKey by remember { mutableStateOf("") }
    var history by remember { mutableStateOf<List<GitHistoryItem>>(emptyList()) }
    var stats by remember { mutableStateOf<GitHistoryStats?>(null) }
    var revisions by remember { mutableStateOf<List<GitRevision>>(emptyList()) }
    var milestones by remember { mutableStateOf<List<GitMilestone>>(emptyList()) }
    var milestoneName by remember { mutableStateOf("") }
    var compareBefore by remember { mutableStateOf("") }
    var compareAfter by remember { mutableStateOf("") }
    var diff by remember { mutableStateOf<List<GitStateDiff>>(emptyList()) }
    var proposalRef by remember { mutableStateOf("") }
    var patchId by remember { mutableStateOf("") }
    var restore by remember { mutableStateOf<GitRevision?>(null) }

    fun refresh() {
        scope.launch {
            busy = true
            error = false
            val localResult = withContext(Dispatchers.IO) {
                runCatching {
                    GitHistory.recent(
                        context,
                        limit = 200,
                        author = author,
                        table = table.trim().ifBlank { null },
                        rowKey = rowKey.trim().ifBlank { null },
                    ) to GitHistory.stats(context)
                }
            }
            localResult.onSuccess { (local, localStats) ->
                history = local
                stats = localStats
            }.onFailure { error = true }

            val configured = runCatching { GitDataSettings.configuration(context) }.getOrNull()
            if (configured?.enabled == true && configured.configured) {
                val remoteResult = withContext(Dispatchers.IO) {
                    runCatching {
                        GitHistory.revisions(context, 50) to GitHistory.milestones(context)
                    }
                }
                remoteResult.onSuccess { (remoteRevisions, remoteMilestones) ->
                    revisions = remoteRevisions
                    milestones = remoteMilestones
                }.onFailure {
                    // Local audit/history remains usable while GitHub is offline.
                    error = true
                }
            } else {
                revisions = emptyList()
                milestones = emptyList()
            }
            busy = false
        }
    }

    LaunchedEffect(author) { refresh() }

    if (restore != null) {
        AlertDialog(
            onDismissRequest = { restore = null },
            title = { Text(stringResource(R.string.git_history_restore_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.git_history_restore_confirm_text,
                        restore!!.sha.take(12),
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val revision = restore!!.sha
                        restore = null
                        scope.launch {
                            busy = true
                            val failure = withContext(Dispatchers.IO) {
                                runCatching {
                                    GitDataSync.restoreRevision(context, revision)
                                }.exceptionOrNull()
                            }
                            busy = false
                            if (failure == null) restartHistoryGraph(context, false)
                            else {
                                error = true
                                if (failure is ImportRolledBack) restartHistoryGraph(context, true)
                            }
                        }
                    },
                ) { Text(stringResource(R.string.git_history_restore_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { restore = null }) {
                    Text(stringResource(R.string.git_history_cancel))
                }
            },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = onBack, enabled = !busy) {
            Text(stringResource(R.string.settings_back))
        }
        Text(
            stringResource(R.string.git_history_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(stringResource(R.string.git_history_description))

        Text(stringResource(R.string.git_history_author), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf<String?>(null, "user", "chatgpt", "codex").forEach { value ->
                FilterChip(
                    selected = author == value,
                    onClick = { author = value },
                    label = {
                        Text(
                            when (value) {
                                null -> stringResource(R.string.git_history_all)
                                else -> value
                            },
                        )
                    },
                )
            }
        }
        OutlinedTextField(
            table,
            { table = it },
            label = { Text(stringResource(R.string.git_history_table_filter)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            rowKey,
            { rowKey = it },
            label = { Text(stringResource(R.string.git_history_row_filter)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = ::refresh, enabled = !busy) {
            Text(stringResource(R.string.git_history_apply_filters))
        }

        HorizontalDivider()
        Text(stringResource(R.string.git_history_stats), style = MaterialTheme.typography.titleMedium)
        stats?.let { value ->
            Text(
                stringResource(
                    R.string.git_history_stats_summary,
                    value.byAuthor.sumOf { it.count },
                    value.byAuthor.joinToString { it.key + "=" + it.count },
                ),
            )
        }

        HorizontalDivider()
        Text(stringResource(R.string.git_history_recent), style = MaterialTheme.typography.titleMedium)
        history.forEach { item ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    DateFormat.getDateTimeInstance().format(Date(item.occurredAt)) +
                        " · " + item.author + " · " + item.source +
                        " · " + item.table + " · " + item.operation,
                    style = MaterialTheme.typography.bodyMedium,
                )
                item.reason?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                if (item.changedColumns.isNotBlank()) {
                    Text(item.changedColumns, style = MaterialTheme.typography.bodySmall)
                }
                if (item.revertedBy == null) {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                val failure = withContext(Dispatchers.IO) {
                                    runCatching { GitHistory.revertEvent(context, item.id) }.exceptionOrNull()
                                }
                                busy = false
                                if (failure == null) refresh() else error = true
                            }
                        },
                    ) { Text(stringResource(R.string.git_history_revert_edit)) }
                } else {
                    Text(stringResource(R.string.git_history_reverted))
                }
            }
        }

        HorizontalDivider()
        Text(stringResource(R.string.git_history_time_machine), style = MaterialTheme.typography.titleMedium)
        revisions.take(20).forEach { revision ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (revision.committedAt > 0) {
                            DateFormat.getDateTimeInstance().format(Date(revision.committedAt))
                        } else revision.sha.take(12),
                    )
                    Text(revision.message, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { restore = revision }, enabled = !busy) {
                    Text(stringResource(R.string.git_history_restore))
                }
            }
        }

        HorizontalDivider()
        Text(stringResource(R.string.git_history_compare), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            compareBefore,
            { compareBefore = it },
            label = { Text(stringResource(R.string.git_history_before_revision)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            compareAfter,
            { compareAfter = it },
            label = { Text(stringResource(R.string.git_history_after_revision)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            enabled = !busy && compareBefore.isNotBlank() && compareAfter.isNotBlank(),
            onClick = {
                scope.launch {
                    busy = true
                    val result = withContext(Dispatchers.IO) {
                        runCatching { GitHistory.compare(context, compareBefore, compareAfter) }
                    }
                    busy = false
                    result.onSuccess { diff = it }.onFailure { error = true }
                }
            },
        ) { Text(stringResource(R.string.git_history_compare_action)) }
        diff.filter { it.changed }.forEach { item ->
            Text(item.table + ": " + item.beforeRows + " → " + item.afterRows)
        }

        HorizontalDivider()
        Text(stringResource(R.string.git_history_milestones), style = MaterialTheme.typography.titleMedium)
        milestones.forEach { Text(it.name + " · " + it.sha.take(12)) }
        OutlinedTextField(
            milestoneName,
            { milestoneName = it },
            label = { Text(stringResource(R.string.git_history_milestone_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            enabled = !busy && milestoneName.isNotBlank(),
            onClick = {
                scope.launch {
                    busy = true
                    val result = withContext(Dispatchers.IO) {
                        runCatching { GitHistory.createMilestone(context, milestoneName) }
                    }
                    busy = false
                    result.onSuccess {
                        milestoneName = ""
                        refresh()
                    }.onFailure { error = true }
                }
            },
        ) { Text(stringResource(R.string.git_history_create_milestone)) }

        HorizontalDivider()
        Text(stringResource(R.string.git_history_proposal), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            proposalRef,
            { proposalRef = it },
            label = { Text(stringResource(R.string.git_history_proposal_ref)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            patchId,
            { patchId = it },
            label = { Text(stringResource(R.string.git_history_patch_id)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            enabled = !busy && proposalRef.isNotBlank() && patchId.isNotBlank(),
            onClick = {
                scope.launch {
                    busy = true
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            GitDataSync.applyPatchFromRevision(context, proposalRef, patchId)
                        }
                    }
                    busy = false
                    result.onSuccess { refresh() }.onFailure { error = true }
                }
            },
        ) { Text(stringResource(R.string.git_history_cherry_pick)) }

        OutlinedButton(
            enabled = !busy,
            onClick = {
                scope.launch {
                    busy = true
                    val result = withContext(Dispatchers.IO) {
                        runCatching { GitHistory.rebuildIndex(context) }
                    }
                    busy = false
                    result.onSuccess { refresh() }.onFailure { error = true }
                }
            },
        ) { Text(stringResource(R.string.git_history_rebuild_index)) }

        if (busy) CircularProgressIndicator()
        if (error) {
            Text(
                stringResource(R.string.git_history_error),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun restartHistoryGraph(context: android.content.Context, rolledBack: Boolean) {
    val activity = context as? Activity ?: return
    activity.startActivity(
        Intent(activity, DatabaseRestartActivity::class.java)
            .putExtra("rolled_back", rolledBack)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    activity.finishAffinity()
    android.os.Process.killProcess(android.os.Process.myPid())
}
