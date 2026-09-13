// v471
// v461
@file:android.annotation.SuppressLint("LocalContextGetResourceValueCall")

package com.example.multitimetracker.ui
import android.os.Trace
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.unit.dp
import com.example.multitimetracker.ui.util.formatDuration

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ManageHistory
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.QueryBuilder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.DisposableEffect
import com.example.multitimetracker.BuildConfig
import com.example.multitimetracker.MainViewModel
import com.example.multitimetracker.core.session.DefaultSessionCore
import com.example.multitimetracker.model.UiState
import com.example.multitimetracker.model.TimeMachinePeriodSelection
import com.example.multitimetracker.model.TimeMachineTagComparisonRow
import com.example.multitimetracker.model.TimeMachineTagTotalRow
import com.example.multitimetracker.model.effectiveTimeContext
import com.example.multitimetracker.export.BackupFolderStore
import com.example.multitimetracker.capsules.alerts.ui.AlertsScreen
import com.example.multitimetracker.capsules.alerts.state.AlertsUiState
import com.example.multitimetracker.capsules.auditlog.state.AuditLogUiState
import com.example.multitimetracker.capsules.system.ui.AppRootSystemPrefs
import com.example.multitimetracker.capsules.system.ui.DevToolsDialog
import com.example.multitimetracker.capsules.system.ui.DevToolsRuntimeState
import com.example.multitimetracker.capsules.system.ui.buildSessionDiagnosticsReport
import com.example.multitimetracker.capsules.sincewhen.ui.LifePeriodsScreen
import com.example.multitimetracker.capsules.sincewhen.state.SinceWhenUiState
import com.example.multitimetracker.capsules.tags.ui.TagsScreen
import com.example.multitimetracker.capsules.tags.state.TagsUiState
import com.example.multitimetracker.capsules.now.ui.NowScreen
import com.example.multitimetracker.capsules.quickevents.ui.QuickEventsScreen
import com.example.multitimetracker.core.quickevent.QuickEventTarget
import com.example.multitimetracker.capsules.chains.ui.ChainsScreen
import com.example.multitimetracker.capsules.auditlog.ui.AuditLogScreen
import com.example.multitimetracker.capsules.timeline.ui.TimelineScreen
import com.example.multitimetracker.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.Dispatchers
import android.content.ClipData
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import com.example.multitimetracker.ui.components.AppSettingsDialog
import com.example.multitimetracker.ui.components.AlertPopupHost
import com.example.multitimetracker.ui.components.DateTimePickerCommitMode
import com.example.multitimetracker.ui.components.LocalOpenAppMenu
import com.example.multitimetracker.ui.components.MttDateTimePickerDialog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class Tab { NOW, QUICK_EVENTS, TAGS, TIMELINE, DA_QUANDO, ALERT, CHAINS, AUDIT }
private enum class TimeMachinePickerMode { MOMENT, PERIOD }
private enum class TimeMachinePickTarget { MOMENT, START, END }
private enum class DrawerDestination {
    NOW,
    QUICK_EVENTS,
    TAGS,
    TIMELINE,
    TIME_MACHINE,
    SINCE_WHEN,
    ALERTS,
    CHAINS,
    AUDIT_LOG,
    IMPORT,
    EXPORT,
    STATISTICS,
    SETTINGS,
    INFO
}

private data class DrawerItemSpec(
    val destination: DrawerDestination,
    val labelRes: Int,
    val icon: ImageVector
)

private const val TRACE_TAB_TAGS = "mtt_bench_tab_tags"
private const val TRACE_TAB_TIMELINE = "mtt_bench_tab_timeline"
private const val TRACE_TAB_QUICK_EVENTS = "mtt_bench_tab_quick_events"

    // === FEATURE CAPSULE: AppRoot+Navigation+Settings (UI) START ===
