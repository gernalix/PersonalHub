@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.example.multitimetracker.capsules.quickevents.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.multitimetracker.R
import com.example.multitimetracker.capsules.quickevents.controller.QuickEventsCapsuleViewModel
import com.example.multitimetracker.capsules.quickevents.core.quickEventSinceWhenActionEnabled
import com.example.multitimetracker.capsules.quickevents.state.QuickEventsUiState
import com.example.multitimetracker.core.quickevent.QuickEventTarget
import com.example.multitimetracker.model.QuickEventDefaults
import com.example.multitimetracker.model.QuickEventEntry
import com.example.multitimetracker.model.QuickEventFieldDefinition
import com.example.multitimetracker.model.QuickEventFieldType
import com.example.multitimetracker.model.QuickEventFieldValue
import com.example.multitimetracker.model.QuickEventFilters
import com.example.multitimetracker.model.QuickEventMacro
import com.example.multitimetracker.model.QuickEventMacroAction
import com.example.multitimetracker.model.QuickEventTemplate
import com.example.multitimetracker.model.Tag
import com.gernalix.personalhub.contracts.database.HubDeepLinkContract
import com.gernalix.personalhub.contracts.database.SinceWhenSourceDescriptor
import com.gernalix.personalhub.contracts.database.SinceWhenTimestampSource
import com.gernalix.personalhub.core.ui.SinceWhenCreationControl
import com.example.multitimetracker.ui.components.AppTopBar
import com.example.multitimetracker.ui.components.MttDateTimePickerDialog
import com.example.multitimetracker.ui.components.ScreenEmptyStateCard
import com.example.multitimetracker.ui.components.ScreenHelpAction
import com.example.multitimetracker.ui.components.TagSelectionFlow
import com.example.multitimetracker.ui.util.TagSelectionOrder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun QuickEventsScreen(
    modifier: Modifier,
    capsule: QuickEventsCapsuleViewModel,
    onOpenTag: (Long) -> Unit,
    showSeconds: Boolean,
    hideHoursIfZero: Boolean,
    pendingWidgetTarget: QuickEventTarget? = null,
    onPendingWidgetTargetConsumed: () -> Unit = {},
    initialOpenedEntryId: Long? = null,
    onConsumedInitialOpenedEntryId: () -> Unit = {},
) {
    val state by capsule.uiState.collectAsState()
    val context = LocalContext.current
    val effectiveTime = remember(state.nowMs) { state.effectiveTimeContext() }
    val visibleTags = remember(state.tags) { state.tags.filter { !it.isDeleted && !it.isArchived }.distinctBy { it.id } }
    val fieldsByTemplate = remember(state.quickEventFieldDefinitions) { state.quickEventFieldDefinitions.groupBy { it.templateId } }
    val valuesByEntry = remember(state.quickEventFieldValues) { state.quickEventFieldValues.groupBy { it.entryId } }
    val macroActionsByMacro = remember(state.quickEventMacroActions) {
        state.quickEventMacroActions
            .groupBy { it.macroId }
            .mapValues { (_, actions) -> actions.sortedBy { it.displayOrder } }
    }
    val templatesById = remember(state.quickEventTemplates) { state.quickEventTemplates.associateBy { it.id } }
    val requiredFieldsByTemplateId = remember(fieldsByTemplate) {
        fieldsByTemplate.mapValues { (_, fields) -> QuickEventDefaults.requiredFieldsMissingDefaults(fields) }
    }

    LaunchedEffect(capsule, context) {
        capsule.refreshScreenSnapshot(context.applicationContext)
    }

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var filterTagIds by rememberSaveable { mutableStateOf(setOf<Long>()) }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var creatingEntry by remember { mutableStateOf(false) }
    var creatingTemplate by remember { mutableStateOf(false) }
    var creatingMacro by remember { mutableStateOf(false) }
    var editingTemplate by remember { mutableStateOf<QuickEventTemplate?>(null) }
    var editingMacro by remember { mutableStateOf<QuickEventMacro?>(null) }
    var editingEntry by remember { mutableStateOf<QuickEventEntry?>(null) }
    var customTemplate by remember { mutableStateOf<QuickEventTemplate?>(null) }
    var customMacro by remember { mutableStateOf<QuickEventMacro?>(null) }

    LaunchedEffect(pendingWidgetTarget, state.quickEventTemplates, state.quickEventMacros) {
        when (val target = pendingWidgetTarget) {
            is QuickEventTarget.Template -> {
                state.quickEventTemplates.firstOrNull { it.id == target.templateId && it.deletedAtMs == null && !it.isArchived }?.let {
                    customTemplate = it
                    onPendingWidgetTargetConsumed()
                }
            }
            is QuickEventTarget.Macro -> {
                state.quickEventMacros.firstOrNull { it.id == target.macroId && it.deletedAtMs == null && !it.isArchived }?.let {
                    customMacro = it
                    onPendingWidgetTargetConsumed()
                }
            }
            null -> Unit
        }
    }

    val templates = remember(state.quickEventTemplates, searchQuery, filterTagIds, visibleTags, showArchived) {
        QuickEventFilters.filterTemplates(state.quickEventTemplates, visibleTags, searchQuery, filterTagIds, showArchived)
    }
    val macros = remember(state.quickEventMacros, searchQuery, filterTagIds, visibleTags, showArchived) {
        QuickEventFilters.filterMacros(state.quickEventMacros, visibleTags, searchQuery, filterTagIds, showArchived)
    }
    val templateSections = remember(templates, visibleTags) {
        QuickEventFilters.groupTemplatesByPrimaryTag(templates, visibleTags)
    }
    val macroSections = remember(macros, visibleTags) {
        QuickEventFilters.groupMacrosByPrimaryTag(macros, visibleTags)
    }
    val eventHistory = remember(state.quickEventEntries, visibleTags, searchQuery, filterTagIds) {
        QuickEventFilters.filterEntries(state.quickEventEntries, visibleTags, searchQuery, filterTagIds)
    }
    LaunchedEffect(initialOpenedEntryId, state.quickEventEntries.map { it.id }) {
        val id = initialOpenedEntryId ?: return@LaunchedEffect
        state.quickEventEntries.firstOrNull { it.id == id }?.let { entry ->
            editingEntry = entry
            onConsumedInitialOpenedEntryId()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.quick_events),
                actions = {
                    ScreenHelpAction(
                        title = stringResource(R.string.quick_events_intro_title),
                        body = stringResource(R.string.quick_events_intro_body)
                    )
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { if (!state.isReadOnly) creatingEntry = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.quick_event_add_entry))
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "quick_event_primary_action", span = { GridItemSpan(maxLineSpan) }) {
                    Button(
                        onClick = { creatingEntry = true },
                        enabled = !state.isReadOnly,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.quick_event_register_event))
                    }
                }
                item(key = "quick_event_controls", span = { GridItemSpan(maxLineSpan) }) {
                    QuickEventSearchAndFilters(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        activeFilterCount = filterTagIds.size,
                        onOpenFilters = { filtersOpen = true },
                        showArchived = showArchived,
                        onShowArchivedChange = { showArchived = it }
                    )
                }
                item(key = "quick_event_templates_header", span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle(stringResource(R.string.quick_events_templates))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(
                                onClick = { creatingMacro = true },
                                enabled = !state.isReadOnly,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.quick_event_add_macro))
                            }
                            OutlinedButton(
                                onClick = { creatingTemplate = true },
                                enabled = !state.isReadOnly,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.quick_event_add_template))
                            }
                        }
                    }
                }
                if (templates.isEmpty()) {
                    item(key = "quick_event_templates_empty", span = { GridItemSpan(maxLineSpan) }) {
                        ScreenEmptyStateCard(
                            title = stringResource(R.string.quick_events_empty_templates_title),
                            body = stringResource(R.string.quick_events_empty_templates_body),
                            icon = Icons.Filled.Event
                        )
                    }
                } else {
                    templateSections.forEach { section ->
                        item(key = "quick_event_template_section_${section.tagId ?: "none"}", span = { GridItemSpan(maxLineSpan) }) {
                            SectionTitle(section.tagName ?: stringResource(R.string.quick_event_section_untagged))
                        }
                        items(
                            items = section.items,
                            key = { template -> "quick_event_template_${template.id}" }
                        ) { template ->
                            val fields = fieldsByTemplate[template.id].orEmpty()
                            val hasRequiredFieldPrompt = requiredFieldsByTemplateId[template.id] == true
                            QuickEventTemplateButton(
                                modifier = Modifier.fillMaxWidth(),
                                template = template,
                                hasRequiredFieldPrompt = hasRequiredFieldPrompt,
                                readOnly = state.isReadOnly,
                                onTap = {
                                    if (!state.isReadOnly) {
                                        if (hasRequiredFieldPrompt) {
                                            customTemplate = template
                                        } else {
                                            capsule.createEntryFromTemplate(template.id, null)
                                        }
                                    }
                                },
                                onCustomize = { if (!state.isReadOnly) customTemplate = template },
                                onEditTemplate = { if (!state.isReadOnly) editingTemplate = template },
                                onArchiveTemplate = {
                                    if (!state.isReadOnly) capsule.updateTemplate(template.id, template.title, template.tagIds, template.sortOrder, !template.isArchived, fields)
                                },
                                onDeleteTemplate = { if (!state.isReadOnly) capsule.deleteTemplate(template.id) }
                            )
                        }
                    }
                }
                if (macroSections.isNotEmpty()) {
                    item(key = "quick_event_macro_header", span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            SectionTitle(stringResource(R.string.quick_event_macros))
                            OutlinedButton(
                                onClick = { creatingMacro = true },
                                enabled = !state.isReadOnly,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(stringResource(R.string.quick_event_add_macro))
                            }
                        }
                    }
                    macroSections.forEach { section ->
                        item(key = "quick_event_macro_section_${section.tagId ?: "none"}", span = { GridItemSpan(maxLineSpan) }) {
                            SectionTitle(section.tagName ?: stringResource(R.string.quick_event_section_untagged))
                        }
                        items(
                            items = section.items,
                            key = { macro -> "quick_event_macro_${macro.id}" }
                        ) { macro ->
                            val actions = macroActionsByMacro[macro.id].orEmpty()
                            QuickEventMacroButton(
                                modifier = Modifier.fillMaxWidth(),
                                macro = macro,
                                readOnly = state.isReadOnly,
                                onTap = {
                                    if (!state.isReadOnly) {
                                        val needsWizard = actions.any { action ->
                                            requiredFieldsByTemplateId[action.templateId] == true
                                        }
                                        if (needsWizard) customMacro = macro else capsule.createEntriesFromMacro(macro.id, null)
                                    }
                                },
                                onCustomize = { if (!state.isReadOnly) customMacro = macro },
                                onEditMacro = { if (!state.isReadOnly) editingMacro = macro },
                                onArchiveMacro = {
                                    if (!state.isReadOnly) {
                                        capsule.updateMacro(
                                            macro.id,
                                            macro.title,
                                            macro.tagIds,
                                            macro.sortOrder,
                                            !macro.isArchived,
                                            actions
                                        )
                                    }
                                },
                                onDeleteMacro = { if (!state.isReadOnly) capsule.deleteMacro(macro.id) }
                            )
                        }
                    }
                }
                item(key = "quick_event_history_header", span = { GridItemSpan(maxLineSpan) }) {
                    SectionTitle(stringResource(R.string.quick_events_history))
                }
                if (eventHistory.isEmpty()) {
                    item(key = "quick_event_history_empty", span = { GridItemSpan(maxLineSpan) }) {
                        ScreenEmptyStateCard(title = stringResource(R.string.quick_event_no_recent_entries), icon = Icons.Filled.Event)
                    }
                } else {
                    items(
                        items = eventHistory,
                        key = { entry -> "quick_event_entry_${entry.id}" },
                        span = { GridItemSpan(maxLineSpan) }
                    ) { entry ->
                        QuickEventEntryRow(
                            entry = entry,
                            fieldValues = valuesByEntry[entry.id].orEmpty(),
                            readOnly = state.isReadOnly,
                            onEdit = { if (!state.isReadOnly) editingEntry = entry },
                            onCreateSinceWhen = {
                                if (quickEventSinceWhenActionEnabled(state.isReadOnly)) {
                                    val source = SinceWhenSourceDescriptor(
                                        entityType = "timer/quick_event_entry",
                                        entityId = entry.id.toString(),
                                        defaultCounterTitle = entry.title,
                                        timestampSources = listOf(SinceWhenTimestampSource(
                                            id = "event_date",
                                            label = context.getString(R.string.since_when_event_date),
                                            timestamp = entry.timestampMs,
                                            isDefault = true,
                                        )),
                                    )
                                    context.startActivity(Intent(Intent.ACTION_VIEW, HubDeepLinkContract.sinceWhenCreateUri(source)))
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (filtersOpen) {
        QuickEventFilterDialog(
            tags = visibleTags,
            selectedTagIds = filterTagIds,
            onToggleTag = { tagId -> filterTagIds = if (tagId in filterTagIds) filterTagIds - tagId else filterTagIds + tagId },
            onClear = { filterTagIds = emptySet() },
            onDismiss = { filtersOpen = false }
        )
    }
    if (creatingEntry) {
        QuickEventEntryDialog(
            title = stringResource(R.string.quick_event_new_entry),
            initialEntry = null,
            initialValues = emptyList(),
            template = null,
            fields = emptyList(),
            tags = visibleTags,
            tagLastUsedMsByTagId = state.tagLastUsedMsByTagId,
            initialTimestampMs = effectiveTime.nowMs,
            readOnly = state.isReadOnly,
            onAddTag = capsule::addTag,
            onDismiss = { creatingEntry = false },
            showCreateTemplateOption = true,
            onSave = { entryTitle, timestampMs, tagIds, values, createReusableTemplate, complete ->
                capsule.createStandaloneEntry(entryTitle, timestampMs, tagIds, values, createReusableTemplate, complete)
            },
            onCreated = { id, entryTitle, timestampMs, createCounter ->
                creatingEntry = false
                if (createCounter) context.startActivity(Intent(Intent.ACTION_VIEW, HubDeepLinkContract.sinceWhenCreateUri(quickEventSinceWhenDescriptor(id, entryTitle, timestampMs), startEnabled = true)))
            },
            onDelete = null
        )
    }
    if (creatingTemplate) {
        QuickEventTemplateDialog(
            template = null,
            fields = emptyList(),
            tags = visibleTags,
            tagLastUsedMsByTagId = state.tagLastUsedMsByTagId,
            readOnly = state.isReadOnly,
            onAddTag = capsule::addTag,
            onDismiss = { creatingTemplate = false },
            onSave = { title, tagIds, sortOrder, archived, fields ->
                capsule.createTemplate(title, tagIds, sortOrder, archived, fields)
                creatingTemplate = false
            },
            onDelete = null
        )
    }
    editingTemplate?.let { template ->
        QuickEventTemplateDialog(
            template = template,
            fields = fieldsByTemplate[template.id].orEmpty(),
            tags = visibleTags,
            tagLastUsedMsByTagId = state.tagLastUsedMsByTagId,
            readOnly = state.isReadOnly,
            onAddTag = capsule::addTag,
            onDismiss = { editingTemplate = null },
            onSave = { title, tagIds, sortOrder, archived, fields ->
                capsule.updateTemplate(template.id, title, tagIds, sortOrder, archived, fields)
                editingTemplate = null
            },
            onDelete = {
                capsule.deleteTemplate(template.id)
                editingTemplate = null
            }
        )
    }
    customTemplate?.let { template ->
        val fields = fieldsByTemplate[template.id].orEmpty()
        QuickEventEntryDialog(
            title = stringResource(R.string.quick_event_customize_entry),
            initialEntry = null,
            initialValues = emptyList(),
            template = template,
            fields = fields,
            tags = visibleTags,
            tagLastUsedMsByTagId = state.tagLastUsedMsByTagId,
            initialTimestampMs = effectiveTime.nowMs,
            readOnly = state.isReadOnly,
            onAddTag = capsule::addTag,
            onDismiss = { customTemplate = null },
            onSave = { entryTitle, timestampMs, tagIds, values, _, complete ->
                capsule.createCustomEntry(template.id, null, entryTitle, timestampMs, tagIds, values, complete)
            },
            onCreated = { id, entryTitle, timestampMs, createCounter ->
                customTemplate = null
                if (createCounter) context.startActivity(Intent(Intent.ACTION_VIEW, HubDeepLinkContract.sinceWhenCreateUri(quickEventSinceWhenDescriptor(id, entryTitle, timestampMs), startEnabled = true)))
            },
            onDelete = null
        )
    }
    editingEntry?.let { entry ->
        val template = state.quickEventTemplates.firstOrNull { it.id == entry.templateId }
        QuickEventEntryDialog(
            title = stringResource(R.string.quick_event_edit_entry),
            initialEntry = entry,
            initialValues = valuesByEntry[entry.id].orEmpty(),
            template = template,
            fields = template?.let { fieldsByTemplate[it.id].orEmpty() }.orEmpty(),
            tags = visibleTags,
            tagLastUsedMsByTagId = state.tagLastUsedMsByTagId,
            initialTimestampMs = entry.timestampMs,
            readOnly = state.isReadOnly,
            onAddTag = capsule::addTag,
            onDismiss = { editingEntry = null },
            onSave = { entryTitle, timestampMs, tagIds, values, _, complete ->
                capsule.updateEntry(entry.id, entryTitle, timestampMs, tagIds, values)
                editingEntry = null
                complete(Result.success(entry.id))
            },
            onCreated = { _, _, _, _ -> },
            onDelete = {
                capsule.deleteEntry(entry.id)
                editingEntry = null
            }
        )
    }
    if (creatingMacro || editingMacro != null) {
        val macro = editingMacro
        QuickEventMacroDialog(
            macro = macro,
            selectedActions = macro?.let { state.quickEventMacroActions.filter { action -> action.macroId == it.id } }.orEmpty(),
            templates = state.quickEventTemplates.filter { it.deletedAtMs == null },
            tags = visibleTags,
            tagLastUsedMsByTagId = state.tagLastUsedMsByTagId,
            readOnly = state.isReadOnly,
            onAddTag = capsule::addTag,
            onDismiss = {
                creatingMacro = false
                editingMacro = null
            },
            onSave = { title, tagIds, sortOrder, archived, actions ->
                if (macro == null) capsule.createMacro(title, tagIds, sortOrder, archived, actions)
                else capsule.updateMacro(macro.id, title, tagIds, sortOrder, archived, actions)
                creatingMacro = false
                editingMacro = null
            },
            onDelete = macro?.let {
                {
                    capsule.deleteMacro(it.id)
                    editingMacro = null
                }
            }
        )
    }
    customMacro?.let { macro ->
        val actions = macroActionsByMacro[macro.id].orEmpty()
        QuickEventMacroRunDialog(
            macro = macro,
            actions = actions,
            templatesById = templatesById,
            fieldsByTemplate = fieldsByTemplate,
            onDismiss = { customMacro = null },
            onConfirm = { timestampMs, entries ->
                entries.forEach { entry ->
                    capsule.createCustomEntry(
                        templateId = entry.templateId,
                        macroId = macro.id,
                        title = entry.title,
                        timestampMs = timestampMs,
                        tagIds = entry.tagIds,
                        fieldValues = entry.fieldValues
                    )
                }
                customMacro = null
            }
        )
    }
}

private data class MacroEntrySave(
    val templateId: Long,
    val title: String,
    val tagIds: Set<Long>,
    val fieldValues: List<QuickEventFieldValue>
)

private sealed class QuickEventAutoFocusTarget(val key: String) {
    object Title : QuickEventAutoFocusTarget("title")
    data class Field(val fieldId: Long) : QuickEventAutoFocusTarget("field:$fieldId")
}

@Composable
private fun QuickEventSearchAndFilters(
    query: String,
    onQueryChange: (String) -> Unit,
    activeFilterCount: Int,
    onOpenFilters: () -> Unit,
    showArchived: Boolean,
    onShowArchivedChange: (Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotBlank()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.quick_event_clear_search))
                    }
                }
            },
            label = { Text(stringResource(R.string.quick_event_search)) }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onOpenFilters) {
                Icon(Icons.Filled.FilterList, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.quick_event_filters_count, activeFilterCount))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.quick_event_show_archived), style = MaterialTheme.typography.labelMedium)
                Switch(checked = showArchived, onCheckedChange = onShowArchivedChange)
            }
        }
    }
}

