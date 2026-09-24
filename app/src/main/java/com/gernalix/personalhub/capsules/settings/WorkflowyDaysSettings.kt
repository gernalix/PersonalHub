package com.gernalix.personalhub.capsules.settings

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gernalix.personalhub.R
import com.gernalix.personalhub.workflowydays.WorkflowyDaysSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREFS = "workflowy_days_sync"
private const val KEY_URL = "feed_url"
private const val KEY_ENABLED = "enabled"
private const val DEFAULT_FEED_URL = ""

@Composable
internal fun WorkflowyDaysSettings(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(prefs.getString(KEY_URL, DEFAULT_FEED_URL).orEmpty()) }
    var enabled by remember { mutableStateOf(prefs.getBoolean(KEY_ENABLED, false)) }
    var status by remember { mutableStateOf(WorkflowyDaysSync.status(context)) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            status = WorkflowyDaysSync.status(context)
            enabled = prefs.getBoolean(KEY_ENABLED, false)
            delay(1000)
        }
    }
    BackHandler(onBack = onBack)

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.settings_back)) }
        Text(stringResource(R.string.workflowy_days_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.workflowy_days_description))

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(stringResource(R.string.workflowy_days_feed_url)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            enabled = !busy && url.trim().startsWith("https://"),
            onClick = {
                busy = true
                scope.launch {
                    failed = withContext(Dispatchers.IO) {
                        runCatching { WorkflowyDaysSync.configure(context, url.trim(), enabled) }.isFailure
                    }
                    busy = false
                    if (!failed) url = prefs.getString(KEY_URL, DEFAULT_FEED_URL).orEmpty()
                }
            },
        ) { Text(stringResource(R.string.workflowy_days_save)) }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.workflowy_days_enable), Modifier.weight(1f))
            Switch(
                checked = enabled,
                enabled = !busy && url.trim().startsWith("https://"),
                onCheckedChange = { target ->
                    busy = true
                    scope.launch {
                        failed = withContext(Dispatchers.IO) {
                            runCatching {
                                if (prefs.getString(KEY_URL, "").orEmpty() != url.trim()) {
                                    WorkflowyDaysSync.configure(context, url.trim(), target)
                                } else {
                                    WorkflowyDaysSync.setEnabled(context, target)
                                }
                            }.isFailure
                        }
                        enabled = prefs.getBoolean(KEY_ENABLED, false)
                        busy = false
                    }
                },
            )
        }

        Text(
            stringResource(
                if (!enabled) R.string.workflowy_days_off else when (status) {
                    "downloading" -> R.string.workflowy_days_downloading
                    "complete" -> R.string.workflowy_days_complete
                    "retry" -> R.string.workflowy_days_retry
                    else -> R.string.workflowy_days_waiting
                },
            ),
        )
        if (busy) CircularProgressIndicator()
        if (failed) Text(stringResource(R.string.workflowy_days_error), color = MaterialTheme.colorScheme.error)
    }
}