@Composable
fun AppRoot(
    vm: MainViewModel,
    hubSessionId: Long? = null,
    onHubSessionDismiss: () -> Unit = {},
    pendingQuickEventTarget: QuickEventTarget? = null,
    onPendingQuickEventTargetConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val view = LocalView.current
    val state by vm.state.collectAsState()
    val tagsState by vm.tagsCapsule.uiState.collectAsState()
    val sinceWhenState by vm.sinceWhenCapsule.uiState.collectAsState()
    val alertsState by vm.alertsCapsule.uiState.collectAsState()
    val auditLogState by vm.auditLogCapsule.uiState.collectAsState()

    val showSeconds = rememberSaveable { mutableStateOf(AppRootSystemPrefs.getShowSeconds(context)) }
    val hideHoursIfZero = rememberSaveable { mutableStateOf(AppRootSystemPrefs.getHideHoursIfZero(context)) }
    val keepScreenOn = rememberSaveable { mutableStateOf(AppRootSystemPrefs.getKeepScreenOn(context)) }

    // Apply "keep screen on" to the hosting view (affects the whole activity).
    DisposableEffect(keepScreenOn.value) {
        val prev = view.keepScreenOn
        view.keepScreenOn = keepScreenOn.value
        onDispose { view.keepScreenOn = prev }
    }

    VarTabScaffold(
        state = state,
        tagsState = tagsState,
        sinceWhenState = sinceWhenState,
        alertsState = alertsState,
        auditLogState = auditLogState,
        vm = vm,
        showSeconds = showSeconds.value,
        hideHoursIfZero = hideHoursIfZero.value,
        onShowSecondsChange = {
            if (state.isReadOnly) return@VarTabScaffold
            showSeconds.value = it
            AppRootSystemPrefs.setShowSeconds(context, it)
        },
        onHideHoursIfZeroChange = {
            if (state.isReadOnly) return@VarTabScaffold
            hideHoursIfZero.value = it
            AppRootSystemPrefs.setHideHoursIfZero(context, it)
        },
        keepScreenOn = keepScreenOn.value,
        onKeepScreenOnChange = {
            if (state.isReadOnly) return@VarTabScaffold
            keepScreenOn.value = it
            AppRootSystemPrefs.setKeepScreenOn(context, it)
        },
        pendingQuickEventTarget = pendingQuickEventTarget,
        onPendingQuickEventTargetConsumed = onPendingQuickEventTargetConsumed,
    )
    val hubSession by produceState<com.example.multitimetracker.model.SessionUi?>(null, hubSessionId) {
        value = hubSessionId?.let { id -> withContext(Dispatchers.IO) { DefaultSessionCore(context).readSessionById(id) } }
    }
    hubSession?.let { session ->
        AlertDialog(
            modifier = Modifier.semantics { contentDescription = "hub-detail-timer/session/${session.id}" },
            onDismissRequest = onHubSessionDismiss,
            title = { Text(stringResource(R.string.modifica_sessione)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(session.title, style = MaterialTheme.typography.titleMedium)
                    Text("#${session.id}")
                    com.gernalix.personalhub.core.hubcontext.HubContextLinks(
                        com.gernalix.personalhub.contracts.database.HubEntityRef("timer", "session", session.id.toString()),
                    )
                }
            },
            confirmButton = { TextButton(onClick = onHubSessionDismiss) { Text(stringResource(R.string.annulla)) } },
        )
    }
}
    // === FEATURE CAPSULE: AppRoot+Navigation+Settings (UI) END ===

@Composable
private fun VarTabScaffold(
    state: UiState,
    tagsState: TagsUiState,
    sinceWhenState: SinceWhenUiState,
    alertsState: AlertsUiState,
    auditLogState: AuditLogUiState,
    vm: MainViewModel,
    showSeconds: Boolean,
    onShowSecondsChange: (Boolean) -> Unit,
    hideHoursIfZero: Boolean,
    onHideHoursIfZeroChange: (Boolean) -> Unit,
    keepScreenOn: Boolean,
    onKeepScreenOnChange: (Boolean) -> Unit,
    pendingQuickEventTarget: QuickEventTarget?,
    onPendingQuickEventTargetConsumed: () -> Unit,
) {
    val tabState = remember { mutableStateOf(Tab.NOW) }
    var showSettings by remember { mutableStateOf(false) }
    var showDevTools by remember { mutableStateOf(false) }
    var showDevReport by remember { mutableStateOf(false) }
    var devReport by remember { mutableStateOf("") }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showStatistics by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var diagnosticsReport by remember { mutableStateOf<String?>(null) }
    var diagnosticsError by remember { mutableStateOf<String?>(null) }
    var showTimeMachineDialog by remember { mutableStateOf(false) }
    var timeMachineDraftMs by remember { mutableStateOf<Long?>(null) }
    var showCompareWithToday by remember { mutableStateOf(false) }
    var selectedPeriod by remember { mutableStateOf<TimeMachinePeriodSelection?>(null) }

    val focusTagIdState = rememberSaveable { mutableStateOf<Long?>(null) }
    val tab = tabState.value
    LaunchedEffect(pendingQuickEventTarget) {
        if (pendingQuickEventTarget != null) tabState.value = Tab.QUICK_EVENTS
    }
    val isCloneBenchmark = remember { BuildConfig.APPLICATION_ID.endsWith(".devicetest") }
    val pendingTrace = remember { mutableStateOf<String?>(null) }
    val stateHolder = rememberSaveableStateHolder()
    var drawerChromeEnabled by remember { mutableStateOf(false) }
    var drawerContentMounted by remember { mutableStateOf(false) }
    val drawerState = remember {
        DrawerState(
            initialValue = DrawerValue.Closed,
            confirmStateChange = { target ->
                drawerChromeEnabled || target == DrawerValue.Closed
            }
        )
    }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        withFrameNanos { }
        drawerState.snapTo(DrawerValue.Closed)
        drawerChromeEnabled = true
    }

    val context = LocalContext.current
    val developerSurfaceEnabled = AppRootSystemPrefs.isDeveloperSurfaceAvailable()
    val effectiveTime = remember(state.nowMs, state.timeMachineTargetMs) { state.effectiveTimeContext() }
    val devToolsRuntimeState = remember(
        tagsState.tasks,
        tagsState.tags,
        tagsState.closedSessions,
        effectiveTime.nowMs,
    ) {
        DevToolsRuntimeState(
            sessionOnlyModeEnabled = true,
            tasks = tagsState.tasks,
            tags = tagsState.tags,
            closedSessions = tagsState.closedSessions,
            effectiveNowMs = effectiveTime.nowMs,
        )
    }
    val diagnosticsLoading = stringResource(R.string.diagnostics_loading)
    val timeMachineFormatter = remember { DateTimeFormatter.ofPattern("dd-MM-yy HH:mm", Locale.getDefault()) }
    var pendingAfterFolderPick by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun beginTabTrace(target: Tab) {
        if (!isCloneBenchmark) return
        val name = when (target) {
            Tab.QUICK_EVENTS -> TRACE_TAB_QUICK_EVENTS
            Tab.TAGS -> TRACE_TAB_TAGS
            Tab.TIMELINE -> TRACE_TAB_TIMELINE
            else -> null
        } ?: return
        if (pendingTrace.value != null) {
            Trace.endSection()
        }
        Trace.beginSection(name)
        pendingTrace.value = name
    }

    LaunchedEffect(tab) {
        if (!isCloneBenchmark) return@LaunchedEffect
        val expected = when (tab) {
            Tab.QUICK_EVENTS -> TRACE_TAB_QUICK_EVENTS
            Tab.TAGS -> TRACE_TAB_TAGS
            Tab.TIMELINE -> TRACE_TAB_TIMELINE
            else -> null
        }
        if (expected != null && pendingTrace.value == expected) {
            Trace.endSection()
            pendingTrace.value = null
        }
    }

    // v435: SAF folder picker usable from Settings (Change SAF folder).
    val safTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            vm.setBackupRootFolder(context, treeUri)
            pendingAfterFolderPick?.invoke()
        }
        pendingAfterFolderPick = null
    }

    val importDbLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            vm.importDatabaseFromUri(context, uri)
        }
    }

    val importVerifyReport by vm.importVerificationReport.collectAsState()
    val persistenceFailureReport by vm.persistenceFailureReport.collectAsState()

    LaunchedEffect(showDiagnostics) {
        if (!showDiagnostics) return@LaunchedEffect
        diagnosticsReport = null
        diagnosticsError = null
        try {
            val nowMs = effectiveTime.nowMs
            // Build on a background dispatcher; report is a pure string.
            val report = buildSessionDiagnosticsReport(context, devToolsRuntimeState, nowMs)
            diagnosticsReport = report
        } catch (t: Throwable) {
            diagnosticsError = t.message ?: t.toString()
        }
    }

    

