// v339
package com.example.multitimetracker.capsules.system.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.example.multitimetracker.R

/**
 * Cross-capsule UI primitive: reusable Trash dialog.
 *
 * Why it's here:
 * - Tags/Alerts/Chains all need the same UX: search, restore, purge, empty.
 * - Keeping it centralized avoids copy/paste bugs and keeps capsules thin.
 */
@Composable
fun <T : Any> TrashListDialog(
    title: @Composable () -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    searchLabelResId: Int = R.string.cerca,
    items: List<T>,
    key: (T) -> Any,
    itemTitle: (T) -> String,
    onRestore: (T) -> Unit,
    onPurge: (T) -> Unit,
    onRequestEmpty: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = title,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = query,
                    onValueChange = onQueryChange,
                    label = { Text(stringResource(searchLabelResId)) },
                    singleLine = true
                )

                if (items.isEmpty()) {
                    Text(stringResource(R.string.trash_empty))
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 520.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(items, key = key) { it ->
                            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = itemTitle(it),
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        TextButton(onClick = { onRestore(it) }) { Text(stringResource(R.string.ripristina)) }
                                        TextButton(onClick = { onPurge(it) }) { Text(stringResource(R.string.purge)) }
                                    }
                                }
                            }
                        }
                    }
                }

                if (items.isNotEmpty() && onRequestEmpty != null) {
                    FilledTonalButton(
                        onClick = onRequestEmpty,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.empty_trash)) }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chiudi)) }
        }
    )
}

@Composable
fun ConfirmEmptyTrashDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.empty_trash_confirm_title)) },
        text = { Text(stringResource(R.string.empty_trash_confirm_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.empty_trash)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.annulla)) }
        }
    )
}
