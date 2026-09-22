package com.example.multitimetracker.capsules.tags.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.multitimetracker.TimeFenceNotifier
import com.example.multitimetracker.R
import com.example.multitimetracker.capsules.system.ui.ConfirmEmptyTrashDialog
import com.example.multitimetracker.capsules.tags.controller.TagsCapsuleViewModel
import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.TimedTagNotificationType
import com.example.multitimetracker.capsules.tags.state.TagsUiState
import com.example.multitimetracker.ui.components.AddOrRenameTagDialog
import com.example.multitimetracker.ui.components.InlineHelpAction
import com.example.multitimetracker.ui.components.ScreenEmptyStateCard
import com.example.multitimetracker.ui.components.ScreenHelpAction
import com.example.multitimetracker.ui.components.ScreenIntroCard
import com.example.multitimetracker.ui.components.ScreenScaffold
import com.example.multitimetracker.ui.components.SingleSubmitButton
import com.example.multitimetracker.ui.components.SingleSubmitTextButton
import com.example.multitimetracker.ui.components.TagRow
import com.example.multitimetracker.ui.components.TagSelectionFlow
import com.example.multitimetracker.ui.util.TagSelectionOrder
import com.example.multitimetracker.ui.util.formatDuration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class TagSortKey { FIRST_SESSION, NAME, TOTAL_TIME, SESSION_COUNT }
private enum class TagBrowserMode { ARCHIVED, TRASH }
private enum class TagSessionSortKey { DATE, DURATION, TITLE }

private data class TagSessionIndex(
    val sessionsByTagId: Map<Long, List<SessionUi>>,
    val sessionCountByTagId: Map<Long, Int>,
    val firstStartByTagId: Map<Long, Long>,
)

