// v352
@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.example.multitimetracker.ui.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.multitimetracker.R
import com.example.multitimetracker.isTimedTag
import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.timedTagDisplayLabel
import com.example.multitimetracker.ui.util.TagHierarchy
import com.example.multitimetracker.ui.util.TagSelectionOrder
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.core.hubcontext.HubContextLinks

private enum class SessionTimePickTarget {
    START,
    END
}

/**
 * Session-only editor dialog.
 *
 * Why this exists:
 * - Starting from the "session-only write path" migration, editing/deleting must target `sessions`
 *   (and `session_tags`) directly — NOT the legacy Task engine.
 * - The same dialog is reused from multiple screens (e.g., Chronology and Now) to prevent UI drift.
 *
 * Invariants:
 * - `endMs == null` means the session is running.
 * - Deleting here should delete the session row (soft delete) via the provided callback; callers that
 *   also keep a legacy "running task" mirror should stop that task first.
 */
@Composable
fun SessionEditDialog(
    session: SessionUi,
    isNewSession: Boolean,
    tags: List<Tag>,
    tagLastUsedMsByTagId: Map<Long, Long>,
    tagParentsByChild: Map<Long, Set<Long>>,
    showSeconds: Boolean,
    referenceNowMs: Long = session.endMs ?: session.startMs,
    readOnly: Boolean = false,
    onAddTag: (String) -> Unit,
    onSaveMeta: (Long, String, Set<Long>) -> Unit,
    onCreateNewSession: (String, Long, Set<Long>, (SessionUi) -> Unit) -> Unit = { _, _, _, _ -> },
    onSaveTimes: (Long, Long, Long?) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val zoneId = remember { ZoneId.systemDefault() }
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val dateTimeFmt = remember(showSeconds) {
        DateTimeFormatter.ofPattern(if (showSeconds) "d MMM uuuu HH:mm:ss" else "d MMM uuuu HH:mm")
    }

    fun dismissKeyboard() {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }
    var isSavingMeta by remember { mutableStateOf(false) }
    var isSavingTimes by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    val saveScope = rememberCoroutineScope()
    var name by remember { mutableStateOf(session.title) }

    // Tag selection: same closure/exclusions model as tasks
    var selectedManual by remember { mutableStateOf(session.tagIds) }
    val initialClosure = remember(session.tagIds, tagParentsByChild) { TagHierarchy.closure(session.tagIds, tagParentsByChild) }
    var excludedAuto by remember { mutableStateOf(initialClosure - session.tagIds) }

    val closureSelected = remember(selectedManual, tagParentsByChild) {
        TagHierarchy.closure(selectedManual, tagParentsByChild)
    }
    val selectedEffective = remember(closureSelected, excludedAuto) { closureSelected - excludedAuto }

    var tagQuery by remember { mutableStateOf("") }
    var showTagPicker by remember { mutableStateOf(false) }
    var pendingSelectTagName by remember { mutableStateOf<String?>(null) }

    val pendingName = pendingSelectTagName
    if (pendingName != null) {
        val newlyAdded = tags.firstOrNull { it.name.equals(pendingName, ignoreCase = true) }
        if (newlyAdded != null && !selectedManual.contains(newlyAdded.id)) {
            selectedManual = selectedManual + newlyAdded.id
            excludedAuto = excludedAuto - newlyAdded.id
            pendingSelectTagName = null
        }
    }

    var showTimesDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    fun timedTagsCount(selectedIds: Set<Long>): Int = tags.count { it.id in selectedIds && it.isTimedTag() }
    fun toggleTagSelection(tag: Tag) {
        if (readOnly) return
        val id = tag.id
        val isSelected = selectedEffective.contains(id)
        if (!isSelected) {
            if (tag.isTimedTag() && timedTagsCount(selectedEffective - id) > 0) {
                Toast.makeText(context, context.getString(R.string.timed_tag_single_per_session), Toast.LENGTH_SHORT).show()
                return
            }
            val withParents = TagHierarchy.closure(setOf(id), tagParentsByChild)
            selectedManual = selectedManual + withParents
            excludedAuto = excludedAuto - withParents
            tagQuery = ""
        } else {
            selectedManual = selectedManual - id
            if (closureSelected.contains(id) && !session.tagIds.contains(id)) {
                excludedAuto = excludedAuto + id
            }
        }
    }

    fun addTagFromQuery(query: String) {
        if (readOnly) return
        val nameToAdd = query.trim()
        if (nameToAdd.isNotEmpty()) {
            pendingSelectTagName = nameToAdd
            onAddTag(nameToAdd)
            tagQuery = ""
        }
    }

    val utcZFmt = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC) }
    fun utcZ(ts: Long): String = utcZFmt.format(Instant.ofEpochMilli(ts))
    fun localDateTime(ts: Long): String = dateTimeFmt.format(Instant.ofEpochMilli(ts).atZone(zoneId))

    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = {
            // v352: if this dialog is for a freshly-created session (from the "+" action),
            // cancel must not leave behind a phantom session.
            if (isNewSession && session.id > 0L) onDelete(session.id)
            onDismiss()
        },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = if (isNewSession) stringResource(R.string.new_session) else stringResource(R.string.modifica_sessione),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    IconButton(
                        onClick = {
                            dismissKeyboard()
                            if (isNewSession && session.id > 0L) onDelete(session.id)
                            onDismiss()
                        }
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.annulla))
                    }
                    IconButton(
                        onClick = {
                            if (readOnly) return@IconButton
                            if (isSavingMeta) return@IconButton
                            if (timedTagsCount(selectedEffective) > 1) {
                                Toast.makeText(context, context.getString(R.string.timed_tag_single_per_session), Toast.LENGTH_SHORT).show()
                                return@IconButton
                            }
                            isSavingMeta = true
                            dismissKeyboard()
                            saveScope.launch {
                                if (isNewSession) {
                                    onCreateNewSession(name, session.startMs, selectedEffective) { onDismiss() }
                                } else {
                                    saveSessionMetadataOnly(session.id, name, selectedEffective, onSaveMeta)
                                    onDismiss()
                                }
                            }
                        },
                        enabled = !isSavingMeta && !readOnly
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.salva))
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.session_dialog_time_summary_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = buildString {
                                append(localDateTime(session.startMs))
                                if (session.endMs != null) {
                                    append("  ->  ")
                                    append(localDateTime(session.endMs))
                                } else {
                                    append("  ->  ")
                                    append(stringResource(R.string.running))
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 230.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.session_dialog_title_section),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.titolo_opzionale)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (!isNewSession) HubContextLinks(HubEntityRef("timer", "session", session.id.toString()))

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.session_dialog_timing_section),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                InlineHelpAction(
                                    title = stringResource(R.string.session_dialog_timing_section),
                                    body = stringResource(R.string.session_dialog_timing_help)
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    onClick = { showTimesDialog = true },
                                    modifier = Modifier.weight(1f),
                                    enabled = !readOnly
                                ) {
                                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit_times))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.edit_times))
                                }

                                OutlinedButton(
                                    onClick = {
                                        val end = session.endMs
                                        if (end != null) {
                                            clipboard.setText(AnnotatedString("${utcZ(session.startMs)} - ${utcZ(end)}"))
                                        }
                                    },
                                    enabled = session.endMs != null
                                ) {
                                    Text(stringResource(R.string.timezone_utc_z))
                                }
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = true),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.session_dialog_tags_section),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            InlineHelpAction(
                                title = stringResource(R.string.session_dialog_tags_section),
                                body = stringResource(R.string.session_dialog_tags_help, selectedEffective.size)
                            )
                        }
                        Text(
                            text = stringResource(R.string.session_dialog_tags_help, selectedEffective.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = tagQuery,
                            onValueChange = {},
                            label = { Text(stringResource(R.string.cerca_tag)) },
                            singleLine = true,
                            readOnly = true,
                            trailingIcon = {
                                if (tagQuery.isNotBlank()) {
                                    IconButton(onClick = { tagQuery = "" }) {
                                        Icon(Icons.Filled.Close, contentDescription = null)
                                    }
                                }
                            },
                            enabled = !readOnly,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clickable(enabled = !readOnly) { showTagPicker = true }
                        )
                    }

                    val selectedTags = SessionTagPickerRules.selectedTags(tags, selectedEffective)
                    if (selectedTags.isEmpty()) {
                        Text(stringResource(R.string.nessun_tag))
                    } else {
                        TagSelectionFlow(
                            tags = selectedTags,
                            selectedIds = selectedEffective,
                            enabled = !readOnly,
                            emphasizeTimedDuration = true,
                            onToggle = ::toggleTagSelection,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                if (!isNewSession) {
                    TextButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !readOnly
                    ) {
                        Text(stringResource(R.string.elimina))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {}
    )

    if (showTagPicker) {
        SessionTagPickerDialog(
            tags = tags,
            selectedIds = selectedEffective,
            query = tagQuery,
            lastUsedMsByTagId = tagLastUsedMsByTagId,
            enabled = !readOnly,
            onQueryChange = { tagQuery = it },
            onAddQuery = ::addTagFromQuery,
            onToggle = ::toggleTagSelection,
            onDismiss = { showTagPicker = false }
        )
    }

    if (showTimesDialog) {
        val nowMs = referenceNowMs
        var startMs by remember { mutableStateOf(session.startMs) }
        var endMs by remember { mutableStateOf(session.endMs ?: session.startMs) }
        var timePickTarget by remember { mutableStateOf<SessionTimePickTarget?>(null) }

        AlertDialog(
            onDismissRequest = { showTimesDialog = false },
            title = { Text(stringResource(R.string.edit_times)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.inizio_2, Instant.ofEpochMilli(startMs).atZone(zoneId).toLocalTime().toString()))
                    Button(
                        onClick = { timePickTarget = SessionTimePickTarget.START },
                        enabled = !readOnly
                    ) { Text(stringResource(R.string.cambia_inizio)) }

                    if (session.endMs != null) {
                        Text(stringResource(R.string.fine_2, Instant.ofEpochMilli(endMs).atZone(zoneId).toLocalTime().toString()))
                        Button(
                            onClick = { timePickTarget = SessionTimePickTarget.END },
                            enabled = !readOnly
                        ) { Text(stringResource(R.string.cambia_fine)) }
                    } else {
                        Text(stringResource(R.string.running))
                    }

                    if (session.endMs != null && endMs < startMs) {
                        Text(stringResource(R.string.la_fine_deve_essere_dopo_l_inizio), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (readOnly) return@Button
                        if (session.endMs == null) {
                            if (startMs <= nowMs) {
                                onSaveTimes(session.id, startMs, null)
                                showTimesDialog = false
                            } else {
                                Toast.makeText(context, context.getString(R.string.start_time_cannot_be_future), Toast.LENGTH_SHORT).show()
                            }
                        } else if (endMs >= startMs) {
                            onSaveTimes(session.id, startMs, endMs)
                            showTimesDialog = false
                        }
                    }
                , enabled = !isSavingTimes && !readOnly
                ) {
                    Text(stringResource(R.string.salva))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { if (!isSavingTimes) showTimesDialog = false },
                    enabled = !isSavingTimes
                ) { Text(stringResource(R.string.annulla)) }
            }
        )

        timePickTarget?.let { target ->
            MttDateTimePickerDialog(
                title = stringResource(
                    when (target) {
                        SessionTimePickTarget.START -> R.string.cambia_inizio
                        SessionTimePickTarget.END -> R.string.cambia_fine
                    }
                ),
                initialTimestampMs = when (target) {
                    SessionTimePickTarget.START -> startMs
                    SessionTimePickTarget.END -> endMs
                },
                onDismiss = { timePickTarget = null },
                onConfirm = { picked ->
                    when (target) {
                        SessionTimePickTarget.START -> startMs = picked
                        SessionTimePickTarget.END -> endMs = picked
                    }
                    timePickTarget = null
                }
            )
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.elimina_sessione)) },
            text = { Text(stringResource(R.string.delete_session_this)) },
            confirmButton = {
                Button(
                    onClick = {
                        if (readOnly) return@Button
                        if (isDeleting) return@Button
                        isDeleting = true
                        onDelete(session.id)
                        showDeleteConfirm = false
                    },
                    enabled = !isDeleting && !readOnly
                ) { Text(stringResource(R.string.elimina)) }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { if (!isDeleting) showDeleteConfirm = false },
                    enabled = !isDeleting
                ) { Text(stringResource(R.string.annulla)) }
            }
        )
    }
}