@Composable
private fun QuickEventFilterDialog(
    tags: List<Tag>,
    selectedTagIds: Set<Long>,
    onToggleTag: (Long) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.quick_event_filter_tags)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (tags.isEmpty()) {
                    Text(stringResource(R.string.nessun_tag))
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        tags.forEach { tag ->
                            FilterChip(
                                selected = tag.id in selectedTagIds,
                                onClick = { onToggleTag(tag.id) },
                                label = { Text(tag.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }
                }
                TextButton(onClick = onClear) { Text(stringResource(R.string.quick_event_clear_filters)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } }
    )
}

@Composable
private fun QuickEventTemplateButton(
    modifier: Modifier = Modifier,
    template: QuickEventTemplate,
    hasRequiredFieldPrompt: Boolean,
    readOnly: Boolean,
    onTap: () -> Unit,
    onCustomize: () -> Unit,
    onEditTemplate: () -> Unit,
    onArchiveTemplate: () -> Unit,
    onDeleteTemplate: () -> Unit
) {
    QuickEventActionButton(
        modifier = modifier,
        title = template.title,
        hasRequiredFieldPrompt = hasRequiredFieldPrompt,
        archived = template.isArchived,
        macro = false,
        readOnly = readOnly,
        onTap = onTap,
        onLongTap = onCustomize,
        onEdit = onEditTemplate,
        onArchive = onArchiveTemplate,
        onDelete = onDeleteTemplate
    )
}

@Composable
private fun QuickEventMacroButton(
    modifier: Modifier = Modifier,
    macro: QuickEventMacro,
    readOnly: Boolean,
    onTap: () -> Unit,
    onCustomize: () -> Unit,
    onEditMacro: () -> Unit,
    onArchiveMacro: () -> Unit,
    onDeleteMacro: () -> Unit
) {
    QuickEventActionButton(
        modifier = modifier,
        title = macro.title,
        hasRequiredFieldPrompt = false,
        archived = macro.isArchived,
        macro = true,
        readOnly = readOnly,
        onTap = onTap,
        onLongTap = onCustomize,
        onEdit = onEditMacro,
        onArchive = onArchiveMacro,
        onDelete = onDeleteMacro
    )
}

@Composable
private fun QuickEventActionButton(
    modifier: Modifier = Modifier,
    title: String,
    hasRequiredFieldPrompt: Boolean,
    archived: Boolean,
    macro: Boolean,
    readOnly: Boolean,
    onTap: () -> Unit,
    onLongTap: () -> Unit,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .heightIn(min = 34.dp)
            .combinedClickable(enabled = !readOnly, onClick = onTap, onLongClick = onLongTap),
        shape = RoundedCornerShape(8.dp),
        color = when {
            archived -> MaterialTheme.colorScheme.surfaceVariant
            macro -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MaterialTheme.colorScheme.primaryContainer
        },
        tonalElevation = if (macro) 2.dp else 1.dp,
        border = if (macro) BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary) else null
    ) {
        Row(
            Modifier.padding(start = 8.dp, end = 0.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (hasRequiredFieldPrompt) {
                Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(13.dp))
            }
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = !readOnly, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.quick_event_template_actions), modifier = Modifier.size(16.dp))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(stringResource(if (macro) R.string.quick_event_edit_macro else R.string.quick_event_edit_template)) }, leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) }, onClick = { menuOpen = false; onEdit() })
                    DropdownMenuItem(
                        text = { Text(stringResource(if (archived) R.string.quick_event_unarchive_template else R.string.quick_event_archive_template)) },
                        leadingIcon = { Icon(if (archived) Icons.Filled.Unarchive else Icons.Filled.Archive, contentDescription = null) },
                        onClick = { menuOpen = false; onArchive() }
                    )
                    DropdownMenuItem(text = { Text(stringResource(R.string.quick_event_delete_template)) }, leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) }, onClick = { menuOpen = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun QuickEventEntryRow(
    entry: QuickEventEntry,
    fieldValues: List<QuickEventFieldValue>,
    readOnly: Boolean,
    onEdit: () -> Unit,
    onCreateSinceWhen: () -> Unit
) {
    val formatter = remember { DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm", Locale.getDefault()) }
    var menuOpen by remember { mutableStateOf(false) }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().combinedClickable(enabled = !readOnly, onClick = onEdit, onLongClick = { menuOpen = true }),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(formatter.format(Instant.ofEpochMilli(entry.timestampMs).atZone(ZoneId.systemDefault())), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                Box {
                    IconButton(onClick = { menuOpen = true }, enabled = !readOnly) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.quick_event_entry_actions), modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.quick_event_edit_entry)) },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onEdit()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.quick_event_create_since_when)) },
                            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                            enabled = quickEventSinceWhenActionEnabled(readOnly),
                            onClick = {
                                menuOpen = false
                                onCreateSinceWhen()
                            }
                        )
                    }
                }
            }
            fieldValues.filter { it.value.isNotBlank() }.forEach { value ->
                Text("${value.label}: ${value.value}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun QuickEventTemplateDialog(
    template: QuickEventTemplate?,
    fields: List<QuickEventFieldDefinition>,
    tags: List<Tag>,
    tagLastUsedMsByTagId: Map<Long, Long>,
    readOnly: Boolean,
    onAddTag: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, Set<Long>, Int, Boolean, List<QuickEventFieldDefinition>) -> Unit,
    onDelete: (() -> Unit)?
) {
    var title by remember(template) { mutableStateOf(template?.title.orEmpty()) }
    var tagIds by remember(template) { mutableStateOf(template?.tagIds ?: emptySet()) }
    var sortOrderText by remember(template) { mutableStateOf((template?.sortOrder ?: 0).toString()) }
    var archived by remember(template) { mutableStateOf(template?.isArchived ?: false) }
    var fieldDrafts by remember(template, fields) { mutableStateOf(fields.ifEmpty { emptyList() }) }
    var showTags by remember { mutableStateOf(false) }
    var fieldDialog by remember { mutableStateOf<QuickEventFieldDefinition?>(null) }
    var addingField by remember { mutableStateOf(false) }
    val cleanTitle = title.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (template == null) R.string.quick_event_new_template else R.string.quick_event_edit_template)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, enabled = !readOnly, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.quick_event_title)) })
                OutlinedTextField(value = sortOrderText, onValueChange = { sortOrderText = it.filter { ch -> ch == '-' || ch.isDigit() } }, enabled = !readOnly, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.quick_event_sort_order)) })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.quick_event_archived))
                    Switch(checked = archived, onCheckedChange = { archived = it }, enabled = !readOnly)
                }
                OutlinedButton(onClick = { showTags = true }, enabled = !readOnly) { Text(stringResource(R.string.quick_event_selected_tags_count, tagIds.size)) }
                SectionTitle(stringResource(R.string.quick_event_custom_fields))
                fieldDrafts.sortedBy { it.displayOrder }.forEach { field ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("${field.label} - ${field.type.name.lowercase()}", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { fieldDialog = field }, enabled = !readOnly) { Icon(Icons.Filled.Edit, contentDescription = null) }
                        IconButton(onClick = { fieldDrafts = fieldDrafts - field }, enabled = !readOnly) { Icon(Icons.Filled.Delete, contentDescription = null) }
                    }
                }
                OutlinedButton(onClick = { addingField = true }, enabled = !readOnly) { Text(stringResource(R.string.quick_event_add_field)) }
            }
        },
        confirmButton = {
            Button(enabled = !readOnly && cleanTitle.isNotBlank(), onClick = {
                onSave(cleanTitle, tagIds, sortOrderText.toIntOrNull() ?: 0, archived, fieldDrafts)
            }) { Text(stringResource(R.string.salva)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onDelete != null) TextButton(onClick = onDelete, enabled = !readOnly) { Text(stringResource(R.string.elimina)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.annulla)) }
            }
        }
    )

    if (showTags) {
        QuickEventTagPickerDialog(tags, tagIds, tagLastUsedMsByTagId, onAddTag, onToggle = { tag -> tagIds = if (tag.id in tagIds) tagIds - tag.id else tagIds + tag.id }, onDismiss = { showTags = false })
    }
    if (addingField || fieldDialog != null) {
        QuickEventFieldDialog(
            field = fieldDialog,
            templateId = template?.id ?: 0L,
            onDismiss = { addingField = false; fieldDialog = null },
            onSave = { saved ->
                fieldDrafts = if (fieldDialog == null) {
                    fieldDrafts + saved.copy(displayOrder = fieldDrafts.size)
                } else {
                    fieldDrafts.map { if (it.id == fieldDialog?.id && it.label == fieldDialog?.label) saved else it }
                }
                addingField = false
                fieldDialog = null
            }
        )
    }
}

