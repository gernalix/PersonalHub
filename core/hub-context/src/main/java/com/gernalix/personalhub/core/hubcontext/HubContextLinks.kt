package com.gernalix.personalhub.core.hubcontext

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gernalix.personalhub.contracts.database.HubDeepLinkContract
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.contracts.database.HubEntitySummary
import kotlinx.coroutines.launch

@Composable
fun HubContextLinks(anchor: HubEntityRef, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var linked by remember(anchor) { mutableStateOf<List<HubEntitySummary>>(emptyList()) }
    var contexts by remember(anchor) { mutableStateOf<List<HubContextView>>(emptyList()) }
    var composerContextId by rememberSaveable(anchor) { mutableStateOf<String?>(null) }
    var composerOpen by rememberSaveable(anchor) { mutableStateOf(false) }
    var explorerOpen by rememberSaveable(anchor) { mutableStateOf(false) }
    var workflowyEnabled by remember(context) { mutableStateOf(WorkflowyIntegrationSettings.isEnabled(context)) }
    var workflowyOpen by rememberSaveable(anchor) { mutableStateOf(false) }
    var workflowyUrl by rememberSaveable(anchor) { mutableStateOf("") }
    var workflowyError by rememberSaveable(anchor) { mutableStateOf<String?>(null) }
    var workflowyNoteOpen by rememberSaveable(anchor) { mutableStateOf(false) }
    var workflowyNote by rememberSaveable(anchor) { mutableStateOf("") }
    var workflowyNoteError by rememberSaveable(anchor) { mutableStateOf<String?>(null) }
    var workflowyBusy by remember(anchor) { mutableStateOf(false) }
    val invalidWorkflowyMessage = stringResource(R.string.hub_workflowy_invalid)
    val existingWorkflowyMessage = stringResource(R.string.hub_workflowy_exists)
    val workflowyNoteFailedMessage = stringResource(R.string.hub_workflowy_note_failed)
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        contexts = HubContextRuntime.contexts(anchor)
        linked = contexts.flatMap { it.members }.filter { it.ref != anchor }.distinctBy { it.ref }
    }

    DisposableEffect(context) {
        val dispose = WorkflowyIntegrationSettings.observeEnabled(context) { workflowyEnabled = it }
        onDispose(dispose)
    }
    LaunchedEffect(anchor) { refresh() }

    if (composerOpen) {
        HubContextComposerDialog(
            anchor,
            composerContextId,
            { composerOpen = false },
            { composerOpen = false; scope.launch { refresh() } },
        )
    }
    if (explorerOpen) HubContextExplorerDialog(anchor) { explorerOpen = false }

    if (workflowyEnabled && workflowyOpen) {
        ExistingWorkflowyLinkDialog(
            value = workflowyUrl,
            error = workflowyError,
            busy = workflowyBusy,
            onValueChange = { workflowyUrl = it; workflowyError = null },
            onDismiss = {
                if (!workflowyBusy) {
                    workflowyOpen = false
                    workflowyError = null
                }
            },
            onSave = {
                scope.launch {
                    val normalized = WorkflowyLinkPolicy.normalize(workflowyUrl)
                    if (normalized == null) {
                        workflowyError = invalidWorkflowyMessage
                        return@launch
                    }
                    if (linked.any { WorkflowyLinkPolicy.isWorkflowyResource(it) && it.attributes["value"] == normalized }) {
                        workflowyError = existingWorkflowyMessage
                        return@launch
                    }
                    workflowyBusy = true
                    runCatching { WorkflowyHubBridge.attachUrl(context, anchor, normalized) }
                        .onSuccess {
                            workflowyOpen = false
                            workflowyUrl = ""
                            workflowyError = null
                            refresh()
                        }
                        .onFailure {
                            workflowyError = it.message ?: invalidWorkflowyMessage
                        }
                    workflowyBusy = false
                }
            },
        )
    }

    if (workflowyEnabled && workflowyNoteOpen) {
        WorkflowyNoteDialog(
            value = workflowyNote,
            error = workflowyNoteError,
            busy = workflowyBusy,
            onValueChange = { workflowyNote = it; workflowyNoteError = null },
            onDismiss = {
                if (!workflowyBusy) {
                    workflowyNoteOpen = false
                    workflowyNoteError = null
                }
            },
            onSave = { openAfter ->
                scope.launch {
                    workflowyBusy = true
                    runCatching { WorkflowyHubBridge.createNote(context, anchor, workflowyNote) }
                        .onSuccess { node ->
                            workflowyNoteOpen = false
                            workflowyNote = ""
                            workflowyNoteError = null
                            refresh()
                            if (openAfter) WorkflowyHubBridge.open(context, node.deepLink)
                        }
                        .onFailure {
                            workflowyNoteError = it.message ?: workflowyNoteFailedMessage
                        }
                    workflowyBusy = false
                }
            },
        )
    }

    val visibleLinked = linked.filter { workflowyEnabled || !WorkflowyLinkPolicy.isWorkflowyResource(it) }
    Surface(
        modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.hub_context_links_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            TextButton(onClick = {
                copyHubLink(context, HubDeepLinkContract.entityUri(anchor).toString())
            }) { Text(stringResource(R.string.hub_copy_personalhub_link)) }

            if (workflowyEnabled) {
                val hasApiKey = WorkflowyIntegrationSettings.configuration(context).hasApiKey
                TextButton(
                    enabled = hasApiKey,
                    onClick = {
                        workflowyNote = ""
                        workflowyNoteError = null
                        workflowyNoteOpen = true
                    },
                ) { Text(stringResource(R.string.hub_workflowy_new_note)) }
                if (!hasApiKey) {
                    Text(
                        stringResource(R.string.hub_workflowy_note_requires_key),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = {
                    workflowyUrl = ""
                    workflowyError = null
                    workflowyOpen = true
                }) { Text(stringResource(R.string.hub_workflowy_attach_existing)) }
            }

            TextButton(
                onClick = { composerContextId = null; composerOpen = true },
                modifier = Modifier.testTag("hub-link-action"),
            ) { Text(stringResource(R.string.hub_link_action)) }
            TextButton(onClick = { explorerOpen = true }) {
                Text(stringResource(R.string.hub_explore_action))
            }

            contexts.forEach { view ->
                val members = view.members.filter { summary ->
                    summary.ref != anchor && (workflowyEnabled || !WorkflowyLinkPolicy.isWorkflowyResource(summary))
                }
                if (members.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(
                            onClick = { composerContextId = view.context.id; composerOpen = true },
                            modifier = Modifier.weight(1f).testTag("hub-context-" + view.context.id),
                        ) {
                            Text(members.joinToString(" · ") { it.label })
                        }
                        TextButton(onClick = {
                            copyHubLink(context, HubDeepLinkContract.contextUri(view.context.id).toString())
                        }) { Text(stringResource(R.string.hub_copy_context_link)) }
                    }
                }
            }

            visibleLinked.forEach { summary ->
                Text(
                    summary.label,
                    modifier = Modifier.fillMaxWidth().clickable {
                        scope.launch {
                            if (WorkflowyLinkPolicy.isWorkflowyResource(summary)) {
                                WorkflowyHubBridge.open(context, summary.attributes["value"].orEmpty())
                                return@launch
                            }
                            val target = HubContextRuntime
                                .adapter(summary.ref.moduleId, summary.ref.entityKind)
                                .openTarget(summary.ref.canonicalId) ?: return@launch
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target.uri))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            target.activityClassName?.let { intent.setClassName(context.packageName, it) }
                            runCatching { context.startActivity(intent) }
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ExistingWorkflowyLinkDialog(
    value: String,
    error: String?,
    busy: Boolean,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hub_workflowy_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.hub_workflowy_url)) },
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) CircularProgressIndicator()
            }
        },
        confirmButton = {
            Button(enabled = !busy && value.isNotBlank(), onClick = onSave) {
                Text(stringResource(R.string.hub_workflowy_save))
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(stringResource(R.string.hub_cancel))
            }
        },
    )
}

@Composable
private fun WorkflowyNoteDialog(
    value: String,
    error: String?,
    busy: Boolean,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hub_workflowy_new_note_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.hub_workflowy_note_text)) },
                    minLines = 2,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) CircularProgressIndicator()
            }
        },
        confirmButton = {
            Row {
                TextButton(enabled = !busy && value.isNotBlank(), onClick = { onSave(false) }) {
                    Text(stringResource(R.string.hub_workflowy_note_save))
                }
                Button(enabled = !busy && value.isNotBlank(), onClick = { onSave(true) }) {
                    Text(stringResource(R.string.hub_workflowy_note_save_open))
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(stringResource(R.string.hub_cancel))
            }
        },
    )
}

private fun copyHubLink(context: android.content.Context, value: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.hub_copy_personalhub_link), value))
    Toast.makeText(context, R.string.hub_permalink_copied, Toast.LENGTH_SHORT).show()
}
