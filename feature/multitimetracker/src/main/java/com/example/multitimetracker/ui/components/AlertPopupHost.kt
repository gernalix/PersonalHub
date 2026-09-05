package com.example.multitimetracker.ui.components

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.multitimetracker.R
import com.example.multitimetracker.model.PreFencePrompt
import com.example.multitimetracker.ui.alerts.AlertMessageSegment
import com.example.multitimetracker.ui.alerts.isAllowedAlertLink
import com.example.multitimetracker.ui.alerts.parseAlertMessageSegments

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
            .zIndex(10f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onDismiss(prompts.last()) }
            ),
        contentAlignment = Alignment.Center
    ) {
        AlertPopupCard(
            prompt = prompts.last(),
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .widthIn(max = 560.dp),
            onDismiss = { onDismiss(prompts.last()) }
        )
    }
}

@Composable
private fun AlertPopupCard(
    prompt: PreFencePrompt,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val segments = remember(prompt.message) { parseAlertMessageSegments(prompt.message) }
    val linkColor = MaterialTheme.colorScheme.primary
    val annotatedMessage = remember(segments, linkColor) {
        buildAnnotatedString {
            segments.forEach { segment ->
                when (segment) {
                    is AlertMessageSegment.Text -> append(segment.value)
                    is AlertMessageSegment.Link -> {
                        val start = length
                        append(segment.label)
                        addStyle(
                            SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline,
                            ),
                            start,
                            length
                        )
                        addStringAnnotation("url", segment.url, start, length)
                    }
                }
            }
        }
    }

    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            ),
        shape = RoundedCornerShape(8.dp),
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
                    if (annotatedMessage.text.isNotBlank()) {
                        ClickableText(
                            text = annotatedMessage,
                            style = MaterialTheme.typography.bodyMedium
                                .copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                            onClick = { offset ->
                                annotatedMessage
                                    .getStringAnnotations("url", offset, offset)
                                    .firstOrNull()
                                    ?.let { openAlertLink(context, it.item) }
                            }
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
