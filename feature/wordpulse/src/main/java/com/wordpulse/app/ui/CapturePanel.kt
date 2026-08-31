package com.wordpulse.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.wordpulse.app.R
import com.wordpulse.app.domain.CaptureTextValue
import com.wordpulse.app.domain.TypingAlert
import com.wordpulse.app.domain.TypingAlertAction
import com.wordpulse.app.domain.TypingAlertType
import kotlinx.coroutines.delay

@Composable
internal fun CaptureInputField(
    value: CaptureTextValue,
    focusRequester: FocusRequester,
    onValueChange: (CaptureTextValue) -> Unit,
    onSubmit: () -> Unit,
    onPlaced: () -> Unit,
    restoreFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value.toTextFieldValue(),
        onValueChange = { onValueChange(it.toCaptureTextValue()) },
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onGloballyPositioned { onPlaced() }
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Spacebar || event.key == Key.Enter) {
                    if (event.type == KeyEventType.KeyDown) {
                        onSubmit()
                        restoreFocus()
                    }
                    true
                } else {
                    false
                }
            }
            .testTag("word-input"),
        textStyle = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Medium),
        singleLine = true,
        placeholder = { Text(stringResource(R.string.capture_hint)) },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                onSubmit()
                restoreFocus()
            },
        ),
    )
}

@Composable
internal fun TypingAlertHost(
    alert: TypingAlert?,
    onAction: (TypingAlertAction) -> Unit,
    onDismiss: (Long) -> Unit,
    restoreFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (alert == null) return

    LaunchedEffect(alert.id) {
        delay(alert.visibleDurationMs())
        onDismiss(alert.id)
    }

    val colors = alert.colors()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.container, RoundedCornerShape(6.dp))
            .padding(horizontal = 16.dp, vertical = if (alert.type == TypingAlertType.Success) 10.dp else 16.dp)
            .testTag("typing-alert-host"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .testTag("typing-alert-${alert.type.name}"),
            ) {
                Text(
                    text = alert.message,
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.content,
                    style = if (alert.type == TypingAlertType.Success) {
                        MaterialTheme.typography.bodyLarge
                    } else {
                        MaterialTheme.typography.titleLarge
                    },
                    fontWeight = if (alert.type == TypingAlertType.Success) {
                        FontWeight.Medium
                    } else {
                        FontWeight.Bold
                    },
                    maxLines = 3,
                )
            }
            if (alert.dismissible) {
                IconButton(
                    onClick = {
                        onDismiss(alert.id)
                        restoreFocus()
                    },
                    modifier = Modifier.testTag("typing-alert-dismiss"),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.dismiss_alert),
                        tint = colors.content,
                    )
                }
            }
        }
        alert.action?.let { action ->
            Button(
                onClick = {
                    onAction(action)
                    restoreFocus()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.content,
                    contentColor = colors.container,
                ),
                modifier = Modifier.testTag("typing-alert-edit"),
            ) {
                Text(stringResource(R.string.edit_last_submission))
            }
        }
    }
}

private fun CaptureTextValue.toTextFieldValue(): TextFieldValue =
    TextFieldValue(
        text = text,
        selection = TextRange(
            selectionStart.coerceIn(0, text.length),
            selectionEnd.coerceIn(0, text.length),
        ),
        composition = if (compositionStart != null && compositionEnd != null) {
            TextRange(
                compositionStart.coerceIn(0, text.length),
                compositionEnd.coerceIn(0, text.length),
            )
        } else {
            null
        },
    )

private fun TextFieldValue.toCaptureTextValue(): CaptureTextValue =
    CaptureTextValue(
        text = text,
        selectionStart = selection.start,
        selectionEnd = selection.end,
        compositionStart = composition?.start,
        compositionEnd = composition?.end,
    )

private fun TypingAlert.visibleDurationMs(): Long =
    when (type) {
        TypingAlertType.Success -> 1_800L
        TypingAlertType.InvalidInput -> 4_000L
        TypingAlertType.ModeratePerformanceDeviation -> 8_000L
        TypingAlertType.MarkedPerformanceDeviation -> 12_000L
        TypingAlertType.Duplicate -> 10_000L
        TypingAlertType.Error -> 12_000L
    }

private fun TypingAlert.colors(): AlertColors =
    when (type) {
        TypingAlertType.Success -> AlertColors(
            container = Color(0xFFE4F3E8),
            content = Color(0xFF164A2C),
        )
        TypingAlertType.InvalidInput -> AlertColors(
            container = Color(0xFFFFE8C7),
            content = Color(0xFF5A3300),
        )
        TypingAlertType.ModeratePerformanceDeviation -> AlertColors(
            container = Color(0xFFFFDDB8),
            content = Color(0xFF4D2800),
        )
        TypingAlertType.MarkedPerformanceDeviation -> AlertColors(
            container = Color(0xFFFFDAD6),
            content = Color(0xFF690005),
        )
        TypingAlertType.Duplicate -> AlertColors(
            container = Color(0xFFFFE047),
            content = Color(0xFF2E2600),
        )
        TypingAlertType.Error -> AlertColors(
            container = Color(0xFFFFDAD6),
            content = Color(0xFF690005),
        )
    }

private data class AlertColors(
    val container: Color,
    val content: Color,
)
