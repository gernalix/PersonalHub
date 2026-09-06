package com.gernalix.personalhub.core.hubcontext

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
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
    val scope = rememberCoroutineScope()
    suspend fun refresh() { contexts = HubContextRuntime.contexts(anchor); linked = contexts.flatMap { it.members }.filter { it.ref != anchor }.distinctBy { it.ref } }
    LaunchedEffect(anchor) { refresh() }
    if (composerOpen) HubContextComposerDialog(anchor, composerContextId, { composerOpen = false }, { composerOpen = false; scope.launch { refresh() } })
    if (explorerOpen) HubContextExplorerDialog(anchor) { explorerOpen = false }
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.hub_context_links_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = { composerContextId = null; composerOpen = true }, Modifier.testTag("hub-link-action")) { Text(stringResource(R.string.hub_link_action)) }
            TextButton(onClick = { explorerOpen = true }) { Text(stringResource(R.string.hub_explore_action)) }
            contexts.forEach { view ->
                TextButton(onClick = { composerContextId = view.context.id; composerOpen = true }, Modifier.fillMaxWidth().testTag("hub-context-${view.context.id}")) {
                    Text(view.members.filter { it.ref != anchor }.joinToString(" · ") { it.label }.ifBlank { stringResource(R.string.hub_context_empty) })
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