if (developerSurfaceEnabled && showDevReport) {
    AlertDialog(
        onDismissRequest = { showDevReport = false },
        title = { Text(stringResource(R.string.dev_tools_report_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(devReport)
            }
        },
        confirmButton = {
            TextButton(onClick = { showDevReport = false }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("dev_report", devReport))
                Toast.makeText(context, context.getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
            }) {
                Text(stringResource(R.string.copy))
            }
        }
    )
}

    if (importVerifyReport != null) {
        val report = importVerifyReport ?: ""
        AlertDialog(
            onDismissRequest = { vm.setImportVerificationReport(null) },
            title = { Text(stringResource(R.string.import_verify_title)) },
            text = {
                val scroll = rememberScrollState()
                SelectionContainer {
                    Text(
                        text = report,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(420.dp)
                            .verticalScroll(scroll)
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.setImportVerificationReport(null) }) {
                        Text(stringResource(R.string.chiudi))
                    }
                    TextButton(onClick = {
                        vm.restoreLastPreImportBackup(context)
                    }) {
                        Text(stringResource(R.string.import_verify_restore_backup))
                    }
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(ClipData.newPlainText("import_verify", report))
                        Toast.makeText(context, context.getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
                    }) {
                        Text(stringResource(R.string.copy))
                    }
                    if (developerSurfaceEnabled) {
                        TextButton(onClick = {
                            vm.setImportVerificationReport(null)
                            showDiagnostics = true
                        }) {
                            Text(stringResource(R.string.import_verify_debug))
                        }
                    }
                }
            }
        )
    }



    if (persistenceFailureReport != null) {
        val report = persistenceFailureReport ?: ""
        AlertDialog(
            onDismissRequest = { vm.setPersistenceFailureReport(null) },
            title = { Text(stringResource(R.string.persistence_failure_title)) },
            text = {
                val scroll = rememberScrollState()
                SelectionContainer {
                    Text(
                        text = report,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                            .verticalScroll(scroll)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.setPersistenceFailureReport(null) }) {
                    Text(stringResource(R.string.chiudi))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                    cm?.setPrimaryClip(ClipData.newPlainText("persistence_failure", report))
                    Toast.makeText(context, context.getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
                }) {
                    Text(stringResource(R.string.copy))
                }
            }
        )
    }

    if (developerSurfaceEnabled && showDevTools && AppRootSystemPrefs.isDevModeEnabled(context)) {
    DevToolsDialog(
        state = devToolsRuntimeState,
        onDismiss = { showDevTools = false },
        onShowReport = { report: String ->
            devReport = report
            showDevReport = true
        },
        onReloadFromSnapshot = {
            vm.reloadFromSnapshot(context)
            Toast.makeText(context, context.getString(R.string.dev_tools_reloaded), Toast.LENGTH_SHORT).show()
        }
    )
}

    if (showDiagnostics) {
        AlertDialog(
            onDismissRequest = { showDiagnostics = false },
            title = { Text(stringResource(R.string.diagnostics_title)) },
            text = {
                val scroll = rememberScrollState()
                SelectionContainer {
                    Text(
                        text = diagnosticsError?.let { stringResource(R.string.diagnostics_error_prefix) + "\n\n" + it }
                            ?: (diagnosticsReport ?: diagnosticsLoading),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(420.dp)
                            .verticalScroll(scroll)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val txtToCopy = diagnosticsReport ?: diagnosticsError ?: ""
                        if (txtToCopy.isNotBlank()) {
                            val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                            cm?.setPrimaryClip(android.content.ClipData.newPlainText("diagnostics", txtToCopy))
                            Toast.makeText(context, context.getString(R.string.diagnostics_copied), Toast.LENGTH_SHORT).show()
                        }
                    }
                ) { Text(stringResource(R.string.diagnostics_copy)) }
            },
            dismissButton = {
                TextButton(onClick = { showDiagnostics = false }) {
                    Text(stringResource(R.string.chiudi))
                }
            }
        )
    }

    if (showStatistics) {
        val sessionsCount = remember(tagsState.chronologySessions, tagsState.runningSessions) {
            tagsState.chronologySessions.count { it.deletedAtMs == null } + tagsState.runningSessions.count { it.deletedAtMs == null }
        }
        val tagsCount = remember(tagsState.tags) { tagsState.tags.count { !it.isDeleted && !it.isArchived } }
        val archivedTagsCount = remember(tagsState.tags) { tagsState.tags.count { !it.isDeleted && it.isArchived } }
        val totalMs = remember(tagsState.chronologySessions, tagsState.runningSessions, effectiveTime.nowMs) {
            val closed = tagsState.chronologySessions
                .asSequence()
                .filter { it.deletedAtMs == null }
                // SessionUi.endMs is nullable (null = running). For closed sessions it is non-null.
                .map { ((it.endMs ?: effectiveTime.nowMs) - it.startMs) }
                .map { it.coerceAtLeast(0L) }
                .sum()
            val running = tagsState.runningSessions
                .asSequence()
                .filter { it.deletedAtMs == null }
                .map { effectiveTime.nowMs - it.startMs }
                .map { it.coerceAtLeast(0L) }
                .sum()
            closed + running
        }

        AlertDialog(
            onDismissRequest = { showStatistics = false },
            title = { Text(stringResource(R.string.statistics_title)) },
            text = {
                Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.statistics_sessions, sessionsCount))
                    Text(stringResource(R.string.statistics_tags, tagsCount))
                    Text(stringResource(R.string.statistics_archived_tags, archivedTagsCount))
                    Text(stringResource(R.string.statistics_total_time, formatDuration(totalMs, showSeconds, hideHoursIfZero)))
                }
            },
            confirmButton = {
                TextButton(onClick = { showStatistics = false }) {
                    Text(stringResource(R.string.chiudi))
                }
            }
        )
    }


    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(stringResource(R.string.drawer_info)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.info_app_name))
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) {
                    Text(stringResource(R.string.chiudi))
                }
            }
        )
    }

    if (showCompareWithToday && state.timeMachineTargetMs != null) {
        val rowsState = produceState<List<TimeMachineTagComparisonRow>?>(initialValue = null, state.timeMachineTargetMs, state.nowMs) {
            value = withContext(Dispatchers.Default) { vm.buildCompareWithTodayRows() }
        }
        TimeMachineCompareDialog(
            selectedDayLabel = vm.currentTimeMachineDayLabel().orEmpty(),
            rows = rowsState.value,
            showSeconds = showSeconds,
            hideHoursIfZero = hideHoursIfZero,
            onDismiss = { showCompareWithToday = false }
        )
    }

    selectedPeriod?.let { period ->
        val rowsState = produceState<List<TimeMachineTagTotalRow>?>(initialValue = null, period, state.nowMs) {
            value = withContext(Dispatchers.Default) { vm.buildPeriodTagTotals(period) }
        }
        TimeMachinePeriodTotalsDialog(
            period = period,
            rows = rowsState.value,
            showSeconds = showSeconds,
            hideHoursIfZero = hideHoursIfZero,
            onDismiss = { selectedPeriod = null }
        )
    }

    val timeMachineBannerText = remember(state.timeMachineTargetMs) {
        state.timeMachineTargetMs?.let { targetMs ->
            val formatted = timeMachineFormatter.format(Instant.ofEpochMilli(targetMs).atZone(ZoneId.systemDefault()))
            context.getString(R.string.time_machine_banner, formatted)
        }
    }
    val drawerGroups = remember {
        listOf(
            listOf(
                DrawerItemSpec(DrawerDestination.NOW, R.string.now, Icons.Filled.Timer),
                DrawerItemSpec(DrawerDestination.QUICK_EVENTS, R.string.quick_events, Icons.Filled.Event),
                DrawerItemSpec(DrawerDestination.TAGS, R.string.tags, Icons.AutoMirrored.Filled.Label),
                DrawerItemSpec(DrawerDestination.TIMELINE, R.string.chronology, Icons.Filled.History)
            ),
            listOf(
                DrawerItemSpec(DrawerDestination.TIME_MACHINE, R.string.time_machine_title, Icons.Filled.ManageHistory),
                DrawerItemSpec(DrawerDestination.SINCE_WHEN, R.string.tab_da_quando, Icons.Filled.QueryBuilder),
                DrawerItemSpec(DrawerDestination.ALERTS, R.string.alert, Icons.Filled.Notifications),
                DrawerItemSpec(DrawerDestination.CHAINS, R.string.catene, Icons.Filled.Link),
                DrawerItemSpec(DrawerDestination.AUDIT_LOG, R.string.audit_log, Icons.AutoMirrored.Filled.List)
            ),
            listOf(
                DrawerItemSpec(DrawerDestination.IMPORT, R.string.cd_import, Icons.Filled.CloudDownload),
                DrawerItemSpec(DrawerDestination.EXPORT, R.string.cd_export, Icons.Filled.CloudUpload),
                DrawerItemSpec(DrawerDestination.STATISTICS, R.string.statistics, Icons.Filled.Assessment),
                DrawerItemSpec(DrawerDestination.SETTINGS, R.string.cd_settings, Icons.Filled.Settings),
                DrawerItemSpec(DrawerDestination.INFO, R.string.drawer_info, Icons.Filled.Info)
            )
        )
    }

    fun isDrawerItemSelected(destination: DrawerDestination): Boolean {
        return when (destination) {
            DrawerDestination.NOW -> tab == Tab.NOW
            DrawerDestination.QUICK_EVENTS -> tab == Tab.QUICK_EVENTS
            DrawerDestination.TAGS -> tab == Tab.TAGS
            DrawerDestination.TIMELINE -> tab == Tab.TIMELINE
            DrawerDestination.SINCE_WHEN -> tab == Tab.DA_QUANDO
            DrawerDestination.ALERTS -> tab == Tab.ALERT
            DrawerDestination.CHAINS -> tab == Tab.CHAINS
            DrawerDestination.AUDIT_LOG -> tab == Tab.AUDIT
            DrawerDestination.TIME_MACHINE -> state.isReadOnly
            else -> false
        }
    }

    fun isDrawerItemEnabled(destination: DrawerDestination): Boolean {
        return when (destination) {
            DrawerDestination.IMPORT -> !state.isReadOnly
            else -> true
        }
    }

    fun openDrawerDestination(destination: DrawerDestination) {
        when (destination) {
            DrawerDestination.NOW -> tabState.value = Tab.NOW
            DrawerDestination.QUICK_EVENTS -> tabState.value = Tab.QUICK_EVENTS
            DrawerDestination.TAGS -> {
                beginTabTrace(Tab.TAGS)
                tabState.value = Tab.TAGS
            }
            DrawerDestination.TIMELINE -> {
                beginTabTrace(Tab.TIMELINE)
                tabState.value = Tab.TIMELINE
            }
            DrawerDestination.TIME_MACHINE -> {
                if (state.isReadOnly) {
                    vm.exitTimeMachine()
                } else {
                    timeMachineDraftMs = effectiveTime.nowMs
                    showTimeMachineDialog = true
                }
            }
            DrawerDestination.SINCE_WHEN -> tabState.value = Tab.DA_QUANDO
            DrawerDestination.ALERTS -> tabState.value = Tab.ALERT
            DrawerDestination.CHAINS -> tabState.value = Tab.CHAINS
            DrawerDestination.AUDIT_LOG -> tabState.value = Tab.AUDIT
            DrawerDestination.IMPORT, DrawerDestination.EXPORT ->
                com.gernalix.personalhub.core.database.DatabaseNavigation.open(context)
            DrawerDestination.STATISTICS -> showStatistics = true
            DrawerDestination.SETTINGS -> showSettings = true
            DrawerDestination.INFO -> showInfo = true
        }
    }

    CompositionLocalProvider(
        LocalOpenAppMenu provides {
            drawerChromeEnabled = true
            drawerContentMounted = true
            scope.launch {
                withFrameNanos { }
                drawerState.open()
            }
        }
    ) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = drawerChromeEnabled,
            drawerContent = {
                if (drawerContentMounted) {
                    ModalDrawerSheet(
                        modifier = Modifier
                            .width(304.dp)
                            .widthIn(max = 336.dp)
                            .verticalScroll(rememberScrollState()),
                        drawerContainerColor = MaterialTheme.colorScheme.surface,
                        drawerContentColor = MaterialTheme.colorScheme.onSurface
                    ) {
                        Spacer(Modifier.height(18.dp))
                        drawerGroups.forEachIndexed { groupIndex, group ->
                            if (groupIndex > 0) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                            group.forEach { item ->
                                val itemEnabled = isDrawerItemEnabled(item.destination)
                                NavigationDrawerItem(
                                    selected = isDrawerItemSelected(item.destination),
                                    onClick = {
                                        if (itemEnabled) {
                                            scope.launch {
                                                drawerState.close()
                                                drawerContentMounted = false
                                            }
                                            openDrawerDestination(item.destination)
                                        } else {
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.drawer_read_only_unavailable),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    },
                                    icon = {
                                        Icon(
                                            imageVector = item.icon,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(24.dp)
                                                .alpha(if (itemEnabled) 1f else 0.38f)
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = stringResource(item.labelRes),
                                            style = MaterialTheme.typography.bodyLarge,
                                            modifier = Modifier.alpha(if (itemEnabled) 1f else 0.38f)
                                        )
                                    },
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                    }
                } else {
                    Spacer(Modifier.width(0.dp))
                }
            }
        ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    if (timeMachineBannerText != null) {
                        Column {
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .statusBarsPadding()
                                    .clickable { vm.exitTimeMachine() }
                            ) {
                                Text(
                                    text = timeMachineBannerText,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                OutlinedButton(onClick = { showCompareWithToday = true }) {
                                    Text(stringResource(R.string.time_machine_compare_with_today))
                                }
                            }
                        }
                    }
                },
                bottomBar = {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                        }
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            tonalElevation = 2.dp
                        ) {
                            NavigationBarItem(
                                selected = tab == Tab.NOW,
                                onClick = { tabState.value = Tab.NOW },
                                icon = { Icon(Icons.Filled.Timer, contentDescription = stringResource(R.string.tab_now)) },
                                label = { Text(stringResource(R.string.tab_now)) }
                            )
                            NavigationBarItem(
                                selected = tab == Tab.QUICK_EVENTS,
                                onClick = {
                                    beginTabTrace(Tab.QUICK_EVENTS)
                                    tabState.value = Tab.QUICK_EVENTS
                                },
                                icon = { Icon(Icons.Filled.Event, contentDescription = stringResource(R.string.quick_events)) },
                                label = { Text(stringResource(R.string.quick_events)) }
                            )
                            NavigationBarItem(
                                selected = tab == Tab.TAGS,
                                onClick = {
                                    beginTabTrace(Tab.TAGS)
                                    tabState.value = Tab.TAGS
                                },
                                icon = { Icon(Icons.AutoMirrored.Filled.Label, contentDescription = stringResource(R.string.tags)) },
                                label = { Text(stringResource(R.string.tags)) }
                            )
                            NavigationBarItem(
                                selected = tab == Tab.TIMELINE,
                                onClick = {
                                    beginTabTrace(Tab.TIMELINE)
                                    tabState.value = Tab.TIMELINE
                                },
                                icon = { Icon(Icons.Filled.History, contentDescription = stringResource(R.string.chronology)) },
                                label = { Text(stringResource(R.string.chronology)) }
                            )
                        }
                    }
                }
            ) { inner ->
                if (showSettings) {
                    AppSettingsDialog(
                        keepScreenOn = keepScreenOn,
                        onKeepScreenOnChange = onKeepScreenOnChange,
                        showSeconds = showSeconds,
                        onShowSecondsChange = onShowSecondsChange,
                        hideHoursIfZero = hideHoursIfZero,
                        onHideHoursIfZeroChange = onHideHoursIfZeroChange,
                        onChangeSafFolder = {
                            if (!state.isReadOnly) {
                                safTreeLauncher.launch(null)
                            }
                        },
                        onDismiss = { showSettings = false }
                    )
                }

                when (tab) {
                    Tab.NOW -> stateHolder.SaveableStateProvider("now") {
                        NowScreen(
                            modifier = Modifier.padding(inner),
                            capsule = vm.nowCapsule,
                            onOpenTag = { tagId ->
                                focusTagIdState.value = tagId
                                tabState.value = Tab.TAGS
                            },
                            onOpenStatistics = { showStatistics = true },
                            onOpenDiagnostics = { showDiagnostics = true },
                            showSeconds = showSeconds,
                            onShowSecondsChange = onShowSecondsChange,
                            hideHoursIfZero = hideHoursIfZero,
                            onHideHoursIfZeroChange = onHideHoursIfZeroChange,
                            keepScreenOn = keepScreenOn,
                            onKeepScreenOnChange = onKeepScreenOnChange
                        )
                    }
                    Tab.QUICK_EVENTS -> stateHolder.SaveableStateProvider("quick_events") {
                        QuickEventsScreen(
                            modifier = Modifier.padding(inner),
                            capsule = vm.quickEventsCapsule,
                            onOpenTag = { tagId ->
                                focusTagIdState.value = tagId
                                tabState.value = Tab.TAGS
                            },
                            onAddLifePeriod = vm.sinceWhenCapsule::addLifePeriod,
                            showSeconds = showSeconds,
                            hideHoursIfZero = hideHoursIfZero,
                            pendingWidgetTarget = pendingQuickEventTarget,
                            onPendingWidgetTargetConsumed = onPendingQuickEventTargetConsumed,
                        )
                    }
                    Tab.TAGS -> stateHolder.SaveableStateProvider("tags") {
                        TagsScreen(
                            modifier = Modifier.padding(inner),
                            state = tagsState,
                            capsule = vm.tagsCapsule,
                            initialOpenedTagId = focusTagIdState.value,
                            onConsumedInitialOpenedTagId = { focusTagIdState.value = null },
                            showSeconds = showSeconds,
                            hideHoursIfZero = hideHoursIfZero
                        )
                    }
                    Tab.TIMELINE -> stateHolder.SaveableStateProvider("timeline") {
                        TimelineScreen(
                            capsule = vm.timelineCapsule,
                            showSeconds = showSeconds,
                            hideHoursIfZero = hideHoursIfZero,
                            modifier = Modifier.padding(inner)
                        )
                    }
                    Tab.DA_QUANDO -> stateHolder.SaveableStateProvider("da_quando") {
                        LifePeriodsScreen(
                            modifier = Modifier.padding(inner),
                            state = sinceWhenState,
                            onAddPeriod = vm.sinceWhenCapsule::addLifePeriod,
                            onUpdatePeriod = vm.sinceWhenCapsule::updateLifePeriod,
                            onDeletePeriod = vm.sinceWhenCapsule::deleteLifePeriod,
                            onEndSelectedNow = vm.sinceWhenCapsule::endSelectedNow,
                        )
                    }
                    Tab.ALERT -> stateHolder.SaveableStateProvider("alert") {
                        AlertsScreen(
                            modifier = Modifier.padding(inner),
                            state = alertsState,
                            onAddTimeFenceRule = { msg, trigger, scope, matchMode, tagIds, cooldownMs, delivery, randomEnabled, randomCount, randomWindow ->
                                vm.alertsCapsule.addTimeFenceRule(
                                    message = msg,
                                    trigger = trigger,
                                    delivery = delivery,
                                    scope = scope,
                                    matchMode = matchMode,
                                    tagIds = tagIds,
                                    cooldownMs = cooldownMs,
                                    randomAlertsEnabled = randomEnabled,
                                    randomAlertsCount = randomCount,
                                    randomAlertsWindow = randomWindow,
                                )
                            },
                            onUpdateTimeFenceRule = { id, msg, trigger, scope, matchMode, tagIds, cooldownMs, delivery, randomEnabled, randomCount, randomWindow ->
                                vm.alertsCapsule.updateTimeFenceRule(
                                    ruleId = id,
                                    message = msg,
                                    trigger = trigger,
                                    delivery = delivery,
                                    scope = scope,
                                    matchMode = matchMode,
                                    tagIds = tagIds,
                                    cooldownMs = cooldownMs,
                                    randomAlertsEnabled = randomEnabled,
                                    randomAlertsCount = randomCount,
                                    randomAlertsWindow = randomWindow,
                                )
                            },
                            onDeleteTimeFenceRule = vm.alertsCapsule::deleteTimeFenceRule,
                            onRestoreTimeFenceRule = vm.alertsCapsule::restoreTimeFenceRule,
                            onPurgeTimeFenceRule = vm.alertsCapsule::purgeTimeFenceRule,
                            onPurgeAllDeletedTimeFenceRules = vm.alertsCapsule::purgeAllDeletedTimeFenceRules,
                            onSetTimeFenceRuleEnabled = vm.alertsCapsule::setTimeFenceRuleEnabled
                        )
                    }
                    Tab.CHAINS -> stateHolder.SaveableStateProvider("chains") {
                        ChainsScreen(
                            capsule = vm.chainsCapsule,
                            modifier = Modifier.padding(inner)
                        )
                    }
                    Tab.AUDIT -> stateHolder.SaveableStateProvider("audit") {
                        AuditLogScreen(
                            capsule = vm.auditLogCapsule,
                            state = auditLogState,
                            onOpenDiagnostics = if (developerSurfaceEnabled) {
                                { showDiagnostics = true }
                            } else {
                                null
                            },
                            onOpenDevTools = if (developerSurfaceEnabled) {
                                { showDevTools = true }
                            } else {
                                null
                            },
                            modifier = Modifier.padding(inner)
                        )
                    }
                }

                AlertPopupHost(
                    prompts = alertsState.preFencePrompts,
                    onDismiss = vm.alertsCapsule::dismissPreFencePrompt
                )
            }
        }
        }
    }
    if (showTimeMachineDialog) {
        TimeMachineDialog(
            initialMs = timeMachineDraftMs ?: effectiveTime.nowMs,
            onDismiss = {
                timeMachineDraftMs = null
                showTimeMachineDialog = false
            },
            onConfirmMoment = { targetMs ->
                vm.enterTimeMachine(minOf(targetMs, effectiveTime.liveNowMs))
                timeMachineDraftMs = null
                showTimeMachineDialog = false
            },
            onConfirmPeriod = { period ->
                selectedPeriod = period
                timeMachineDraftMs = null
                showTimeMachineDialog = false
            }
        )
    }
}

