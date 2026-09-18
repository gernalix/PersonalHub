@file:android.annotation.SuppressLint("LocalContextGetResourceValueCall")

package com.gernalix.personalhub

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gernalix.personalhub.capsules.shortcuts.HubModule
import com.gernalix.personalhub.capsules.shortcuts.LauncherShortcutsCapsule
import com.gernalix.personalhub.contracts.database.HubDeepLinkContract
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.core.database.HubActivityEntity
import com.gernalix.personalhub.core.database.HubActivityPayloadKind
import com.gernalix.personalhub.core.database.HubActivityStatus
import com.gernalix.personalhub.core.database.HubActivityUndoConflict
import com.gernalix.personalhub.core.database.HubActivityUndoEffect
import com.gernalix.personalhub.core.database.HubActivityUndoEngine
import com.gernalix.personalhub.core.database.HubActivityUndoResult
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.hubcontext.HubContextRuntime
import com.gernalix.personalhub.core.hubcontext.formatHubDateTime
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val ACTIVITY_PAGE_SIZE = 50
private const val ALL_ACTIVITY_MODULES = "__all__"

private data class ActivityUiItem(
    val activity: HubActivityEntity,
    val label: String?,
    val navigationRef: HubEntityRef?,
)

private data class ActivityFilter(val id: String, val labelRes: Int)

