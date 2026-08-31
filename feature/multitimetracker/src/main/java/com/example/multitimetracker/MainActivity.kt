// v471
// v458
package com.example.multitimetracker

import com.example.multitimetracker.util.CapsuleWriteApi

import android.Manifest
import android.content.Intent
import android.content.Context
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.DisposableEffect
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.multitimetracker.capsules.now.state.NowUiState
import com.example.multitimetracker.capsules.alerts.state.AlertsUiState
import com.example.multitimetracker.export.BackupFolderStore
import com.example.multitimetracker.model.TimeFenceDelivery
import com.example.multitimetracker.model.TimeFenceTrigger
import com.example.multitimetracker.model.TimedTagNotificationType
import com.example.multitimetracker.persistence.AuditLogSqlite
import com.example.multitimetracker.persistence.SnapshotSqlite
import com.example.multitimetracker.perf.StartupPerfTrace
import com.example.multitimetracker.ui.AppRoot
import com.example.multitimetracker.ui.theme.MultiTimeTrackerTheme
import com.example.multitimetracker.widget.QuickSessionWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.example.multitimetracker.util.CapsuleAudit
import androidx.compose.ui.res.stringResource

private enum class FirstRunWorkStep {
    CHECKING_FOLDER,
    RESTORING_DATA,
}

internal fun shouldRenderFirstRunSetupPrompt(
    setupDone: Boolean,
    setupCheckComplete: Boolean,
): Boolean = !setupDone && setupCheckComplete

@OptIn(CapsuleWriteApi::class)
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        StartupPerfTrace.activityOnCreateStart()
        super.onCreate(savedInstanceState)

        StartupPerfTrace.section("main_activity_on_create") {
            // v138 Capsule Audit Engine: emit known capsule boundary leaks in Logcat (debug only)
            CapsuleAudit.logKnownViolations()
            enableEdgeToEdge()
            setContent {
                MultiTimeTrackerTheme {
                    MultiTimeTrackerApp()
                }
            }
        }
    }
}

