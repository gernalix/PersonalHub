package com.example.multitimetracker.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.multitimetracker.R

@Composable
fun ScreenHelpAction(
    title: String,
    body: String,
    modifier: Modifier = Modifier
) {
    HelpIconButton(
        title = title,
        body = body,
        modifier = modifier
    )
}

@Composable
fun InlineHelpAction(
    title: String,
    body: String,
    modifier: Modifier = Modifier
) {
    HelpIconButton(
        title = title,
        body = body,
        modifier = modifier
    )
}

@Composable
private fun HelpIconButton(
    title: String,
    body: String,
    modifier: Modifier = Modifier
) {
    var showDialog by remember(title, body) { mutableStateOf(false) }

    IconButton(
        onClick = { showDialog = true },
        modifier = modifier
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
            contentDescription = stringResource(R.string.cd_open_help),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.chiudi))
                }
            }
        )
    }
}
