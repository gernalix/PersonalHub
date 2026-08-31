// v355
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.multitimetracker.capsules.timeline.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.multitimetracker.R
import com.example.multitimetracker.capsules.timeline.controller.TimelineCapsuleViewModel
import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.ui.components.SessionEditDialog
import com.example.multitimetracker.ui.components.TitleWithVaultIndicator
import com.example.multitimetracker.ui.util.formatDuration

@Composable
fun TimelineCapsuleUi(
    vm: TimelineCapsuleViewModel,
    showSeconds: Boolean,
    hideHoursIfZero: Boolean,
    modifier: Modifier = Modifier
) {
    val state by vm.uiState.collectAsState()
    val visibleTags = remember(state.tags) { state.tags.filter { !it.isDeleted && !it.isArchived }.distinctBy { it.id } }
    val tagNameById = remember(visibleTags) { visibleTags.associate { it.id to it.name } }

    var editing by remember { mutableStateOf<SessionUi?>(null) }
    val sessionToEdit = editing
    if (sessionToEdit != null) {
        SessionEditDialog(
            session = sessionToEdit,
            isNewSession = false,
            tags = visibleTags,
            tagLastUsedMsByTagId = state.tagLastUsedMsByTagId,
            tagParentsByChild = state.tagParentsByChild,
            showSeconds = showSeconds,
            onAddTag = { name -> vm.addTag(name) },
            onSaveMeta = { id, title, tagIds ->
                vm.updateSession(id, title, tagIds)
                editing = null
            },
            onSaveTimes = { id, startMs, endMs ->
                vm.updateSessionTimes(id, startMs, endMs)
                editing = null
            },
            onDelete = { id ->
                vm.deleteSession(id)
                editing = null
            },
            onDismiss = { editing = null }
        )
    }

    val sessions = remember(state.chronologySessions) {
        state.chronologySessions
            .asSequence()
            .filter { it.deletedAtMs == null }
            .sortedByDescending { it.startMs }
            .distinctBy { it.id }
            .toList()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { TitleWithVaultIndicator(title = stringResource(R.string.chronology)) })
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (sessions.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.placeholder_dash),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(sessions, key = { it.id }) { s ->
                    TimelineSessionCard(
                        session = s,
                        tags = s.tagIds.mapNotNull { id -> tagNameById[id] },
                        durationMs = ((s.endMs ?: state.nowMs) - s.startMs).coerceAtLeast(0L),
                        showSeconds = showSeconds,
                        hideHoursIfZero = hideHoursIfZero,
                        onEdit = { editing = s },
                        onDelete = { vm.deleteSession(s.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineSessionCard(
    session: SessionUi,
    tags: List<String>,
    durationMs: Long,
    showSeconds: Boolean,
    hideHoursIfZero: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = session.title.ifBlank { stringResource(R.string.senza_titolo) },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (tags.isNotEmpty()) {
                Text(
                    text = tags.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = formatDuration(durationMs, showSeconds = showSeconds, hideHoursIfZero = hideHoursIfZero),
                style = MaterialTheme.typography.bodySmall
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.modifica_task))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.elimina))
                }
            }
        }
    }
}