@Composable
private fun QuickEventFieldDialog(
    field: QuickEventFieldDefinition?,
    templateId: Long,
    onDismiss: () -> Unit,
    onSave: (QuickEventFieldDefinition) -> Unit
) {
    var label by remember(field) { mutableStateOf(field?.label.orEmpty()) }
    var type by remember(field) { mutableStateOf(field?.type ?: QuickEventFieldType.TEXT) }
    var required by remember(field) { mutableStateOf(field?.required ?: false) }
    var defaultValue by remember(field) { mutableStateOf(field?.defaultValue.orEmpty()) }
    var choices by remember(field) { mutableStateOf(field?.choiceOptions?.joinToString(", ").orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.quick_event_field)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = label, onValueChange = { label = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.quick_event_field_label)) })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickEventFieldType.values().forEach { option ->
                        FilterChip(selected = type == option, onClick = { type = option }, label = { Text(option.name.lowercase()) })
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.quick_event_field_required))
                    Switch(checked = required, onCheckedChange = { required = it })
                }
                OutlinedTextField(value = defaultValue, onValueChange = { defaultValue = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.quick_event_field_default)) })
                if (type == QuickEventFieldType.CHOICE) {
                    OutlinedTextField(value = choices, onValueChange = { choices = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.quick_event_field_choices)) })
                }
            }
        },
        confirmButton = {
            Button(enabled = label.trim().isNotBlank(), onClick = {
                onSave(
                    QuickEventFieldDefinition(
                        id = field?.id ?: 0L,
                        templateId = field?.templateId ?: templateId,
                        label = label.trim(),
                        type = type,
                        required = required,
                        defaultValue = defaultValue.trim(),
                        choiceOptions = choices.split(",").map { it.trim() }.filter { it.isNotBlank() },
                        displayOrder = field?.displayOrder ?: 0
                    )
                )
            }) { Text(stringResource(R.string.salva)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.annulla)) } }
    )
}

