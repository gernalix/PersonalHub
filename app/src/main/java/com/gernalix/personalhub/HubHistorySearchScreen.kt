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
        if (!parsedFrom.valid || !parsedTo.valid || invalidRange || selectedSet.isEmpty()) {
            entries = emptyList()
            return
        }
        if (debounceQuery && query.isNotBlank()) delay(60)
        if (gitEnabled) {
            val rows = withContext(Dispatchers.IO) { GitHistory.recentForDisplay(context.applicationContext, limit = 1000) }
            val humanized = rows.map { item ->
                val moduleId = gitHistoryModule(item.table)
                val text = humanizeGitHistory(item, moduleDisplayName(context, moduleId))
                ActivityUiItem(
                    id = item.id,
                    occurredAt = item.occurredAt,
                    moduleId = moduleId,
                    title = text.title,
                    detail = text.detail,
                    searchText = text.searchText,
                    relatedCount = 1,
                    navigationRef = null,
                    undoActivityId = null,
                    git = item,
                )
            }
            val needle = normalizeHistorySearchText(query)
            entries = humanized.filter { item ->
                (scopeModuleId == null || item.moduleId == scopeModuleId) &&
                    (item.moduleId in selectedSet || (scopeModuleId == null && selectedSet == allModuleIds)) &&
                    (parsedFrom.value == null || item.occurredAt >= parsedFrom.value!!) &&
                    (parsedTo.value == null || item.occurredAt <= parsedTo.value!!) &&
                    (initialEventId == null || item.id == initialEventId) &&
                    (initialEntityId == null || item.git?.rowKey == initialEntityId) &&
                    (needle.isBlank() || normalizeHistorySearchText(item.searchText).contains(needle))
            }.sortedWith(compareByDescending<ActivityUiItem> { it.occurredAt }.thenByDescending { it.id })
            eventMissing = initialEventId != null && entries.isEmpty()
            return
        }
        if (initialEventId != null) {
            val row = database.activityDao().byId(initialEventId)
            eventMissing = row == null
            entries = row?.let { resolveActivityItems(context, listOf(it)) }.orEmpty()
            return
        }
        val rows = database.activityDao().search(
            moduleIds = selectedSet.sorted(),
            allModules = if (scopeModuleId == null && selectedSet == allModuleIds) 1 else 0,
            includeSystem = 0,
            fromMs = parsedFrom.value,
            toMs = parsedTo.value,
            entityKind = initialEntityKind,
            entityId = initialEntityId,
            limit = ACTIVITY_SEARCH_LIMIT,
        )
        val humanized = resolveActivityItems(context, rows)
        val needle = normalizeHistorySearchText(query)
        entries = if (needle.isBlank()) humanized
        else humanized.filter { normalizeHistorySearchText(it.searchText).contains(needle) }
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
                    style = MaterialTheme.typography.headlineSmall,
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
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().testTag("history-module-filter"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(modules, key = { it.shortcutPath }) { module ->
                            val checked = module.shortcutPath in selectedSet
                            Row(
                                modifier = Modifier
                                    .clickable {
                                        selectedModuleIds = if (checked) {
                                            selectedSet.minus(module.shortcutPath).sorted()
                                        } else {
                                            selectedSet.plus(module.shortcutPath).sorted()
                                        }
                                    }
                                    .padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { isChecked ->
                                        selectedModuleIds = if (isChecked) {
                                            selectedSet.plus(module.shortcutPath).sorted()
                                        } else {
                                            selectedSet.minus(module.shortcutPath).sorted()
                                        }
                                    },
                                )
                                Text(stringResource(module.titleRes))
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().testTag("history-query"),
                    label = { Text(stringResource(R.string.activity_search_hint)) },
                    singleLine = true,
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
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(entries, key = { it.id }) { item ->
                        ActivityCard(
                            item = item,
                            moduleName = moduleDisplayName(context, item.moduleId),
                            onOpen = { coroutineScope.launch { openActivityTarget(context, item) } },
                            onUndo = {
                                val gitItem = item.git
                                if (gitItem != null) {
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
                                        refreshVisibleEntries()
                                    }
                                }
                                val undoId = item.undoActivityId
                                if (undoId != null) coroutineScope.launch {
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
                                            refreshVisibleEntries()
                                        }
                                        is HubActivityUndoResult.Conflict -> {
                                            snackbar.showSnackbar(context.getString(result.reason.messageRes()))
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

@Composable
private fun ActivityCard(
    item: ActivityUiItem,
    moduleName: String,
    onOpen: () -> Unit,
    onUndo: () -> Unit,
) {
    val activity = item.activity
    val canOpen = activity != null && (item.navigationRef != null || moduleFor(item.moduleId) != null)
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = item.detail != null || canOpen) { expanded = !expanded },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = "${item.title} · $moduleName · ${historyDateLabel(item.occurredAt)}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = if (expanded) 6 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
                OutlinedButton(
                    onClick = onUndo,
                    enabled = item.undoActivityId != null || (item.git != null && item.git.revertedBy == null),
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
                if (item.undoActivityId == null && item.git == null && activity?.status == HubActivityStatus.ACTIVE) {
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