@Composable
fun HubActivityRegisterScreen(onBack: () -> Unit, initialEventId: String? = null) {
    val context = LocalContext.current
    val database = remember(context) { PersonalHubDatabase.get(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var selectedModule by rememberSaveable { mutableStateOf(ALL_ACTIVITY_MODULES) }
    var includeSystem by rememberSaveable { mutableStateOf(true) }
    var entries by remember { mutableStateOf<List<ActivityUiItem>>(emptyList()) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var eventMissing by remember(initialEventId) { mutableStateOf(false) }

    val filters = listOf(
        ActivityFilter(ALL_ACTIVITY_MODULES, R.string.activity_filter_all),
        ActivityFilter("people", R.string.module_people),
        ActivityFilter("timer", R.string.module_timer),
        ActivityFilter("places", R.string.module_places),
        ActivityFilter("soldi", R.string.module_soldi),
        ActivityFilter("substances", R.string.module_substances),
        ActivityFilter("wordpulse", R.string.module_wordpulse),
        ActivityFilter("hub", R.string.activity_module_episodes),
        ActivityFilter("settings", R.string.settings_title),
    )

    suspend fun load(reset: Boolean) {
        if (loading) return
        loading = true
        try {
            if (initialEventId != null) {
                val row = database.activityDao().byId(initialEventId)
                eventMissing = row == null
                entries = row?.let { resolveActivityItems(listOf(it)) }.orEmpty()
                hasMore = false
                return
            }
            val cursor = if (reset) null else entries.lastOrNull()?.activity
            val rows = database.activityDao().page(
                moduleId = selectedModule.takeUnless { it == ALL_ACTIVITY_MODULES },
                includeSystem = if (includeSystem) 1 else 0,
                beforeOccurredAt = cursor?.occurredAt,
                beforeId = cursor?.id,
                limit = ACTIVITY_PAGE_SIZE + 1,
            )
            hasMore = rows.size > ACTIVITY_PAGE_SIZE
            val page = resolveActivityItems(rows.take(ACTIVITY_PAGE_SIZE))
            entries = if (reset) page else (entries + page).distinctBy { it.activity.id }
            eventMissing = false
        } finally {
            loading = false
        }
    }

    LaunchedEffect(selectedModule, includeSystem, initialEventId) { load(reset = true) }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)
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
                    text = stringResource(R.string.activity_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (initialEventId == null) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filters, key = { it.id }) { filter ->
                        FilterChip(
                            selected = selectedModule == filter.id,
                            onClick = { selectedModule = filter.id },
                            label = { Text(stringResource(filter.labelRes)) },
                        )
                    }
                    item {
                        FilterChip(
                            selected = includeSystem,
                            onClick = { includeSystem = !includeSystem },
                            label = { Text(stringResource(R.string.activity_filter_system)) },
                        )
                    }
                }
            }

            if (entries.isEmpty() && !loading) {
                Text(
                    text = stringResource(if (eventMissing) R.string.activity_event_not_found else R.string.activity_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(entries, key = { it.activity.id }) { item ->
                        ActivityCard(
                            item = item,
                            onOpen = {
                                scope.launch {
                                    openActivityTarget(context, item)
                                }
                            },
                            onUndo = {
                                scope.launch {
                                    when (val result = HubActivityUndoEngine.undo(database, item.activity.id)) {
                                        is HubActivityUndoResult.Success -> {
                                            result.entityRef?.let { ref ->
                                                when (result.effect) {
                                                    HubActivityUndoEffect.DELETED -> HubContextRuntime.canonicalDeletedIfInitialized(ref)
                                                    HubActivityUndoEffect.LIFECYCLE_CHANGED -> HubContextRuntime.canonicalLifecycleChangedIfInitialized(ref)
                                                }
                                            }
                                            snackbar.showSnackbar(context.getString(R.string.activity_undo_success))
                                            load(reset = true)
                                        }
                                        is HubActivityUndoResult.Conflict -> {
                                            snackbar.showSnackbar(context.getString(result.reason.messageRes()))
                                            load(reset = true)
                                        }
                                    }
                                }
                            },
                            onCopyLink = { copyActivityLink(context, item.activity.id) },
                        )
                    }
                    if (hasMore) {
                        item("load-more") {
                            Button(
                                onClick = { scope.launch { load(reset = false) } },
                                enabled = !loading,
                            ) {
                                Text(stringResource(R.string.activity_more))
                            }
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
    onOpen: () -> Unit,
    onUndo: () -> Unit,
    onCopyLink: () -> Unit,
) {
    val activity = item.activity
    val moduleName = moduleLabel(activity.moduleId)
    val subject = when {
        activity.entityKind == "setting" -> moduleName
        activity.moduleId == "wordpulse" && activity.entityKind == "session" -> moduleName
        else -> item.label?.takeIf(String::isNotBlank) ?: moduleName
    }
    val title = humanActivityTitle(activity.action, subject)
    val canOpen = item.navigationRef != null || moduleFor(activity.moduleId) != null
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (canOpen) Modifier.clickable(onClick = onOpen) else Modifier),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.activity_meta, moduleName, formatHubDateTime(activity.occurredAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (activity.status) {
                    HubActivityStatus.REVERTED -> AssistChip(onClick = {}, label = { Text(stringResource(R.string.activity_status_reverted)) })
                    HubActivityStatus.CONFLICT -> AssistChip(onClick = {}, label = { Text(stringResource(R.string.activity_status_conflict)) })
                }
                if (!activity.reversible && activity.status == HubActivityStatus.ACTIVE) {
                    Text(
                        text = stringResource(R.string.activity_status_non_reversible),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (activity.reversible && activity.status == HubActivityStatus.ACTIVE && activity.revertsActivityId == null) {
                    OutlinedButton(onClick = onUndo) { Text(stringResource(R.string.activity_undo)) }
                }
            }
            OutlinedButton(onClick = onCopyLink) { Text(stringResource(R.string.deep_link_copy)) }
        }
    }
}

@Composable
private fun moduleLabel(moduleId: String): String = when (moduleId) {
    "people" -> stringResource(R.string.module_people)
    "timer" -> stringResource(R.string.module_timer)
    "places" -> stringResource(R.string.module_places)
    "soldi" -> stringResource(R.string.module_soldi)
    "substances" -> stringResource(R.string.module_substances)
    "wordpulse" -> stringResource(R.string.module_wordpulse)
    "hub" -> stringResource(R.string.activity_module_episodes)
    "settings" -> stringResource(R.string.settings_title)
    else -> stringResource(R.string.activity_module_other)
}

@Composable
private fun humanActivityTitle(action: String, subject: String): String {
    val normalized = action.lowercase()
    val template = when {
        "restore" in normalized || "reopen" in normalized -> R.string.activity_action_restored
        "archive" in normalized -> R.string.activity_action_archived
        "delete" in normalized || normalized == "deleted" -> R.string.activity_action_deleted
        "create" in normalized || normalized == "created" || "add" in normalized -> R.string.activity_action_created
        "record" in normalized -> R.string.activity_action_recorded
        "start" in normalized -> R.string.activity_action_started
        "stop" in normalized || "complete" in normalized -> R.string.activity_action_completed
        "update" in normalized || "modify" in normalized || "edit" in normalized || "change" in normalized -> R.string.activity_action_updated
        else -> R.string.activity_action_changed
    }
    return stringResource(template, subject)
}

private suspend fun resolveActivityItems(rows: List<HubActivityEntity>): List<ActivityUiItem> {
    val refs = rows.mapNotNull(::navigationRef).distinct()
    val adapters = runCatching { HubContextRuntime.adapters() }.getOrDefault(emptyList())
    val supportedRefs = refs.filter { ref ->
        adapters.any { adapter -> adapter.moduleId == ref.moduleId && adapter.entityKind == ref.entityKind }
    }
    val summaries = runCatching { HubContextRuntime.summaries(supportedRefs) }.getOrDefault(emptyMap())
    return rows.map { activity ->
        val ref = navigationRef(activity)
        ActivityUiItem(
            activity = activity,
            label = activity.entityLabel?.takeIf(String::isNotBlank) ?: ref?.let { summaries[it]?.label },
            navigationRef = ref,
        )
    }
}

private fun navigationRef(activity: HubActivityEntity): HubEntityRef? {
    if (activity.payloadKind == HubActivityPayloadKind.PLACES_AUDIT_V1) {
        val payload = activity.afterPayload ?: activity.beforePayload
        val placeId = runCatching { JSONObject(payload.orEmpty()).optString("place_id") }.getOrNull()?.takeIf(String::isNotBlank)
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

private suspend fun openActivityTarget(context: android.content.Context, item: ActivityUiItem) {
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
    moduleFor(item.activity.moduleId)?.let { module ->
        context.startActivity(LauncherShortcutsCapsule.moduleIntent(context, module))
    }
}

private fun moduleFor(moduleId: String): HubModule? =
    HubModule.entries.firstOrNull { it.shortcutPath == moduleId }

private fun HubActivityUndoConflict.messageRes(): Int = when (this) {
    HubActivityUndoConflict.NOT_FOUND -> R.string.activity_undo_not_found
    HubActivityUndoConflict.NOT_REVERSIBLE, HubActivityUndoConflict.UNSUPPORTED -> R.string.activity_undo_not_supported
    HubActivityUndoConflict.ALREADY_REVERTED -> R.string.activity_undo_already_reverted
    HubActivityUndoConflict.STALE -> R.string.activity_undo_stale
    HubActivityUndoConflict.REFERENCED -> R.string.activity_undo_referenced
    HubActivityUndoConflict.INVALID_PAYLOAD -> R.string.activity_undo_invalid
}

private fun copyActivityLink(context: Context, eventId: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.activity_title), HubDeepLinkContract.eventUri(eventId).toString()))
    Toast.makeText(context, R.string.deep_link_copied, Toast.LENGTH_SHORT).show()
}