private fun quickEventSinceWhenDescriptor(id: Long, title: String, timestampMs: Long) = SinceWhenSourceDescriptor(
    entityType = "timer/quick_event_entry",
    entityId = id.toString(),
    defaultCounterTitle = title,
    timestampSources = listOf(SinceWhenTimestampSource("event_date", "Event date", timestampMs, true)),
)

@Composable
private fun QuickEventEntryDialog(
    title: String,
    initialEntry: QuickEventEntry?,
    initialValues: List<QuickEventFieldValue>,
    template: QuickEventTemplate?,
    fields: List<QuickEventFieldDefinition>,
    tags: List<Tag>,
    tagLastUsedMsByTagId: Map<Long, Long>,
    initialTimestampMs: Long,
    readOnly: Boolean,
    onAddTag: (String) -> Unit,
    onDismiss: () -> Unit,
    showCreateTemplateOption: Boolean = false,
    onSave: (String, Long, Set<Long>, List<QuickEventFieldValue>, Boolean, (Result<Long>) -> Unit) -> Unit,
    onCreated: (Long, String, Long, Boolean) -> Unit,
    onDelete: (() -> Unit)?
) {
    var entryTitle by remember(initialEntry, template) { mutableStateOf(initialEntry?.title ?: template?.title.orEmpty()) }
    var timestampMs by remember(initialEntry, initialTimestampMs) { mutableStateOf(initialEntry?.timestampMs ?: initialTimestampMs) }
    var tagIds by remember(initialEntry, template) { mutableStateOf(initialEntry?.tagIds ?: template?.tagIds ?: emptySet()) }
    val fieldValueText = remember(initialEntry, initialValues, fields) {
        mutableStateMapOf<Long, String>().apply {
            fields.forEach { field ->
                put(field.id, initialValues.firstOrNull { it.fieldId == field.id }?.value ?: field.defaultValue)
            }
        }
    }
    var showTags by remember { mutableStateOf(false) }
    var showTimestampPicker by remember { mutableStateOf(false) }
    var createReusableTemplate by remember(initialEntry, template) { mutableStateOf(false) }
    var createSinceWhen by remember(initialEntry) { mutableStateOf(false) }
    var saving by remember(initialEntry) { mutableStateOf(false) }
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val formatter = remember { DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm", Locale.getDefault()) }
    val cleanTitle = entryTitle.trim()
    val titleFocusRequester = remember { FocusRequester() }
    val fieldFocusRequesters = remember(fields) {
        fields.associate { it.id to FocusRequester() }
    }
    val initialValuesByFieldId = remember(initialValues) {
        initialValues.associate { it.fieldId to it.value }
    }
    val autoFocusTarget = remember(initialEntry, template, fields, initialValuesByFieldId, readOnly) {
        if (readOnly) {
            null
        } else {
            quickEventAutoFocusTarget(
                initialTitle = initialEntry?.title ?: template?.title.orEmpty(),
                fields = fields,
                initialValuesByFieldId = initialValuesByFieldId
            )
        }
    }
    val autoFocusRequester = when (val target = autoFocusTarget) {
        QuickEventAutoFocusTarget.Title -> titleFocusRequester
        is QuickEventAutoFocusTarget.Field -> fieldFocusRequesters[target.fieldId]
        null -> null
    }

    LaunchedEffect(autoFocusTarget?.key) {
        val requester = autoFocusRequester ?: return@LaunchedEffect
        withFrameNanos { }
        runCatching { requester.requestFocus() }
        withFrameNanos { }
        keyboardController?.show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = entryTitle, onValueChange = { entryTitle = it }, enabled = !readOnly, modifier = Modifier.fillMaxWidth().focusRequester(titleFocusRequester), singleLine = true, label = { Text(stringResource(R.string.quick_event_title)) })
                OutlinedButton(onClick = { showTimestampPicker = true }, enabled = !readOnly, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.quick_event_timestamp_value, formatter.format(Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault()))))
                }
                fields.sortedBy { it.displayOrder }.forEach { field ->
                    QuickEventFieldValueEditor(
                        field = field,
                        value = fieldValueText[field.id].orEmpty(),
                        onValueChange = { fieldValueText[field.id] = it },
                        readOnly = readOnly,
                        focusRequester = fieldFocusRequesters[field.id]
                    )
                }
                OutlinedButton(onClick = { showTags = true }, enabled = !readOnly) { Text(stringResource(R.string.quick_event_selected_tags_count, tagIds.size)) }
                if (showCreateTemplateOption) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.quick_event_create_reusable_template), modifier = Modifier.weight(1f))
                        Checkbox(
                            checked = createReusableTemplate,
                            onCheckedChange = { createReusableTemplate = it },
                            enabled = !readOnly
                        )
                    }
                }
                if (initialEntry == null) {
                    SinceWhenCreationControl(
                        timestampSources = listOf(SinceWhenTimestampSource("event_date", stringResource(R.string.since_when_event_date), timestampMs, true)),
                        enabled = createSinceWhen,
                        selectedSourceId = "event_date",
                        saving = saving || readOnly,
                        onEnabledChange = { createSinceWhen = it },
                        onSourceSelected = {},
                    )
                }
            }
        },
        confirmButton = {
            val requiredOk = fields.none { it.required && fieldValueText[it.id].orEmpty().isBlank() }
            Button(enabled = !readOnly && !saving && cleanTitle.isNotBlank() && requiredOk, onClick = {
                val values = fields.sortedBy { it.displayOrder }.mapIndexed { index, field ->
                    QuickEventFieldValue(
                        id = initialValues.firstOrNull { it.fieldId == field.id }?.id ?: 0L,
                        entryId = initialEntry?.id ?: 0L,
                        fieldId = field.id,
                        label = field.label,
                        type = field.type,
                        value = fieldValueText[field.id].orEmpty(),
                        displayOrder = field.displayOrder.takeIf { it != 0 } ?: index
                    )
                }
                saving = true
                onSave(cleanTitle, timestampMs, tagIds, values, createReusableTemplate) { result ->
                    saving = false
                    result.onSuccess { entryId ->
                        if (initialEntry == null) onCreated(entryId, cleanTitle, timestampMs, createSinceWhen)
                    }
                }
            }) { Text(stringResource(R.string.quick_event_record)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onDelete != null) TextButton(onClick = onDelete, enabled = !readOnly) { Text(stringResource(R.string.elimina)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.annulla)) }
            }
        }
    )

    if (showTags) {
        QuickEventTagPickerDialog(tags, tagIds, tagLastUsedMsByTagId, onAddTag, onToggle = { tag -> tagIds = if (tag.id in tagIds) tagIds - tag.id else tagIds + tag.id }, onDismiss = { showTags = false })
    }
    if (showTimestampPicker) {
        MttDateTimePickerDialog(
            title = stringResource(R.string.history_timestamp_edit),
            initialTimestampMs = timestampMs,
            onDismiss = { showTimestampPicker = false },
            onConfirm = { picked ->
                timestampMs = picked
                showTimestampPicker = false
            }
        )
    }
}