@Composable
fun TagsCapsuleUi(
    modifier: Modifier = Modifier,
    state: TagsUiState,
    capsule: TagsCapsuleViewModel,
    initialOpenedTagId: Long? = null,
    onConsumedInitialOpenedTagId: () -> Unit = {},
    showSeconds: Boolean,
    hideHoursIfZero: Boolean
) {
    var namespaceTab by rememberSaveable { mutableStateOf("now") }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        FilterChip(namespaceTab == "now", { namespaceTab = "now" }, label = { Text("Now") })
        FilterChip(namespaceTab == "events", { namespaceTab = "events" }, label = { Text("Events") })
        FilterChip(namespaceTab == "since", { namespaceTab = "since" }, label = { Text("Since when") })
    }
    if (namespaceTab != "now") {
        val namespaceTags = if (namespaceTab == "events") state.eventTags else state.sinceWhenTags
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(namespaceTags.filterNot { it.isDeleted }, key = { it.id }) { tag ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(tag.name, style = MaterialTheme.typography.titleMedium)
                        Text(if (tag.isArchived) "Archived" else if (namespaceTab == "events") "timer.events" else "timer.since_when")
                    }
                }
            }
        }
        return
    }
    val effectiveTime = remember(state.nowMs) { state.effectiveTimeContext() }
    val listState = rememberLazyListState()

    var showAdd by remember { mutableStateOf(false) }
    var renameTagId by remember { mutableStateOf<Long?>(null) }
    var editParentsTagId by remember { mutableStateOf<Long?>(null) }
    var confirmDeleteTagId by remember { mutableStateOf<Long?>(null) }
    var confirmPurgeTagId by remember { mutableStateOf<Long?>(null) }
    var selectedTagId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingScrollToTagId by rememberSaveable { mutableStateOf<Long?>(null) }
    var browserMode by remember { mutableStateOf<TagBrowserMode?>(null) }
    var confirmEmptyTrash by remember { mutableStateOf(false) }

    var isTagSearchVisible by remember { mutableStateOf(false) }
    var tagSearchQuery by rememberSaveable { mutableStateOf("") }
    var archiveQuery by rememberSaveable { mutableStateOf("") }
    var trashQuery by rememberSaveable { mutableStateOf("") }

    var sortKey by rememberSaveable { mutableStateOf(TagSortKey.TOTAL_TIME.name) }
    var sortAsc by rememberSaveable { mutableStateOf(false) }

    fun collapseTagSearch() {
        isTagSearchVisible = false
        tagSearchQuery = ""
    }

    val tagSessionIndex = remember(state.runningSessions, state.chronologySessions) {
        val sessions = (state.runningSessions + state.chronologySessions)
            .asSequence()
            .filter { it.deletedAtMs == null }
            .distinctBy { it.id }
            .toList()

        val sessionsByTagId = mutableMapOf<Long, MutableList<SessionUi>>()
        sessions.forEach { session ->
            session.tagIds.forEach { tagId ->
                sessionsByTagId.getOrPut(tagId) { mutableListOf() }.add(session)
            }
        }

        TagSessionIndex(
            sessionsByTagId = sessionsByTagId.mapValues { entry ->
                entry.value.sortedByDescending { it.startMs }
            },
            sessionCountByTagId = sessionsByTagId.mapValues { it.value.size },
            firstStartByTagId = sessionsByTagId.mapNotNull { (tagId, linkedSessions) ->
                linkedSessions.minOfOrNull { it.startMs }?.let { earliest -> tagId to earliest }
            }.toMap()
        )
    }

    val archivedTags = remember(state.tags) {
        state.tags.filter { !it.isDeleted && it.isArchived }.distinctBy { it.id }
    }
    val deletedTags = remember(state.tags) {
        state.tags.filter { it.isDeleted }.distinctBy { it.id }
    }

    val visibleTags by remember(
        state.tags,
        tagSearchQuery,
        sortKey,
        sortAsc,
        tagSessionIndex,
        state.tagTotalsMsByTagId
    ) {
        val selectedSort = runCatching { TagSortKey.valueOf(sortKey) }.getOrElse { TagSortKey.TOTAL_TIME }
        derivedStateOf {
            state.tags
                .asSequence()
                .filter { !it.isDeleted && !it.isArchived }
                .filter {
                    val query = tagSearchQuery.trim()
                    query.isBlank() || it.name.contains(query, ignoreCase = true)
                }
                .distinctBy { it.id }
                .sortedWith { left, right ->
                    compareTags(
                        left = left,
                        right = right,
                        sortKey = selectedSort,
                        ascending = sortAsc,
                        firstStartByTagId = tagSessionIndex.firstStartByTagId,
                        sessionCountByTagId = tagSessionIndex.sessionCountByTagId,
                        totalsMsByTagId = state.tagTotalsMsByTagId
                    )
                }
                .toList()
        }
    }
    val hasTrackedSessions = remember(state.runningSessions, state.chronologySessions) {
        state.runningSessions.any { it.deletedAtMs == null } ||
            state.chronologySessions.any { it.deletedAtMs == null }
    }
    val showOnboardingIntro = remember(visibleTags, archivedTags, deletedTags, hasTrackedSessions) {
        visibleTags.isEmpty() && archivedTags.isEmpty() && deletedTags.isEmpty() && !hasTrackedSessions
    }

    val selectedTag = remember(state.tags, selectedTagId) {
        state.tags.firstOrNull { it.id == selectedTagId }
    }

    LaunchedEffect(initialOpenedTagId) {
        val targetId = initialOpenedTagId ?: return@LaunchedEffect
        selectedTagId = targetId
        pendingScrollToTagId = targetId
        onConsumedInitialOpenedTagId()
    }

    LaunchedEffect(selectedTagId, state.tags) {
        val currentId = selectedTagId ?: return@LaunchedEffect
        if (state.tags.none { it.id == currentId }) {
            selectedTagId = null
        }
    }

    LaunchedEffect(pendingScrollToTagId, visibleTags) {
        val targetId = pendingScrollToTagId ?: return@LaunchedEffect
        val index = visibleTags.indexOfFirst { it.id == targetId }
        if (index >= 0) {
            runCatching { listState.animateScrollToItem(index) }
                .recoverCatching { listState.scrollToItem(index) }
        }
        pendingScrollToTagId = null
    }

    LaunchedEffect(isTagSearchVisible, listState) {
        if (!isTagSearchVisible) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress }
            .collect { isScrolling ->
                if (isScrolling) collapseTagSearch()
            }
    }

    ScreenScaffold(
        title = stringResource(R.string.tag),
        modifier = modifier,
        actions = {
            ScreenHelpAction(
                title = stringResource(R.string.tags_intro_title),
                body = stringResource(R.string.tags_intro_body)
            )
            IconButton(onClick = {
                if (isTagSearchVisible) collapseTagSearch() else isTagSearchVisible = true
            }) {
                Icon(
                    imageVector = if (isTagSearchVisible) Icons.Default.Close else Icons.Default.Search,
                    contentDescription = if (isTagSearchVisible) {
                        stringResource(R.string.chiudi)
                    } else {
                        stringResource(R.string.cerca_tag)
                    }
                )
            }
            IconButton(onClick = { browserMode = TagBrowserMode.ARCHIVED }) {
                Icon(
                    imageVector = Icons.Default.VisibilityOff,
                    contentDescription = stringResource(R.string.archived_tags)
                )
            }
            IconButton(onClick = { browserMode = TagBrowserMode.TRASH }) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.tag_trash_title)
                )
            }
            IconButton(onClick = {
                if (!state.isReadOnly) {
                    showAdd = true
                }
            }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_tag_cd))
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(
                            R.string.tags_intro_counts,
                            visibleTags.size,
                            archivedTags.size,
                            deletedTags.size
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        var sortMenuExpanded by remember { mutableStateOf(false) }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null)
                            Text(
                                text = stringResource(R.string.ordina_per),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box {
                                TextButton(onClick = { sortMenuExpanded = true }) {
                                    Text(text = tagSortLabel(runCatching {
                                        TagSortKey.valueOf(sortKey)
                                    }.getOrElse { TagSortKey.TOTAL_TIME }))
                                }
                                DropdownMenu(
                                    expanded = sortMenuExpanded,
                                    onDismissRequest = { sortMenuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.tag_first_session)) },
                                        onClick = {
                                            sortKey = TagSortKey.FIRST_SESSION.name
                                            sortMenuExpanded = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.nome)) },
                                        onClick = {
                                            sortKey = TagSortKey.NAME.name
                                            sortMenuExpanded = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.tempo_totale)) },
                                        onClick = {
                                            sortKey = TagSortKey.TOTAL_TIME.name
                                            sortMenuExpanded = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.num_task)) },
                                        onClick = {
                                            sortKey = TagSortKey.SESSION_COUNT.name
                                            sortMenuExpanded = false
                                        }
                                    )
                                }
                            }

                            IconButton(onClick = { sortAsc = !sortAsc }) {
                                Icon(
                                    imageVector = if (sortAsc) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                    contentDescription = if (sortAsc) {
                                        stringResource(R.string.ordine_crescente)
                                    } else {
                                        stringResource(R.string.ordine_decrescente)
                                    }
                                )
                            }
                        }
                    }

                    if (isTagSearchVisible) {
                        OutlinedTextField(
                            value = tagSearchQuery,
                            onValueChange = { tagSearchQuery = it },
                            label = { Text(stringResource(R.string.cerca_tag)) },
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = { collapseTagSearch() }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(R.string.chiudi)
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            if (visibleTags.isEmpty()) {
                if (showOnboardingIntro) {
                    ScreenIntroCard(
                        title = stringResource(R.string.tags_first_run_intro_title),
                        body = stringResource(R.string.tags_first_run_intro_body),
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
                ScreenEmptyStateCard(
                    title = stringResource(R.string.tags_empty_title),
                    body = stringResource(R.string.tags_empty_body),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(visibleTags, key = { "tag:${it.id}" }) { tag ->
                        val sessionCount = tagSessionIndex.sessionCountByTagId[tag.id] ?: 0
                        val isRunning = tag.lastStartedAtMs != null || state.activeTagStart.containsKey(tag.id)
                        val supportParts = buildList {
                            if (isRunning) add(stringResource(R.string.tag_running_now))
                            if (tag.timedDurationMinutes != null) add(stringResource(R.string.timed_tag_label))
                            if (sessionCount > 0) {
                                add(
                                    pluralStringResource(
                                        R.plurals.tag_session_count,
                                        sessionCount,
                                        sessionCount
                                    )
                                )
                            }
                        }

                        TagRow(
                            tag = tag,
                            shownMs = computeShownMs(
                                tag = tag,
                                nowMs = effectiveTime.nowMs,
                                totalsMsByTagId = state.tagTotalsMsByTagId,
                                runningMinStartByTagId = state.runningMinStartByTagId
                            ),
                            supportingText = supportParts.joinToString(" • "),
                            highlightRunning = isRunning,
                            showSeconds = showSeconds,
                            hideHoursIfZero = hideHoursIfZero,
                            selected = selectedTagId == tag.id,
                            onOpen = { selectedTagId = tag.id }
                        )
                    }
                }
            }
        }
    }

    selectedTag?.let { tag ->
        TagDetailsDialog(
            tag = tag,
            state = state,
            linkedSessions = tagSessionIndex.sessionsByTagId[tag.id].orEmpty(),
            sessionCount = tagSessionIndex.sessionCountByTagId[tag.id] ?: 0,
            firstSessionMs = tagSessionIndex.firstStartByTagId[tag.id],
            showSeconds = showSeconds,
            hideHoursIfZero = hideHoursIfZero,
            onDismiss = { selectedTagId = null },
            onEdit = {
                selectedTagId = null
                renameTagId = tag.id
            },
            onEditParents = {
                selectedTagId = null
                editParentsTagId = tag.id
            },
            onTimedTagSave = { timedDurationMinutes, notificationType ->
                if (!state.isReadOnly) {
                    capsule.renameTag(tag.id, tag.name, timedDurationMinutes, notificationType)
                }
            },
            onArchiveToggle = { archived ->
                if (!state.isReadOnly) {
                    capsule.setTagArchived(tag.id, archived)
                }
                selectedTagId = null
            },
            onShowInTimelineChange = { show ->
                if (!state.isReadOnly) {
                    capsule.setTagShowInTimeline(tag.id, show)
                }
            },
            onDelete = {
                selectedTagId = null
                if (!state.isReadOnly) {
                    confirmDeleteTagId = tag.id
                }
            },
            onRestore = {
                if (!state.isReadOnly) {
                    capsule.restoreTag(tag.id)
                }
                selectedTagId = null
            },
            onPurge = {
                selectedTagId = null
                if (!state.isReadOnly) {
                    confirmPurgeTagId = tag.id
                }
            }
        )
    }

    if (showAdd) {
        AddOrRenameTagDialog(
            title = stringResource(R.string.tags_add_tag),
            initialName = "",
            confirmText = stringResource(R.string.aggiungi),
            onDismiss = { showAdd = false },
            onConfirm = { name ->
                if (!state.isReadOnly) {
                    capsule.addTag(name)
                }
                showAdd = false
            }
        )
    }

    renameTagId?.let { id ->
        val tag = state.tags.firstOrNull { it.id == id }
        if (tag == null) {
            renameTagId = null
        } else {
            AddOrRenameTagDialog(
                title = stringResource(R.string.tags_rename_tag),
                initialName = tag.name,
                confirmText = stringResource(R.string.salva),
                onDismiss = { renameTagId = null },
                onConfirm = { name ->
                    if (!state.isReadOnly) {
                        capsule.renameTag(id, name, tag.timedDurationMinutes, tag.notificationType)
                    }
                    renameTagId = null
                }
            )
        }
    }

    editParentsTagId?.let { id ->
        val child = state.tags.firstOrNull { it.id == id && !it.isDeleted }
        if (child == null) {
            editParentsTagId = null
        } else {
            EditTagParentsDialog(
                child = child,
                allTags = state.tags,
                tagLastUsedMsByTagId = state.tagLastUsedMsByTagId,
                currentParents = state.tagParentsByChild[child.id].orEmpty(),
                onDismiss = { editParentsTagId = null },
                onConfirm = { newParents ->
                    if (!state.isReadOnly) {
                        capsule.setTagParents(child.id, newParents)
                    }
                    editParentsTagId = null
                }
            )
        }
    }

    confirmDeleteTagId?.let { id ->
        val tag = state.tags.firstOrNull { it.id == id }
        if (tag == null) {
            confirmDeleteTagId = null
        } else {
            AlertDialog(
                onDismissRequest = { confirmDeleteTagId = null },
                title = { Text(stringResource(R.string.eliminare_tag)) },
                text = { Text(stringResource(R.string.vuoi_eliminare_t_name, tag.name)) },
                confirmButton = {
                    SingleSubmitTextButton(
                        onClick = {
                            if (!state.isReadOnly) {
                                capsule.deleteTag(id, false)
                            }
                            confirmDeleteTagId = null
                        }
                    ) {
                        Text(stringResource(R.string.elimina))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDeleteTagId = null }) {
                        Text(stringResource(R.string.annulla))
                    }
                }
            )
        }
    }

    confirmPurgeTagId?.let { id ->
        val tag = state.tags.firstOrNull { it.id == id }
        if (tag == null) {
            confirmPurgeTagId = null
        } else {
            AlertDialog(
                onDismissRequest = { confirmPurgeTagId = null },
                title = { Text(stringResource(R.string.purge_definitivo)) },
                text = { Text(stringResource(R.string.rimuovere_definitivamente_t_name, tag.name)) },
                confirmButton = {
                    SingleSubmitTextButton(
                        onClick = {
                            if (!state.isReadOnly) {
                                capsule.purgeTag(id)
                            }
                            confirmPurgeTagId = null
                        }
                    ) {
                        Text(stringResource(R.string.purge))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmPurgeTagId = null }) {
                        Text(stringResource(R.string.annulla))
                    }
                }
            )
        }
    }

    when (browserMode) {
        TagBrowserMode.ARCHIVED -> {
            val filtered = remember(archivedTags, archiveQuery) {
                val query = archiveQuery.trim()
                if (query.isBlank()) {
                    archivedTags
                } else {
                    archivedTags.filter {
                        it.name.contains(query, ignoreCase = true) || it.id.toString().contains(query)
                    }
                }
            }

            TagBrowserDialog(
                title = stringResource(R.string.archived_tags),
                query = archiveQuery,
                onQueryChange = { archiveQuery = it },
                tags = filtered,
                emptyText = stringResource(R.string.no_archived_tags),
                sessionCountByTagId = tagSessionIndex.sessionCountByTagId,
                onOpen = { tag ->
                    browserMode = null
                    selectedTagId = tag.id
                },
                onDismiss = { browserMode = null }
            )
        }

        TagBrowserMode.TRASH -> {
            val filtered = remember(deletedTags, trashQuery) {
                val query = trashQuery.trim()
                if (query.isBlank()) {
                    deletedTags
                } else {
                    deletedTags.filter {
                        it.name.contains(query, ignoreCase = true) || it.id.toString().contains(query)
                    }
                }
            }

            TagBrowserDialog(
                title = stringResource(R.string.tag_trash_title),
                query = trashQuery,
                onQueryChange = { trashQuery = it },
                tags = filtered,
                emptyText = stringResource(R.string.trash_empty),
                sessionCountByTagId = tagSessionIndex.sessionCountByTagId,
                onOpen = { tag ->
                    browserMode = null
                    selectedTagId = tag.id
                },
                onDismiss = { browserMode = null },
                onRequestEmpty = if (deletedTags.isNotEmpty()) {
                    { confirmEmptyTrash = true }
                } else {
                    null
                }
            )
        }

        null -> Unit
    }

    if (confirmEmptyTrash) {
        ConfirmEmptyTrashDialog(
            onConfirm = {
                confirmEmptyTrash = false
                if (!state.isReadOnly) {
                    state.tags.filter { it.isDeleted }.forEach { capsule.purgeTag(it.id) }
                }
                browserMode = null
            },
            onDismiss = { confirmEmptyTrash = false }
        )
    }
}

