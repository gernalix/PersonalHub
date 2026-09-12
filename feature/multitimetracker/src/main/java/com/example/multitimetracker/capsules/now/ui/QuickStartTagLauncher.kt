package com.example.multitimetracker.capsules.now.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.multitimetracker.R
import com.example.multitimetracker.isTimedTag
import com.example.multitimetracker.model.Tag

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun QuickStartTagLauncher(
    tags: List<Tag>,
    enabled: Boolean,
    onStartSession: (List<Tag>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedTagIds by remember { mutableStateOf(emptySet<Long>()) }
    var selectionMessage by remember { mutableStateOf<String?>(null) }
    val timedTagError = stringResource(R.string.timed_tag_single_per_session)

    val filteredTags = remember(tags, query) {
        val normalized = query.trim()
        if (normalized.isEmpty()) tags else tags.filter { it.name.contains(normalized, ignoreCase = true) }
    }

    fun clearMultiSelect() {
        multiSelectMode = false
        selectedTagIds = emptySet()
        selectionMessage = null
    }

    fun toggleSelection(tag: Tag) {
        selectionMessage = null
        if (tag.id in selectedTagIds) {
            selectedTagIds = selectedTagIds - tag.id
            return
        }
        if (tag.isTimedTag() && tags.any { it.id in selectedTagIds && it.isTimedTag() }) {
            selectionMessage = timedTagError
            return
        }
        selectedTagIds = selectedTagIds + tag.id
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(
                    if (multiSelectMode) R.string.quick_start_select_tags else R.string.quick_start_header
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (multiSelectMode) {
                IconButton(onClick = { clearMultiSelect() }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.quick_start_cancel_cd),
                    )
                }
                IconButton(
                    onClick = {
                        if (!enabled || selectedTagIds.isEmpty()) return@IconButton
                        val selected = tags.filter { it.id in selectedTagIds }
                        if (selected.isNotEmpty()) {
                            onStartSession(selected)
                            clearMultiSelect()
                        }
                    },
                    enabled = enabled && selectedTagIds.isNotEmpty(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = stringResource(R.string.quick_start_confirm_cd),
                    )
                }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.cerca_tag)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = if (query.isNotBlank()) {
                {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.annulla))
                    }
                }
            } else {
                null
            },
        )

        Text(
            text = stringResource(R.string.quick_start_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        selectionMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        when {
            tags.isEmpty() -> {
                Text(
                    text = stringResource(R.string.nessun_tag_disponibile),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            filteredTags.isEmpty() -> {
                Text(
                    text = stringResource(R.string.quick_start_no_results),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    item(key = "quick_start_tag_flow") {
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            filteredTags.forEach { tag ->
                                QuickStartTagChip(
                                    tag = tag,
                                    selected = tag.id in selectedTagIds,
                                    enabled = enabled,
                                    onClick = {
                                        if (multiSelectMode) {
                                            toggleSelection(tag)
                                        } else {
                                            onStartSession(listOf(tag))
                                        }
                                    },
                                    onLongClick = {
                                        if (!multiSelectMode) {
                                            multiSelectMode = true
                                        }
                                        if (tag.id !in selectedTagIds) {
                                            toggleSelection(tag)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickStartTagChip(
    tag: Tag,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.combinedClickable(
            enabled = enabled,
            onClick = onClick,
            onLongClick = onLongClick,
        ),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = tag.name,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
