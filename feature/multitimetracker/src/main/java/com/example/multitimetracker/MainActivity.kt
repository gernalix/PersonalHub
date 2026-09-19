package com.example.multitimetracker

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.multitimetracker.capsules.alerts.state.AlertsUiState
import com.example.multitimetracker.capsules.now.state.NowUiState
import com.example.multitimetracker.core.quickevent.QuickEventTarget
import com.example.multitimetracker.perf.StartupPerfTrace
import com.example.multitimetracker.persistence.SnapshotSqlite
import com.example.multitimetracker.ui.AppRoot
import com.example.multitimetracker.ui.theme.MultiTimeTrackerTheme
import com.example.multitimetracker.widget.QuickEventWidgetDeepLink
import com.example.multitimetracker.widget.QuickSessionWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var hubSessionId by mutableStateOf<Long?>(null)
    private var pendingQuickEventTarget by mutableStateOf<QuickEventTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        StartupPerfTrace.activityOnCreateStart()
        super.onCreate(savedInstanceState)
        updateIntentState(intent)
        enableEdgeToEdge()
        setContent {
            MultiTimeTrackerTheme {
                MultiTimeTrackerApp(
                    hubSessionId = hubSessionId,
                    onHubSessionDismiss = { hubSessionId = null },
                    pendingQuickEventTarget = pendingQuickEventTarget,
                    onPendingQuickEventTargetConsumed = { pendingQuickEventTarget = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateIntentState(intent)
    }

    private fun updateIntentState(intent: Intent?) {
        hubSessionId = intent.hubSessionId()
        pendingQuickEventTarget = QuickEventWidgetDeepLink.requestFrom(intent)
    }
}

private fun Intent?.hubSessionId(): Long? = this?.data
    ?.takeIf { it.scheme == "personalhub" && it.host == "module" && it.path == "/timer" }
    ?.getQueryParameter("sessionId")?.toLongOrNull()

@Composable
private fun MultiTimeTrackerApp(
    hubSessionId: Long?,
    onHubSessionDismiss: () -> Unit,
    pendingQuickEventTarget: QuickEventTarget?,
    onPendingQuickEventTargetConsumed: () -> Unit,
    vm: MainViewModel = viewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val alertsState by vm.alertsCapsule.uiState.collectAsState()
    val nowState by vm.nowCapsule.uiState.collectAsState()
    val integrityBlock by vm.integrityBlock.collectAsState()
    val notificationPermissionGranted = remember { mutableStateOf(hasNotificationPermission(context)) }
    val showNotificationPermissionRationale = remember { mutableStateOf(false) }
    val exactAlarmAllowed = remember { mutableStateOf(TimeFenceTimerScheduler.canScheduleExactAlarms(context)) }
    val exactAlarmPromptDismissed = remember { mutableStateOf(false) }

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
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationPermissionGranted.value = it
    }

    LaunchedEffect(Unit) {
        val app = context.applicationContext
        withContext(Dispatchers.IO) {
            SnapshotSqlite.ensureStartupSessionSchema(app)
            val fastHomeApplied = vm.loadStartupHomeFromSessionTables(app)
            vm.initialize(app, fastHomeAlreadyApplied = fastHomeApplied)
            SnapshotSqlite.ensureStartupQuickEventSchema(app)
        }
        StartupPerfTrace.firstFrame()
        android.view.Choreographer.getInstance().postFrameCallback {
            android.view.Choreographer.getInstance().postFrameCallback {
                com.example.multitimetracker.api.TimerStartupApi.signalFirstUsableScreen()
            }
        }
        if (Build.VERSION.SDK_INT >= 33 && !notificationPermissionGranted.value) {
            showNotificationPermissionRationale.value = true
        }
    }

    val latestVm = rememberUpdatedState(vm)
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == QuickSessionWidgetProvider.ACTION_SNAPSHOT_CHANGED) {
                    latestVm.value.reloadFromSnapshot(context)
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(QuickSessionWidgetProvider.ACTION_SNAPSHOT_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    if (integrityBlock?.ok == false) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(integrityBlock?.blockingTitle ?: stringResource(R.string.integrity_gate_title)) },
            text = { Text(integrityBlock?.blockingBody ?: stringResource(R.string.integrity_gate_body)) },
            confirmButton = {},
        )
        return
    }

    val exactAlarmRequired = requiresExactAlarmPermission(nowState, alertsState)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && exactAlarmRequired && !exactAlarmAllowed.value && !exactAlarmPromptDismissed.value) {
        AlertDialog(
            onDismissRequest = { exactAlarmPromptDismissed.value = true },
            title = { Text(stringResource(R.string.exact_alarm_permission_title)) },
            text = { Text(stringResource(R.string.exact_alarm_permission_body)) },
            confirmButton = {
                TextButton(onClick = { TimeFenceTimerScheduler.openExactAlarmSettings(context) }) {
                    Text(stringResource(R.string.timed_tag_alarm_permission_open_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { exactAlarmPromptDismissed.value = true }) {
                    Text(stringResource(R.string.annulla))
                }
            },
        )
    }

    if (Build.VERSION.SDK_INT >= 33 && !notificationPermissionGranted.value && showNotificationPermissionRationale.value) {
        AlertDialog(
            onDismissRequest = { showNotificationPermissionRationale.value = false },
            title = { Text(stringResource(R.string.notification_permission_title)) },
            text = { Text(stringResource(R.string.notification_permission_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showNotificationPermissionRationale.value = false
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text(stringResource(R.string.notification_permission_allow)) }
            },
            dismissButton = {
                TextButton(onClick = { showNotificationPermissionRationale.value = false }) {
                    Text(stringResource(R.string.notification_permission_not_now))
                }
            },
        )
    }

    AppRoot(vm, hubSessionId, onHubSessionDismiss, pendingQuickEventTarget, onPendingQuickEventTargetConsumed)
}

private fun requiresExactAlarmPermission(nowState: NowUiState, alertsState: AlertsUiState): Boolean {
    val timed = nowState.runningSessions.any { session ->
        val end = session.expectedEndMs
        session.endMs == null && end != null && end > nowState.nowMs &&
            TimedSessionSupport.findTimedTagMatchForSession(session, nowState.tags)?.notificationType?.let {
                it != com.example.multitimetracker.model.TimedTagNotificationType.NONE
            } == true
    }
    return timed || alertsState.timeFenceRules.any { it.isEnabled && !it.isDeleted }
}

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
