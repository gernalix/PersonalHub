package com.gernalix.personalhub.capsules.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.gernalix.personalhub.R
import com.gernalix.personalhub.core.hubcontext.WorkflowyIntegrationSettings

@Composable
internal fun WorkflowyIntegrationSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var configuration by remember { mutableStateOf(WorkflowyIntegrationSettings.configuration(context)) }
    var apiKey by remember { mutableStateOf("") }
    var target by remember(configuration.target) { mutableStateOf(configuration.target) }
    var saved by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.settings_back)) }
        Text(stringResource(R.string.workflowy_integration_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.workflowy_integration_description))

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; saved = false; failed = false },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.workflowy_api_key)) },
            supportingText = {
                Text(
                    stringResource(
                        if (configuration.hasApiKey) R.string.workflowy_api_key_saved
                        else R.string.workflowy_api_key_hint,
                    ),
                )
            },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
        )

        OutlinedTextField(
            value = target,
            onValueChange = { target = it; saved = false; failed = false },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.workflowy_target)) },
            supportingText = { Text(stringResource(R.string.workflowy_target_hint)) },
            singleLine = true,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = apiKey.isNotBlank() || configuration.hasApiKey,
                onClick = {
                    failed = runCatching {
                        WorkflowyIntegrationSettings.save(context, apiKey, target)
                    }.isFailure
                    if (!failed) {
                        apiKey = ""
                        configuration = WorkflowyIntegrationSettings.configuration(context)
                        target = configuration.target
                        saved = true
                        onBack()
                    }
                },
            ) { Text(stringResource(R.string.workflowy_settings_save)) }

            if (configuration.hasApiKey) {
                OutlinedButton(onClick = {
                    WorkflowyIntegrationSettings.clearApiKey(context)
                    configuration = WorkflowyIntegrationSettings.configuration(context)
                    apiKey = ""
                    saved = false
                    failed = false
                }) { Text(stringResource(R.string.workflowy_settings_clear_key)) }
            }
        }

        if (!configuration.hasApiKey) {
            Text(
                stringResource(R.string.workflowy_api_key_missing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (saved) Text(stringResource(R.string.workflowy_settings_saved))
        if (failed) Text(stringResource(R.string.workflowy_settings_error), color = MaterialTheme.colorScheme.error)
    }
}