@Composable
private fun QuickEventFieldValueEditor(
    field: QuickEventFieldDefinition,
    value: String,
    onValueChange: (String) -> Unit,
    readOnly: Boolean,
    focusRequester: FocusRequester? = null
) {
    val textFieldModifier = if (focusRequester != null) {
        Modifier.fillMaxWidth().focusRequester(focusRequester)
    } else {
        Modifier.fillMaxWidth()
    }
    when (field.type) {
        QuickEventFieldType.BOOLEAN -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(field.label)
            Switch(checked = value == "true", onCheckedChange = { onValueChange(it.toString()) }, enabled = !readOnly)
        }
        QuickEventFieldType.CHOICE -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(field.label, style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    field.choiceOptions.forEach { option ->
                        FilterChip(selected = value == option, onClick = { onValueChange(option) }, label = { Text(option) }, enabled = !readOnly)
                    }
                }
            }
        }
        QuickEventFieldType.NUMBER -> OutlinedTextField(value = value, onValueChange = { onValueChange(it.filter { ch -> ch == '-' || ch == '.' || ch.isDigit() }) }, enabled = !readOnly, modifier = textFieldModifier, singleLine = true, label = { Text(field.label) })
        QuickEventFieldType.TEXT -> OutlinedTextField(value = value, onValueChange = onValueChange, enabled = !readOnly, modifier = textFieldModifier, singleLine = true, label = { Text(field.label) })
    }
}