@Composable
private fun TimeMachineDialog(
    initialMs: Long,
    onDismiss: () -> Unit,
    onConfirmMoment: (Long) -> Unit,
    onConfirmPeriod: (TimeMachinePeriodSelection) -> Unit
) {
    val zoneId = remember { ZoneId.systemDefault() }
    val formatter = remember { DateTimeFormatter.ofPattern("dd-MM-yy HH:mm", Locale.getDefault()) }
    var mode by rememberSaveable { mutableStateOf(TimeMachinePickerMode.MOMENT.name) }
    var selectedMomentMs by remember { mutableStateOf(initialMs) }
    var selectedStartMs by remember { mutableStateOf(initialMs) }
    var selectedEndMs by remember { mutableStateOf(initialMs + 60 * 60 * 1000L) }
    var pickTarget by remember { mutableStateOf<TimeMachinePickTarget?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.time_machine_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val pickerMode = runCatching { TimeMachinePickerMode.valueOf(mode) }
                    .getOrElse { TimeMachinePickerMode.MOMENT }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = pickerMode == TimeMachinePickerMode.MOMENT,
                        onClick = { mode = TimeMachinePickerMode.MOMENT.name },
                        label = { Text(stringResource(R.string.time_machine_mode_moment)) }
                    )
                    FilterChip(
                        selected = pickerMode == TimeMachinePickerMode.PERIOD,
                        onClick = { mode = TimeMachinePickerMode.PERIOD.name },
                        label = { Text(stringResource(R.string.time_machine_mode_period)) }
                    )
                }

                if (pickerMode == TimeMachinePickerMode.MOMENT) {
                    val formattedTarget = formatter.format(Instant.ofEpochMilli(selectedMomentMs).atZone(zoneId))
                    Text(stringResource(R.string.time_machine_picker_label, formattedTarget))
                    OutlinedButton(onClick = { pickTarget = TimeMachinePickTarget.MOMENT }) {
                        Text(stringResource(R.string.time_machine_pick_datetime))
                    }
                } else {
                    val formattedStart = formatter.format(Instant.ofEpochMilli(selectedStartMs).atZone(zoneId))
                    val formattedEnd = formatter.format(Instant.ofEpochMilli(selectedEndMs).atZone(zoneId))
                    Text(stringResource(R.string.time_machine_period_start, formattedStart))
                    OutlinedButton(onClick = { pickTarget = TimeMachinePickTarget.START }) {
                        Text(stringResource(R.string.time_machine_pick_start))
                    }
                    Text(stringResource(R.string.time_machine_period_end, formattedEnd))
                    OutlinedButton(onClick = { pickTarget = TimeMachinePickTarget.END }) {
                        Text(stringResource(R.string.time_machine_pick_end))
                    }
                    if (selectedEndMs <= selectedStartMs) {
                        Text(
                            text = stringResource(R.string.time_machine_period_invalid),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            val pickerMode = runCatching { TimeMachinePickerMode.valueOf(mode) }
                .getOrElse { TimeMachinePickerMode.MOMENT }
            TextButton(
                onClick = {
                    if (pickerMode == TimeMachinePickerMode.MOMENT) {
                        onConfirmMoment(selectedMomentMs)
                    } else {
                        onConfirmPeriod(
                            TimeMachinePeriodSelection(
                                startMs = minOf(selectedStartMs, selectedEndMs),
                                endMs = maxOf(selectedStartMs, selectedEndMs)
                            )
                        )
                    }
                },
                enabled = pickerMode == TimeMachinePickerMode.MOMENT || selectedEndMs > selectedStartMs
            ) {
                Text(
                    stringResource(
                        if (pickerMode == TimeMachinePickerMode.MOMENT) {
                            R.string.time_machine_enter_moment
                        } else {
                            R.string.time_machine_show_period
                        }
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.annulla))
            }
        }
    )

    pickTarget?.let { target ->
        MttDateTimePickerDialog(
            title = stringResource(
                when (target) {
                    TimeMachinePickTarget.MOMENT -> R.string.time_machine_pick_datetime
                    TimeMachinePickTarget.START -> R.string.time_machine_pick_start
                    TimeMachinePickTarget.END -> R.string.time_machine_pick_end
                }
            ),
            initialTimestampMs = when (target) {
                TimeMachinePickTarget.MOMENT -> selectedMomentMs
                TimeMachinePickTarget.START -> selectedStartMs
                TimeMachinePickTarget.END -> selectedEndMs
            },
            commitMode = DateTimePickerCommitMode.END_OF_MINUTE,
            onDismiss = { pickTarget = null },
            onConfirm = { picked ->
                when (target) {
                    TimeMachinePickTarget.MOMENT -> selectedMomentMs = picked
                    TimeMachinePickTarget.START -> selectedStartMs = picked
                    TimeMachinePickTarget.END -> selectedEndMs = picked
                }
                pickTarget = null
            }
        )
    }
}

