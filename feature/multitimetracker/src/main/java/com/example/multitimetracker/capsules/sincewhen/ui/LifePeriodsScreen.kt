@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)

package com.example.multitimetracker.capsules.sincewhen.ui

import android.content.res.Resources
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.multitimetracker.R
import com.example.multitimetracker.model.DEFAULT_LIFE_PERIOD_COLOR_ARGB
import com.example.multitimetracker.model.LifePeriod
import com.example.multitimetracker.model.LifePeriodDurationMode
import com.example.multitimetracker.model.LifePeriodDisplayUnit
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.capsules.sincewhen.state.SinceWhenUiState
import com.example.multitimetracker.persistence.UiPrefsStore
import com.example.multitimetracker.ui.components.MttDateTimePickerDialog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private data class PeriodColorOption(
    val labelRes: Int,
    val colorArgb: Long
)

private enum class LifePeriodStatus {
    ACTIVE,
    SCHEDULED,
    ENDED
}

private enum class LifePeriodPickTarget {
    START,
    END
}

private val PeriodColorOptions = listOf(
    PeriodColorOption(labelRes = R.string.life_period_color_teal, colorArgb = DEFAULT_LIFE_PERIOD_COLOR_ARGB),
    PeriodColorOption(labelRes = R.string.life_period_color_blue, colorArgb = 0xFF2B5DAAL),
    PeriodColorOption(labelRes = R.string.life_period_color_amber, colorArgb = 0xFFB86E00L),
    PeriodColorOption(labelRes = R.string.life_period_color_rose, colorArgb = 0xFF9B3D5CL),
    PeriodColorOption(labelRes = R.string.life_period_color_olive, colorArgb = 0xFF5E7F28L),
    PeriodColorOption(labelRes = R.string.life_period_color_slate, colorArgb = 0xFF5C6470L)
)

