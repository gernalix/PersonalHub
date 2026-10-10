package com.gernalix.personalhub.sincewhen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gernalix.personalhub.core.ui.R
import com.gernalix.personalhub.contracts.database.*
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.hubcontext.HubContextRuntime
import androidx.room.withTransaction
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Change only the editable snapshot fields; IDs, legacy tags and provenance remain untouched. */
internal suspend fun saveSinceWhenCounter(db: PersonalHubDatabase, value: SinceWhenCounterEntity, tagIds: Set<String>): Long {
    require(value.title.isNotBlank())
    val end = value.endTimestamp
    require(end == null || end > value.initialTimestamp)
    require(JSONArray(value.displayUnitsJson).length() > 0)
    return db.withTransaction {
        val dao = db.sinceWhenCounterDao()
        val id = if (value.id == 0L) dao.insert(value) else { dao.update(value); value.id }
        HubContextRuntime.tags().replace(HubEntityRef("since_when", "counter", requireNotNull(dao.get(id)).canonicalId), HubTagNamespaces.SINCE_WHEN, tagIds)
        id
    }
}

/** Material date values are UTC calendar days; stored values are instants shown in the local zone. */
internal fun sinceWhenPickedTimestamp(original: Long, dateMillis: Long, hour: Int, minute: Int, zone: ZoneId): Long {
    val previous = Instant.ofEpochMilli(original).atZone(zone)
    val date = Instant.ofEpochMilli(dateMillis).atZone(ZoneOffset.UTC).toLocalDate()
    val local = date.atTime(hour, minute, previous.second, previous.nano)
    return ZonedDateTime.ofLocal(local, zone, previous.offset).toInstant().toEpochMilli()
}

