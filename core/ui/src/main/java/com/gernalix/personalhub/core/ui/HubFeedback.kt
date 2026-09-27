package com.gernalix.personalhub.core.ui

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Shared compact feedback surface for PH modules and widget entry points. */
object HubFeedback {
    fun makeText(context: Context, message: CharSequence, duration: Int): Toast =
        Toast.makeText(context.applicationContext, message, duration)

    fun makeText(context: Context, @StringRes message: Int, duration: Int): Toast =
        Toast.makeText(context.applicationContext, message, duration)
}

/** Keeps snackbar actions while using the same compact, bottom-aligned presentation. */
@Composable
fun HubFeedbackHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    val dismissLabel = LocalContext.current.getString(R.string.feedback_dismiss)
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 360.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shadowElevation = 6.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        data.visuals.message,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    data.visuals.actionLabel?.let { label ->
                        Text(
                            label,
                            modifier = Modifier.clickable(onClick = data::performAction),
                            color = MaterialTheme.colorScheme.inversePrimary,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    if (data.visuals.withDismissAction) {
                        Text(
                            "✕",
                            modifier = Modifier.semantics { contentDescription = dismissLabel }
                                .clickable(onClick = data::dismiss),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}
