package com.example.multitimetracker.ui.components
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.multitimetracker.R
import com.example.multitimetracker.ui.theme.Dimens

@Composable
fun EditTagDialog(
    title: String,
    initialName: String,
    initialArchived: Boolean,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: (String, Boolean) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var archived by remember { mutableStateOf(initialArchived) }

    val trimmed = name.trim()
    val canConfirm = trimmed.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.nome_tag)) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(Dimens.SectionSpacing))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = archived,
                        onCheckedChange = { archived = it }
                    )
                    Text(stringResource(R.string.archivio), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            SingleSubmitTextButton(
                onClick = { if (canConfirm) onConfirm(trimmed, archived) },
                enabled = canConfirm
            ) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.annulla)) }
        }
    )
}