@Composable
fun LifePeriodsScreen(
    modifier: Modifier = Modifier,
    state: SinceWhenUiState,
    onAddPeriod: (String, String, Long, Long?, Long, Set<Long>, Set<LifePeriodDisplayUnit>) -> Unit,
    onUpdatePeriod: (Long, String, String, Long, Long?, Long, Set<Long>, Set<LifePeriodDisplayUnit>) -> Unit,
    onDeletePeriod: (Long) -> Unit,
    onEndSelectedNow: (Set<Long>, Long) -> Boolean,
) {
    val context = LocalContext.current
    var editingPeriod by remember { mutableStateOf<LifePeriod?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var durationModeMenuExpanded by remember { mutableStateOf(false) }
    var durationMode by rememberSaveable { mutableStateOf(UiPrefsStore.getLifePeriodDurationMode(context)) }
    var selectedActiveIds by rememberSaveable { mutableStateOf(emptySet<Long>()) }
    val referenceNowMs = remember(state.nowMs, state.timeMachineTargetMs) {
        state.timeMachineTargetMs ?: state.nowMs
    }

    val visibleTags = remember(state.tags) {
        state.tags
            .filter { !it.isDeleted && !it.isArchived }
            .sortedBy { it.name.lowercase(Locale.getDefault()) }
    }
    val visibleTagsById = remember(visibleTags) { visibleTags.associateBy { it.id } }
    val orderedPeriods = remember(state.lifePeriods, referenceNowMs) {
        state.lifePeriods.sortedWith(
            compareBy<LifePeriod> { statusForPeriod(it, referenceNowMs).ordinal }
                .thenByDescending { it.startMs }
                .thenBy { it.title.lowercase(Locale.getDefault()) }
        )
    }
    selectedActiveIds = selectedActiveIds.filterTo(mutableSetOf()) { id ->
        state.lifePeriods.any { it.id == id && statusForPeriod(it, referenceNowMs) == LifePeriodStatus.ACTIVE }
    }

    LifePeriodsScreenScaffold(
        title = stringResource(R.string.tab_da_quando),
        modifier = modifier,
        actions = {
            IconButton(onClick = { durationModeMenuExpanded = true }) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.life_period_duration_mode_cd)
                )
            }
            DropdownMenu(
                expanded = durationModeMenuExpanded,
                onDismissRequest = { durationModeMenuExpanded = false }
            ) {
                LifePeriodDurationMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = buildString {
                                    if (mode == durationMode) append("✓ ")
                                    append(
                                        when (mode) {
                                            LifePeriodDurationMode.EXACT_DURATION -> stringResource(R.string.life_period_duration_mode_exact)
                                            LifePeriodDurationMode.CALENDAR_DAYS -> stringResource(R.string.life_period_duration_mode_calendar_days)
                                        }
                                    )
                                }
                            )
                        },
                        onClick = {
                            durationMode = mode
                            durationModeMenuExpanded = false
                            UiPrefsStore.setLifePeriodDurationMode(context, mode)
                        }
                    )
                }
            }
            LifePeriodsScreenHelpAction(
                title = stringResource(R.string.life_period_intro_title),
                body = stringResource(R.string.life_period_intro_body)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add))
            }
        }
    ) { inner ->
        if (showCreateDialog) {
            SinceWhenLifePeriodEditorDialog(
                title = stringResource(R.string.life_period_add_title),
                initial = null,
                initialNowMs = referenceNowMs,
                availableTags = visibleTags,
                onDismiss = { showCreateDialog = false },
                onConfirm = { title, description, startMs, endMs, colorArgb, tagIds, displayUnits ->
                    onAddPeriod(title, description, startMs, endMs, colorArgb, tagIds, displayUnits)
                    showCreateDialog = false
                }
            )
        }

        editingPeriod?.let { period ->
            SinceWhenLifePeriodEditorDialog(
                title = stringResource(R.string.life_period_edit_title),
                initial = period,
                initialNowMs = referenceNowMs,
                availableTags = visibleTags,
                onDismiss = { editingPeriod = null },
                onConfirm = { title, description, startMs, endMs, colorArgb, tagIds, displayUnits ->
                    onUpdatePeriod(period.id, title, description, startMs, endMs, colorArgb, tagIds, displayUnits)
                    editingPeriod = null
                }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
        ) {
            if (orderedPeriods.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
                ) {
                    LifePeriodsEmptyStateCard(
                        title = stringResource(R.string.life_period_empty_title)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (selectedActiveIds.isNotEmpty()) {
                        item {
                            Button(
                                onClick = {
                                    val ended = onEndSelectedNow(selectedActiveIds, referenceNowMs)
                                    if (ended) selectedActiveIds = emptySet()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.life_period_end_selected_now))
                            }
                        }
                    }
                    items(orderedPeriods, key = { it.id }) { period ->
                        val active = statusForPeriod(period, referenceNowMs) == LifePeriodStatus.ACTIVE
                        LifePeriodCard(
                            period = period,
                            nowMs = referenceNowMs,
                            durationMode = durationMode,
                            tagsById = visibleTagsById,
                            selected = period.id in selectedActiveIds,
                            selectable = active,
                            onToggleSelected = {
                                selectedActiveIds = if (period.id in selectedActiveIds) {
                                    selectedActiveIds - period.id
                                } else {
                                    selectedActiveIds + period.id
                                }
                            },
                            onEdit = { editingPeriod = period },
                            onDelete = { onDeletePeriod(period.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LifePeriodCard(
    period: LifePeriod,
    nowMs: Long,
    durationMode: LifePeriodDurationMode,
    tagsById: Map<Long, Tag>,
    selected: Boolean,
    selectable: Boolean,
    onToggleSelected: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val resources = context.resources
    val locale = remember { Locale.getDefault() }
    val zoneId = remember { ZoneId.systemDefault() }
    val dateFormatter = remember(locale) { DateTimeFormatter.ofPattern("d MMM uuuu HH:mm:ss", locale) }
    val periodColor = Color(period.colorArgb)
    val status = statusForPeriod(period, nowMs)
    val tagNames = remember(period, tagsById) {
        period.tagIds.mapNotNull { tagsById[it]?.name }.sortedBy { it.lowercase(locale) }
    }

    key(period.id) {
        val dismissState = rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                when (value) {
                    SwipeToDismissBoxValue.StartToEnd -> {
                        onEdit()
                        false
                    }
                    SwipeToDismissBoxValue.EndToStart -> {
                        onDelete()
                        false
                    }
                    else -> false
                }
            }
        )

        SwipeToDismissBox(
            state = dismissState,
            backgroundContent = {
                val isEdit = dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd
                val isDelete = dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart
                val tint = when {
                    isDelete -> MaterialTheme.colorScheme.errorContainer
                    isEdit -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
                val icon = when {
                    isDelete -> Icons.Filled.Delete
                    else -> Icons.Filled.Edit
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    contentAlignment = if (isDelete) Alignment.CenterEnd else Alignment.CenterStart
                ) {
                    Surface(color = tint, shape = MaterialTheme.shapes.medium) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            },
            content = {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = onEdit, onLongClick = onDelete),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (selectable) {
                                Checkbox(
                                    checked = selected,
                                    onCheckedChange = { onToggleSelected() },
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f, fill = true),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = period.title.ifBlank { stringResource(R.string.senza_titolo) },
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text(
                                        text = formatDateRange(period, zoneId, dateFormatter),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (period.description.isNotBlank()) {
                                    Text(
                                        text = period.description,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (tagNames.isNotEmpty()) {
                                    TagPills(tagNames = tagNames)
                                }
                            }
                            Column(
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                StatusBadge(status = status)
                                Surface(
                                    color = periodColor.copy(alpha = 0.12f),
                                    contentColor = periodColor,
                                    shape = MaterialTheme.shapes.medium
                                ) {
                                    Text(
                                        text = formatLifePeriodDuration(resources, period, nowMs, zoneId, durationMode),
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        textAlign = TextAlign.End
                                    )
                                }
                            }
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun StatusBadge(status: LifePeriodStatus) {
    val text = when (status) {
        LifePeriodStatus.ACTIVE -> stringResource(R.string.life_period_status_active)
        LifePeriodStatus.SCHEDULED -> stringResource(R.string.life_period_status_scheduled)
        LifePeriodStatus.ENDED -> stringResource(R.string.life_period_status_ended)
    }
    val color = when (status) {
        LifePeriodStatus.ACTIVE -> MaterialTheme.colorScheme.primaryContainer
        LifePeriodStatus.SCHEDULED -> MaterialTheme.colorScheme.secondaryContainer
        LifePeriodStatus.ENDED -> MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TagPills(tagNames: List<String>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        tagNames.forEach { tagName ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = tagName,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun SinceWhenLifePeriodEditorDialog(
    title: String,
    initial: LifePeriod?,
    initialDraft: LifePeriod? = null,
    initialNowMs: Long,
    availableTags: List<Tag>,
    onDismiss: () -> Unit,
    onConfirm: (String, String, Long, Long?, Long, Set<Long>, Set<LifePeriodDisplayUnit>) -> Unit
) {
    val locale = remember { Locale.getDefault() }
    val zoneId = remember { ZoneId.systemDefault() }
    val dateFormatter = remember(locale) { DateTimeFormatter.ofPattern("d MMM uuuu HH:mm:ss", locale) }
    val initialValues = initial ?: initialDraft

    var periodTitle by remember(initialValues) { mutableStateOf(initialValues?.title ?: "") }
    var description by remember(initialValues) { mutableStateOf(initialValues?.description ?: "") }
    var startMs by remember(initialValues) { mutableStateOf(initialValues?.startMs ?: initialNowMs) }
    var hasEnd by remember(initialValues) { mutableStateOf(initialValues?.endMs != null) }
    var endMs by remember(initialValues) { mutableStateOf(initialValues?.endMs ?: initialNowMs) }
    var selectedColor by remember(initialValues) { mutableStateOf(initialValues?.colorArgb ?: DEFAULT_LIFE_PERIOD_COLOR_ARGB) }
    var selectedTagIds by remember(initialValues) { mutableStateOf(initialValues?.tagIds ?: emptySet()) }
    var selectedUnits by remember(initialValues) {
        mutableStateOf(initialValues?.displayUnits?.ifEmpty { setOf(LifePeriodDisplayUnit.DAYS) } ?: setOf(LifePeriodDisplayUnit.DAYS))
    }
    var isSaving by remember(initialValues) { mutableStateOf(false) }
    var pickTarget by remember(initialValues) { mutableStateOf<LifePeriodPickTarget?>(null) }

    val isValid = periodTitle.trim().isNotEmpty() && selectedUnits.isNotEmpty() && (!hasEnd || endMs > startMs)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = {
            TextButton(
                enabled = isValid && !isSaving,
                onClick = {
                    if (isSaving) return@TextButton
                    isSaving = true
                    onConfirm(
                        periodTitle.trim(),
                        description.trim(),
                        startMs,
                        if (hasEnd) endMs else null,
                        selectedColor,
                        selectedTagIds,
                        selectedUnits
                    )
                }
            ) {
                Text(if (initial == null) stringResource(R.string.crea) else stringResource(R.string.salva))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.annulla))
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = periodTitle,
                    onValueChange = { periodTitle = it },
                    label = { Text(stringResource(R.string.nome)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.life_period_description)) },
                    modifier = Modifier.fillMaxWidth()
                )

                DateTimePickerRow(
                    label = stringResource(R.string.inizio),
                    value = formatDateTime(startMs, zoneId, dateFormatter),
                    onPick = { pickTarget = LifePeriodPickTarget.START }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = hasEnd,
                        onCheckedChange = { checked -> hasEnd = checked }
                    )
                    Text(stringResource(R.string.life_period_has_end), style = MaterialTheme.typography.bodyMedium)
                }

                if (hasEnd) {
                    DateTimePickerRow(
                        label = stringResource(R.string.fine),
                        value = formatDateTime(endMs, zoneId, dateFormatter),
                        onPick = { pickTarget = LifePeriodPickTarget.END }
                    )
                    if (endMs <= startMs) {
                        Text(
                            text = stringResource(R.string.la_fine_deve_essere_dopo_l_inizio),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                Text(stringResource(R.string.life_period_color), style = MaterialTheme.typography.labelLarge)
                ColorPickerRow(selectedColor = selectedColor, onSelect = { selectedColor = it })

                Text(stringResource(R.string.life_period_units), style = MaterialTheme.typography.labelLarge)
                UnitPickerRow(selectedUnits = selectedUnits, onToggle = { unit ->
                    selectedUnits = if (unit in selectedUnits) selectedUnits - unit else selectedUnits + unit
                })

                Text(stringResource(R.string.tags), style = MaterialTheme.typography.labelLarge)
                if (availableTags.isEmpty()) {
                    Text(
                        text = stringResource(R.string.nessun_tag_disponibile),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    TagPickerRow(
                        tags = availableTags,
                        selectedTagIds = selectedTagIds,
                        onToggle = { tagId ->
                            selectedTagIds = if (tagId in selectedTagIds) selectedTagIds - tagId else selectedTagIds + tagId
                        }
                    )
                }
            }
        }
    )

    pickTarget?.let { target ->
        MttDateTimePickerDialog(
            title = stringResource(
                when (target) {
                    LifePeriodPickTarget.START -> R.string.inizio
                    LifePeriodPickTarget.END -> R.string.fine
                }
            ),
            initialTimestampMs = when (target) {
                LifePeriodPickTarget.START -> startMs
                LifePeriodPickTarget.END -> endMs
            },
            onDismiss = { pickTarget = null },
            onConfirm = { picked ->
                when (target) {
                    LifePeriodPickTarget.START -> startMs = picked
                    LifePeriodPickTarget.END -> endMs = picked
                }
                pickTarget = null
            }
        )
    }
}

@Composable
private fun DateTimePickerRow(
    label: String,
    value: String,
    onPick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.labelLarge)
            Text(text = value, style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = onPick) {
            Text(stringResource(R.string.edit))
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ColorPickerRow(
    selectedColor: Long,
    onSelect: (Long) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        PeriodColorOptions.forEach { option ->
            FilterChip(
                selected = selectedColor == option.colorArgb,
                onClick = { onSelect(option.colorArgb) },
                label = { Text(stringResource(option.labelRes)) },
                leadingIcon = {
                    Surface(
                        color = Color(option.colorArgb),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Box(modifier = Modifier.size(14.dp))
                    }
                }
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun UnitPickerRow(
    selectedUnits: Set<LifePeriodDisplayUnit>,
    onToggle: (LifePeriodDisplayUnit) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        LifePeriodDisplayUnit.displayOrder.forEach { unit ->
            FilterChip(
                selected = unit in selectedUnits,
                onClick = { onToggle(unit) },
                label = { Text(unitLabel(unit)) }
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TagPickerRow(
    tags: List<Tag>,
    selectedTagIds: Set<Long>,
    onToggle: (Long) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val chipColor = MaterialTheme.colorScheme.secondaryContainer
        tags.forEach { tag ->
            FilterChip(
                selected = tag.id in selectedTagIds,
                onClick = { onToggle(tag.id) },
                label = { Text(tag.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = chipColor,
                    containerColor = chipColor.copy(alpha = 0.65f)
                )
            )
        }
    }
}

private fun statusForPeriod(period: LifePeriod, nowMs: Long): LifePeriodStatus {
    return when {
        nowMs < period.startMs -> LifePeriodStatus.SCHEDULED
        period.endMs != null && nowMs > period.endMs -> LifePeriodStatus.ENDED
        else -> LifePeriodStatus.ACTIVE
    }
}

private fun formatDateRange(
    period: LifePeriod,
    zoneId: ZoneId,
    formatter: DateTimeFormatter
): String {
    val start = formatDateTime(period.startMs, zoneId, formatter)
    val end = period.endMs?.let { formatDateTime(it, zoneId, formatter) }
    return if (end == null) start else "$start -> $end"
}

private fun formatDateTime(
    timestampMs: Long,
    zoneId: ZoneId,
    formatter: DateTimeFormatter
): String = formatter.format(Instant.ofEpochMilli(timestampMs).atZone(zoneId))

private fun formatLifePeriodDuration(
    resources: Resources,
    period: LifePeriod,
    nowMs: Long,
    zoneId: ZoneId,
    mode: LifePeriodDurationMode
): String {
    val status = statusForPeriod(period, nowMs)
    if (status == LifePeriodStatus.SCHEDULED) {
        return resources.getString(R.string.life_period_not_started)
    }

    val endReferenceMs = when {
        period.endMs != null && nowMs > period.endMs -> period.endMs
        else -> nowMs
    } ?: nowMs

    if (mode == LifePeriodDurationMode.CALENDAR_DAYS) {
        val calendarDays = lifePeriodCalendarDaysBetween(
            startMs = period.startMs,
            endReferenceMs = endReferenceMs,
            zoneId = zoneId
        )
        return quantityLabel(resources, LifePeriodDisplayUnit.DAYS, calendarDays)
    }

    val startDateTime = Instant.ofEpochMilli(period.startMs).atZone(zoneId).toLocalDateTime()
    val endDateTime = Instant.ofEpochMilli(endReferenceMs).atZone(zoneId).toLocalDateTime()
    val orderedUnits = LifePeriodDisplayUnit.displayOrder.filter { it in period.displayUnits }.ifEmpty {
        listOf(LifePeriodDisplayUnit.DAYS)
    }

    var cursor = startDateTime
    val parts = mutableListOf<String>()
    orderedUnits.forEachIndexed { index, unit ->
        val amount = when (unit) {
            LifePeriodDisplayUnit.YEARS -> ChronoUnit.YEARS.between(cursor, endDateTime)
            LifePeriodDisplayUnit.MONTHS -> ChronoUnit.MONTHS.between(cursor, endDateTime)
            LifePeriodDisplayUnit.WEEKS -> ChronoUnit.WEEKS.between(cursor, endDateTime)
            LifePeriodDisplayUnit.DAYS -> ChronoUnit.DAYS.between(cursor, endDateTime)
            LifePeriodDisplayUnit.HOURS -> ChronoUnit.HOURS.between(cursor, endDateTime)
            LifePeriodDisplayUnit.MINUTES -> ChronoUnit.MINUTES.between(cursor, endDateTime)
            LifePeriodDisplayUnit.SECONDS -> ChronoUnit.SECONDS.between(cursor, endDateTime)
        }.coerceAtLeast(0L)

        if (amount > 0L || (parts.isEmpty() && index == orderedUnits.lastIndex)) {
            parts += quantityLabel(resources, unit, amount)
        }

        cursor = when (unit) {
            LifePeriodDisplayUnit.YEARS -> cursor.plusYears(amount)
            LifePeriodDisplayUnit.MONTHS -> cursor.plusMonths(amount)
            LifePeriodDisplayUnit.WEEKS -> cursor.plusWeeks(amount)
            LifePeriodDisplayUnit.DAYS -> cursor.plusDays(amount)
            LifePeriodDisplayUnit.HOURS -> cursor.plusHours(amount)
            LifePeriodDisplayUnit.MINUTES -> cursor.plusMinutes(amount)
            LifePeriodDisplayUnit.SECONDS -> cursor.plusSeconds(amount)
        }
    }

    return parts.joinToString(" ")
}

internal fun lifePeriodCalendarDaysBetween(
    startMs: Long,
    endReferenceMs: Long,
    zoneId: ZoneId
): Long {
    val startDate = Instant.ofEpochMilli(startMs).atZone(zoneId).toLocalDate()
    val endDate = Instant.ofEpochMilli(endReferenceMs).atZone(zoneId).toLocalDate()
    return ChronoUnit.DAYS.between(startDate, endDate).coerceAtLeast(0L)
}

private fun quantityLabel(
    resources: Resources,
    unit: LifePeriodDisplayUnit,
    value: Long
): String {
    val quantity = value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val pluralRes = when (unit) {
        LifePeriodDisplayUnit.YEARS -> R.plurals.life_period_years
        LifePeriodDisplayUnit.MONTHS -> R.plurals.life_period_months
        LifePeriodDisplayUnit.WEEKS -> R.plurals.life_period_weeks
        LifePeriodDisplayUnit.DAYS -> R.plurals.life_period_days
        LifePeriodDisplayUnit.HOURS -> R.plurals.life_period_hours
        LifePeriodDisplayUnit.MINUTES -> R.plurals.life_period_minutes
        LifePeriodDisplayUnit.SECONDS -> R.plurals.life_period_seconds
    }
    return when (unit) {
        LifePeriodDisplayUnit.DAYS -> resources.getString(R.string.life_period_days_short, value)
        LifePeriodDisplayUnit.HOURS -> resources.getString(R.string.life_period_hours_short, value)
        LifePeriodDisplayUnit.MINUTES -> resources.getString(R.string.life_period_minutes_short, value)
        LifePeriodDisplayUnit.SECONDS -> resources.getString(R.string.life_period_seconds_short, value)
        else -> resources.getQuantityString(pluralRes, quantity, value)
    }
}

@Composable
private fun unitLabel(unit: LifePeriodDisplayUnit): String {
    return when (unit) {
        LifePeriodDisplayUnit.YEARS -> stringResource(R.string.life_period_unit_years)
        LifePeriodDisplayUnit.MONTHS -> stringResource(R.string.life_period_unit_months)
        LifePeriodDisplayUnit.WEEKS -> stringResource(R.string.life_period_unit_weeks)
        LifePeriodDisplayUnit.DAYS -> stringResource(R.string.life_period_unit_days)
        LifePeriodDisplayUnit.HOURS -> stringResource(R.string.life_period_unit_hours)
        LifePeriodDisplayUnit.MINUTES -> stringResource(R.string.life_period_unit_minutes)
        LifePeriodDisplayUnit.SECONDS -> stringResource(R.string.life_period_unit_seconds)
    }
}