@Composable
private fun TagDetailsDialog(
    tag: Tag,
    state: TagsUiState,
    linkedSessions: List<SessionUi>,
    sessionCount: Int,
    firstSessionMs: Long?,
    showSeconds: Boolean,
    hideHoursIfZero: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onEditParents: () -> Unit,
    onTimedTagSave: (Int?, TimedTagNotificationType) -> Unit,
    onArchiveToggle: (Boolean) -> Unit,
    onShowInTimelineChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
    onPurge: () -> Unit
) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val dateFormatter = remember { DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.getDefault()) }
    val dateTimeFormatter = remember { DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm", Locale.getDefault()) }
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()) }
    val parentNames = remember(state.tagParentsByChild, state.tags, tag.id) {
        state.tagParentsByChild[tag.id]
            .orEmpty()
            .mapNotNull { parentId -> state.tags.firstOrNull { it.id == parentId && !it.isDeleted }?.name }
            .sorted()
    }
    var sessionSortKey by rememberSaveable(tag.id) { mutableStateOf(TagSessionSortKey.DATE.name) }
    var timedTagEnabled by rememberSaveable(tag.id, tag.timedDurationMinutes, tag.notificationType) {
        mutableStateOf((tag.timedDurationMinutes ?: 0) > 0)
    }
    var hours by rememberSaveable(tag.id, tag.timedDurationMinutes) {
        mutableStateOf(initialTimedTagHours(tag.timedDurationMinutes))
    }
    var minutes by rememberSaveable(tag.id, tag.timedDurationMinutes) {
        mutableStateOf(initialTimedTagMinutes(tag.timedDurationMinutes))
    }
    var notificationType by rememberSaveable(tag.id, tag.timedDurationMinutes, tag.notificationType) {
        mutableStateOf(if ((tag.timedDurationMinutes ?: 0) > 0) tag.notificationType else TimedTagNotificationType.NONE)
    }
    var showAlarmPermissionDialog by remember { mutableStateOf(false) }

    val parsedHours = hours.toIntOrNull()?.coerceAtLeast(0) ?: 0
    val parsedMinutes = minutes.toIntOrNull()?.coerceIn(0, 59) ?: 0
    val totalTimedMinutes = if (timedTagEnabled) (parsedHours * 60 + parsedMinutes).takeIf { it > 0 } else null
    val normalizedNotificationType =
        if (totalTimedMinutes == null) TimedTagNotificationType.NONE else notificationType
    val originalNotificationType =
        if (tag.timedDurationMinutes == null) TimedTagNotificationType.NONE else tag.notificationType
    val timedTagDirty =
        totalTimedMinutes != tag.timedDurationMinutes || normalizedNotificationType != originalNotificationType
    val canSaveTimedTag = !state.isReadOnly && timedTagDirty && (!timedTagEnabled || totalTimedMinutes != null)

    fun trySelectAlarmMode() {
        if (TimeFenceNotifier.canUseFullScreenIntent(context)) {
            notificationType = TimedTagNotificationType.ALARM
        } else {
            showAlarmPermissionDialog = true
        }
    }

    val sortedSessions = remember(linkedSessions, sessionSortKey, state.nowMs, tag.name) {
        val selectedSort = runCatching { TagSessionSortKey.valueOf(sessionSortKey) }
            .getOrElse { TagSessionSortKey.DATE }
        linkedSessions.sortedWith { left, right ->
            when (selectedSort) {
                TagSessionSortKey.DATE -> right.startMs.compareTo(left.startMs)
                TagSessionSortKey.DURATION -> sessionDurationMs(right, state.nowMs)
                    .compareTo(sessionDurationMs(left, state.nowMs))
                TagSessionSortKey.TITLE -> sessionDisplayTitle(left, tag.name)
                    .compareTo(sessionDisplayTitle(right, tag.name), ignoreCase = true)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.tag_actions_title, tag.name.ifBlank { "#${tag.id}" }),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.chiudi))
                    }
                }

                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = pluralStringResource(
                                R.plurals.tag_session_count,
                                sessionCount,
                                sessionCount
                            ),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = "${stringResource(R.string.tempo_totale)}: ${
                                formatDuration(
                                    computeShownMs(
                                        tag = tag,
                                        nowMs = state.effectiveNowMs,
                                        totalsMsByTagId = state.tagTotalsMsByTagId,
                                        runningMinStartByTagId = state.runningMinStartByTagId
                                    ),
                                    showSeconds = showSeconds,
                                    hideHoursIfZero = hideHoursIfZero
                                )
                            }",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${stringResource(R.string.tag_first_session)}: ${
                                firstSessionMs?.let {
                                    Instant.ofEpochMilli(it).atZone(zone).format(dateFormatter)
                                } ?: stringResource(R.string.no_sessions_for_tag)
                            }",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${stringResource(R.string.timed_tag_label)}: ${timedTagSummary(tag)}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = if (parentNames.isEmpty()) {
                                "${stringResource(R.string.padri)}: ${stringResource(R.string.parents_none)}"
                            } else {
                                "${stringResource(R.string.padri)}: ${parentNames.joinToString(", ")}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (!tag.isDeleted) {
                    ElevatedCard(
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = timedTagEnabled,
                                    onCheckedChange = if (state.isReadOnly) {
                                        null
                                    } else { checked ->
                                        timedTagEnabled = checked
                                        if (!checked) {
                                            notificationType = TimedTagNotificationType.NONE
                                        } else if (notificationType == TimedTagNotificationType.NONE) {
                                            notificationType = TimedTagNotificationType.NORMAL
                                        }
                                    }
                                )
                                Text(
                                    text = stringResource(R.string.timed_tag_label),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }

                            if (timedTagEnabled) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    OutlinedTextField(
                                        value = hours,
                                        onValueChange = { hours = it.filter(Char::isDigit) },
                                        label = { Text(stringResource(R.string.hours_label)) },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        enabled = !state.isReadOnly,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    OutlinedTextField(
                                        value = minutes,
                                        onValueChange = { minutes = it.filter(Char::isDigit).take(2) },
                                        label = { Text(stringResource(R.string.minutes_label)) },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        enabled = !state.isReadOnly,
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                Text(
                                    text = stringResource(R.string.notification_label),
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Row {
                                    TextButton(
                                        onClick = { notificationType = TimedTagNotificationType.NONE },
                                        enabled = !state.isReadOnly
                                    ) {
                                        Text(
                                            if (notificationType == TimedTagNotificationType.NONE) {
                                                "[${stringResource(R.string.timed_notification_none)}]"
                                            } else {
                                                stringResource(R.string.timed_notification_none)
                                            }
                                        )
                                    }
                                    TextButton(
                                        onClick = { notificationType = TimedTagNotificationType.NORMAL },
                                        enabled = !state.isReadOnly
                                    ) {
                                        Text(
                                            if (notificationType == TimedTagNotificationType.NORMAL) {
                                                "[${stringResource(R.string.timed_notification_normal)}]"
                                            } else {
                                                stringResource(R.string.timed_notification_normal)
                                            }
                                        )
                                    }
                                    TextButton(
                                        onClick = ::trySelectAlarmMode,
                                        enabled = !state.isReadOnly
                                    ) {
                                        Text(
                                            if (notificationType == TimedTagNotificationType.ALARM) {
                                                "[${stringResource(R.string.timed_notification_alarm)}]"
                                            } else {
                                                stringResource(R.string.timed_notification_alarm)
                                            }
                                        )
                                    }
                                }
                            }

                            if (timedTagDirty) {
                                SingleSubmitTextButton(
                                    onClick = {
                                        if (!canSaveTimedTag) return@SingleSubmitTextButton
                                        onTimedTagSave(totalTimedMinutes, normalizedNotificationType)
                                    },
                                    enabled = canSaveTimedTag
                                ) {
                                    Text(stringResource(R.string.salva))
                                }
                            }
                        }
                    }
                }

                if (!tag.isDeleted) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.tag_show_in_timeline),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            InlineHelpAction(
                                title = stringResource(R.string.tag_show_in_timeline),
                                body = stringResource(R.string.tag_show_in_timeline_help)
                            )
                        }
                        Checkbox(
                            checked = tag.showInTimeline,
                            onCheckedChange = if (state.isReadOnly) null else onShowInTimelineChange
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = onEdit,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 40.dp),
                        contentPadding = tagActionButtonPadding(),
                        enabled = !state.isReadOnly && !tag.isDeleted
                    ) {
                        TagActionButtonLabel(stringResource(R.string.edit))
                    }
                    OutlinedButton(
                        onClick = onEditParents,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 40.dp),
                        contentPadding = tagActionButtonPadding(),
                        enabled = !state.isReadOnly && !tag.isDeleted
                    ) {
                        TagActionButtonLabel(stringResource(R.string.padri))
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (tag.isDeleted) {
                        FilledTonalButton(
                            onClick = onRestore,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 40.dp),
                            contentPadding = tagActionButtonPadding(),
                            enabled = !state.isReadOnly
                        ) {
                            Icon(Icons.Default.RestoreFromTrash, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            TagActionButtonLabel(stringResource(R.string.ripristina))
                        }
                        OutlinedButton(
                            onClick = onPurge,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 40.dp),
                            contentPadding = tagActionButtonPadding(),
                            enabled = !state.isReadOnly
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            TagActionButtonLabel(stringResource(R.string.purge))
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onArchiveToggle(!tag.isArchived) },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 40.dp),
                            contentPadding = tagActionButtonPadding(),
                            enabled = !state.isReadOnly
                        ) {
                            TagActionButtonLabel(
                                stringResource(if (tag.isArchived) R.string.unarchive else R.string.archive)
                            )
                        }
                        OutlinedButton(
                            onClick = onDelete,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 40.dp),
                            contentPadding = tagActionButtonPadding(),
                            enabled = !state.isReadOnly
                        ) {
                            TagActionButtonLabel(stringResource(R.string.elimina))
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.session_history_title),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = stringResource(R.string.tag_sort_linked_sessions),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistChip(
                        onClick = { sessionSortKey = TagSessionSortKey.DATE.name },
                        label = { Text(stringResource(R.string.data)) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (sessionSortKey == TagSessionSortKey.DATE.name) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                        )
                    )
                    AssistChip(
                        onClick = { sessionSortKey = TagSessionSortKey.DURATION.name },
                        label = { Text(stringResource(R.string.durata)) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (sessionSortKey == TagSessionSortKey.DURATION.name) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                        )
                    )
                    AssistChip(
                        onClick = { sessionSortKey = TagSessionSortKey.TITLE.name },
                        label = { Text(stringResource(R.string.titolo)) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (sessionSortKey == TagSessionSortKey.TITLE.name) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                        )
                    )
                }

                if (sortedSessions.isEmpty()) {
                    Text(
                        text = stringResource(R.string.no_sessions_for_tag),
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    sortedSessions.forEach { session ->
                        val effectiveEndMs = session.endMs ?: state.effectiveNowMs
                        val headline = sessionDisplayTitle(session, tag.name)
                        val timeRange = buildString {
                            append(
                                Instant.ofEpochMilli(session.startMs)
                                    .atZone(zone)
                                    .format(dateTimeFormatter)
                            )
                            append("  ")
                            append(
                                Instant.ofEpochMilli(session.startMs)
                                    .atZone(zone)
                                    .format(timeFormatter)
                            )
                            append(" → ")
                            if (session.endMs == null) {
                                append(stringResource(R.string.tab_now))
                            } else {
                                append(
                                    Instant.ofEpochMilli(effectiveEndMs)
                                        .atZone(zone)
                                        .format(timeFormatter)
                                )
                            }
                            append("  (")
                            append(
                                formatDuration(
                                    (effectiveEndMs - session.startMs).coerceAtLeast(0L),
                                    showSeconds = showSeconds,
                                    hideHoursIfZero = hideHoursIfZero
                                )
                            )
                            append(")")
                        }

                        ListItem(
                            headlineContent = {
                                Text(
                                    text = headline,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            supportingContent = { Text(timeRange) }
                        )
                    }
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.chiudi))
                }
            }
        }
    }

    if (showAlarmPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showAlarmPermissionDialog = false },
            title = { Text(stringResource(R.string.timed_tag_alarm_permission_title)) },
            text = { Text(stringResource(R.string.timed_tag_alarm_permission_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAlarmPermissionDialog = false
                        TimeFenceNotifier.openFullScreenIntentSettings(context)
                    }
                ) {
                    Text(stringResource(R.string.timed_tag_alarm_permission_open_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAlarmPermissionDialog = false }) {
                    Text(stringResource(R.string.annulla))
                }
            }
        )
    }
}