@Composable
private fun TimeMachinePeriodTotalsDialog(
    period: TimeMachinePeriodSelection,
    rows: List<TimeMachineTagTotalRow>?,
    showSeconds: Boolean,
    hideHoursIfZero: Boolean,
    onDismiss: () -> Unit
) {
    val formatter = remember { DateTimeFormatter.ofPattern("dd-MM-yy HH:mm", Locale.getDefault()) }
    val zoneId = remember { ZoneId.systemDefault() }
    val start = remember(period.startMs) { formatter.format(Instant.ofEpochMilli(period.startMs).atZone(zoneId)) }
    val end = remember(period.endMs) { formatter.format(Instant.ofEpochMilli(period.endMs).atZone(zoneId)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.time_machine_mode_period)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(stringResource(R.string.time_machine_period_range, start, end))
                when {
                    rows == null -> Text(stringResource(R.string.loading))
                    rows.isEmpty() -> Text(stringResource(R.string.time_machine_no_period_totals))
                    else -> rows.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = row.tagName,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(formatDuration(row.totalMs, showSeconds, hideHoursIfZero))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.chiudi))
            }
        }
    )
}

@Composable
private fun TimeMachineCompareDialog(
    selectedDayLabel: String,
    rows: List<TimeMachineTagComparisonRow>?,
    showSeconds: Boolean,
    hideHoursIfZero: Boolean,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.time_machine_compare_with_today)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(stringResource(R.string.time_machine_compare_range, selectedDayLabel))
                when {
                    rows == null -> Text(stringResource(R.string.loading))
                    rows.isEmpty() -> Text(stringResource(R.string.time_machine_compare_empty))
                    else -> rows.forEach { row ->
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(row.tagName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(
                                    R.string.time_machine_compare_selected_day,
                                    formatDuration(row.selectedDayTotalMs, showSeconds, hideHoursIfZero)
                                )
                            )
                            Text(
                                stringResource(
                                    R.string.time_machine_compare_today,
                                    formatDuration(row.todayTotalMs, showSeconds, hideHoursIfZero)
                                )
                            )
                            Text(
                                stringResource(
                                    R.string.time_machine_compare_difference,
                                    signedDurationText(row.differenceMs, showSeconds, hideHoursIfZero)
                                )
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.chiudi))
            }
        }
    )
}

private fun signedDurationText(
    durationMs: Long,
    showSeconds: Boolean,
    hideHoursIfZero: Boolean
): String {
    val sign = when {
        durationMs > 0L -> "+"
        durationMs < 0L -> "-"
        else -> "0 "
    }
    if (durationMs == 0L) {
        return formatDuration(0L, showSeconds, hideHoursIfZero)
    }
    return sign + formatDuration(kotlin.math.abs(durationMs), showSeconds, hideHoursIfZero)
}