internal fun formatSinceWhenTimestamp(timestamp: Long): String = DateTimeFormatter
    .ofPattern("d MMM uuuu HH:mm:ss", Locale.getDefault())
    .format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SinceWhenEditorDialog(initial: SinceWhenCounterEntity?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val db = remember { PersonalHubDatabase.get(context) }
    val tags = remember { HubContextRuntime.tags() }
    val available by tags.observe(HubTagNamespaces.SINCE_WHEN).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var value by remember(initial) { mutableStateOf(initial ?: SinceWhenCounterEntity(title = "", initialTimestamp = System.currentTimeMillis(), createdAt = System.currentTimeMillis())) }
    var hasEnd by remember(initial) { mutableStateOf(initial?.endTimestamp != null) }
    var end by remember(initial) { mutableStateOf(initial?.endTimestamp ?: System.currentTimeMillis()) }
    var selectedTags by remember(initial) { mutableStateOf(emptySet<String>()) }
    var tagsReady by remember(initial) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var pickEnd by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(initial?.id) {
        selectedTags = initial?.let { tags.tags(HubEntityRef("since_when", "counter", it.canonicalId)).filter { t -> t.namespace == HubTagNamespaces.SINCE_WHEN || t.isGlobal }.map { t -> t.id }.toSet() }.orEmpty()
        tagsReady = true
    }
    val units = remember(value.displayUnitsJson) {
        val array = JSONArray(value.displayUnitsJson)
        (0 until array.length()).map { array.getString(it) }.toSet()
    }
    val valid = value.title.isNotBlank() && units.isNotEmpty() && (!hasEnd || end > value.initialTimestamp)
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(if (initial == null) R.string.since_when_editor_create else R.string.since_when_editor_edit)) },
        confirmButton = { TextButton(modifier = Modifier.testTag("sincewhen-save"), enabled = valid && tagsReady && !saving, onClick = {
            saving = true
            failed = false
            scope.launch {
                try {
                    saveSinceWhenCounter(db, value.copy(title = value.title.trim(), description = value.description.trim(), endTimestamp = if (hasEnd) end else null), selectedTags)
                    onSaved()
                } catch (_: Exception) { failed = true } finally { saving = false }
            }
        }) { Text(stringResource(R.string.since_when_editor_save)) } },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.since_when_editor_cancel)) } },
        text = {
            Column(Modifier.testTag("sincewhen-fields").verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value.title, { value = value.copy(title = it) }, modifier = Modifier.fillMaxWidth().testTag("sincewhen-title"), label = { Text(stringResource(R.string.since_when_editor_counter_name)) }, singleLine = true, enabled = !saving)
                OutlinedTextField(value.description, { value = value.copy(description = it) }, modifier = Modifier.fillMaxWidth().testTag("sincewhen-description"), label = { Text(stringResource(R.string.since_when_editor_description)) }, enabled = !saving)
                TextButton(modifier = Modifier.testTag("sincewhen-start"), enabled = !saving, onClick = { pickEnd = false }) {
                    Text(stringResource(R.string.since_when_editor_starts_from, formatSinceWhenTimestamp(value.initialTimestamp)))
                }
                Row { Checkbox(hasEnd, { hasEnd = it }, modifier = Modifier.testTag("sincewhen-has-end"), enabled = !saving); Text(stringResource(R.string.since_when_editor_has_end)) }
                if (hasEnd) {
                    TextButton(modifier = Modifier.testTag("sincewhen-end"), enabled = !saving, onClick = { pickEnd = true }) { Text(stringResource(R.string.since_when_editor_ends_at, formatSinceWhenTimestamp(end))) }
                    if (end <= value.initialTimestamp) Text(stringResource(R.string.since_when_editor_end_error), color = MaterialTheme.colorScheme.error)
                }
                Text(stringResource(R.string.since_when_editor_color))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0xFF168A83L to R.string.since_when_editor_teal, 0xFF2B5DAAL to R.string.since_when_editor_blue, 0xFFB86E00L to R.string.since_when_editor_amber, 0xFF9B3D5CL to R.string.since_when_editor_rose, 0xFF5E7F28L to R.string.since_when_editor_olive, 0xFF5C6470L to R.string.since_when_editor_slate).forEach { (color, label) ->
                        FilterChip(value.colorArgb == color, { value = value.copy(colorArgb = color) }, label = { Text(stringResource(label)) }, enabled = !saving, modifier = Modifier.testTag("sincewhen-color-$color"), leadingIcon = { Surface(color = androidx.compose.ui.graphics.Color(color)) { Spacer(Modifier.size(14.dp)) } })
                    }
                }
                Text(stringResource(R.string.since_when_editor_units))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("YEARS" to R.string.since_when_editor_years, "MONTHS" to R.string.since_when_editor_months, "WEEKS" to R.string.since_when_editor_weeks, "DAYS" to R.string.since_when_editor_days, "HOURS" to R.string.since_when_editor_hours, "MINUTES" to R.string.since_when_editor_minutes, "SECONDS" to R.string.since_when_editor_seconds).forEach { (unit, label) ->
                        FilterChip(unit in units, { val next = if (unit in units) units - unit else units + unit; value = value.copy(displayUnitsJson = JSONArray(next.toList()).toString()) }, label = { Text(stringResource(label)) }, enabled = !saving, modifier = Modifier.testTag("sincewhen-unit-$unit"))
                    }
                }
                Text(stringResource(R.string.since_when_editor_tags))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    available.filter { !it.archived || it.id in selectedTags }.forEach { tag ->
                        FilterChip(tag.id in selectedTags, { selectedTags = if (tag.id in selectedTags) selectedTags - tag.id else selectedTags + tag.id }, label = { Text(tag.name) }, enabled = tagsReady && !saving, modifier = Modifier.testTag("sincewhen-tag-${tag.id}"))
                    }
                }
                if (available.isEmpty()) Text(stringResource(R.string.since_when_editor_no_tags))
                if (failed) Text(stringResource(R.string.since_when_editor_save_error), color = MaterialTheme.colorScheme.error)
            }
        },
    )
    pickEnd?.let { isEnd ->
        SinceWhenDateTimePicker(if (isEnd) end else value.initialTimestamp, onDismiss = { pickEnd = null }) { picked ->
            if (isEnd) end = picked else value = value.copy(initialTimestamp = picked)
            pickEnd = null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SinceWhenDateTimePicker(initial: Long, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val local = remember(initial) { Instant.ofEpochMilli(initial).atZone(zone) }
    val date = rememberDatePickerState(initialSelectedDateMillis = local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    var hour by remember { mutableStateOf(local.hour.toString()) }
    var minute by remember { mutableStateOf(local.minute.toString()) }
    val h = hour.toIntOrNull()
    val m = minute.toIntOrNull()
    DatePickerDialog(onDismissRequest = onDismiss,
        confirmButton = { TextButton(modifier = Modifier.testTag("sincewhen-time-confirm"), enabled = h in 0..23 && m in 0..59 && date.selectedDateMillis != null, onClick = { onConfirm(sinceWhenPickedTimestamp(initial, date.selectedDateMillis!!, h!!, m!!, zone)) }) { Text(stringResource(R.string.since_when_editor_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.since_when_editor_cancel)) } },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            DatePicker(date)
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(hour, { hour = it.filter(Char::isDigit).take(2) }, modifier = Modifier.weight(1f).testTag("sincewhen-hour"), label = { Text(stringResource(R.string.since_when_editor_hours)) }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                OutlinedTextField(minute, { minute = it.filter(Char::isDigit).take(2) }, modifier = Modifier.weight(1f).testTag("sincewhen-minute"), label = { Text(stringResource(R.string.since_when_editor_minutes)) }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            }
        }
    }
}
