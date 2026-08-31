package com.example.multitimetracker.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * v231 – UX safety: prevent multi-tap duplication.
 *
 * Wraps common buttons with a one-shot "submitted" latch so confirm actions
 * cannot be triggered multiple times by rapid taps.
 */
@Composable
fun SingleSubmitTextButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    var submitted by remember { mutableStateOf(false) }
    TextButton(
        onClick = {
            if (submitted) return@TextButton
            submitted = true
            onClick()
        },
        enabled = enabled && !submitted,
        modifier = modifier,
        content = content
    )
}

@Composable
fun SingleSubmitButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    var submitted by remember { mutableStateOf(false) }
    Button(
        onClick = {
            if (submitted) return@Button
            submitted = true
            onClick()
        },
        enabled = enabled && !submitted,
        modifier = modifier,
        content = content
    )
}

@Composable
fun SingleSubmitOutlinedButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    var submitted by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = {
            if (submitted) return@OutlinedButton
            submitted = true
            onClick()
        },
        enabled = enabled && !submitted,
        modifier = modifier,
        content = content
    )
}
