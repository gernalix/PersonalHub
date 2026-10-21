package com.supercontacts.app.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.supercontacts.app.R

data class CallOverlaySignal(
    val sequence: Long,
    val phoneNumber: String?,
    val isManualTest: Boolean = false,
)

@Composable
internal fun CallOverlay(
    signal: CallOverlaySignal,
    matchedContactId: Long?,
    onDismiss: () -> Unit,
    onOpenContact: (Long) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("call-overlay"),
        contentAlignment = Alignment.TopCenter,
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            if (signal.isManualTest) {
                                R.string.call_overlay_manual_title
                            } else {
                                R.string.call_overlay_title
                            },
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("call-overlay-close"),
                    ) {
                        Text("X")
                    }
                }
                Text(
                    text = signal.phoneNumber
                        ?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.call_overlay_number_unknown),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (matchedContactId == null) {
                    Text(
                        text = stringResource(R.string.call_overlay_contact_not_found),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Button(
                        onClick = { onOpenContact(matchedContactId) },
                        modifier = Modifier.testTag("call-overlay-open-contact"),
                    ) {
                        Text(stringResource(R.string.call_overlay_open_contact))
                    }
                }
            }
        }
    }
}
