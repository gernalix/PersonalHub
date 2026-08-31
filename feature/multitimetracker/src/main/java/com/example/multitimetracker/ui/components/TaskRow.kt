@file:OptIn(ExperimentalLayoutApi::class)
package com.example.multitimetracker.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.example.multitimetracker.R
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.Task
import com.example.multitimetracker.model.TimeEngine
import com.example.multitimetracker.ui.theme.LocalSpacing
import com.example.multitimetracker.ui.theme.taskRolesFromTagName
import androidx.compose.ui.text.font.FontWeight

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskRow(
    task: Task,
    tags: List<Tag>,
    nowMs: Long,
    highlightRunning: Boolean,
    showTime: Boolean = true,
    showSeconds: Boolean = true,
    hideHoursIfZero: Boolean = false,
    showTags: Boolean = true,
    highlightJustCreated: Boolean = false,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    val s = LocalSpacing.current

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    val engine = TimeEngine()
    val shownMs = engine.displayMs(task.totalMs, task.lastStartedAtMs, nowMs)
    val taskTags = tags.filter { task.tagIds.contains(it.id) }
    val displayName = run {
        val n = task.name.trim()
        if (n.isNotEmpty()) n
        else if (taskTags.isNotEmpty()) taskTags.joinToString(" · ") { it.name }
        else stringResource(R.string.senza_titolo)
    }

    var showAllTags by remember { mutableStateOf(false) }

    val dominantTagName = taskTags.firstOrNull()?.name
    val roles = dominantTagName?.let { taskRolesFromTagName(it, isDarkTheme = isDark) }

    // Running sessions should pop more (user feedback): use a stronger container color.
    val container = if (highlightRunning) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
    val border = if (highlightJustCreated) {
        androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f))
    } else null

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onLongPress() }
                )
            }
            .then(if (border != null) Modifier.border(border, MaterialTheme.shapes.medium) else Modifier),
        colors = CardDefaults.elevatedCardColors(containerColor = container),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = s.l, vertical = s.m),
            horizontalArrangement = Arrangement.spacedBy(s.m),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // LEFT: precision rail
            PrecisionLeftRail(
                railColor = roles?.rail ?: MaterialTheme.colorScheme.outlineVariant,
                isRunning = task.isRunning
            )

            // CENTER: title + metadata
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (showTags && taskTags.isNotEmpty()) {
                        CompactTagLine(
                            tags = taskTags,
                            maxVisible = 5,
                            isDark = isDark,
                            onOverflowClick = { showAllTags = true }
                        )
                    }

                    if (onEdit != null) {
                        IconButton(
                            onClick = onEdit,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = stringResource(R.string.edit),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    if (onDelete != null) {
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.elimina),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                }
            }

            // RIGHT: duration (dominant, aligned)
            if (showTime) {
                Text(
                    text = formatDuration(shownMs, showSeconds, hideHoursIfZero),
                    style = if (highlightRunning) MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold) else MaterialTheme.typography.titleMedium,
                    maxLines = 1
                )
            }
        }
    }

    if (showAllTags) {
        ModalBottomSheet(onDismissRequest = { showAllTags = false }) {
            Text(
                text = stringResource(R.string.tags),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = s.l, vertical = s.m)
            )

            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = s.l, vertical = s.m),
                horizontalArrangement = Arrangement.spacedBy(s.s),
                verticalArrangement = Arrangement.spacedBy(s.s)
            ) {
                taskTags.forEach { tag ->
                    TagToken(label = tag.name, isDark = isDark)
                }
            }

            Spacer(Modifier.height(s.xxl))
        }
    }
}

@Composable
private fun CompactTagLine(
    tags: List<Tag>,
    maxVisible: Int,
    isDark: Boolean,
    onOverflowClick: () -> Unit
) {
    val visible = tags.take(maxVisible)
    val overflow = tags.size - visible.size

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        visible.forEach { tag ->
            TagToken(label = tag.name, isDark = isDark)
        }
        if (overflow > 0) {
            Box(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f), MaterialTheme.shapes.small)
                    .pointerInput(Unit) { detectTapGestures(onTap = { onOverflowClick() }) }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "+$overflow",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun TagToken(label: String, isDark: Boolean) {
    val roles = taskRolesFromTagName(label, isDarkTheme = isDark)
    val fg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .border(
                width = 1.dp,
                color = roles.rail.copy(alpha = 0.55f),
                shape = MaterialTheme.shapes.small
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(MaterialTheme.shapes.small)
                .border(1.dp, roles.rail.copy(alpha = 0.9f), MaterialTheme.shapes.small)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun formatDuration(ms: Long, showSeconds: Boolean, hideHoursIfZero: Boolean): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L

    val parts = mutableListOf<String>()

    if (!hideHoursIfZero || hours > 0L) {
        if (hours > 0L) parts.add("${hours}h")
    }

    val showMinutes = (hours > 0L) || (minutes > 0L)
    if (showMinutes) {
        parts.add("${minutes}m")
    }

    if (showSeconds) {
        val showSecondsPart = seconds > 0L || parts.isEmpty()
        if (showSecondsPart) {
            parts.add("${seconds}s")
        }
    }

    if (!showSeconds && parts.isEmpty()) {
        parts.add("0m")
    }

    return parts.joinToString(" ")
}