private fun quickEventAutoFocusTarget(
    initialTitle: String,
    fields: List<QuickEventFieldDefinition>,
    initialValuesByFieldId: Map<Long, String>
): QuickEventAutoFocusTarget? {
    if (initialTitle.isBlank()) return QuickEventAutoFocusTarget.Title
    val keyboardFields = fields
        .asSequence()
        .filter { it.deletedAtMs == null && it.isKeyboardField() }
        .sortedBy { it.displayOrder }
        .toList()
    keyboardFields.firstOrNull { field ->
        field.required && (initialValuesByFieldId[field.id] ?: field.defaultValue).isBlank()
    }?.let { return QuickEventAutoFocusTarget.Field(it.id) }
    keyboardFields.firstOrNull { field ->
        (initialValuesByFieldId[field.id] ?: field.defaultValue).isBlank()
    }?.let { return QuickEventAutoFocusTarget.Field(it.id) }
    return null
}

private fun QuickEventFieldDefinition.isKeyboardField(): Boolean =
    type == QuickEventFieldType.TEXT || type == QuickEventFieldType.NUMBER

@Composable
private fun QuickEventMacroDialog(
    macro: QuickEventMacro?,
    selectedActions: List<QuickEventMacroAction>,
    templates: List<QuickEventTemplate>,
    tags: List<Tag>,
    tagLastUsedMsByTagId: Map<Long, Long>,
    readOnly: Boolean,
    onAddTag: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, Set<Long>, Int, Boolean, List<QuickEventMacroAction>) -> Unit,
    onDelete: (() -> Unit)?
) {
    var title by remember(macro) { mutableStateOf(macro?.title.orEmpty()) }
    var tagIds by remember(macro) { mutableStateOf(macro?.tagIds ?: emptySet()) }
    var sortOrderText by remember(macro) { mutableStateOf((macro?.sortOrder ?: 0).toString()) }
    var archived by remember(macro) { mutableStateOf(macro?.isArchived ?: false) }
    var selectedTemplateIds by remember(macro, selectedActions) { mutableStateOf(selectedActions.map { it.templateId }.toSet()) }
    var showTags by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (macro == null) R.string.quick_event_new_macro else R.string.quick_event_edit_macro)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, enabled = !readOnly, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.quick_event_title)) })
                OutlinedTextField(value = sortOrderText, onValueChange = { sortOrderText = it.filter { ch -> ch == '-' || ch.isDigit() } }, enabled = !readOnly, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.quick_event_sort_order)) })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.quick_event_archived))
                    Switch(checked = archived, onCheckedChange = { archived = it }, enabled = !readOnly)
                }
                OutlinedButton(onClick = { showTags = true }, enabled = !readOnly) { Text(stringResource(R.string.quick_event_selected_tags_count, tagIds.size)) }
                SectionTitle(stringResource(R.string.quick_event_macro_actions))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    templates.forEach { template ->
                        FilterChip(
                            selected = template.id in selectedTemplateIds,
                            onClick = { selectedTemplateIds = if (template.id in selectedTemplateIds) selectedTemplateIds - template.id else selectedTemplateIds + template.id },
                            label = { Text(template.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            enabled = !readOnly
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = !readOnly && title.trim().isNotBlank() && selectedTemplateIds.isNotEmpty(), onClick = {
                val actions = selectedTemplateIds.toList().mapIndexed { index, templateId -> QuickEventMacroAction(macroId = macro?.id ?: 0L, templateId = templateId, displayOrder = index) }
                onSave(title.trim(), tagIds, sortOrderText.toIntOrNull() ?: 0, archived, actions)
            }) { Text(stringResource(R.string.salva)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onDelete != null) TextButton(onClick = onDelete, enabled = !readOnly) { Text(stringResource(R.string.elimina)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.annulla)) }
            }
        }
    )
    if (showTags) {
        QuickEventTagPickerDialog(tags, tagIds, tagLastUsedMsByTagId, onAddTag, onToggle = { tag -> tagIds = if (tag.id in tagIds) tagIds - tag.id else tagIds + tag.id }, onDismiss = { showTags = false })
    }
}

