@file:android.annotation.SuppressLint("LocalContextGetResourceValueCall")

package com.gernalix.personalhub

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import com.gernalix.personalhub.core.ui.HubFeedbackHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gernalix.personalhub.core.ui.R as UiR
import com.gernalix.personalhub.capsules.shortcuts.HubModule
import com.gernalix.personalhub.capsules.shortcuts.LauncherShortcutsCapsule
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.core.database.HubActivityEntity
import com.gernalix.personalhub.core.database.HubActivityPayloadKind
import com.gernalix.personalhub.core.database.HubActivityStatus
import com.gernalix.personalhub.core.database.HubActivityUndoConflict
import com.gernalix.personalhub.core.database.HubActivityUndoEffect
import com.gernalix.personalhub.core.database.HubActivityUndoEngine
import com.gernalix.personalhub.core.database.HubActivityUndoResult
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.database.capsules.gitdata.GitDataSettings
import com.gernalix.personalhub.core.database.capsules.gitdata.GitHistory
import com.gernalix.personalhub.core.database.capsules.gitdata.GitHistoryItem
import com.gernalix.personalhub.core.database.capsules.gitdata.GitHistoryStore
import com.gernalix.personalhub.core.database.capsules.history.HistoryQueryCapsule
import com.gernalix.personalhub.core.database.capsules.identity.CanonicalIdentityCapsule
import com.gernalix.personalhub.core.database.capsules.mutationevents.MutationEventStore
import com.gernalix.personalhub.core.database.capsules.mutationevents.MutationUndoResolver
import com.gernalix.personalhub.core.database.capsules.mutationevents.MutationUndoTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.gernalix.personalhub.core.hubcontext.HubContextRuntime
import com.gernalix.personalhub.core.hubcontext.formatHubDateTime
import com.gernalix.personalhub.core.hubcontext.parseHubDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val HISTORY_PAGE_SIZE = 50

private data class ActivityUiItem(
    val id: String,
    val occurredAt: Long,
    val moduleId: String,
    val title: String,
    val detail: String?,
    val searchText: String,
    val relatedCount: Int,
    val navigationRef: HubEntityRef?,
    val undoActivityId: String?,
    val activity: HubActivityEntity? = null,
    val git: GitHistoryItem? = null,
    val semanticTransactionId: String? = null,
    val semanticEventIds: Set<String> = emptySet(),
    val semanticEntityIds: Set<String> = emptySet(),
    val semanticEntityKinds: Set<String> = emptySet(),
    val isRecord: Boolean = false,
    val provenance: String? = null,
    val patchId: String? = null,
    val patchRef: String? = null,
)

private data class ParsedHistoryBoundary(
    val value: Long?,
    val valid: Boolean,
)