internal fun saveSessionMetadataOnly(
    sessionId: Long,
    title: String,
    tagIds: Set<Long>,
    onSaveMeta: (Long, String, Set<Long>) -> Unit,
) = onSaveMeta(sessionId, title, tagIds)

@Composable
private fun SessionTagPickerDialog(
    tags: List<Tag>,
    selectedIds: Set<Long>,
    query: String,
    lastUsedMsByTagId: Map<Long, Long>,
    enabled: Boolean,
    onQueryChange: (String) -> Unit,
    onAddQuery: (String) -> Unit,
    onToggle: (Tag) -> Unit,
    onDismiss: () -> Unit
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val q = SessionTagPickerRules.cleanQuery(query)
    val base = SessionTagPickerRules.filterByQuery(tags, q)
    val ordered = TagSelectionOrder.sortForPicker(
        tags = base,
        selectedIds = selectedIds,
        lastUsedMsByTagId = lastUsedMsByTagId
    )
    val showAddTagAction = SessionTagPickerRules.canCreateExactName(q, tags)

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.session_dialog_tags_section),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.annulla))
                    }
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    label = { Text(stringResource(R.string.cerca_tag)) },
                    singleLine = true,
                    enabled = enabled,
                    trailingIcon = {
                        if (query.isNotBlank()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(Icons.Filled.Close, contentDescription = null)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )

                if (showAddTagAction) {
                    TextButton(
                        onClick = { onAddQuery(q) },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.add_tag_with_query, q))
                    }
                }

                if (ordered.isEmpty() && !showAddTagAction) {
                    Text(stringResource(R.string.nessun_tag))
                } else {
                    CompleteTagRows(
                        tags = ordered,
                        selectedIds = selectedIds,
                        enabled = enabled,
                        onToggle = onToggle,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = true)
                    )
                }
            }
        }
    }
}

@Composable
private fun CompleteTagRows(
    tags: List<Tag>,
    selectedIds: Set<Long>,
    enabled: Boolean,
    onToggle: (Tag) -> Unit,
    modifier: Modifier = Modifier
) {
    val rowHeight = 64.dp
    BoxWithConstraints(modifier = modifier) {
        val visibleRows = (maxHeight.value / rowHeight.value).toInt().coerceAtLeast(1)
        val listHeight = rowHeight * visibleRows
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(listHeight)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            tags.chunked(2).forEach { rowTags ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowHeight),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    rowTags.forEach { tag ->
                        TagSelectionFlow(
                            tags = listOf(tag),
                            selectedIds = selectedIds,
                            enabled = enabled,
                            emphasizeTimedDuration = true,
                            onToggle = onToggle,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (rowTags.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
