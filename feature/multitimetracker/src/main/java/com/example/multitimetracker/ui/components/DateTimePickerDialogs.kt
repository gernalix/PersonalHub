package com.example.multitimetracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.multitimetracker.R
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

enum class DateTimePickerCommitMode {
    START_OF_MINUTE,
    END_OF_MINUTE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MttDateTimePickerDialog(
    title: String,
    initialTimestampMs: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
    modifier: Modifier = Modifier,
    commitMode: DateTimePickerCommitMode = DateTimePickerCommitMode.START_OF_MINUTE
) {
    val zoneId = remember { ZoneId.systemDefault() }
    val initialDateTime = remember(initialTimestampMs, zoneId) {
        Instant.ofEpochMilli(initialTimestampMs).atZone(zoneId)
    }
    val initialDateMillis = remember(initialTimestampMs, zoneId) {
        initialDateTime.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialDateMillis)
    val timePickerState = rememberTimePickerState(
        initialHour = initialDateTime.hour,
        initialMinute = initialDateTime.minute,
        is24Hour = true,
    )
    var hourText by remember(initialTimestampMs, zoneId) {
        mutableStateOf(initialDateTime.hour.toString().padStart(2, '0'))
    }
    var minuteText by remember(initialTimestampMs, zoneId) {
        mutableStateOf(initialDateTime.minute.toString().padStart(2, '0'))
    }
    fun commit() {
        val selectedDate = datePickerState.selectedDateMillis
            ?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
            ?: initialDateTime.toLocalDate()
        val selectedTime = LocalTime.of(timePickerState.hour, timePickerState.minute)
        val localDateTime = selectedDate.atTime(selectedTime)
        val instant = when (commitMode) {
            DateTimePickerCommitMode.START_OF_MINUTE -> localDateTime.atZone(zoneId).toInstant()
            DateTimePickerCommitMode.END_OF_MINUTE -> localDateTime
                .withSecond(59)
                .withNano(999_000_000)
                .atZone(zoneId)
                .toInstant()
        }
        onConfirm(instant.toEpochMilli())
    }

    fun commitValidTimeInput() {
        val parsed = parseMttTimeInput(hourText, minuteText) ?: return
        timePickerState.hour = parsed.hour
        timePickerState.minute = parsed.minute
        commit()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(8.dp)
                .imePadding(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.annulla),
                        )
                    }
                }
                DatePicker(
                    state = datePickerState,
                    modifier = Modifier.fillMaxWidth(),
                    title = null,
                    headline = null,
                    showModeToggle = false,
                )
                MttTimeInputFields(
                    hourText = hourText,
                    minuteText = minuteText,
                    onHourTextChange = { raw ->
                        hourText = raw.filter(Char::isDigit).take(2)
                        hourText.toIntOrNull()?.takeIf { it in 0..23 }?.let {
                            timePickerState.hour = it
                        }
                    },
                    onMinuteTextChange = { raw ->
                        minuteText = raw.filter(Char::isDigit).take(2)
                        minuteText.toIntOrNull()?.takeIf { it in 0..59 }?.let {
                            timePickerState.minute = it
                        }
                    },
                    onCommit = { commitValidTimeInput() },
                )
            }
        }
    }
}

internal data class MttParsedTimeInput(val hour: Int, val minute: Int)

internal fun parseMttTimeInput(hourText: String, minuteText: String): MttParsedTimeInput? {
    val hour = hourText.toIntOrNull()?.takeIf { it in 0..23 } ?: return null
    val minute = minuteText.toIntOrNull()?.takeIf { it in 0..59 } ?: return null
    return MttParsedTimeInput(hour = hour, minute = minute)
}

@Composable
private fun MttTimeInputFields(
    hourText: String,
    minuteText: String,
    onHourTextChange: (String) -> Unit,
    onMinuteTextChange: (String) -> Unit,
    onCommit: () -> Unit,
) {
    fun Modifier.confirmOnEnter(): Modifier = onPreviewKeyEvent { event ->
        if ((event.key == Key.Enter || event.key == Key.NumPadEnter) &&
            event.type == KeyEventType.KeyUp
        ) {
            onCommit()
            true
        } else {
            false
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = hourText,
            onValueChange = onHourTextChange,
            modifier = Modifier
                .weight(1f)
                .confirmOnEnter(),
            label = { Text(stringResource(R.string.hours_label)) },
            isError = parseMttTimeInput(hourText, minuteText) == null &&
                (hourText.isBlank() || hourText.toIntOrNull()?.let { it !in 0..23 } == true),
            singleLine = true,
            textStyle = MaterialTheme.typography.headlineMedium.copy(textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = { onCommit() },
            ),
        )
        Text(
            text = ":",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        TextField(
            value = minuteText,
            onValueChange = onMinuteTextChange,
            modifier = Modifier
                .weight(1f)
                .confirmOnEnter(),
            label = { Text(stringResource(R.string.minutes_label)) },
            isError = parseMttTimeInput(hourText, minuteText) == null &&
                (minuteText.isBlank() || minuteText.toIntOrNull()?.let { it !in 0..59 } == true),
            singleLine = true,
            textStyle = MaterialTheme.typography.headlineMedium.copy(textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = { onCommit() },
            ),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MttDatePickerDialog(
    title: String,
    initialEpochDay: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val initialDate = remember(initialEpochDay) { java.time.LocalDate.ofEpochDay(initialEpochDay) }
    val initialDateMillis = remember(initialEpochDay) {
        initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialDateMillis)
    var initialSelectionConsumed by remember { mutableStateOf(false) }

    LaunchedEffect(datePickerState.selectedDateMillis) {
        val selected = datePickerState.selectedDateMillis ?: return@LaunchedEffect
        if (!initialSelectionConsumed) {
            initialSelectionConsumed = true
            return@LaunchedEffect
        }
        val picked = Instant.ofEpochMilli(selected).atZone(ZoneOffset.UTC).toLocalDate()
        onConfirm(picked.toEpochDay())
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(8.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.annulla),
                        )
                    }
                }
                DatePicker(
                    state = datePickerState,
                    modifier = Modifier.fillMaxWidth(),
                    title = null,
                    headline = null,
                    showModeToggle = false,
                )
            }
        }
    }
}