@Composable
private fun MultiTimeTrackerApp(
    vm: MainViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by vm.state.collectAsState()
    val alertsState by vm.alertsCapsule.uiState.collectAsState()
    val nowState by vm.nowCapsule.uiState.collectAsState()
    val integrityBlock by vm.integrityBlock.collectAsState()
    val notificationPermissionGranted = remember { mutableStateOf(hasNotificationPermission(context)) }
    val showNotificationPermissionRationale = remember { mutableStateOf(false) }
    val exactAlarmAllowed = remember { mutableStateOf(TimeFenceTimerScheduler.canScheduleExactAlarms(context)) }

    // Track "tempo trascorso sull'app" (foreground only).
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    vm.onAppForeground(context)
                    notificationPermissionGranted.value = hasNotificationPermission(context)
                    exactAlarmAllowed.value = TimeFenceTimerScheduler.canScheduleExactAlarms(context)
                }
                Lifecycle.Event.ON_PAUSE -> vm.onAppBackground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // If we start observing AFTER the Activity is already RESUMED (common on first composition),
        // we won't receive ON_RESUME and the "app usage" counter would stay frozen until the next resume.
        // So we eagerly sync the current state.
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            vm.onAppForeground(context)
            notificationPermissionGranted.value = hasNotificationPermission(context)
            exactAlarmAllowed.value = TimeFenceTimerScheduler.canScheduleExactAlarms(context)
        }
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val scope = rememberCoroutineScope()

    // --- First-run setup: explicit data-folder + restore contract.
    val setupDone = remember { mutableStateOf(false) }
    val setupCheckComplete = remember { mutableStateOf(false) }
    val setupState = remember { mutableStateOf<FirstRunSetupState?>(null) }
    val setupWork = remember { mutableStateOf<FirstRunWorkStep?>(null) }
    val continuationMode = remember { mutableStateOf(FirstRunContinuationMode.EMPTY_SETUP) }
    val startupInitializationStarted = remember { mutableStateOf(false) }

    fun resolveContinuationMode(): FirstRunContinuationMode {
        return if (vm.hasPersistedInternalData(context)) {
            FirstRunContinuationMode.CURRENT_DATA
        } else {
            FirstRunContinuationMode.EMPTY_SETUP
        }
    }

    fun refreshSetupState() {
        continuationMode.value = resolveContinuationMode()
        val hasFolder = BackupFolderStore.ensureSavedTreeWritable(context)
        val inspection = if (hasFolder) vm.inspectBackupFolder(context) else null
        setupState.value = decideFirstRunSetupState(
            hasChosenFolder = hasFolder,
            inspection = inspection,
            continuationMode = continuationMode.value,
        )
    }

    val treePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            setupWork.value = null
            setupState.value = FirstRunSetupState.NoFolderChosen
            setupCheckComplete.value = true
            return@rememberLauncherForActivityResult
        }
        setupWork.value = FirstRunWorkStep.CHECKING_FOLDER
        vm.setBackupRootFolder(context, uri)
        refreshSetupState()
        setupWork.value = null
        setupCheckComplete.value = true
    }

    val notifPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationPermissionGranted.value = granted
    }
    val exactAlarmPromptDismissed = remember { mutableStateOf(false) }
    val exactAlarmRequired = requiresExactAlarmPermission(nowState, alertsState)

    suspend fun runStartupInitializationIfNeeded() {
        if (startupInitializationStarted.value) return
        startupInitializationStarted.value = true
        val appContext = context.applicationContext
        withContext(Dispatchers.IO) {
            StartupPerfTrace.section("startup_critical_initialize") {
                ensureStartupSessionSchemaForLaunch(appContext)
                vm.initialize(appContext)
            }
            StartupPerfTrace.section("deferred_startup_schemas") {
                ensureDeferredStartupSchemasForLaunch(appContext)
            }
            StartupPerfTrace.section("deferred_vault_autorestore_flag") {
                vm.setVaultAutoRestoreEnabled(appContext, true)
            }
        }
    }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        StartupPerfTrace.firstFrame()

        // Provide context early for setup flows that may persist/import before initialize().
        vm.bindContext(context)
        val appContext = context.applicationContext
        withContext(Dispatchers.IO) {
            StartupPerfTrace.section("startup_home_prefetch") {
                ensureStartupSessionSchemaForLaunch(appContext)
                vm.loadStartupHomeFromSessionTables(appContext)
            }
        }

        // Setup phase (folder pick + optional import/cleanup) must happen BEFORE initialize,
        // otherwise empty/default state could race against the real restore decision.
        val hasSnapshot = StartupPerfTrace.section("startup_internal_data_probe") {
            vm.hasPersistedInternalDataFast(context)
        }
        continuationMode.value = if (hasSnapshot) {
            FirstRunContinuationMode.CURRENT_DATA
        } else {
            FirstRunContinuationMode.EMPTY_SETUP
        }
        val hasFolder = StartupPerfTrace.section("startup_saf_writable_probe") {
            BackupFolderStore.ensureSavedTreeWritable(context)
        }
        val isCloneBenchmark = BuildConfig.APPLICATION_ID.endsWith(".devicetest")
        val allowPersonalHubLocalDataWithoutSaf =
            appContext.packageName == "com.gernalix.personalhub" &&
                continuationMode.value == FirstRunContinuationMode.CURRENT_DATA
        if (isCloneBenchmark && hasSnapshot) {
            setupDone.value = true
        } else if ((hasFolder && continuationMode.value == FirstRunContinuationMode.CURRENT_DATA) || allowPersonalHubLocalDataWithoutSaf) {
            setupDone.value = true
        } else {
            refreshSetupState()
        }
        setupCheckComplete.value = true
        if (setupDone.value) {
            withFrameNanos { }
            runStartupInitializationIfNeeded()
        }
        if (Build.VERSION.SDK_INT >= 33 && !notificationPermissionGranted.value) {
            showNotificationPermissionRationale.value = true
        }
    }

    LaunchedEffect(setupDone.value) {
        if (setupDone.value) {
            runStartupInitializationIfNeeded()
        }
    }

    LaunchedEffect(exactAlarmRequired, exactAlarmAllowed.value) {
        if (!exactAlarmRequired || exactAlarmAllowed.value) {
            exactAlarmPromptDismissed.value = false
        }
    }