@Composable
private fun TagBrowserDialog(
    title: String,
    query: String,
    onQueryChange: (String) -> Unit,
    tags: List<Tag>,
    emptyText: String,
    sessionCountByTagId: Map<Long, Int>,
    onOpen: (Tag) -> Unit,
    onDismiss: () -> Unit,
    onRequestEmpty: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    label = { Text(stringResource(R.string.cerca_tag)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (tags.isEmpty()) {
                    Text(
                        text = emptyText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                    ) {
                        items(tags, key = { "browser-tag:${it.id}" }) { tag ->
                            val sessionCount = sessionCountByTagId[tag.id] ?: 0
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = tag.name.ifBlank { "#${tag.id}" },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                supportingContent = {
                                    if (sessionCount > 0) {
                                        Text(
                                            pluralStringResource(
                                                R.plurals.tag_session_count,
                                                sessionCount,
                                                sessionCount
                                            )
                                        )
                                    }
                                },
                                modifier = Modifier.clickable { onOpen(tag) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onRequestEmpty != null) {
                    TextButton(onClick = onRequestEmpty) {
                        Text(stringResource(R.string.empty_trash))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.chiudi))
                }
            }
        }
    )
}

@Composable
private fun tagSortLabel(sortKey: TagSortKey): String = when (sortKey) {
    TagSortKey.FIRST_SESSION -> stringResource(R.string.tag_first_session)
    TagSortKey.NAME -> stringResource(R.string.nome)
    TagSortKey.TOTAL_TIME -> stringResource(R.string.tempo_totale)
    TagSortKey.SESSION_COUNT -> stringResource(R.string.num_task)
}

@Composable
private fun timedTagSummary(tag: Tag): String {
    val durationMinutes = tag.timedDurationMinutes ?: return stringResource(R.string.timed_tag_off)
    val durationText = formatDuration(
        durationMinutes * 60_000L,
        showSeconds = false,
        hideHoursIfZero = false
    )
    val notificationText = when (tag.notificationType) {
        TimedTagNotificationType.NONE -> stringResource(R.string.timed_notification_none)
        TimedTagNotificationType.NORMAL -> stringResource(R.string.timed_notification_normal)
        TimedTagNotificationType.ALARM -> stringResource(R.string.timed_notification_alarm)
    }
    return stringResource(R.string.timed_tag_summary, durationText, notificationText)
}

private fun initialTimedTagHours(timedDurationMinutes: Int?): String =
    (((timedDurationMinutes ?: 0) / 60).takeIf { it > 0 }?.toString()).orEmpty()

private fun initialTimedTagMinutes(timedDurationMinutes: Int?): String =
    (((timedDurationMinutes ?: 0) % 60).takeIf { timedDurationMinutes != null }?.toString()).orEmpty()

private fun tagActionButtonPadding(): PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 8.dp)