@Composable
fun HubHistorySearchScreen(
    onBack: () -> Unit,
    initialEventId: String? = null,
    scopeModuleId: String? = null,
    initialModules: Set<String> = emptySet(),
    initialQuery: String = "",
    initialFromMs: Long? = null,
    initialToMs: Long? = null,
    initialEntityKind: String? = null,
    initialEntityId: String? = null,
) {
    val context = LocalContext.current
    val database = remember(context) { PersonalHubDatabase.get(context) }
    val gitEnabled = remember(context) {
        runCatching { GitDataSettings.configuration(context).enabled }.getOrDefault(false)
    }
    val coroutineScope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val modules = remember { HubModule.entries.toList() }
    val allModuleIds = remember(modules) { modules.map { it.shortcutPath }.toSet() + setOf("since_when", "tags", "hub", "settings") }
    val initialSelection = remember(scopeModuleId, initialModules, allModuleIds) {
        when {
            scopeModuleId != null -> setOf(scopeModuleId)
            initialModules.isNotEmpty() -> initialModules.intersect(allModuleIds).ifEmpty { allModuleIds }
            else -> allModuleIds
        }
    }

    var selectedModuleIds by rememberSaveable(scopeModuleId, initialModules) {
        mutableStateOf(initialSelection.sorted())
    }
    var moduleMenuOpen by remember { mutableStateOf(false) }
    var query by rememberSaveable(initialQuery) { mutableStateOf(initialQuery) }
    var fromText by rememberSaveable(initialFromMs) {
        mutableStateOf(initialFromMs?.let(::formatHubDateTime).orEmpty())
    }
    var toText by rememberSaveable(initialToMs) {
        mutableStateOf(initialToMs?.let(::formatHubDateTime).orEmpty())
    }
    var showRecords by rememberSaveable { mutableStateOf(false) }
    var requestRevision by remember { mutableStateOf(0) }
    var entries by remember { mutableStateOf<List<ActivityUiItem>>(emptyList()) }
    var nextPage by remember { mutableStateOf<HistoryQueryCapsule.Cursor?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var reviewingPatch by remember { mutableStateOf<Pair<String,String>?>(null) }
    var eventMissing by remember(initialEventId) { mutableStateOf(false) }
    var undoRevision by remember { mutableStateOf(0) }

    val selectedSet = remember(selectedModuleIds) { selectedModuleIds.toSet() }
    val parsedFrom = remember(fromText) { parseHistoryBoundary(fromText) }
    val parsedTo = remember(toText) { parseHistoryBoundary(toText) }
    val invalidRange = parsedFrom.valid &&
        parsedTo.valid &&
        parsedFrom.value != null &&
        parsedTo.value != null &&
        parsedFrom.value!! > parsedTo.value!!
    val filtersDirty = query.isNotBlank() ||
        fromText.isNotBlank() ||
        toText.isNotBlank() ||
        (scopeModuleId == null && selectedSet != allModuleIds)

    suspend fun refreshVisibleEntries(debounceQuery: Boolean = false, append: Boolean = false) {
        eventMissing = false
        loadFailed = false
        if (!parsedFrom.valid || !parsedTo.valid || invalidRange || (selectedSet.isEmpty() && initialEventId == null)) {
            entries = emptyList()
            nextPage = null
            return
        }
        if (debounceQuery) delay(300)
        val requestedRevision = requestRevision
        val result = withContext(Dispatchers.IO) {
            val db = database.openHelper.readableDatabase
            val filter = HistoryQueryCapsule.Filter(
                modules = if (scopeModuleId == null && selectedSet == allModuleIds) null else selectedSet,
                fromMs = parsedFrom.value, toMs = parsedTo.value, query = query,
                entityKind = initialEntityKind, entityId = initialEntityId, eventId = initialEventId,
            )
            val page = if (showRecords) HistoryQueryCapsule.records(db, filter, if (append) nextPage else null, HISTORY_PAGE_SIZE)
                else HistoryQueryCapsule.page(db, filter, if (append) nextPage else null, HISTORY_PAGE_SIZE)
            val items = page.groups.flatMap { group ->
                when (group.source) {
                    "patch" -> {
                        val json = db.query("SELECT metadata_json FROM hub_git_applied_patches WHERE id=?",arrayOf(group.groupId)).use { c ->
                            if(c.moveToFirst() && !c.isNull(0)) JSONObject(c.getString(0)) else JSONObject()
                        }
                        val ref = json.optString("revision").takeIf { it.isNotBlank() && it != "null" }
                        listOf(ActivityUiItem(id=group.key,occurredAt=group.occurredAt,moduleId=group.module,
                            title=context.getString(UiR.string.history_patch_applied),
                            detail=group.groupId,searchText=group.groupId,
                            relatedCount=json.optInt("operation_count",1),navigationRef=null,undoActivityId=null,
                            patchId=group.groupId,patchRef=ref,
                            provenance=json.optString("author").takeIf { it.isNotBlank() }?.let { "$it · Git" } ?: "Git",
                        ))
                    }
                    "record" -> listOf(ActivityUiItem(
                        id = "record:${group.key}", occurredAt = group.occurredAt, moduleId = group.module,
                        title = group.label?.takeIf(String::isNotBlank) ?: context.getString(when(group.entityKind) {
                            "people/person" -> UiR.string.history_record_person
                            "wordpulse/session" -> UiR.string.history_record_typing_session
                            else -> UiR.string.history_record_generic
                        }), detail = group.detail?.replace('_', ' '),
                        searchText = group.label.orEmpty(), relatedCount = 1,
                        navigationRef = group.entityKind?.let { HubEntityRef(it.substringBefore('/'),it.substringAfter('/'),group.groupId) },
                        undoActivityId = null, isRecord = true,
                    ))
                    "semantic" -> semanticHistoryRows(MutationEventStore.byTransaction(db, group.groupId)
                        .filter { (if (it.module == "money") "soldi" else it.module) == group.module }).map { row ->
                        val primary = row.primaryEvent
                        val ref = primary?.let { event ->
                            val kind = event.entityKind
                            if (kind != null && event.canonicalId != null) HubEntityRef(
                                kind.substringBefore('/'), kind.substringAfter('/'), event.canonicalId!!,
                            ) else event.entityId?.let { HubEntityRef(group.module, event.entityType, it) }
                        }
                        ActivityUiItem(
                            id = group.key, occurredAt = group.occurredAt, moduleId = group.module,
                            title = row.text.title, detail = row.text.detail, searchText = row.text.searchText,
                            relatedCount = row.events.size, navigationRef = ref, undoActivityId = null,
                            provenance = row.events.map { it.actorType.replace('_',' ') + " · " + it.actorSource.orEmpty().replace('_',' ') }.distinct().joinToString("; "),
                            semanticTransactionId = row.transactionId,
                            semanticEventIds = row.events.mapTo(mutableSetOf()) { it.eventId },
                        )
                    }
                    "git" -> {
                        val rows = GitHistoryStore.byGroup(db, group.groupId).ifEmpty {
                            listOfNotNull(GitHistoryStore.find(db, group.groupId))
                        }.filter { gitHistoryModule(it.table) == group.module }
                        if (rows.isEmpty() || !displayableGitHistoryGroup(rows)) emptyList() else {
                            val (item, text) = humanizeGitHistoryGroup(rows) { module -> moduleDisplayName(context, module) }
                            val canonical = runCatching { CanonicalIdentityCapsule.eventRef(db, item.table, item.rowKey) }.getOrNull()
                            listOf(ActivityUiItem(
                                id = group.key, occurredAt = group.occurredAt, moduleId = group.module,
                                title = text.title, detail = text.detail, searchText = text.searchText,
                                relatedCount = rows.size, navigationRef = canonical?.let {
                                    HubEntityRef(it.entityKind.substringBefore('/'), it.entityKind.substringAfter('/'), it.canonicalId)
                                }, undoActivityId = null, git = item,
                                provenance = item.author + " · Git · " + item.commitSha.take(12),
                            ))
                        }
                    }
                    else -> resolveActivityItems(context, database.activityDao().historyGroup(group.groupId)
                        .filter { it.moduleId == group.module }).map { it.copy(id = group.key, occurredAt = group.occurredAt) }
                }
            }
            page to items
        }
        if (requestedRevision != requestRevision) return
        entries = if (append) entries + result.second else result.second
        nextPage = result.first.next
        eventMissing = initialEventId != null && entries.isEmpty()
    }

    LaunchedEffect(
        initialEventId,
        scopeModuleId,
        selectedModuleIds,
        query,
        showRecords,
        fromText,
        toText,
        initialEntityKind,
        initialEntityId,
    ) {
        requestRevision++
        entries = emptyList()
        nextPage = null
        loading = true
        try {
            refreshVisibleEntries(debounceQuery = true)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            loadFailed = true
        } finally {
            loading = false
        }
    }

    reviewingPatch?.let { (id,ref) ->
        androidx.activity.compose.BackHandler { reviewingPatch = null }
        com.gernalix.personalhub.capsules.settings.GitPatchReviewPanel(id,ref,onBack={ reviewingPatch=null },onApplied={ reviewingPatch=null })
        return
    }

    Scaffold(snackbarHost = { HubFeedbackHost(snackbar) }) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onBack) { Text(stringResource(R.string.activity_back)) }
                Text(
                    text = buildString {
                        append(stringResource(R.string.activity_title))
                        scopeModuleId?.let {
                            append(" · ")
                            append(moduleDisplayName(context, it))
                        }
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (initialEventId == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !showRecords, onClick = { showRecords = false },
                        label = { Text(stringResource(UiR.string.history_mutations)) }, modifier = Modifier.testTag("history-mode-mutations"))
                    FilterChip(selected = showRecords, onClick = { showRecords = true },
                        label = { Text(stringResource(UiR.string.history_records)) }, modifier = Modifier.testTag("history-mode-records"))
                }
                Text(stringResource(if (showRecords) UiR.string.history_records_help else UiR.string.history_mutations_help), style = MaterialTheme.typography.bodySmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = fromText,
                        onValueChange = { fromText = it },
                        modifier = Modifier.weight(1f).testTag("history-from"),
                        label = { Text(stringResource(R.string.activity_from)) },
                        isError = !parsedFrom.valid || invalidRange,
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = toText,
                        onValueChange = { toText = it },
                        modifier = Modifier.weight(1f).testTag("history-to"),
                        label = { Text(stringResource(R.string.activity_to)) },
                        isError = !parsedTo.valid || invalidRange,
                        singleLine = true,
                    )
                }

                if (scopeModuleId == null) {
                    androidx.compose.foundation.layout.Box(modifier = Modifier.testTag("history-module-filter")) {
                        OutlinedButton(onClick = { moduleMenuOpen = true }) {
                            Text(stringResource(R.string.activity_modules_selected, selectedSet.size, allModuleIds.size))
                        }
                        DropdownMenu(expanded = moduleMenuOpen, onDismissRequest = { moduleMenuOpen = false }) {
                            allModuleIds.sorted().forEach { moduleId ->
                                val checked = moduleId in selectedSet
                                DropdownMenuItem(
                                    text = { Text(moduleDisplayName(context,moduleId)) },
                                    leadingIcon = { Checkbox(checked = checked, onCheckedChange = null) },
                                    onClick = {
                                        selectedModuleIds = (if (checked) selectedSet - moduleId
                                            else selectedSet + moduleId).sorted()
                                        moduleMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                }

                com.gernalix.personalhub.core.ui.HubSearchField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().testTag("history-query"),
                    label = stringResource(R.string.activity_search_hint),
                )

                if (filtersDirty) {
                    TextButton(
                        onClick = {
                            query = ""
                            fromText = ""
                            toText = ""
                            if (scopeModuleId == null) selectedModuleIds = allModuleIds.sorted()
                        },
                        modifier = Modifier.testTag("history-reset"),
                    ) {
                        Text(stringResource(R.string.activity_reset_filters))
                    }
                }
            }

            if (invalidRange) {
                Text(
                    text = stringResource(R.string.activity_invalid_range),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (!parsedFrom.valid || !parsedTo.valid) {
                Text(
                    text = stringResource(R.string.activity_invalid_date),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (loadFailed) Text(stringResource(UiR.string.history_load_failed), color = MaterialTheme.colorScheme.error,modifier=Modifier.testTag("history-load-error"))

            if (entries.isNotEmpty()) Text(stringResource(UiR.string.history_loaded_count,entries.size),modifier=Modifier.testTag("history-loaded-count"),style=MaterialTheme.typography.labelSmall)
            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (entries.isEmpty() && !loading && nextPage == null) {
                Text(
                    text = stringResource(
                        if (eventMissing) R.string.activity_event_not_found else if (showRecords) UiR.string.history_records_empty else R.string.activity_empty,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).testTag("history-results"),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    entries.forEachIndexed { index, activityRow ->
                        if (index == 0 || historyDayLabel(activityRow.occurredAt) != historyDayLabel(entries[index - 1].occurredAt)) {
                            item(key = "day-${activityRow.id}") { Text(historyDayLabel(activityRow.occurredAt), style = MaterialTheme.typography.labelMedium) }
                        }
                        item(key = activityRow.id) {
                        ActivityCard(
                            item = activityRow,
                            moduleName = moduleDisplayName(context, activityRow.moduleId),
                            undoRevision = undoRevision,
                            onOpen = { coroutineScope.launch {
                                if (activityRow.patchId != null && activityRow.patchRef != null) {
                                    reviewingPatch = activityRow.patchId to activityRow.patchRef
                                    return@launch
                                }
                                if (!openActivityTarget(context, activityRow)) snackbar.showSnackbar(context.getString(UiR.string.history_target_unavailable))
                            } },
                            onUndo = {
                                val semanticTransaction = activityRow.semanticTransactionId
                                if (semanticTransaction != null) {
                                    coroutineScope.launch {
                                        val target = withContext(Dispatchers.IO) {
                                            MutationUndoResolver.resolve(database.openHelper.readableDatabase, semanticTransaction, gitEnabled)
                                        }
                                        val success = when (target) {
                                            is MutationUndoTarget.Git -> withContext(Dispatchers.IO) {
                                                runCatching {
                                                    val preview = GitHistory.previewRevert(context.applicationContext, target.gitEventId)
                                                    check(preview.safe) { preview.blockingReason ?: "Undo unavailable" }
                                                    GitHistory.revertEvent(context.applicationContext, target.gitEventId)
                                                }.isSuccess
                                            }
                                            is MutationUndoTarget.Activity -> {
                                                when (val result = HubActivityUndoEngine.undo(database, target.activityId)) {
                                                    is HubActivityUndoResult.Success -> {
                                                        result.entityRef?.let { ref ->
                                                            when (result.effect) {
                                                                HubActivityUndoEffect.DELETED -> HubContextRuntime.canonicalDeletedIfInitialized(ref)
                                                                HubActivityUndoEffect.LIFECYCLE_CHANGED -> HubContextRuntime.canonicalLifecycleChangedIfInitialized(ref)
                                                            }
                                                        }
                                                        true
                                                    }
                                                    is HubActivityUndoResult.Conflict -> false
                                                }
                                            }
                                            null -> false
                                        }
                                        snackbar.showSnackbar(context.getString(
                                            if (success) R.string.activity_undo_success else R.string.activity_undo_not_supported,
                                        ))
                                        undoRevision++
                                        refreshVisibleEntries()
                                    }
                                } else if (activityRow.git != null) {
                                    val gitItem = activityRow.git
                                    coroutineScope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            runCatching {
                                                val preview = GitHistory.previewRevert(context.applicationContext, gitItem.id)
                                                check(preview.safe) { preview.blockingReason ?: "Undo unavailable" }
                                                GitHistory.revertEvent(context.applicationContext, gitItem.id)
                                            }
                                        }
                                        snackbar.showSnackbar(context.getString(
                                            if (result.isSuccess) R.string.activity_undo_success else R.string.activity_undo_not_supported,
                                        ))
                                        undoRevision++
                                        refreshVisibleEntries()
                                    }
                                } else if (activityRow.undoActivityId != null) coroutineScope.launch {
                                    val undoId = activityRow.undoActivityId
                                    when (val result = HubActivityUndoEngine.undo(database, undoId)) {
                                        is HubActivityUndoResult.Success -> {
                                            result.entityRef?.let { ref ->
                                                when (result.effect) {
                                                    HubActivityUndoEffect.DELETED ->
                                                        HubContextRuntime.canonicalDeletedIfInitialized(ref)
                                                    HubActivityUndoEffect.LIFECYCLE_CHANGED ->
                                                        HubContextRuntime.canonicalLifecycleChangedIfInitialized(ref)
                                                }
                                            }
                                            snackbar.showSnackbar(context.getString(R.string.activity_undo_success))
                                            undoRevision++
                                            refreshVisibleEntries()
                                        }
                                        is HubActivityUndoResult.Conflict -> {
                                            snackbar.showSnackbar(context.getString(result.reason.messageRes()))
                                            undoRevision++
                                            refreshVisibleEntries()
                                        }
                                    }
                                }
                            },
                        )
                        }
                    }
                    if (nextPage != null) item(key = "more") {
                        TextButton(enabled = !loading, modifier = Modifier.testTag("history-more"), onClick = {
                            coroutineScope.launch {
                                loading = true
                                try { refreshVisibleEntries(append = true) }
                                catch (error: kotlinx.coroutines.CancellationException) { throw error }
                                catch (_: Exception) { loadFailed = true }
                                finally { loading = false }
                            }
                        }) { Text(stringResource(R.string.temporal_more)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityCard(
    item: ActivityUiItem,
    moduleName: String,
    undoRevision: Int,
    onOpen: () -> Unit,
    onUndo: () -> Unit,
) {
    val activity = item.activity
    val context = LocalContext.current
    var gitUndoSafe by remember(item.id) { mutableStateOf(false) }
    var semanticUndoSafe by remember(item.id) { mutableStateOf(false) }
    val canOpen = item.navigationRef != null || (item.patchId != null && item.patchRef != null)
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    LaunchedEffect(item.semanticTransactionId, undoRevision) {
        semanticUndoSafe = false
        val transactionId = item.semanticTransactionId ?: return@LaunchedEffect
        semanticUndoSafe = withContext(Dispatchers.IO) {
                val target = MutationUndoResolver.resolve(
                    PersonalHubDatabase.get(context).openHelper.readableDatabase,
                    transactionId,
                    GitDataSettings.configuration(context).enabled,
                )
                when (target) {
                    is MutationUndoTarget.Activity -> true
                    is MutationUndoTarget.Git -> runCatching {
                        GitHistory.previewRevert(context.applicationContext, target.gitEventId).safe
                    }.getOrDefault(false)
                    null -> false
                }
        }
    }
    LaunchedEffect(item.git?.id, expanded, undoRevision) {
        gitUndoSafe = false
        val git = item.git ?: return@LaunchedEffect
        if (expanded && git.revertedBy == null) {
            gitUndoSafe = withContext(Dispatchers.IO) {
                runCatching { GitHistory.previewRevert(context.applicationContext, git.id).safe }.getOrDefault(false)
            }
        }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("history-row-${item.id}")
            .clickable(enabled = item.detail != null || canOpen) { expanded = !expanded },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = "${item.title} · $moduleName · ${historyTimeLabel(item.occurredAt)}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = if (expanded) 6 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!item.isRecord && (item.undoActivityId != null || gitUndoSafe || semanticUndoSafe)) OutlinedButton(
                    onClick = onUndo,
                    enabled = item.undoActivityId != null || gitUndoSafe || semanticUndoSafe,
                    modifier = Modifier.testTag("history-undo"),
                ) {
                    Text("↩️")
                }
            }

            if (item.relatedCount > 1) {
                Text(
                    text = stringResource(R.string.activity_related_changes, item.relatedCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when (activity?.status) {
                HubActivityStatus.REVERTED ->
                    AssistChip(onClick = {}, label = { Text(stringResource(R.string.activity_status_reverted)) })
                HubActivityStatus.CONFLICT ->
                    AssistChip(onClick = {}, label = { Text(stringResource(R.string.activity_status_conflict)) })
                else -> Unit
            }

            if (expanded) {
                item.provenance?.let { Text(stringResource(UiR.string.history_provenance,it), style = MaterialTheme.typography.labelSmall) }
                item.detail?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item.undoActivityId == null && item.git == null && !semanticUndoSafe &&
                    (activity?.status == HubActivityStatus.ACTIVE || item.semanticTransactionId != null)) {
                    Text(
                        text = stringResource(R.string.activity_status_non_reversible),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item.git?.let { git ->
                    TextButton(onClick = {
                        val repository = GitDataSettings.configuration(context).repositoryUrl.removeSuffix(".git").trimEnd('/')
                        context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("$repository/commit/${git.commitSha}")))
                    }) { Text(stringResource(UiR.string.history_view_git)) }
                }
                if (canOpen) {
                    TextButton(onClick = onOpen,modifier=Modifier.testTag("history-open")) {
                        Text(stringResource(R.string.activity_open))
                    }
                }
            }
        }
    }
}

private suspend fun resolveActivityItems(
    context: Context,
    rows: List<HubActivityEntity>,
): List<ActivityUiItem> {
    val db = PersonalHubDatabase.get(context).openHelper.readableDatabase
    val canonicalRefs = rows.associate { activity ->
        val canonical = activity.sourceRowKey?.let { key ->
            runCatching { CanonicalIdentityCapsule.eventRef(db,activity.sourceTable,key) }.getOrNull()
        }
        val ref = canonical?.let { HubEntityRef(it.entityKind.substringBefore('/'),it.entityKind.substringAfter('/'),it.canonicalId) }
            ?: navigationRef(activity)?.let { runCatching { CanonicalIdentityCapsule.normalize(db,it) }.getOrNull() }
        activity.id to ref
    }
    val refs = canonicalRefs.values.filterNotNull().distinct()
    val adapters = runCatching { HubContextRuntime.adapters() }.getOrDefault(emptyList())
    val supportedRefs = refs.filter { ref ->
        adapters.any { adapter -> adapter.moduleId == ref.moduleId && adapter.entityKind == ref.entityKind }
    }
    val summaries = runCatching { HubContextRuntime.summaries(supportedRefs) }.getOrDefault(emptyMap())
    val labels = rows.associate { activity ->
        val ref = canonicalRefs[activity.id]
        activity.id to (
            activity.entityLabel?.takeIf(String::isNotBlank)
                ?: ref?.let { summaries[it]?.label }
        )
    }

    return groupActivityRows(rows).map { group ->
        val primary = group.first()
        val texts = group.map { activity ->
            humanizeActivity(
                activity = activity,
                subjectLabel = labels[activity.id],
                moduleLabel = moduleDisplayName(context, activity.moduleId),
            )
        }
        val detail = texts
            .mapNotNull(HumanActivityText::detail)
            .flatMap { it.lines() }
            .distinct()
            .takeIf(List<String>::isNotEmpty)
            ?.joinToString("\n")
        ActivityUiItem(
            id = primary.groupId ?: primary.id,
            occurredAt = primary.occurredAt,
            moduleId = primary.moduleId,
            navigationRef = group.mapNotNull { canonicalRefs[it.id] }.firstOrNull(),
            title = texts.first().title,
            detail = detail,
            searchText = texts.joinToString(" ") { it.searchText },
            relatedCount = group.size,
            undoActivityId = safeUndoActivityId(group),
            activity = primary,
        )
    }
}

private fun parseHistoryBoundary(text: String): ParsedHistoryBoundary {
    if (text.isBlank()) return ParsedHistoryBoundary(null, true)
    return runCatching { parseHubDateTime(text) }
        .fold(
            onSuccess = { ParsedHistoryBoundary(it, true) },
            onFailure = { ParsedHistoryBoundary(null, false) },
        )
}

private fun navigationRef(activity: HubActivityEntity): HubEntityRef? {
    if (activity.payloadKind == HubActivityPayloadKind.PLACES_AUDIT_V1) {
        val payload = activity.afterPayload ?: activity.beforePayload
        val placeId = runCatching { JSONObject(payload.orEmpty()).optString("place_id") }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
        if (placeId != null) return HubEntityRef("places", "place", placeId)
    }
    if (activity.payloadKind == HubActivityPayloadKind.TIMER_AUDIT_V1) {
        val entityId = activity.entityId ?: return null
        if (activity.detailKey?.contains("session", ignoreCase = true) == true) {
            return HubEntityRef("timer", "session", entityId)
        }
    }
    val kind = activity.entityKind ?: return null
    val id = activity.entityId ?: return null
    return HubEntityRef(activity.moduleId, kind, id)
}

private suspend fun openActivityTarget(context: Context, item: ActivityUiItem): Boolean {
    val ref = item.navigationRef
    val target = ref?.let { entityRef ->
        runCatching {
            HubContextRuntime.adapters()
                .firstOrNull { it.moduleId == entityRef.moduleId && it.entityKind == entityRef.entityKind }
                ?.openTarget(entityRef.canonicalId)
        }.getOrNull()
    }
    if (target != null) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target.uri))
        target.activityClassName?.let { intent.setClassName(context.packageName, it) }
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }
    return false
}

private fun moduleDisplayName(context: Context, moduleId: String): String =
    moduleFor(moduleId)?.let { context.getString(it.titleRes) }
        ?: when (moduleId) {
            "hub" -> context.getString(R.string.app_name)
            "settings" -> context.getString(R.string.settings_title)
            "tags" -> "Tags"
            "since_when" -> context.getString(R.string.since_when_title)
            else -> moduleId
                .replace('_', ' ')
                .replaceFirstChar(Char::uppercase)
        }

private fun moduleFor(moduleId: String): HubModule? =
    HubModule.entries.firstOrNull { it.shortcutPath == moduleId }

private fun HubActivityUndoConflict.messageRes(): Int = when (this) {
    HubActivityUndoConflict.NOT_FOUND -> R.string.activity_undo_not_found
    HubActivityUndoConflict.NOT_REVERSIBLE,
    HubActivityUndoConflict.UNSUPPORTED,
    -> R.string.activity_undo_not_supported
    HubActivityUndoConflict.ALREADY_REVERTED -> R.string.activity_undo_already_reverted
    HubActivityUndoConflict.STALE -> R.string.activity_undo_stale
    HubActivityUndoConflict.REFERENCED -> R.string.activity_undo_referenced
    HubActivityUndoConflict.INVALID_PAYLOAD -> R.string.activity_undo_invalid
}