// Listen for snapshot changes emitted by the widget while the app is open.
val latestVm = rememberUpdatedState(vm)
DisposableEffect(Unit) {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == QuickSessionWidgetProvider.ACTION_SNAPSHOT_CHANGED) {
                latestVm.value.reloadFromSnapshot(context)
            }
        }
    }
    val filter = IntentFilter(QuickSessionWidgetProvider.ACTION_SNAPSHOT_CHANGED)
    ContextCompat.registerReceiver(
        context,
        receiver,
        filter,
        ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    onDispose {
        runCatching { context.unregisterReceiver(receiver) }
    }
}

    if (!setupDone.value) {
        if (!shouldRenderFirstRunSetupPrompt(setupDone.value, setupCheckComplete.value)) {
            SilentFirstRunSetupPlaceholder()
            return
        }
        val currentSetupState = setupState.value
        val currentContinuationMode = continuationMode.value
        val currentWork = setupWork.value
        val keepCurrentData = currentContinuationMode == FirstRunContinuationMode.CURRENT_DATA
        val dialogTitle = when (currentWork) {
            FirstRunWorkStep.CHECKING_FOLDER -> context.getString(R.string.first_run_checking_title)
            FirstRunWorkStep.RESTORING_DATA -> context.getString(R.string.first_run_restoring_title)
            null -> when (currentSetupState) {
                FirstRunSetupState.NoFolderChosen -> context.getString(R.string.first_run_choose_folder_title)
                is FirstRunSetupState.FolderChosenEmpty -> context.getString(R.string.first_run_ready_title)
                is FirstRunSetupState.ExistingDataFound -> context.getString(R.string.first_run_restore_found_title)
                is FirstRunSetupState.RestoreSucceeded -> context.getString(R.string.first_run_restore_success_title)
                is FirstRunSetupState.RestoreFailed -> context.getString(R.string.first_run_restore_failed_title)
                is FirstRunSetupState.FallbackToContinuation -> context.getString(
                    if (currentSetupState.reason == FirstRunFallbackReason.LEGACY_BACKUP_ONLY) {
                        R.string.first_run_legacy_found_title
                    } else {
                        R.string.first_run_no_restore_title
                    }
                )

                null -> context.getString(R.string.first_run_choose_folder_title)
            }
        }
        val dialogBody = when (currentWork) {
            FirstRunWorkStep.CHECKING_FOLDER -> context.getString(R.string.first_run_checking_body)
            FirstRunWorkStep.RESTORING_DATA -> context.getString(R.string.first_run_restoring_body)
            null -> when (currentSetupState) {
                FirstRunSetupState.NoFolderChosen -> context.getString(R.string.first_run_choose_folder_body)
                is FirstRunSetupState.FolderChosenEmpty -> context.getString(
                    if (keepCurrentData) {
                        R.string.first_run_ready_keep_current_body_fmt
                    } else {
                        R.string.first_run_ready_empty_body_fmt
                    },
                    currentSetupState.folderLabel
                )

                is FirstRunSetupState.ExistingDataFound -> context.getString(
                    if (keepCurrentData) {
                        R.string.first_run_restore_found_keep_current_body_fmt
                    } else {
                        R.string.first_run_restore_found_empty_body_fmt
                    },
                    currentSetupState.folderLabel,
                    currentSetupState.evidenceFileNames.joinToString(", ")
                )

                is FirstRunSetupState.RestoreSucceeded -> context.getString(
                    R.string.first_run_restore_success_body_fmt,
                    currentSetupState.folderLabel,
                    currentSetupState.evidenceFileNames.joinToString(", ")
                )

                is FirstRunSetupState.RestoreFailed -> context.getString(
                    if (keepCurrentData) {
                        R.string.first_run_restore_failed_keep_current_body_fmt
                    } else {
                        R.string.first_run_restore_failed_empty_body_fmt
                    },
                    currentSetupState.folderLabel
                )

                is FirstRunSetupState.FallbackToContinuation -> {
                    when (currentSetupState.reason) {
                        FirstRunFallbackReason.LEGACY_BACKUP_ONLY -> context.getString(
                            if (keepCurrentData) {
                                R.string.first_run_legacy_keep_current_body_fmt
                            } else {
                                R.string.first_run_legacy_empty_body_fmt
                            },
                            currentSetupState.folderLabel
                        )

                        FirstRunFallbackReason.USER_SKIPPED_RESTORE -> context.getString(
                            if (keepCurrentData) {
                                R.string.first_run_skip_restore_keep_current_body_fmt
                            } else {
                                R.string.first_run_skip_restore_empty_body_fmt
                            },
                            currentSetupState.folderLabel
                        )

                        FirstRunFallbackReason.RESTORE_FAILED -> context.getString(
                            if (keepCurrentData) {
                                R.string.first_run_restore_failed_keep_current_body_fmt
                            } else {
                                R.string.first_run_restore_failed_empty_body_fmt
                            },
                            currentSetupState.folderLabel
                        )

                        FirstRunFallbackReason.NO_RESTORABLE_DATA_FOUND -> context.getString(
                            if (keepCurrentData) {
                                R.string.first_run_no_restore_keep_current_body_fmt
                            } else {
                                R.string.first_run_no_restore_empty_body_fmt
                            },
                            currentSetupState.folderLabel
                        )
                    }
                }

                null -> context.getString(R.string.first_run_choose_folder_body)
            }
        }

        val confirmLabelRes = when (currentWork) {
            FirstRunWorkStep.CHECKING_FOLDER,
            FirstRunWorkStep.RESTORING_DATA -> null

            null -> when (currentSetupState) {
                FirstRunSetupState.NoFolderChosen -> R.string.first_run_choose_folder_confirm
                is FirstRunSetupState.FolderChosenEmpty,
                is FirstRunSetupState.RestoreSucceeded,
                is FirstRunSetupState.FallbackToContinuation -> R.string.first_run_continue
                is FirstRunSetupState.ExistingDataFound -> R.string.first_run_restore_confirm
                is FirstRunSetupState.RestoreFailed -> R.string.first_run_continue_without_restore
                null -> null
            }
        }

        val confirmAction: (() -> Unit)? = when (currentWork) {
            FirstRunWorkStep.CHECKING_FOLDER,
            FirstRunWorkStep.RESTORING_DATA -> null

            null -> when (currentSetupState) {
                FirstRunSetupState.NoFolderChosen -> ({ treePickerLauncher.launch(null) })

                is FirstRunSetupState.FolderChosenEmpty,
                is FirstRunSetupState.RestoreSucceeded,
                is FirstRunSetupState.FallbackToContinuation -> ({
                    vm.setImportVerificationReport(null)
                    setupState.value = null
                    setupDone.value = true
                })

                is FirstRunSetupState.ExistingDataFound -> ({
                    setupWork.value = FirstRunWorkStep.RESTORING_DATA
                    scope.launch {
                        val restored = runCatching {
                            vm.importBackupBlocking(context)
                        }.getOrDefault(false)
                        setupWork.value = null
                        setupState.value = if (restored) {
                            FirstRunSetupState.RestoreSucceeded(
                                folderLabel = currentSetupState.folderLabel,
                                evidenceFileNames = currentSetupState.evidenceFileNames,
                            )
                        } else {
                            FirstRunSetupState.RestoreFailed(
                                folderLabel = currentSetupState.folderLabel,
                                evidenceFileNames = currentSetupState.evidenceFileNames,
                                continuationMode = currentSetupState.continuationMode,
                            )
                        }
                    }
                })

                is FirstRunSetupState.RestoreFailed -> ({
                    vm.setImportVerificationReport(null)
                    setupState.value = FirstRunSetupState.FallbackToContinuation(
                        folderLabel = currentSetupState.folderLabel,
                        reason = FirstRunFallbackReason.RESTORE_FAILED,
                        continuationMode = currentSetupState.continuationMode,
                        evidenceFileNames = currentSetupState.evidenceFileNames,
                    )
                })

                null -> null
            }
        }

        val dismissLabelRes = when (currentWork) {
            FirstRunWorkStep.CHECKING_FOLDER,
            FirstRunWorkStep.RESTORING_DATA -> null

            null -> when (currentSetupState) {
                is FirstRunSetupState.ExistingDataFound -> R.string.first_run_skip_restore_confirm
                is FirstRunSetupState.RestoreFailed,
                is FirstRunSetupState.FallbackToContinuation -> R.string.first_run_choose_other_folder
                else -> null
            }
        }

        val dismissAction: (() -> Unit)? = when (currentWork) {
            FirstRunWorkStep.CHECKING_FOLDER,
            FirstRunWorkStep.RESTORING_DATA -> null

            null -> when (currentSetupState) {
                is FirstRunSetupState.ExistingDataFound -> ({
                    vm.setImportVerificationReport(null)
                    setupState.value = FirstRunSetupState.FallbackToContinuation(
                        folderLabel = currentSetupState.folderLabel,
                        reason = FirstRunFallbackReason.USER_SKIPPED_RESTORE,
                        continuationMode = currentSetupState.continuationMode,
                        evidenceFileNames = currentSetupState.evidenceFileNames,
                    )
                })

                is FirstRunSetupState.RestoreFailed,
                is FirstRunSetupState.FallbackToContinuation -> ({
                    vm.setImportVerificationReport(null)
                    treePickerLauncher.launch(null)
                })

                else -> null
            }
        }

        AlertDialog(
            onDismissRequest = { /* blocked until explicit choice */ },
            title = { Text(dialogTitle) },
            text = { Text(dialogBody) },
            confirmButton = {
                if (confirmAction != null && confirmLabelRes != null) {
                    TextButton(onClick = confirmAction) {
                        Text(stringResource(confirmLabelRes))
                    }
                }
            },
            dismissButton = dismissAction?.let {
                {
                    TextButton(onClick = it) {
                        Text(stringResource(dismissLabelRes!!))
                    }
                }
            },
        )

        return
    }

    // v423: FAIL-FAST integrity gate. If the DB is inconsistent, we BLOCK the app UI and force recovery.
    if (integrityBlock != null && integrityBlock?.ok == false) {
        val block = integrityBlock
        AlertDialog(
            onDismissRequest = { /* blocked */ },
            title = { Text(block?.blockingTitle ?: stringResource(R.string.integrity_gate_title)) },
            text = {
                val tech = block?.technicalReport.takeIf { BuildConfig.DEBUG }
                Text(
                    if (!tech.isNullOrBlank()) {
                        (block?.blockingBody ?: "") + "\n\n" + tech
                    } else {
                        block?.blockingBody ?: ""
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val ok = vm.tryRecoverFromUserFolder(context)
                    Toast.makeText(
                        context,
                        if (ok) context.getString(R.string.integrity_gate_restore_ok) else context.getString(R.string.integrity_gate_restore_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }) {
                    Text(stringResource(R.string.integrity_gate_restore_from_folder))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    val ok = vm.tryRecoverFromPreImportBackup(context)
                    Toast.makeText(
                        context,
                        if (ok) context.getString(R.string.integrity_gate_restore_ok) else context.getString(R.string.integrity_gate_restore_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }) {
                    Text(stringResource(R.string.integrity_gate_restore_preimport))
                }
            }
        )

        // Hard block: don't render the app while inconsistent.
        return
    }

    if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        exactAlarmRequired &&
        !exactAlarmAllowed.value &&
        !exactAlarmPromptDismissed.value &&
        !showNotificationPermissionRationale.value
    ) {
        AlertDialog(
            onDismissRequest = { exactAlarmPromptDismissed.value = true },
            title = { Text(stringResource(R.string.exact_alarm_permission_title)) },
            text = { Text(stringResource(R.string.exact_alarm_permission_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        TimeFenceTimerScheduler.openExactAlarmSettings(context)
                    }
                ) {
                    Text(stringResource(R.string.timed_tag_alarm_permission_open_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { exactAlarmPromptDismissed.value = true }) {
                    Text(stringResource(R.string.annulla))
                }
            }
        )
    }

    if (
        Build.VERSION.SDK_INT >= 33 &&
        !notificationPermissionGranted.value &&
        showNotificationPermissionRationale.value
    ) {
        AlertDialog(
            onDismissRequest = { showNotificationPermissionRationale.value = false },
            title = { Text(stringResource(R.string.notification_permission_title)) },
            text = { Text(stringResource(R.string.notification_permission_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNotificationPermissionRationale.value = false
                        notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                ) {
                    Text(stringResource(R.string.notification_permission_allow))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNotificationPermissionRationale.value = false }) {
                    Text(stringResource(R.string.notification_permission_not_now))
                }
            }
        )
    }

    AppRoot(
        vm = vm
    )
}

@Composable
private fun SilentFirstRunSetupPlaceholder() {
    Box(modifier = Modifier.fillMaxSize())
}

@OptIn(CapsuleWriteApi::class)
private fun ensureStartupSessionSchemaForLaunch(context: Context) {
    // The NOW screen needs only session tables. Quick Events schema hardening is
    // intentionally deferred so active sessions can render first.
    val schemaChanged = StartupPerfTrace.section("ensure_session_tables") {
        SnapshotSqlite.ensureStartupSessionSchema(context)
    }
    if (!schemaChanged) return
    AuditLogSqlite.insert(
        context = context,
        isSystem = true,
        action = "SESSION_TABLES_ENSURED",
        summary = "Ensured sessions/session_tags tables exist",
        payload = JSONObject().apply {
            put("sessions_table", SnapshotSqlite.SESSIONS_TABLE)
            put("session_tags_table", SnapshotSqlite.SESSION_TAGS_TABLE)
        },
    )
}

private fun ensureDeferredStartupSchemasForLaunch(context: Context) {
    StartupPerfTrace.section("ensure_quick_event_tables_deferred") {
        SnapshotSqlite.ensureStartupQuickEventSchema(context)
    }
}

private fun requiresExactAlarmPermission(nowState: NowUiState, alertsState: AlertsUiState): Boolean {
    val hasTimedSessionAlarm = nowState.runningSessions.any { session ->
        val expectedEndMs = session.expectedEndMs
        session.endMs == null &&
            expectedEndMs != null &&
            expectedEndMs > nowState.nowMs &&
            TimedSessionSupport.findTimedTagMatchForSession(session, nowState.tags)?.notificationType?.let {
                it != TimedTagNotificationType.NONE
            } == true
    }
    val hasTimerAlertRule = alertsState.timeFenceRules.any { rule ->
        !rule.isDeleted &&
            rule.isEnabled &&
            rule.delivery == TimeFenceDelivery.NOTIFICATION &&
            rule.trigger == TimeFenceTrigger.ON_START &&
            rule.timerMinutes > 0
    }
    return hasTimedSessionAlarm || hasTimerAlertRule
}

private fun hasNotificationPermission(context: Context): Boolean {
    return Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