@Composable
private fun TagActionButtonLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center
    )
}

private fun compareTags(
    left: Tag,
    right: Tag,
    sortKey: TagSortKey,
    ascending: Boolean,
    firstStartByTagId: Map<Long, Long>,
    sessionCountByTagId: Map<Long, Int>,
    totalsMsByTagId: Map<Long, Long>,
): Int {
    fun withDirection(result: Int): Int = if (ascending) result else -result

    val primary = when (sortKey) {
        TagSortKey.FIRST_SESSION -> compareNullableLongs(
            left = firstStartByTagId[left.id],
            right = firstStartByTagId[right.id],
            ascending = ascending
        )

        TagSortKey.NAME -> withDirection(left.name.lowercase().compareTo(right.name.lowercase()))

        TagSortKey.TOTAL_TIME -> withDirection(
            (totalsMsByTagId[left.id] ?: 0L).compareTo(totalsMsByTagId[right.id] ?: 0L)
        )

        TagSortKey.SESSION_COUNT -> withDirection(
            (sessionCountByTagId[left.id] ?: 0).compareTo(sessionCountByTagId[right.id] ?: 0)
        )
    }

    return if (primary != 0) primary else left.name.lowercase().compareTo(right.name.lowercase())
}

private fun compareNullableLongs(left: Long?, right: Long?, ascending: Boolean): Int {
    if (left == null && right == null) return 0
    if (left == null) return 1
    if (right == null) return -1
    return if (ascending) left.compareTo(right) else right.compareTo(left)
}

