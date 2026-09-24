package com.gernalix.personalhub

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.gernalix.personalhub.core.database.DatabaseStartupGate
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.contracts.database.HubDeepLinkContract
import com.gernalix.personalhub.contracts.database.HubEntitySummary
import com.gernalix.personalhub.contracts.database.HubTagAlias
import com.gernalix.personalhub.contracts.database.HubTagEntity
import com.gernalix.personalhub.contracts.database.HubTagKinds
import com.gernalix.personalhub.contracts.database.HubTagNamespaces
import com.gernalix.personalhub.contracts.database.SinceWhenTimestampSource
import com.gernalix.personalhub.core.hubcontext.HubContextRuntime
import com.gernalix.personalhub.core.ui.SinceWhenCreationControl
import com.gernalix.personalhub.core.ui.launchSinceWhenCreate
import com.gernalix.personalhub.ui.theme.PersonalHubTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HubTagsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (DatabaseStartupGate.blockIfNotReady(this)) return
        enableEdgeToEdge()
        val initialTagId = intent?.data?.lastPathSegment
        setContent {
            PersonalHubTheme {
                Surface(Modifier.fillMaxSize()) {
                    HubTagsScreen(initialTagId = initialTagId, onBack = ::finish)
                }
            }
        }
    }
}

@Composable
fun HubTagsScreen(initialTagId: String? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tags by remember { mutableStateOf<List<HubTagEntity>>(emptyList()) }
    var selectedId by remember(initialTagId) { mutableStateOf(initialTagId) }
    var query by remember { mutableStateOf("") }
    var namespace by remember { mutableStateOf<String?>(null) }
    var linked by remember { mutableStateOf<List<HubEntitySummary>>(emptyList()) }
    var aliases by remember { mutableStateOf<List<HubTagAlias>>(emptyList()) }
    var aliasDraft by remember { mutableStateOf("") }
    var renameDraft by remember { mutableStateOf<String?>(null) }
    var detailsDraft by remember { mutableStateOf<HubTagEntity?>(null) }
    var mergeSource by remember { mutableStateOf<HubTagEntity?>(null) }
    var createDraft by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        tags = withContext(Dispatchers.IO) { HubContextRuntime.tags().all() }
        val id = selectedId
        if (id != null) {
            linked = withContext(Dispatchers.IO) {
                HubContextRuntime.linked(HubEntityRef("tags", "tag", id))
            }
            aliases = withContext(Dispatchers.IO) { HubContextRuntime.tags().aliases(id) }
        } else {
            linked = emptyList()
            aliases = emptyList()
        }
    }

    LaunchedEffect(selectedId) { reload() }
    val selected = tags.firstOrNull { it.id == selectedId }
    val visible = tags.filter { tag ->
        (namespace == null || tag.namespace == namespace || tag.isGlobal) &&
            (query.isBlank() || tag.name.contains(query, true) || tag.description.orEmpty().contains(query, true))
    }

    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Text("Tags", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier)
        }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search tags") })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(namespace == null, { namespace = null }, label = { Text("All") })
            HubTagNamespaces.all.sorted().forEach { value ->
                FilterChip(namespace == value, { namespace = value }, label = { Text(value) })
            }
        }
        if (selected == null) {
            Button(onClick = { createDraft = "" }) { Text("Create tag in ${namespace ?: HubTagNamespaces.GLOBAL}") }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (selected == null) {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visible, key = HubTagEntity::id) { tag ->
                    Card(Modifier.fillMaxWidth().clickable { selectedId = tag.id }) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${tag.icon ?: "🏷"} ${tag.name}", style = MaterialTheme.typography.titleMedium)
                            Text("${tag.namespace} · ${tag.usageCount} uses${if (tag.pinned) " · pinned" else ""}${if (tag.archived) " · archived" else ""}")
                        }
                    }
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text("${selected.icon ?: "🏷"} ${selected.name}", style = MaterialTheme.typography.headlineSmall)
                    Text("${selected.namespace} · ${selected.usageCount} uses")
                    selected.description?.let { Text(it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                val source = HubContextRuntime.adapter("tags", "tag").sinceWhenSource(selected.id) ?: return@launch
                                context.startActivity(Intent(Intent.ACTION_VIEW, HubDeepLinkContract.sinceWhenCreateUri(source)))
                            }
                        }) { Text(stringResource(R.string.since_when_create_counter_action)) }
                        Button(onClick = { scope.launch { HubContextRuntime.tags().pin(selected.id, !selected.pinned); reload() } }) { Text(if (selected.pinned) "Unpin" else "Pin") }
                        OutlinedButton(onClick = { scope.launch { HubContextRuntime.tags().archive(selected.id, !selected.archived); reload() } }) { Text(if (selected.archived) "Unarchive" else "Archive") }
                        OutlinedButton(onClick = { renameDraft = selected.name }) { Text("Rename") }
                        OutlinedButton(onClick = { detailsDraft = selected }) { Text("Edit") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { mergeSource = selected }) { Text("Merge") }
                        OutlinedButton(onClick = {
                            scope.launch {
                                message = if (HubContextRuntime.tags().deleteUnused(selected.id)) "Deleted" else "Tag is still in use"
                                if (message == "Deleted") selectedId = null else reload()
                            }
                        }) { Text("Delete if unused") }
                    }
                }
                item {
                    Text("Aliases", style = MaterialTheme.typography.titleMedium)
                    aliases.forEach { alias ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(alias.alias)
                            TextButton(onClick = { scope.launch { HubContextRuntime.tags().removeAlias(selected.id, alias.alias); reload() } }) { Text("Remove") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(aliasDraft, { aliasDraft = it }, Modifier.weight(1f), label = { Text("New alias") })
                        Button(onClick = { scope.launch { HubContextRuntime.tags().addAlias(selected.id, aliasDraft); aliasDraft = ""; reload() } }, enabled = aliasDraft.isNotBlank()) { Text("Add") }
                    }
                }
                item {
                    Text("Usage by module/type", style = MaterialTheme.typography.titleMedium)
                    linked.groupingBy { "${it.ref.moduleId} / ${it.ref.entityKind}" }.eachCount().forEach { (kind, count) -> Text("$kind · $count") }
                    Text("Recent linked items", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                }
                items(linked, key = { "${it.ref.moduleId}/${it.ref.entityKind}/${it.ref.canonicalId}" }) { summary ->
                    Card(Modifier.fillMaxWidth().clickable {
                        scope.launch {
                            val target = withContext(Dispatchers.IO) {
                                HubContextRuntime.adapter(summary.ref.moduleId, summary.ref.entityKind).openTarget(summary.ref.canonicalId)
                            } ?: return@launch
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target.uri)).apply {
                                target.activityClassName?.let { setClassName(context.packageName, it) }
                            })
                        }
                    }) { Column(Modifier.padding(12.dp)) { Text(summary.label); Text("${summary.ref.moduleId} · ${summary.ref.entityKind}") } }
                }
            }
            TextButton(onClick = { selectedId = null }) { Text("All tags") }
        }
    }

    renameDraft?.let { draft ->
        TextInputDialog("Rename tag", draft, { renameDraft = it }, onDismiss = { renameDraft = null }) {
            scope.launch { HubContextRuntime.tags().rename(requireNotNull(selectedId), renameDraft.orEmpty()); renameDraft = null; reload() }
        }
    }
    createDraft?.let { draft ->
        val tagCreatedAt = remember { System.currentTimeMillis() }
        var createSinceWhen by remember { mutableStateOf(false) }
        TextInputDialog(
            "Create tag in ${namespace ?: HubTagNamespaces.GLOBAL}",
            draft,
            { createDraft = it },
            onDismiss = { createDraft = null },
            extraContent = {
                SinceWhenCreationControl(
                    timestampSources = listOf(SinceWhenTimestampSource("tag_created", stringResource(R.string.since_when_tag_created), tagCreatedAt, true)),
                    enabled = createSinceWhen,
                    selectedSourceId = "tag_created",
                    saving = false,
                    onEnabledChange = { createSinceWhen = it },
                    onSourceSelected = {},
                )
            },
        ) {
            scope.launch {
                val targetNamespace = namespace ?: HubTagNamespaces.GLOBAL
                val outcome = HubContextRuntime.tags().create(
                    targetNamespace,
                    createDraft.orEmpty(),
                    kind = if (targetNamespace == HubTagNamespaces.SOLDI_CATEGORY) HubTagKinds.CATEGORY else HubTagKinds.FREE,
                )
                val created = outcome.tag
                when {
                    created != null -> {
                        if (createSinceWhen) {
                            HubContextRuntime.adapter("tags", "tag").sinceWhenSource(created.id)
                                ?.let { context.launchSinceWhenCreate(it, "tag_created") }
                        }
                        selectedId = created.id
                        createDraft = null
                        reload()
                    }
                    outcome.exactDuplicate != null -> message = "Tag already exists"
                    outcome.nearDuplicates.isNotEmpty() -> message = "Possible duplicate: ${outcome.nearDuplicates.joinToString { it.name }}"
                }
            }
        }
    }
    detailsDraft?.let { original ->
        TagDetailsDialog(original, onDismiss = { detailsDraft = null }) { description, icon, color ->
            scope.launch { HubContextRuntime.tags().updateDetails(original.id, description, icon, color); detailsDraft = null; reload() }
        }
    }
    mergeSource?.let { source ->
        AlertDialog(
            onDismissRequest = { mergeSource = null },
            title = { Text("Merge ${source.name} into") },
            text = { Column { tags.filter { it.namespace == source.namespace && it.id != source.id }.forEach { target -> TextButton(onClick = { scope.launch { HubContextRuntime.tags().merge(source.id, target.id); selectedId = target.id; mergeSource = null; reload() } }) { Text(target.name) } } } },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { mergeSource = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TextInputDialog(
    title: String,
    value: String,
    onValue: (String) -> Unit,
    onDismiss: () -> Unit,
    extraContent: (@Composable () -> Unit)? = null,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value, onValue)
                extraContent?.invoke()
            }
        },
        confirmButton = { TextButton(onClick = onSave, enabled = value.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TagDetailsDialog(tag: HubTagEntity, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var description by remember(tag.id) { mutableStateOf(tag.description.orEmpty()) }
    var icon by remember(tag.id) { mutableStateOf(tag.icon.orEmpty()) }
    var color by remember(tag.id) { mutableStateOf(tag.color.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tag details") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(description, { description = it }, label = { Text("Description") })
            OutlinedTextField(icon, { icon = it }, label = { Text("Icon / emoji") })
            OutlinedTextField(color, { color = it }, label = { Text("Color") })
        } },
        confirmButton = { TextButton(onClick = { onSave(description, icon, color) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