@Composable
private fun QuickEventMacroRunDialog(
    macro: QuickEventMacro,
    actions: List<QuickEventMacroAction>,
    templatesById: Map<Long, QuickEventTemplate>,
    fieldsByTemplate: Map<Long, List<QuickEventFieldDefinition>>,
    onDismiss: () -> Unit,
    onConfirm: (Long, List<MacroEntrySave>) -> Unit
) {
    var timestampMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var showTimestampPicker by remember { mutableStateOf(false) }
    val valueText = remember(macro, actions, fieldsByTemplate) {
        mutableStateMapOf<String, String>().apply {
            actions.forEach { action ->
                fieldsByTemplate[action.templateId].orEmpty().forEach { field ->
                    put("${action.templateId}:${field.id}", field.defaultValue)
                }
            }
        }
    }
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val formatter = remember { DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm", Locale.getDefault()) }
    val orderedFieldKeys = remember(actions, fieldsByTemplate) {
        actions.flatMap { action ->
            fieldsByTemplate[action.templateId].orEmpty()
                .filter { it.deletedAtMs == null && it.isKeyboardField() }
                .sortedBy { it.displayOrder }
                .map { field -> "${action.templateId}:${field.id}" to field }
        }
    }
    val fieldFocusRequesters = remember(orderedFieldKeys) {
        orderedFieldKeys.associate { it.first to FocusRequester() }
    }
    val autoFocusFieldKey = remember(orderedFieldKeys) {
        orderedFieldKeys.firstOrNull { (_, field) ->
            field.required && field.defaultValue.isBlank()
        }?.first ?: orderedFieldKeys.firstOrNull { (_, field) ->
            field.defaultValue.isBlank()
        }?.first
    }
    LaunchedEffect(autoFocusFieldKey) {
        val requester = autoFocusFieldKey?.let { fieldFocusRequesters[it] } ?: return@LaunchedEffect
        withFrameNanos { }
        runCatching { requester.requestFocus() }
        withFrameNanos { }
        keyboardController?.show()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.quick_event_run_macro, macro.title)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { showTimestampPicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.quick_event_timestamp_value, formatter.format(Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault()))))
                }
                actions.forEach { action ->
                    val template = templatesById[action.templateId] ?: return@forEach
                    Text(template.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    fieldsByTemplate[action.templateId].orEmpty().sortedBy { it.displayOrder }.forEach { field ->
                        QuickEventFieldValueEditor(
                            field = field,
                            value = valueText["${action.templateId}:${field.id}"].orEmpty(),
                            onValueChange = { valueText["${action.templateId}:${field.id}"] = it },
                            readOnly = false,
                            focusRequester = fieldFocusRequesters["${action.templateId}:${field.id}"]
                        )
                    }
                }
            }
        },
        confirmButton = {
            val requiredOk = actions.all { action ->
                fieldsByTemplate[action.templateId].orEmpty().none { field ->
                    field.required && valueText["${action.templateId}:${field.id}"].orEmpty().isBlank()
                }
            }
            Button(
                enabled = requiredOk,
                onClick = {
                    val entries = actions.mapNotNull { action ->
                        val template = templatesById[action.templateId] ?: return@mapNotNull null
                        val fields = fieldsByTemplate[action.templateId].orEmpty().sortedBy { it.displayOrder }
                        MacroEntrySave(
                            templateId = template.id,
                            title = template.title,
                            tagIds = template.tagIds + macro.tagIds,
                            fieldValues = fields.mapIndexed { index, field ->
                                QuickEventFieldValue(
                                    id = 0L,
                                    entryId = 0L,
                                    fieldId = field.id,
                                    label = field.label,
                                    type = field.type,
                                    value = valueText["${action.templateId}:${field.id}"].orEmpty(),
                                    displayOrder = field.displayOrder.takeIf { it != 0 } ?: index
                                )
                            }
                        )
                    }
                    onConfirm(timestampMs, entries)
                }
            ) { Text(stringResource(R.string.quick_event_record)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.annulla)) } }
    )
    if (showTimestampPicker) {
        MttDateTimePickerDialog(
            title = stringResource(R.string.history_timestamp_edit),
            initialTimestampMs = timestampMs,
            onDismiss = { showTimestampPicker = false },
            onConfirm = { picked ->
                timestampMs = picked
                showTimestampPicker = false
            }
        )
    }
}

