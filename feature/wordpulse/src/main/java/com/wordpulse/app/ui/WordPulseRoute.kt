package com.wordpulse.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@Composable
fun WordPulseRoute(viewModel: WordPulseViewModel, initialSessionId: String? = null) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val captureFieldValue by viewModel.captureFieldState.collectAsStateWithLifecycle()
    val typingAlert by viewModel.typingAlertState.collectAsStateWithLifecycle()
    val latestPerformance by viewModel.latestPerformanceState.collectAsStateWithLifecycle()
    val sleepIntegration by viewModel.sleepIntegrationState.collectAsStateWithLifecycle()
    val pvtTest by viewModel.pvtTestState.collectAsStateWithLifecycle()
    val latestPvtSummary by viewModel.latestPvtSummary.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val healthPermissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { grantedPermissions -> viewModel.onHealthConnectPermissionsResult(grantedPermissions) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val csv = viewModel.exportBackupCsv()
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(csv.toByteArray(Charsets.UTF_8))
                } ?: error("No output stream")
            }.onSuccess {
                viewModel.notify("CSV exported")
            }.onFailure {
                viewModel.notify("CSV export failed")
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.readBytes().toString(Charsets.UTF_8)
                } ?: error("No input stream")
            }.onSuccess(viewModel::importCsv)
                .onFailure { viewModel.notify("CSV import failed") }
        }
    }

    AlertnessOverlayHost(
        latestPerformance = latestPerformance,
        sleepIntegration = sleepIntegration,
        pvtTest = pvtTest,
        latestPvtSummary = latestPvtSummary,
        onRequestSleepPermission = {
            if (viewModel.healthConnectPermissions.isNotEmpty()) {
                healthPermissionLauncher.launch(viewModel.healthConnectPermissions)
            }
        },
        onRefreshSleep = viewModel::refreshSleepContext,
        onStartPvt = viewModel::startPvtTest,
        onPvtTap = viewModel::onPvtTap,
        onCancelPvt = viewModel::cancelPvtTest,
    ) {
    WordPulseScreen(
        uiState = uiState,
        captureFieldValue = captureFieldValue,
        typingAlert = typingAlert,
        events = viewModel.events,
        snackbarHostState = snackbarHostState,
        onCaptureValueChanged = viewModel::onCaptureValueChanged,
        onSubmitCurrent = viewModel::submitCurrent,
        onTypingAlertAction = viewModel::onTypingAlertAction,
        onDismissTypingAlert = viewModel::dismissTypingAlert,
        onClearInput = viewModel::clearInput,
        onStartNewSession = viewModel::startNewSession,
        onDeleteAllData = viewModel::deleteAllData,
        onSearchChanged = viewModel::onSearchChanged,
        onSearchModeChanged = viewModel::onSearchModeChanged,
        onSearchSortChanged = viewModel::onSearchSortChanged,
        onSelectWord = viewModel::selectWord,
        onClearSelectedWord = viewModel::clearSelectedWord,
        onImportCsv = { importLauncher.launch(arrayOf("text/*", "text/csv", "application/octet-stream")) },
        onExportCsv = {
            val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.now())
            exportLauncher.launch("wordpulse-$stamp.csv")
        },
        initialSessionId = initialSessionId,
    )
    }
}
