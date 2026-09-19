package com.wordpulse.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
    val healthPermissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { grantedPermissions -> viewModel.onHealthConnectPermissionsResult(grantedPermissions) }
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
        initialSessionId = initialSessionId,
    )
    }
}
