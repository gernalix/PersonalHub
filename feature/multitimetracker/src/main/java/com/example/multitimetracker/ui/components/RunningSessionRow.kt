// v478
package com.example.multitimetracker.ui.components
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.multitimetracker.R
import com.example.multitimetracker.ui.theme.LocalSpacing
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ===== FEATURE CAPSULE: Now.SessionRow (UI) — START =====
 *
 * Session-only mode card shown in NOW.
 *
 * Acceptance:
 * - Tap a running session -> STOP immediately.
 * - Long-press -> Edit session.
 * - Swipe right = edit. Swipe left = delete.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun RunningSessionRow(
    sessionId: Long,
    title: String,
    tagNames: List<String>,
    durationText: String,
    durationColor: Color,
    onStop: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDurationClick: (() -> Unit)? = null,
) {
    val s = LocalSpacing.current

    val displayTitle = title.trim().ifEmpty { stringResource(R.string.senza_titolo) }
    val visibleTagNames = tagNames.take(5)
    val hiddenTagsCount = tagNames.size - visibleTagNames.size

    key(sessionId) {
        val deleteRequested = remember(sessionId) { AtomicBoolean(false) }
        val dismissState = rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                when (value) {
                    SwipeToDismissBoxValue.StartToEnd -> {
                        onEdit()
                        false
                    }

                    SwipeToDismissBoxValue.EndToStart -> {
                        if (deleteRequested.compareAndSet(false, true)) onDelete()
                        true
                    }

                    else -> false
                }
            }
        )

        SwipeToDismissBox(
            state = dismissState,
            backgroundContent = {
                val isEdit = dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd
                val isDelete = dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart

                val icon = when {
                    isDelete -> Icons.Filled.Delete
                    isEdit -> Icons.Filled.Edit
                    else -> Icons.Filled.Edit
                }

                val align = when {
                    isDelete -> Alignment.CenterEnd
                    else -> Alignment.CenterStart
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = s.l),
                    contentAlignment = align
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = when {
                            isDelete -> MaterialTheme.colorScheme.onErrorContainer
                            isEdit -> MaterialTheme.colorScheme.onTertiaryContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 6.dp)
                )
            },
            content = {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("running_session_$sessionId")
                        .combinedClickable(
                            onClick = { onStop() },
                            onLongClick = { onEdit() }
                        ),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(s.l),
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = displayTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Text(
                                text = durationText,
                                style = MaterialTheme.typography.headlineMedium,
                                color = durationColor,
                                textAlign = TextAlign.End,
                                modifier = (if (onDurationClick != null) Modifier.combinedClickable(
                                    onClick = onDurationClick,
                                    onLongClick = onEdit
                                ) else Modifier)
                                    .widthIn(min = 92.dp)
                                    .padding(start = s.s)
                            )
                        }

                        if (visibleTagNames.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                visibleTagNames.forEach { tagName ->
                                    SessionTagChip(label = tagName)
                                }
                                if (hiddenTagsCount > 0) {
                                    SessionTagChip(label = "+$hiddenTagsCount")
                                }
                            }
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun SessionTagChip(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.10f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}
/** ===== FEATURE CAPSULE: Now.SessionRow (UI) — END ===== */
