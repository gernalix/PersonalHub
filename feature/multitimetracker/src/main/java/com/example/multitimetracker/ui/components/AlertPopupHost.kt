package com.example.multitimetracker.ui.components

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.multitimetracker.R
import com.example.multitimetracker.model.PreFencePrompt
import com.example.multitimetracker.ui.alerts.AlertMessageSegment
import com.example.multitimetracker.ui.alerts.isAllowedAlertLink
import com.example.multitimetracker.ui.alerts.parseAlertMessageSegments
import kotlinx.coroutines.delay

@Composable
fun AlertPopupHost(
    prompts: List<PreFencePrompt>,
    onDismiss: (PreFencePrompt) -> Unit,
    modifier: Modifier = Modifier
) {
    if (prompts.isEmpty()) return

    Box(
        modifier = modifier
            .fillMaxSize()
            .zIndex(10f),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .widthIn(max = 560.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            prompts.take(MAX_VISIBLE_ALERT_POPUPS).forEach { prompt ->
                AlertPopupCard(prompt = prompt, onDismiss = { onDismiss(prompt) })
            }
            val hiddenCount = prompts.size - MAX_VISIBLE_ALERT_POPUPS
            if (hiddenCount > 0) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    tonalElevation = 2.dp
                ) {
                    Text(
                        text = stringResource(R.string.alert_popup_more_count, hiddenCount),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AlertPopupCard(
    prompt: PreFencePrompt,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val segments = remember(prompt.message) { parseAlertMessageSegments(prompt.message) }
    val text = remember(segments) {
        segments
            .filterIsInstance<AlertMessageSegment.Text>()
            .joinToString(separator = "") { it.value }
            .trim()
    }
    val links = remember(segments) { segments.filterIsInstance<AlertMessageSegment.Link>() }

    LaunchedEffect(prompt.ruleId, prompt.sessionId, prompt.firedAtMs, prompt.message) {
        delay(ALERT_POPUP_AUTO_DISMISS_MS)
        onDismiss()
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = prompt.sessionTitle.ifBlank { stringResource(R.string.task) },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (text.isNotBlank()) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.alert_popup_dismiss)
                    )
                }
            }

            if (links.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    links.forEach { link ->
                        AssistChip(
                            onClick = { openAlertLink(context, link.url) },
                            label = {
                                Text(
                                    text = link.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.OpenInBrowser,
                                    contentDescription = null
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                leadingIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                        )
                    }
                }
            }
        }
    }
}

private fun openAlertLink(context: Context, url: String) {
    if (!isAllowedAlertLink(url)) {
        Toast.makeText(context, context.getString(R.string.alert_link_open_failed), Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.alert_link_open_failed), Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, context.getString(R.string.alert_link_open_failed), Toast.LENGTH_SHORT).show()
    }
}

private const val MAX_VISIBLE_ALERT_POPUPS = 3
private const val ALERT_POPUP_AUTO_DISMISS_MS = 8_000L