private fun sessionDisplayTitle(session: SessionUi, fallbackTagName: String): String {
    return session.title.trim().ifBlank { fallbackTagName }
}

private fun sessionDurationMs(session: SessionUi, nowMs: Long): Long {
    val endMs = session.endMs ?: nowMs
    return (endMs - session.startMs).coerceAtLeast(0L)
}

private fun computeShownMs(
    tag: Tag,
    nowMs: Long,
    totalsMsByTagId: Map<Long, Long>,
    runningMinStartByTagId: Map<Long, Long>
): Long {
    val baseClosed = totalsMsByTagId[tag.id] ?: 0L
    val minStart = runningMinStartByTagId[tag.id]
    val liveDelta = if (minStart != null) (nowMs - minStart).coerceAtLeast(0L) else 0L
    return baseClosed + liveDelta
}

@Composable
private fun EditTagParentsDialog(
    child: Tag,
    allTags: List<Tag>,
    tagLastUsedMsByTagId: Map<Long, Long>,
    currentParents: Set<Long>,
    onDismiss: () -> Unit,
    onConfirm: (Set<Long>) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(currentParents) }

    val candidates = remember(allTags, query, child.id, selected, tagLastUsedMsByTagId) {
        val trimmedQuery = query.trim()
        allTags.asSequence()
            .filter { !it.isDeleted }
            .filter { it.id != child.id }
            .filter { trimmedQuery.isEmpty() || it.name.contains(trimmedQuery, ignoreCase = true) }
            .let { base ->
                TagSelectionOrder.sortForPicker(
                    base.toList(),
                    selectedIds = selected,
                    lastUsedMsByTagId = tagLastUsedMsByTagId
                )
            }
            .toList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.padri_di_child_name, child.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.cerca_tag)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (candidates.isEmpty()) {
                    Text(stringResource(R.string.nessun_tag))
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        TagSelectionFlow(
                            tags = candidates,
                            selectedIds = selected,
                            onToggle = { tag ->
                                selected = if (tag.id in selected) selected - tag.id else selected + tag.id
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            SingleSubmitButton(onClick = { onConfirm(selected) }) {
                Text(stringResource(R.string.salva))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.annulla))
            }
        }
    )
}
