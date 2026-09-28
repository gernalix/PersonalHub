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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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

private const val ACTIVITY_SEARCH_LIMIT = 2_500

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
    val allModuleIds = remember(modules) { modules.map { it.shortcutPath }.toSet() }
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
    var entries by remember { mutableStateOf<List<ActivityUiItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
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

    suspend fun refreshVisibleEntries(debounceQuery: Boolean = false) {
        eventMissing = false
        if (!parsedFrom.valid || !parsedTo.valid || invalidRange || (selectedSet.isEmpty() && initialEventId == null)) {
            entries = emptyList()
            return
        }
        if (debounceQuery && query.isNotBlank()) delay(60)
        val semantic = withContext(Dispatchers.IO) {
            val db = database.openHelper.readableDatabase
            val source = if (initialEventId == null) MutationEventStore.recent(db, limit = 10_000)
                else MutationEventStore.byId(db, initialEventId)?.let { MutationEventStore.byTransaction(db, it.transactionId) }.orEmpty()
            semanticHistoryRows(source).map { row ->
                val primary = row.events.firstOrNull { it.module == row.module && it.entityId != null }
                ActivityUiItem(
                    id = "semantic-${row.transactionId}",
                    occurredAt = row.occurredAt,
                    moduleId = row.module,
                    title = row.text.title,
                    detail = row.text.detail,
                    searchText = row.text.searchText,
                    relatedCount = row.events.size,
                    navigationRef = primary?.entityId?.let { HubEntityRef(row.module, primary.entityType, it) },
                    undoActivityId = null,
                    semanticTransactionId = row.transactionId,
                    semanticEventIds = row.events.mapTo(mutableSetOf()) { it.eventId },
                    semanticEntityIds = row.events.mapNotNullTo(mutableSetOf()) { it.entityId },
                    semanticEntityKinds = row.events.mapTo(mutableSetOf()) { it.entityType },
                )
            }
        }
        // Earlier installations have only the prior audit stores. Keep those rows until the
        // first persisted semantic event, and keep direct links to old audit IDs working.
        val semanticStart = withContext(Dispatchers.IO) {
            MutationEventStore.oldestOccurredAt(database.openHelper.readableDatabase)
        }
        val legacy = if (gitEnabled) {
            val rows = withContext(Dispatchers.IO) { GitHistory.recentForDisplay(context.applicationContext, limit = 1000) }
            groupGitHistoryRows(rows).filter(::displayableGitHistoryGroup).map { group ->
                val (item, text) = humanizeGitHistoryGroup(group) { module -> moduleDisplayName(context, module) }
                val moduleId = gitHistoryModule(item.table)
                ActivityUiItem(
                    id = item.groupId ?: item.id,
                    occurredAt = item.occurredAt,
                    moduleId = moduleId,
                    title = text.title,
                    detail = text.detail,
                    searchText = text.searchText,
                    relatedCount = group.size,
                    navigationRef = null,
                    undoActivityId = null,
                    git = item,
                )
            }
        } else {
            val rows = if (initialEventId != null) database.activityDao().byId(initialEventId)?.let(::listOf).orEmpty()
            else database.activityDao().search(
                moduleIds = selectedSet.sorted(),
                allModules = if (scopeModuleId == null && selectedSet == allModuleIds) 1 else 0,
                includeSystem = 0,
                fromMs = parsedFrom.value,
                toMs = parsedTo.value,
                entityKind = initialEntityKind,
                entityId = initialEntityId,
                limit = ACTIVITY_SEARCH_LIMIT,
            )
            resolveActivityItems(context, rows)
        }
        val needle = normalizeHistorySearchText(query)
        entries = (semantic + legacy.filter { initialEventId != null || semanticStart == null || it.occurredAt < semanticStart })
            .filter { item ->
                if (initialEventId != null) {
                    item.semanticEventIds.contains(initialEventId) || item.git?.id == initialEventId ||
                        item.activity?.id == initialEventId || item.id == initialEventId
                } else {
                    (scopeModuleId == null || item.moduleId == scopeModuleId) &&
                        (item.moduleId in selectedSet || (scopeModuleId == null && selectedSet == allModuleIds)) &&
                        (parsedFrom.value == null || item.occurredAt >= parsedFrom.value!!) &&
                        (parsedTo.value == null || item.occurredAt <= parsedTo.value!!) &&
                        (initialEntityKind == null || item.semanticTransactionId == null ||
                            initialEntityKind in item.semanticEntityKinds) &&
                        (initialEntityId == null || item.semanticEntityIds.contains(initialEntityId) ||
                            item.git?.rowKey == initialEntityId || item.activity?.entityId == initialEntityId) &&
                        (needle.isBlank() || normalizeHistorySearchText(item.searchText).contains(needle))
                }
            }.sortedWith(compareByDescending<ActivityUiItem> { it.occurredAt }.thenByDescending { it.id })
        eventMissing = initialEventId != null && entries.isEmpty()
    }

    LaunchedEffect(
        initialEventId,
        scopeModuleId,
        selectedModuleIds,
        query,
        fromText,
        toText,
        initialEntityKind,
        initialEntityId,
    ) {
        loading = true
        try {
            refreshVisibleEntries(debounceQuery = true)
        } finally {
            loading = false
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { contentPadding ->
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
                            modules.forEach { module ->
                                val checked = module.shortcutPath in selectedSet
                                DropdownMenuItem(
                                    text = { Text(stringResource(module.titleRes)) },
                                    leadingIcon = { Checkbox(checked = checked, onCheckedChange = null) },
                                    onClick = {
                                        selectedModuleIds = (if (checked) selectedSet - module.shortcutPath
                                            else selectedSet + module.shortcutPath).sorted()
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

            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (entries.isEmpty() && !loading) {
                Text(
                    text = stringResource(
                        if (eventMissing) R.string.activity_event_not_found else R.string.activity_empty,
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
                            onOpen = { coroutineScope.launch { openActivityTarget(context, activityRow) } },
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
    val canOpen = item.navigationRef != null || moduleFor(item.moduleId) != null
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
                OutlinedButton(
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
                if (canOpen) {
                    TextButton(onClick = onOpen) {
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
    val refs = rows.mapNotNull(::navigationRef).distinct()
    val adapters = runCatching { HubContextRuntime.adapters() }.getOrDefault(emptyList())
    val supportedRefs = refs.filter { ref ->
        adapters.any { adapter -> adapter.moduleId == ref.moduleId && adapter.entityKind == ref.entityKind }
    }
    val summaries = runCatching { HubContextRuntime.summaries(supportedRefs) }.getOrDefault(emptyMap())
    val labels = rows.associate { activity ->
        val ref = navigationRef(activity)
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
            navigationRef = group.mapNotNull(::navigationRef).firstOrNull(),
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

private suspend fun openActivityTarget(context: Context, item: ActivityUiItem) {
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
        context.startActivity(intent)
        return
    }
    moduleFor(item.moduleId)?.let { module ->
        context.startActivity(LauncherShortcutsCapsule.moduleIntent(context, module))
    }
}

private fun moduleDisplayName(context: Context, moduleId: String): String =
    moduleFor(moduleId)?.let { context.getString(it.titleRes) }
        ?: when (moduleId) {
            "hub" -> context.getString(R.string.app_name)
            "settings" -> context.getString(R.string.settings_title)
            "tags" -> "Tags"
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
