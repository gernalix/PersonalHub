package com.gernalix.personalhub.core.hubcontext

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.contracts.database.HubEntitySummary
import kotlinx.coroutines.launch

internal class HubExplorerState(
    val start: HubEntityRef,
    restoredScope: List<HubEntitySummary> = emptyList(),
    private var initialized: Boolean = false,
) {
    var scope by mutableStateOf(restoredScope)
    var result by mutableStateOf<HubExplorerResult?>(null)
    var limit by mutableIntStateOf(60)
    var error by mutableStateOf<String?>(null)

    suspend fun initialize() {
        if (!initialized) {
            val first = HubContextRuntime.adapter(start.moduleId, start.entityKind).summaries(setOf(start.canonicalId))[start.canonicalId]
            scope = listOfNotNull(first)
            initialized = true
        }
        refresh()
    }

    suspend fun select(candidate: HubEntitySummary) {
        if (scope.none { it.ref == candidate.ref }) scope = scope + candidate
        refresh()
    }

    suspend fun truncate(index: Int) { scope = scope.take(index + 1); refresh() }
    suspend fun back() { if (scope.size > 1) { scope = scope.dropLast(1); refresh() } }
    suspend fun restart() { scope = scope.take(1); refresh() }
    suspend fun loadMore() { limit = (limit + 60).coerceAtMost(200); refresh() }

    private suspend fun refresh() {
        result = HubContextRuntime.explore(scope.map { it.ref }, limit)
        error = null
    }

    companion object {
        val Saver = Saver<HubExplorerState, List<Any?>>(
            save = { state -> listOf(
                listOf(state.start.moduleId, state.start.entityKind, state.start.canonicalId),
                state.scope.map { listOf(it.ref.moduleId, it.ref.entityKind, it.ref.canonicalId, it.label, it.description, it.lifecycle) },
                state.limit,
            ) },
            restore = { saved ->
                @Suppress("UNCHECKED_CAST")
                val start = (saved[0] as List<String>).let { HubEntityRef(it[0], it[1], it[2]) }
                @Suppress("UNCHECKED_CAST")
                val scope = (saved[1] as List<List<String?>>).map { row -> HubEntitySummary(HubEntityRef(row[0]!!, row[1]!!, row[2]!!), row[3]!!, row[4], row[5]!!) }
                HubExplorerState(start, scope, initialized = true).also { it.limit = saved[2] as Int }
            },
        )
    }
}

@Composable
internal fun HubContextExplorerDialog(anchor: HubEntityRef, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberSaveable(anchor, saver = HubExplorerState.Saver) { HubExplorerState(anchor) }
    var composerOpen by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var sections by remember { mutableStateOf<List<HubRelatedSection>>(emptyList()) }
    val preferences = remember(anchor) { HubRelatedSectionPreferences(context, anchor) }
    suspend fun refreshSections() {
        state.initialize()
        val defaults = state.result.orEmptyFacets().map { HubRelatedSection(it.moduleId, it.entityKind) }
        sections = preferences.load(defaults)
    }
    LaunchedEffect(state) { runCatching { refreshSections() }.onFailure { state.error = it.message } }
    BackHandler(state.scope.size > 1) { scope.launch { state.back() } }
    if (composerOpen) HubContextComposerDialog(anchor, null, { composerOpen = false }, { composerOpen = false; scope.launch { state.initialize() } }, state.scope.map { it.ref })
    if (settingsOpen) HubSectionSettingsDialog(sections, { settingsOpen = false }, { updated -> preferences.save(updated); sections = updated; settingsOpen = false })

    AlertDialog(
        onDismissRequest = {
            if (state.scope.size > 1) scope.launch { state.back() } else onDismiss()
        },
        title = { Text(stringResource(R.string.hub_explorer_title)) },
        text = { LazyColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.scope.forEachIndexed { index, entity ->
                        AssistChip(onClick = { scope.launch { state.truncate(index) } }, label = { Text(entity.label) })
                    }
                }
                Row {
                    TextButton(onClick = { scope.launch { state.restart() } }) { Text(stringResource(R.string.hub_restart)) }
                    TextButton(onClick = { settingsOpen = true }) { Text(stringResource(R.string.hub_sections)) }
                    TextButton(onClick = { composerOpen = true }) { Text(stringResource(R.string.hub_composer_from_scope)) }
                }
            }
            val facets = state.result?.facets.orEmpty().associateBy { "${it.moduleId}/${it.entityKind}" }
            sections.filter { it.visible }.forEach { section -> facets[section.key]?.let { facet ->
                item { Text(facet.entityKind, style = MaterialTheme.typography.titleSmall) }
                items(facet.candidates, key = { it.summary.ref.toString() }) { candidate ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${candidate.summary.label} — ${candidate.compatibleContextCount}", Modifier.weight(1f).clickable { scope.launch { state.select(candidate.summary) } })
                        TextButton(onClick = {
                            scope.launch {
                                runCatching {
                                    val target = HubContextRuntime.adapter(candidate.summary.ref.moduleId, candidate.summary.ref.entityKind).openTarget(candidate.summary.ref.canonicalId)
                                        ?: error(context.getString(R.string.hub_resource_cannot_open))
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target.uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    target.activityClassName?.let { intent.setClassName(context.packageName, it) }
                                    context.startActivity(intent)
                                }.onFailure {
                                    state.error = if (it is android.content.ActivityNotFoundException) {
                                        context.getString(R.string.hub_resource_no_handler)
                                    } else it.message ?: context.getString(R.string.hub_resource_cannot_open)
                                }
                            }
                        }, modifier = Modifier.semantics { contentDescription = context.getString(R.string.hub_open_named, candidate.summary.label) }) { Text(stringResource(R.string.hub_open_detail)) }
                    }
                }
            } }
            if ((state.result?.facets?.sumOf { it.candidates.size } ?: 0) >= state.limit && state.limit < 200) item {
                TextButton(onClick = { scope.launch { state.loadMore() } }) { Text(stringResource(R.string.hub_load_more)) }
            }
            state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.hub_close)) } },
    )
}

@Composable
private fun HubSectionSettingsDialog(initial: List<HubRelatedSection>, onDismiss: () -> Unit, onSave: (List<HubRelatedSection>) -> Unit) {
    var sections by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.hub_sections)) }, text = {
        Column { sections.forEachIndexed { index, section ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Row { Checkbox(section.visible, { visible -> sections = sections.map { if (it.key == section.key) it.copy(visible = visible) else it } }); Text(section.entityKind) }
                Row {
                    TextButton(onClick = { if (index > 0) sections = sections.toMutableList().also { java.util.Collections.swap(it, index, index - 1) } }) { Text("↑") }
                    TextButton(onClick = { if (index < sections.lastIndex) sections = sections.toMutableList().also { java.util.Collections.swap(it, index, index + 1) } }) { Text("↓") }
                }
            }
        } }
    }, confirmButton = { Button(onClick = { onSave(sections) }) { Text(stringResource(R.string.hub_save)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.hub_cancel)) } })
}

private fun HubExplorerResult?.orEmptyFacets() = this?.facets.orEmpty()
