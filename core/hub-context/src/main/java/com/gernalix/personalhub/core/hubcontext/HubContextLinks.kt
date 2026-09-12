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
import com.gernalix.personalhub.contracts.database.HubCreateRequest
import com.gernalix.personalhub.contracts.database.HubDeepLinkContract
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.contracts.database.HubEntitySummary
import com.gernalix.personalhub.contracts.database.HubResourceKinds
import kotlinx.coroutines.launch

@Composable
fun HubContextLinks(anchor: HubEntityRef, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var linked by remember(anchor) { mutableStateOf<List<HubEntitySummary>>(emptyList()) }
    var contexts by remember(anchor) { mutableStateOf<List<HubContextView>>(emptyList()) }
    var composerContextId by rememberSaveable(anchor) { mutableStateOf<String?>(null) }
    var composerOpen by rememberSaveable(anchor) { mutableStateOf(false) }
    var explorerOpen by rememberSaveable(anchor) { mutableStateOf(false) }
    var workflowyOpen by rememberSaveable(anchor) { mutableStateOf(false) }
    var workflowyUrl by rememberSaveable(anchor) { mutableStateOf("") }
    var workflowyError by rememberSaveable(anchor) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun refresh() {
        contexts = HubContextRuntime.contexts(anchor)
        linked = contexts.flatMap { it.members }.filter { it.ref != anchor }.distinctBy { it.ref }
    }
    LaunchedEffect(anchor) { refresh() }
    if (composerOpen) HubContextComposerDialog(anchor, composerContextId, { composerOpen = false }, { composerOpen = false; scope.launch { refresh() } })
    if (explorerOpen) HubContextExplorerDialog(anchor) { explorerOpen = false }
    if (workflowyOpen) {
        AlertDialog(
            onDismissRequest = { workflowyOpen = false; workflowyError = null },
            title = { Text(stringResource(R.string.hub_workflowy_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = workflowyUrl,
                        onValueChange = { workflowyUrl = it; workflowyError = null },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.hub_workflowy_url)) },
                    )
                    workflowyError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(
                    enabled = workflowyUrl.isNotBlank(),
                    onClick = {
                        scope.launch {
                            val normalized = normalizeWorkflowyUrl(workflowyUrl)
                            if (normalized == null) {
                                workflowyError = context.getString(R.string.hub_workflowy_invalid)
                                return@launch
                            }
                            if (linked.any { it.ref.moduleId == "hub" && it.ref.entityKind == "resource" && it.attributes["value"] == normalized }) {
                                workflowyError = context.getString(R.string.hub_workflowy_exists)
                                return@launch
                            }
                            val adapter = HubContextRuntime.adapter("hub", "resource")
                            val created = adapter.create(
                                HubCreateRequest(
                                    suggestedLabel = context.getString(R.string.hub_workflowy_title),
                                    extras = mapOf("kind" to HubResourceKinds.WEB_URL, "value" to normalized),
                                ),
                            )
                            if (created == null) {
                                workflowyError = context.getString(R.string.hub_workflowy_invalid)
                                return@launch
                            }
                            try {
                                HubContextRuntime.createContext(
                                    listOf(anchor to "", created.ref to ""),
                                    title = context.getString(R.string.hub_workflowy_title),
                                )
                            } catch (error: Throwable) {
                                (adapter as? ResourceHubAdapter)?.delete(created.ref.canonicalId)
                                workflowyError = error.message ?: context.getString(R.string.hub_workflowy_invalid)
                                return@launch
                            }
                            workflowyOpen = false
                            workflowyUrl = ""
                            workflowyError = null
                            refresh()
                        }
                    },
                ) { Text(stringResource(R.string.hub_workflowy_save)) }
            },
            dismissButton = { TextButton(onClick = { workflowyOpen = false; workflowyError = null }) { Text(stringResource(R.string.hub_cancel)) } },
        )
    }
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.hub_context_links_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = {
                copyHubLink(context, HubDeepLinkContract.entityUri(anchor).toString())
            }) { Text(stringResource(R.string.hub_copy_personalhub_link)) }
            TextButton(onClick = { workflowyUrl = ""; workflowyError = null; workflowyOpen = true }) { Text(stringResource(R.string.hub_attach_workflowy)) }
            TextButton(onClick = { composerContextId = null; composerOpen = true }, Modifier.testTag("hub-link-action")) { Text(stringResource(R.string.hub_link_action)) }
            TextButton(onClick = { explorerOpen = true }) { Text(stringResource(R.string.hub_explore_action)) }
            contexts.forEach { view ->
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { composerContextId = view.context.id; composerOpen = true }, Modifier.weight(1f).testTag("hub-context-${view.context.id}")) {
                        Text(view.members.filter { it.ref != anchor }.joinToString(" · ") { it.label }.ifBlank { stringResource(R.string.hub_context_empty) })
                    }
                    TextButton(onClick = { copyHubLink(context, HubDeepLinkContract.contextUri(view.context.id).toString()) }) {
                        Text(stringResource(R.string.hub_copy_context_link))
                    }
                }
            }
            linked.forEach { summary ->
                Text(
                    summary.label,
                    modifier = Modifier.fillMaxWidth().clickable {
                        scope.launch {
                            val target = HubContextRuntime.adapter(summary.ref.moduleId, summary.ref.entityKind).openTarget(summary.ref.canonicalId) ?: return@launch
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target.uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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

private fun normalizeWorkflowyUrl(raw: String): String? {
    val normalized = raw.trim()
    val uri = runCatching { Uri.parse(normalized) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
    val host = uri.host?.lowercase() ?: return null
    if (host != "workflowy.com" && !host.endsWith(".workflowy.com")) return null
    return normalized
}

private fun copyHubLink(context: android.content.Context, value: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.hub_copy_personalhub_link), value))
    Toast.makeText(context, R.string.hub_permalink_copied, Toast.LENGTH_SHORT).show()
}
