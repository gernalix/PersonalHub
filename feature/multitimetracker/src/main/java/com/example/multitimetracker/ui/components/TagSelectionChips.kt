@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.multitimetracker.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.multitimetracker.model.Tag

@Composable
fun TagSelectionFlow(
    tags: List<Tag>,
    selectedIds: Set<Long>,
    onToggle: (Tag) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasizeTimedDuration: Boolean = false
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        tags.forEach { tag ->
            val selected = tag.id in selectedIds
            val isTimed = tag.isTimedTagChip()
            val containerColor = if (selected) {
                MaterialTheme.colorScheme.primary
            } else if (isTimed) {
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surface
            }
            val contentColor = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else if (isTimed) {
                MaterialTheme.colorScheme.onTertiaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            }
            val borderColor = if (selected) {
                MaterialTheme.colorScheme.primary
            } else if (isTimed) {
                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.75f)
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
            }
            val alpha = if (enabled) 1f else 0.55f

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = containerColor.copy(alpha = alpha),
                border = BorderStroke(1.dp, borderColor.copy(alpha = alpha)),
                tonalElevation = if (selected) 2.dp else 0.dp,
                shadowElevation = if (selected) 1.dp else 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .defaultMinSize(minHeight = 52.dp)
                        .widthIn(max = 320.dp)
                        .clickable(enabled = enabled) { onToggle(tag) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (selected) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    if (isTimed) {
                        val tagLabel = tag.name.trim().ifBlank { tag.id.toString() }
                        val durationText = formatTimedDurationMinutesChip(tag.timedDurationMinutes)
                        Text(
                            text = tagLabel,
                            modifier = Modifier.weight(1f, fill = false),
                            color = contentColor.copy(alpha = alpha * 0.92f),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = when {
                                selected -> FontWeight.Medium
                                emphasizeTimedDuration -> FontWeight.Medium
                                else -> FontWeight.Normal
                            }
                        )
                        TimedTagBadge(
                            durationText = durationText,
                            selected = selected,
                            enabled = enabled
                        )
                    } else {
                        Text(
                            text = buildTagChipLabel(tag),
                            color = contentColor.copy(alpha = alpha),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimedTagBadge(
    durationText: String,
    selected: Boolean,
    enabled: Boolean
) {
    if (durationText.isBlank()) return
    val alpha = if (enabled) 1f else 0.55f
    val badgeContainer: Color
    val badgeContent: Color
    val badgeBorder: Color
    if (selected) {
        badgeContainer = MaterialTheme.colorScheme.surface
        badgeContent = MaterialTheme.colorScheme.primary
        badgeBorder = MaterialTheme.colorScheme.surfaceVariant
    } else {
        badgeContainer = MaterialTheme.colorScheme.tertiary
        badgeContent = MaterialTheme.colorScheme.onTertiary
        badgeBorder = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.72f)
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = badgeContainer.copy(alpha = alpha),
        border = BorderStroke(1.dp, badgeBorder.copy(alpha = alpha))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Schedule,
                contentDescription = null,
                tint = badgeContent.copy(alpha = alpha),
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = durationText,
                color = badgeContent.copy(alpha = alpha),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}

private fun buildTagChipLabel(tag: Tag): String {
    val suffix = if (tag.isTimedTagChip()) " ⏱${formatTimedDurationMinutesChip(tag.timedDurationMinutes)}" else ""
    val base = tag.name.trim().ifBlank { tag.id.toString() }
    return "$base$suffix"
}

private fun Tag.isTimedTagChip(): Boolean = (timedDurationMinutes ?: 0) > 0

private fun formatTimedDurationMinutesChip(totalMinutes: Int?): String {
    val minutes = (totalMinutes ?: 0).coerceAtLeast(0)
    if (minutes <= 0) return ""
    val hoursPart = minutes / 60
    val minutesPart = minutes % 60
    return when {
        hoursPart > 0 && minutesPart > 0 -> "${hoursPart}h ${minutesPart}m"
        hoursPart > 0 -> "${hoursPart}h"
        else -> "${minutesPart}m"
    }
}