@Composable
private fun QuickEventTagPickerDialog(
    tags: List<Tag>,
    selectedIds: Set<Long>,
    lastUsedMsByTagId: Map<Long, Long>,
    onAddTag: (String) -> Unit,
    onToggle: (Tag) -> Unit,
    onDismiss: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val cleanQuery = query.trim()
    val orderedTags = remember(tags, selectedIds, lastUsedMsByTagId, cleanQuery) {
        TagSelectionOrder.sortForPicker(
            tags = tags.filter { tag -> cleanQuery.isBlank() || tag.name.contains(cleanQuery, ignoreCase = true) },
            selectedIds = selectedIds,
            lastUsedMsByTagId = lastUsedMsByTagId
        )
    }
    val canAdd = cleanQuery.isNotBlank() && tags.none { it.name.equals(cleanQuery, ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.quick_event_tags)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.cerca_tag)) })
                if (canAdd) OutlinedButton(onClick = { onAddTag(cleanQuery) }) { Text(stringResource(R.string.add_tag_with_query, cleanQuery)) }
                if (orderedTags.isEmpty()) Text(stringResource(R.string.nessun_tag)) else TagSelectionFlow(tags = orderedTags, selectedIds = selectedIds, onToggle = onToggle, emphasizeTimedDuration = true)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } }
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}
