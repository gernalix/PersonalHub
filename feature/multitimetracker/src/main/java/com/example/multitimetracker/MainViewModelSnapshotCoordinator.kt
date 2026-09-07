// v471
// v470
package com.example.multitimetracker

import android.content.Context
import android.widget.Toast
import com.example.multitimetracker.core.contracts.TaggedSessionRecord
import com.example.multitimetracker.core.contracts.ClosedSessionRecord
import com.example.multitimetracker.core.session.AutoConsistencyCore
import com.example.multitimetracker.core.session.SessionCore
import com.example.multitimetracker.export.AuthoritativeExportPayload
import com.example.multitimetracker.export.AuthoritativeExportPayloadBuilder
import com.example.multitimetracker.export.BackupFolderStore
import com.example.multitimetracker.export.buildSessionOnlyRuntimeTasks
import com.example.multitimetracker.capsules.chains.public.ChainsSnapshot
import com.example.multitimetracker.capsules.quickevents.public.QuickEventsSnapshot
import com.example.multitimetracker.capsules.sessions.public.SessionsSnapshotReadModel
import com.example.multitimetracker.capsules.sessions.public.SessionsRuntimeState
import com.example.multitimetracker.capsules.tags.public.TagsSnapshotProjection
import com.example.multitimetracker.model.LifePeriod
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.model.Task
import com.example.multitimetracker.model.TimeEngine
import com.example.multitimetracker.model.TimeFenceRule
import com.example.multitimetracker.model.UiState
import com.example.multitimetracker.model.HomeLoadState
import com.example.multitimetracker.model.homeLoadStateFor
import com.example.multitimetracker.perf.StartupPerfTrace
import com.example.multitimetracker.persistence.PersistentMutationTracker
import com.example.multitimetracker.persistence.PersistentSaveOrigin
import com.example.multitimetracker.persistence.PersistentSnapshotSaveGate
import com.example.multitimetracker.persistence.SnapshotSqlite
import com.example.multitimetracker.persistence.SnapshotStore
import com.example.multitimetracker.util.AppRestarter
import com.example.multitimetracker.util.CapsuleWriteApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(CapsuleWriteApi::class)
internal class MainViewModelSnapshotCoordinator(
    private val currentAppVersionCodeLong: (Context) -> Long,
    private val autoConsistencyRevision: Long,
    private val viewModelScope: CoroutineScope,
    private val appContext: () -> Context?,
    private val readState: () -> UiState,
    private val updateState: ((UiState) -> UiState) -> Unit,
    private val sessionCore: (Context) -> SessionCore,
    private val importRuntimeSnapshot: (
        tasks: List<Task>,
        tags: List<Tag>,
        closedSessions: List<ClosedSessionRecord>,
        tagSessions: List<TaggedSessionRecord>,
        runtimeSnapshot: TimeEngine.RuntimeSnapshot,
    ) -> Unit,
    private val loadImportedSnapshot: (
        tasks: List<Task>,
        tags: List<Tag>,
        importedClosedSessionRecords: List<ClosedSessionRecord>,
        importedTaggedSessionRecords: List<TaggedSessionRecord>,
        runtimeSnapshot: TimeEngine.RuntimeSnapshot?,
    ) -> Unit,
    private val exportRuntimeSnapshot: () -> TimeEngine.RuntimeSnapshot,
    private val buildAuthoritativeExportPayload: (Context?) -> AuthoritativeExportPayload,
    private val readLifePeriods: () -> List<LifePeriod>,
    private val replaceLifePeriods: (List<LifePeriod>) -> Unit,
    private val readTimeFenceRules: () -> List<TimeFenceRule>,
    private val replaceTimeFenceRules: (List<TimeFenceRule>) -> Unit,
    private val readQuickEvents: () -> QuickEventsSnapshot,
    private val replaceQuickEvents: (QuickEventsSnapshot) -> Unit,
    private val readChains: () -> ChainsSnapshot,
    private val replaceChains: (ChainsSnapshot) -> Unit,
    private val readSessionsRuntime: () -> SessionsRuntimeState,
    private val replaceSessionsRuntime: (SessionsRuntimeState) -> Unit,
    private val readTags: () -> List<Tag>,
    private val replaceTags: (List<Tag>) -> Unit,
    private val prepareSnapshotTags: (SnapshotStore.Snapshot) -> TagsSnapshotProjection,
    private val mergeSnapshotTags: (canonicalTags: List<Tag>, runtimeTags: List<Tag>) -> List<Tag>,
    private val selectCanonicalTags: (currentTags: List<Tag>, persistedCanonicalTags: List<Tag>) -> List<Tag>,
    private val selectTagsForAuthoritativeRead: (
        context: Context,
        currentTags: List<Tag>,
        persistedCanonicalTags: List<Tag>,
    ) -> List<Tag>,
    private val rememberRuntimeCanonicalTags: (List<Tag>) -> Unit,
    private val replaceRuntimeCanonicalTags: (List<Tag>) -> Unit,
    private val repairAutoDerivedSessionTitlesFromPersistedSnapshot: (Context, List<Tag>) -> Unit,
    private val repairPersistedTagNames: (Context, List<Tag>) -> Unit,
    private val withResolvedTagDefinitions: (SnapshotStore.Snapshot, List<Tag>) -> SnapshotStore.Snapshot,
    private val withResolvedTagSessionNames: (SnapshotStore.Snapshot) -> SnapshotStore.Snapshot,
    private val prepareSessionsSnapshotReadModel: (
        context: Context,
        snapshot: SnapshotStore.Snapshot,
        tags: List<Tag>,
    ) -> SessionsSnapshotReadModel,
    private val readAuthoritativeSessionsSnapshotReadModel: (
        context: Context,
        tags: List<Tag>,
        persistedSnapshotTags: List<Tag>,
    ) -> SessionsSnapshotReadModel?,
    private val resolveSessionsSnapshotReadModelTags: (
        readModel: SessionsSnapshotReadModel,
        tags: List<Tag>,
    ) -> SessionsSnapshotReadModel,
    private val bootstrapSessionsTablesIfEmpty: (
        context: Context,
        legacyTasks: List<Task>,
        tags: List<Tag>,
        legacyClosedSessionRecords: List<ClosedSessionRecord>,
        nowMs: Long,
        currentAppVersionCode: Long,
    ) -> Unit,
    private val readTagParentsByChild: () -> Map<Long, Set<Long>>,
    private val replaceTagParentsByChild: (Map<Long, Set<Long>>) -> Unit,
    private val reconcileRuntimeAlarms: (Context, List<TimeFenceRule>, List<SessionUi>, List<Tag>, Long) -> Unit,
    private val setPersistenceFailureReport: (String?) -> Unit,
    private val onSnapshotReloaded: (Context) -> Unit = {},
    private val onPostImport: (Context) -> Unit = {},
    private val showPersistenceFailureToast: (Context) -> Unit,
) {
    private val trackedTimedSessionAlarmIds = mutableSetOf<Long>()

    private fun reconcileTimeFenceAlarms(
        context: Context,
        rules: List<TimeFenceRule>,
        sessions: List<SessionUi>,
        tags: List<Tag>,
        nowMs: Long,
    ) {
        reconcileRuntimeAlarms(context, rules, sessions, tags, nowMs)
    }

    private data class PreparedSnapshotRuntimeState(
        val tags: List<Tag>,
        val sessions: SessionsSnapshotReadModel,
    )

    private data class AppliedSnapshotState(
        val tags: List<Tag>,
        val closedSessions: List<ClosedSessionRecord>,
        val snapshotTasks: List<Task>,
    )

    private enum class SnapshotLoadMode {
        STANDARD,
        IMPORTED,
    }

    private enum class InstallAtMsPolicy {
        FROM_SNAPSHOT,
        KEEP_EARLIEST,
        KEEP_CURRENT,
    }

    private var sessionsRefreshJob: Job? = null
    private var postLoadSyncJob: Job? = null
    private var autoBackupJob: Job? = null
    private var timeMachineCompactionJob: Job? = null
    private val persistenceGate = PersistentSnapshotSaveGate()
    private var lastPersistedSnapshot: SnapshotStore.Snapshot? = null
    private var lastBackupSignature: String? = null

    fun markPersistenceLoading() {
        persistenceGate.markLoading()
    }

    fun markPersistenceReady() {
        persistenceGate.markReady()
    }

    fun markPersistenceFailed() {
        persistenceGate.markFailed()
    }

    fun loadStartupHomeFromSessionTables(context: Context): Boolean {
        return runCatching {
            val runningSessions = StartupPerfTrace.section("startup_read_running_sessions") {
                sessionCore(context).readRunningSessions()
            }.filter { it.endMs == null && it.deletedAtMs == null }

            if (runningSessions.isNotEmpty()) {
                val activeCountByTagId = linkedMapOf<Long, Int>()
                val runningMinStartByTagId = linkedMapOf<Long, Long>()
                runningSessions.forEach { session ->
                    session.tagIds.forEach { tagId ->
                        activeCountByTagId[tagId] = (activeCountByTagId[tagId] ?: 0) + 1
                        val previous = runningMinStartByTagId[tagId]
                        if (previous == null || session.startMs < previous) {
                            runningMinStartByTagId[tagId] = session.startMs
                        }
                    }
                }
                val fastTags = buildStartupFastTags(
                    currentTags = readTags(),
                    activeCountByTagId = activeCountByTagId,
                    runningMinStartByTagId = runningMinStartByTagId,
                )
                val currentRuntime = readSessionsRuntime()

                updateState { current ->
                    current.copy(
                        nowMs = System.currentTimeMillis(),
                        homeLoadState = HomeLoadState.ReadyWithData,
                    )
                }
                replaceTags(fastTags)
                replaceSessionsRuntime(
                    currentRuntime.copy(
                        tasks = buildSessionOnlyRuntimeTasks(runningSessions),
                        chronologySessions = if (currentRuntime.chronologySessions.isEmpty()) {
                            runningSessions
                        } else {
                            currentRuntime.chronologySessions
                        },
                        runningSessions = runningSessions,
                        runningMinStartByTagId = runningMinStartByTagId,
                    )
                )
                StartupPerfTrace.mark(
                    "startup_home_fast_path_applied state=${HomeLoadState.ReadyWithData.name} " +
                        "running_sessions=${runningSessions.size}"
                )
                true
            } else if (!SnapshotSqlite.hasSnapshot(context)) {
                updateState { current ->
                    current.copy(
                        nowMs = System.currentTimeMillis(),
                        homeLoadState = HomeLoadState.ReadyEmpty,
                    )
                }
                replaceSessionsRuntime(readSessionsRuntime().copy(runningSessions = emptyList()))
                StartupPerfTrace.mark(
                    "startup_home_fast_path_applied state=${HomeLoadState.ReadyEmpty.name} running_sessions=0"
                )
                true
            } else {
                StartupPerfTrace.mark("startup_home_fast_path_pending_snapshot running_sessions=0")
                false
            }
        }.getOrElse { err ->
            StartupPerfTrace.mark("startup_home_fast_path_error=${err::class.java.simpleName}")
            false
        }
    }

    fun loadPersistedSnapshotIfAvailable(context: Context): Boolean {
        val snap = SnapshotStore.load(context) ?: return false
        replaceRuntimeCanonicalTags(snap.tags)
        val applied = applySnapshot(
            context = context,
            snap = snap,
            loadMode = SnapshotLoadMode.STANDARD,
            installAtMsPolicy = InstallAtMsPolicy.FROM_SNAPSHOT,
            rememberPersisted = true,
        )
        persistenceGate.markReady()
        launchPostLoadSync(
            context = context,
            legacyTasks = applied.snapshotTasks,
            tags = applied.tags,
            legacyClosedSessionRecords = applied.closedSessions,
            runAutoConsistency = true,
        )
        scheduleAutoBackup()
        scheduleTimeMachineStorageCompaction(context)
        return true
    }

    private fun buildStartupFastTags(
        currentTags: List<Tag>,
        activeCountByTagId: Map<Long, Int>,
        runningMinStartByTagId: Map<Long, Long>,
    ): List<Tag> {
        if (activeCountByTagId.isEmpty() || currentTags.isEmpty()) return currentTags
        val byId = LinkedHashMap<Long, Tag>()
        currentTags.forEach { tag ->
            byId[tag.id] = tag.copy(
                activeChildrenCount = activeCountByTagId[tag.id] ?: tag.activeChildrenCount,
                lastStartedAtMs = runningMinStartByTagId[tag.id] ?: tag.lastStartedAtMs,
            )
        }
        return byId.values.toList()
    }

    fun reloadFromSnapshot(context: Context) {
        val snap = SnapshotStore.load(context) ?: return
        replaceRuntimeCanonicalTags(snap.tags)
        val applied = applySnapshot(
            context = context,
            snap = snap,
            loadMode = SnapshotLoadMode.STANDARD,
            installAtMsPolicy = InstallAtMsPolicy.KEEP_EARLIEST,
            rememberPersisted = true,
        )
        launchPostLoadSync(
            context = context,
            legacyTasks = applied.snapshotTasks,
            tags = applied.tags,
            legacyClosedSessionRecords = applied.closedSessions,
            runAutoConsistency = false,
        )
        onSnapshotReloaded(context)
        scheduleAutoBackup()
        scheduleTimeMachineStorageCompaction(context)
    }

    fun applyImportedSnapshotFromStore(context: Context, snap: SnapshotStore.Snapshot) {
        postLoadSyncJob?.cancel()
        sessionsRefreshJob?.cancel()
        replaceRuntimeCanonicalTags(snap.tags)
        applySnapshot(
            context = context,
            snap = snap,
            loadMode = SnapshotLoadMode.IMPORTED,
            installAtMsPolicy = InstallAtMsPolicy.KEEP_CURRENT,
            rememberPersisted = false,
        )
        replaceRuntimeCanonicalTags(snap.tags)
        persistCurrentSnapshotOrThrow(
            showFailureUi = false,
            allowDuringPendingRestart = true,
            origin = PersistentSaveOrigin.IMPORT,
        )
        SnapshotStore.load(context)?.let { repaired ->
            replaceRuntimeCanonicalTags(snap.tags)
            applySnapshot(
                context = context,
                snap = repaired,
                loadMode = SnapshotLoadMode.IMPORTED,
                installAtMsPolicy = InstallAtMsPolicy.KEEP_CURRENT,
                rememberPersisted = true,
            )
            persistCurrentSnapshotOrThrow(
                showFailureUi = false,
                allowDuringPendingRestart = true,
                origin = PersistentSaveOrigin.IMPORT,
            )
            repairPersistedTagNames(context, snap.tags)
            lastPersistedSnapshot = SnapshotStore.load(context)
        }
        onPostImport(context)
    }

    fun applyImportedCsvSnapshot(snapshot: com.example.multitimetracker.export.CsvImporter.ImportedSnapshot) {
        appContext()?.let { context ->
            runCatching {
                com.example.multitimetracker.core.quickevent.DefaultQuickEventCore(context)
                    .replaceAll(
                        snapshot.quickEventTemplates,
                        snapshot.quickEventEntries,
                        snapshot.quickEventFieldDefinitions,
                        snapshot.quickEventFieldValues,
                        snapshot.quickEventMacros,
                        snapshot.quickEventMacroActions
                    )
            }
            applySnapshot(
                context = context,
                snap = snapshot.toSnapshotStoreSnapshot(installAtMs = readState().installAtMs),
                loadMode = SnapshotLoadMode.IMPORTED,
                installAtMsPolicy = InstallAtMsPolicy.KEEP_CURRENT,
                rememberPersisted = false,
            )
        }
        updateState {
            it.copy(
                appUsageMs = snapshot.appUsageMs,
                installAtMs = it.installAtMs,
                appUsageRunningSinceMs = null,
                nowMs = System.currentTimeMillis(),
            )
        }
        replaceLifePeriods(snapshot.lifePeriods)
        replaceTimeFenceRules(snapshot.timeFenceRules)
        replaceQuickEvents(snapshot.toQuickEventsSnapshot())
        replaceChains(snapshot.toChainsSnapshot())
        replaceTagParentsByChild(snapshot.tagParentsByChild)
        appContext()?.let(onPostImport)

        persist()
        scheduleAutoBackup()
    }

    fun persist(origin: PersistentSaveOrigin = PersistentSaveOrigin.STATE_MUTATION) {
        runCatching {
            persistOrThrow(showFailureUi = true, origin = origin)
        }
    }

    fun persistAsync(origin: PersistentSaveOrigin = PersistentSaveOrigin.STATE_MUTATION) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                persistCurrentSnapshotOrThrow(
                    showFailureUi = true,
                    allowDuringPendingRestart = false,
                    origin = origin,
                )
            }
        }
    }

    fun persistOrThrow(
        showFailureUi: Boolean,
        origin: PersistentSaveOrigin = PersistentSaveOrigin.STATE_MUTATION,
    ) {
        persistCurrentSnapshotOrThrow(
            showFailureUi = showFailureUi,
            allowDuringPendingRestart = false,
            origin = origin,
        )
    }

    private fun persistCurrentSnapshotOrThrow(
        showFailureUi: Boolean,
        allowDuringPendingRestart: Boolean,
        origin: PersistentSaveOrigin,
    ) {
        if (!allowDuringPendingRestart && AppRestarter.isRestartPending()) return

        persistenceGate.execute(origin) { _, _ ->
            persistReadySnapshotOrThrow(
                showFailureUi = showFailureUi,
                allowDuringPendingRestart = allowDuringPendingRestart,
            )
        }
    }

    private fun persistReadySnapshotOrThrow(
        showFailureUi: Boolean,
        allowDuringPendingRestart: Boolean,
    ) {
        val ctx = appContext() ?: return
        try {
            val cur = readState()
            val runtime = exportRuntimeSnapshot()
            val persistedTags = SnapshotStore.load(ctx)?.tags.orEmpty()
            val snapshot = captureCurrentSnapshot(
                cur = cur,
                runtime = runtime,
                preferPersistedSnapshotDuringPendingRestart = !allowDuringPendingRestart,
            )
                .let { withResolvedTagDefinitions(it, persistedTags) }
                .let { withResolvedTagSessionNames(it) }

            SnapshotStore.save(
                context = ctx,
                tasks = snapshot.tasks,
                tags = snapshot.tags,
                closedSessions = snapshot.closedSessions,
                tagSessions = snapshot.tagSessions,
                lifePeriods = snapshot.lifePeriods,
                timeFenceRules = snapshot.timeFenceRules,
                installAtMs = snapshot.installAtMs,
                appUsageMs = snapshot.appUsageMs,
                activeSessionStart = snapshot.activeSessionStart,
                activeTagStart = snapshot.activeTagStart,
                tagParents = snapshot.tagParents,
                chains = snapshot.chains,
                activeChainRun = snapshot.activeChainRun,
                chronologySessions = snapshot.chronologySessions,
                runningSessions = snapshot.runningSessions,
                quickEventTemplates = snapshot.quickEventTemplates,
                quickEventEntries = snapshot.quickEventEntries,
                quickEventFieldDefinitions = snapshot.quickEventFieldDefinitions,
                quickEventFieldValues = snapshot.quickEventFieldValues,
                quickEventMacros = snapshot.quickEventMacros,
                quickEventMacroActions = snapshot.quickEventMacroActions,
            )
            lastPersistedSnapshot = snapshot
            setPersistenceFailureReport(null)
            scheduleSessionsRefresh(ctx, System.currentTimeMillis())
        } catch (t: Throwable) {
            restoreLastPersistedSnapshotInMemory()
            val failureDetail = buildString {
                append(t::class.java.simpleName.ifBlank { "Error" })
                val message = t.message?.takeIf { it.isNotBlank() }
                if (message != null) {
                    append(": ")
                    append(message)
                }
            }
            val failureReport = buildString {
                appendLine(ctx.getString(R.string.persistence_failure_body))
                appendLine()
                appendLine(failureDetail)
            }.trim()
            setPersistenceFailureReport(failureReport)
            if (showFailureUi) {
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    showPersistenceFailureToast(ctx)
                }
            }
            throw t
        }
    }

    fun rememberCurrentStateAsPersisted() {
        val persistedTags = appContext()?.let { SnapshotStore.load(it)?.tags }.orEmpty()
        lastPersistedSnapshot = captureCurrentSnapshot()
            .let { withResolvedTagDefinitions(it, persistedTags) }
            .let { withResolvedTagSessionNames(it) }
    }

    fun clearPersistenceFailureReport() {
        setPersistenceFailureReport(null)
    }

    fun computeBackupSignature(): String {
        return AuthoritativeExportPayloadBuilder.signature(
            buildAuthoritativeExportPayload(appContext())
        )
    }

    fun setLastBackupSignature(signature: String) {
        lastBackupSignature = signature
    }

    fun scheduleAutoBackup() {
        val ctx = appContext() ?: return
        if (BackupFolderStore.getTreeUri(ctx) == null) return

        autoBackupJob?.cancel()
        autoBackupJob = viewModelScope.launch(Dispatchers.IO) {
            delay(1200)
            runCatching {
                val signature = computeBackupSignature()
                if (signature == lastBackupSignature) return@runCatching

                PersistentMutationTracker.requestExport(ctx)
                lastBackupSignature = signature
            }
        }
    }

    private fun scheduleTimeMachineStorageCompaction(context: Context) {
        timeMachineCompactionJob?.cancel()
        val appCtx = context.applicationContext
        timeMachineCompactionJob = viewModelScope.launch(Dispatchers.IO) {
            delay(5_000)
            var compactedInThisRun = false
            repeat(12) {
                val compacted = runCatching {
                    SnapshotSqlite.compactTimeMachineStorage(appCtx)
                }.getOrDefault(false)
                if (!compacted) {
                    val retained = runCatching {
                        SnapshotSqlite.applyStorageRetention(appCtx)
                    }.getOrDefault(false)
                    if (compactedInThisRun || retained) {
                        runCatching { SnapshotSqlite.vacuumTimeMachineStorageIfCompacted(appCtx) }
                    }
                    return@launch
                }
                compactedInThisRun = true
                delay(750)
            }
        }
    }

    fun buildManualExportZipName(nowMs: Long): String {
        val dt = java.time.ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(nowMs),
            java.time.ZoneId.systemDefault(),
        )
        val stamp = dt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        return "multitimer_export_${stamp}.zip"
    }

    fun verifyImportedSnapshotApplied(expected: SnapshotStore.Snapshot): String? {
        val actual = captureCurrentSnapshot()
        val normalizedExpected = expected.copy(installAtMs = actual.installAtMs)
        val mismatches = mutableListOf<String>()

        fun <T> diff(name: String, exp: T, act: T) {
            if (exp != act) mismatches += name
        }

        val actualRuntime = readSessionsRuntime()
        diff("tasks", normalizedExpected.tasks, actualRuntime.tasks)
        diff("tags", normalizedExpected.tags, readTags())
        diff("closedSessions", normalizedExpected.closedSessions, actualRuntime.closedSessions)
        diff("tagSessions", normalizedExpected.tagSessions, actualRuntime.tagSessions)
        diff("lifePeriods", normalizedExpected.lifePeriods, readLifePeriods())
        diff("timeFenceRules", normalizedExpected.timeFenceRules, readTimeFenceRules())
        diff("activeSessionStart", normalizedExpected.activeSessionStart, actual.activeSessionStart)
        diff("activeTagStart", normalizedExpected.activeTagStart, actual.activeTagStart)
        diff("tagParents", normalizedExpected.tagParents, actual.tagParents)
        diff("chains", normalizedExpected.chains, readChains().chains)
        diff("activeChainRun", normalizedExpected.activeChainRun, readChains().activeChainRun)
        diff("appUsageMs", normalizedExpected.appUsageMs, actual.appUsageMs)

        return mismatches.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    fun verifyCurrentPersistedSnapshotActivated(context: Context): String? {
        val expected = SnapshotStore.load(context)
            ?: return context.getString(R.string.import_snapshot_unreadable)
        val prepared = prepareSnapshotRuntimeState(context, expected)
        val expectedPayload = buildActivationPayloadFromSnapshot(
            context = context,
            snap = expected,
            prepared = prepared,
        )
        val expectedSignature = AuthoritativeExportPayloadBuilder.activationSignature(
            expectedPayload
        )
        val actualSignature = AuthoritativeExportPayloadBuilder.activationSignature(
            buildAuthoritativeExportPayload(context)
        )
        val mismatches = verifyRuntimeStateMatchesPrepared(
            expected = expected,
            prepared = prepared,
        ).toMutableList()
        val preparedActiveTagStart = prepared.sessions.runtimeSnapshot.activeTagStart
            .map { (sessionId, tagId, startTs) ->
                SnapshotStore.ActiveTag(sessionId = sessionId, tagId = tagId, startTs = startTs)
            }
            .sortedByActiveTag()
        lastPersistedSnapshot?.let { activated ->
            if (activated.activeSessionStart != prepared.sessions.runtimeSnapshot.activeSessionStart &&
                "runtime.activeSessionStart" !in mismatches
            ) {
                mismatches += "runtime.activeSessionStart"
            }
            if (activated.activeTagStart.sortedByActiveTag() != preparedActiveTagStart &&
                "runtime.activeTagStart" !in mismatches
            ) {
                mismatches += "runtime.activeTagStart"
            }
        }
        if (expectedSignature != actualSignature) {
            mismatches += "activation-signature"
        }
        if (mismatches.isEmpty()) return null
        return context.getString(
            R.string.import_runtime_verify_failed_fmt,
            mismatches.joinToString(", "),
        )
    }

    private fun applySnapshot(
        context: Context,
        snap: SnapshotStore.Snapshot,
        loadMode: SnapshotLoadMode,
        installAtMsPolicy: InstallAtMsPolicy,
        rememberPersisted: Boolean,
    ): AppliedSnapshotState {
        val prepared = prepareSnapshotRuntimeState(context = context, snap = snap)
        val tagParentsByChild = buildTagParentsByChild(snap.tagParents)

        when (loadMode) {
            SnapshotLoadMode.STANDARD -> importRuntimeSnapshot(
                prepared.sessions.runtimeState.tasks,
                prepared.tags,
                prepared.sessions.runtimeState.closedSessions,
                prepared.sessions.runtimeState.tagSessions,
                prepared.sessions.runtimeSnapshot,
            )

            SnapshotLoadMode.IMPORTED -> loadImportedSnapshot(
                prepared.sessions.runtimeState.tasks,
                prepared.tags,
                prepared.sessions.runtimeState.closedSessions,
                prepared.sessions.runtimeState.tagSessions,
                prepared.sessions.runtimeSnapshot,
            )
        }

        updateState { current ->
            current.copy(
                appUsageMs = snap.appUsageMs,
                appUsageRunningSinceMs = null,
                installAtMs = when (installAtMsPolicy) {
                    InstallAtMsPolicy.FROM_SNAPSHOT -> snap.installAtMs
                    InstallAtMsPolicy.KEEP_EARLIEST -> minOf(current.installAtMs, snap.installAtMs)
                    InstallAtMsPolicy.KEEP_CURRENT -> current.installAtMs
                },
                nowMs = System.currentTimeMillis(),
                homeLoadState = homeLoadStateFor(prepared.sessions.runtimeState.runningSessions),
            )
        }
        replaceTags(prepared.tags)
        replaceSessionsRuntime(prepared.sessions.runtimeState)
        replaceLifePeriods(snap.lifePeriods)
        replaceTimeFenceRules(snap.timeFenceRules)
        replaceQuickEvents(snap.toQuickEventsSnapshot())
        replaceChains(snap.toChainsSnapshot())
        replaceTagParentsByChild(tagParentsByChild)

        StartupPerfTrace.mark(
            "state_applied home_load_state=${homeLoadStateFor(prepared.sessions.runtimeState.runningSessions).name} " +
                "running_sessions=${prepared.sessions.runtimeState.runningSessions.count { it.endMs == null && it.deletedAtMs == null }}"
        )

        reconcileTimeFenceAlarms(
            context = context,
            rules = snap.timeFenceRules,
            sessions = prepared.sessions.runtimeState.runningSessions,
            tags = prepared.tags,
            nowMs = System.currentTimeMillis(),
        )

        if (rememberPersisted) {
            rememberCurrentStateAsPersisted()
            clearPersistenceFailureReport()
        }

        return AppliedSnapshotState(
            tags = prepared.tags,
            closedSessions = prepared.sessions.runtimeState.closedSessions,
            snapshotTasks = snap.tasks,
        )
    }

    private fun launchPostLoadSync(
        context: Context,
        legacyTasks: List<Task>,
        tags: List<Tag>,
        legacyClosedSessionRecords: List<ClosedSessionRecord>,
        runAutoConsistency: Boolean,
    ) {
        postLoadSyncJob?.cancel()
        postLoadSyncJob = viewModelScope.launch(Dispatchers.IO) {
            bootstrapSessionsTablesIfEmpty(
                context,
                legacyTasks,
                tags,
                legacyClosedSessionRecords,
                System.currentTimeMillis(),
                currentAppVersionCodeLong(context),
            )
            scheduleSessionsRefresh(context, System.currentTimeMillis())

            if (!runAutoConsistency) return@launch

            val auto = AutoConsistencyCore.runIfNeeded(
                context = context,
                nowMs = System.currentTimeMillis(),
                currentPatch = autoConsistencyRevision,
            )
            if (auto.changed) {
                withContext(Dispatchers.Main) {
                    reloadFromSnapshot(context)
                }
            }
        }
    }

    fun scheduleSessionsRefresh(context: Context, nowMs: Long) {
        sessionsRefreshJob?.cancel()
        sessionsRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            delay(50)

            runCatching {
                val effectiveNowMs = System.currentTimeMillis().coerceAtLeast(nowMs)
                val currentTags = readTags()
                val persistedCanonicalTags = SnapshotStore.load(context)?.tags.orEmpty()
                val tagsForAuthoritativeRead = selectTagsForAuthoritativeRead(
                    context,
                    currentTags,
                    persistedCanonicalTags,
                )
                repairAutoDerivedSessionTitlesFromPersistedSnapshot(context, currentTags)
                TimedSessionSupport.reconcileExpiredSessions(
                    context = context,
                    tags = tagsForAuthoritativeRead,
                    nowMs = effectiveNowMs,
                    notify = true,
                )
                val authoritative = readAuthoritativeSessionsSnapshotReadModel(
                    context,
                    tagsForAuthoritativeRead,
                    persistedCanonicalTags,
                ) ?: return@runCatching
                val preferredCanonicalTags = selectCanonicalTags(
                    tagsForAuthoritativeRead,
                    persistedCanonicalTags,
                )
                val resolvedTags = mergeSnapshotTags(
                    if (preferredCanonicalTags.isNotEmpty()) preferredCanonicalTags else currentTags,
                    authoritative.tags,
                )
                val resolvedAuthoritative = resolveSessionsSnapshotReadModelTags(
                    authoritative,
                    resolvedTags,
                )
                rememberRuntimeCanonicalTags(resolvedTags)

                updateState {
                    requireSessionOnlyMode(true)
                    it.copy(
                        homeLoadState = homeLoadStateFor(resolvedAuthoritative.runtimeState.runningSessions),
                    )
                }
                replaceTags(resolvedTags)
                replaceSessionsRuntime(resolvedAuthoritative.runtimeState)

                TimedSessionSupport.syncScheduledAlarms(
                    context = context,
                    sessions = resolvedAuthoritative.runtimeState.chronologySessions,
                    tags = resolvedTags,
                    trackedSessionIds = trackedTimedSessionAlarmIds,
                    nowMs = effectiveNowMs,
                )
                reconcileTimeFenceAlarms(
                    context = context,
                    rules = readTimeFenceRules(),
                    sessions = resolvedAuthoritative.runtimeState.runningSessions,
                    tags = resolvedTags,
                    nowMs = effectiveNowMs,
                )
            }
        }
    }

    private fun prepareSnapshotRuntimeState(
        context: Context,
        snap: SnapshotStore.Snapshot,
    ): PreparedSnapshotRuntimeState {
        val tagProjection = prepareSnapshotTags(snap)
        val sessions = prepareSessionsSnapshotReadModel(
            context,
            snap,
            tagProjection.tags,
        )
        val resolvedTags = mergeSnapshotTags(
            tagProjection.tags,
            sessions.tags,
        )
        val resolvedSessions = resolveSessionsSnapshotReadModelTags(
            sessions,
            resolvedTags,
        )
        replaceRuntimeCanonicalTags(resolvedTags)
        return PreparedSnapshotRuntimeState(
            tags = resolvedTags,
            sessions = resolvedSessions,
        )
    }

    private fun captureCurrentSnapshot(
        cur: UiState = readState(),
        runtime: TimeEngine.RuntimeSnapshot = exportRuntimeSnapshot(),
        preferPersistedSnapshotDuringPendingRestart: Boolean = true,
    ): SnapshotStore.Snapshot {
        val context = appContext()
        val currentRuntime = readSessionsRuntime()
        val currentTags = readTags()
        if (
            preferPersistedSnapshotDuringPendingRestart &&
            AppRestarter.isRestartPending() &&
            context != null
        ) {
            SnapshotStore.load(context)?.let { persisted ->
                return persisted
            }
        }

        val persistedCanonicalTags = context?.let { SnapshotStore.load(it)?.tags }.orEmpty()
        val preferredCanonicalTags = if (AppRestarter.isRestartPending() && persistedCanonicalTags.isNotEmpty()) {
            persistedCanonicalTags
        } else {
            selectCanonicalTags(
                currentTags,
                persistedCanonicalTags,
            )
        }
        val cacheTags = if (preferredCanonicalTags.isNotEmpty()) preferredCanonicalTags else currentTags
        val authoritative = context?.let { resolvedContext ->
            readAuthoritativeSessionsSnapshotReadModel(
                resolvedContext,
                cacheTags,
                persistedCanonicalTags,
            )
        }
        val resolvedTags = mergeSnapshotTags(
            if (preferredCanonicalTags.isNotEmpty()) preferredCanonicalTags else currentTags,
            authoritative?.tags ?: currentTags,
        )
        val resolvedAuthoritative = authoritative?.let {
            resolveSessionsSnapshotReadModelTags(
                it,
                resolvedTags,
            )
        }
        val resolvedCurrentRuntime = resolveSessionsSnapshotReadModelTags(
            SessionsSnapshotReadModel(
                runtimeState = currentRuntime,
                runtimeSnapshot = runtime,
                activeTagStartByTagId = currentRuntime.runningMinStartByTagId,
                tags = currentTags,
            ),
            resolvedTags,
        )
        rememberRuntimeCanonicalTags(resolvedTags)
        val authoritativeHasChronology =
            resolvedAuthoritative?.runtimeState?.let {
                it.closedSessions.isNotEmpty() ||
                    it.tagSessions.isNotEmpty() ||
                    it.chronologySessions.isNotEmpty() ||
                    it.runningSessions.isNotEmpty()
            } == true
        val shouldUseAuthoritativeChronology =
            authoritativeHasChronology ||
                (currentRuntime.closedSessions.isEmpty() &&
                    currentRuntime.tagSessions.isEmpty() &&
                    currentRuntime.chronologySessions.isEmpty() &&
                currentRuntime.runningSessions.isEmpty())
        val selectedRuntime = if (shouldUseAuthoritativeChronology) {
            resolvedAuthoritative?.runtimeState ?: resolvedCurrentRuntime.runtimeState
        } else {
            resolvedCurrentRuntime.runtimeState
        }

        return SnapshotStore.Snapshot(
            tasks = selectedRuntime.tasks,
            tags = resolvedTags,
            closedSessions = selectedRuntime.closedSessions,
            tagSessions = selectedRuntime.tagSessions,
            lifePeriods = readLifePeriods(),
            timeFenceRules = readTimeFenceRules(),
            installAtMs = cur.installAtMs,
            appUsageMs = cur.appUsageMs,
            activeSessionStart = runtime.activeSessionStart,
            activeTagStart = runtime.activeTagStart.map { (sessionId, tagId, startTs) ->
                SnapshotStore.ActiveTag(sessionId = sessionId, tagId = tagId, startTs = startTs)
            },
            tagParents = flattenTagParents(readTagParentsByChild()),
            chains = readChains().chains,
            activeChainRun = readChains().activeChainRun,
            quickEventTemplates = readQuickEvents().templates,
            quickEventEntries = readQuickEvents().entries,
            quickEventFieldDefinitions = readQuickEvents().fieldDefinitions,
            quickEventFieldValues = readQuickEvents().fieldValues,
            quickEventMacros = readQuickEvents().macros,
            quickEventMacroActions = readQuickEvents().macroActions,
            chronologySessions = selectedRuntime.chronologySessions,
            runningSessions = selectedRuntime.runningSessions,
        )
    }

    private fun verifyRuntimeStateMatchesPrepared(
        expected: SnapshotStore.Snapshot,
        prepared: PreparedSnapshotRuntimeState,
    ): List<String> {
        val actual = readState()
        val actualRuntime = readSessionsRuntime()
        val actualTags = readTags()
        val mismatches = mutableListOf<String>()

        fun <T> diff(name: String, exp: T, act: T) {
            if (exp != act) mismatches += name
        }

        diff("tasks", prepared.sessions.runtimeState.tasks, actualRuntime.tasks)
        diff("tags", prepared.tags, actualTags)
        diff("closedSessions", prepared.sessions.runtimeState.closedSessions, actualRuntime.closedSessions)
        diff("tagSessions", prepared.sessions.runtimeState.tagSessions, actualRuntime.tagSessions)
        diff("chronologySessions", prepared.sessions.runtimeState.chronologySessions, actualRuntime.chronologySessions)
        diff("runningSessions", prepared.sessions.runtimeState.runningSessions, actualRuntime.runningSessions)
        diff("activeTagTotalsMsByTagId", prepared.sessions.runtimeState.activeTagTotalsMsByTagId, actualRuntime.activeTagTotalsMsByTagId)
        diff("runningMinStartByTagId", prepared.sessions.runtimeState.runningMinStartByTagId, actualRuntime.runningMinStartByTagId)
        diff("tagTotalsMsByTagId", prepared.sessions.runtimeState.tagTotalsMsByTagId, actualRuntime.tagTotalsMsByTagId)
        diff("tagLastUsedMsByTagId", prepared.sessions.runtimeState.tagLastUsedMsByTagId, actualRuntime.tagLastUsedMsByTagId)
        diff("activeTagStart", prepared.sessions.activeTagStartByTagId, actualRuntime.runningMinStartByTagId)
        diff("lifePeriods", expected.lifePeriods, readLifePeriods())
        diff("timeFenceRules", expected.timeFenceRules, readTimeFenceRules())
        diff("tagParentsByChild", buildTagParentsByChild(expected.tagParents), readTagParentsByChild())
        diff("chains", expected.chains, readChains().chains)
        diff("activeChainRun", expected.activeChainRun, readChains().activeChainRun)
        diff("appUsageMs", expected.appUsageMs, actual.appUsageMs)

        val actualEngineRuntime = exportRuntimeSnapshot()
        diff("runtime.activeSessionStart", prepared.sessions.runtimeSnapshot.activeSessionStart, actualEngineRuntime.activeSessionStart)
        diff(
            "runtime.activeTagStart",
            prepared.sessions.runtimeSnapshot.activeTagStart.sortedRuntimeActiveTags(),
            actualEngineRuntime.activeTagStart.sortedRuntimeActiveTags(),
        )

        return mismatches
    }

    private fun Sequence<Triple<Long, Long, Long>>.sortedRuntimeActiveTags(): List<Triple<Long, Long, Long>> {
        return sortedWith(compareBy({ it.first }, { it.second }, { it.third })).toList()
    }

    private fun List<Triple<Long, Long, Long>>.sortedRuntimeActiveTags(): List<Triple<Long, Long, Long>> {
        return sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
    }

    private fun List<SnapshotStore.ActiveTag>.sortedByActiveTag(): List<SnapshotStore.ActiveTag> {
        return sortedWith(compareBy({ it.sessionId }, { it.tagId }, { it.startTs }))
    }

    private fun buildActivationPayloadFromSnapshot(
        context: Context,
        snap: SnapshotStore.Snapshot,
        prepared: PreparedSnapshotRuntimeState,
    ): AuthoritativeExportPayload {
        return AuthoritativeExportPayloadBuilder.fromSessionTables(
            tags = prepared.tags,
            appUsageMs = snap.appUsageMs,
            sessionCore = sessionCore(context),
            lifePeriods = snap.lifePeriods,
            timeFenceRules = snap.timeFenceRules,
            quickEvents = snap.toQuickEventsSnapshot(),
            chains = snap.toChainsSnapshot(),
            tagParentsByChild = buildTagParentsByChild(snap.tagParents),
        )
    }

    private fun SnapshotStore.Snapshot.toChainsSnapshot(): ChainsSnapshot {
        return ChainsSnapshot(
            chains = chains,
            activeChainRun = activeChainRun,
        )
    }

    private fun SnapshotStore.Snapshot.toQuickEventsSnapshot(): QuickEventsSnapshot {
        return QuickEventsSnapshot(
            templates = quickEventTemplates,
            entries = quickEventEntries,
            fieldDefinitions = quickEventFieldDefinitions,
            fieldValues = quickEventFieldValues,
            macros = quickEventMacros,
            macroActions = quickEventMacroActions,
        )
    }

    private fun com.example.multitimetracker.export.CsvImporter.ImportedSnapshot.toQuickEventsSnapshot(): QuickEventsSnapshot {
        return QuickEventsSnapshot(
            templates = quickEventTemplates,
            entries = quickEventEntries,
            fieldDefinitions = quickEventFieldDefinitions,
            fieldValues = quickEventFieldValues,
            macros = quickEventMacros,
            macroActions = quickEventMacroActions,
        )
    }

    private fun com.example.multitimetracker.export.CsvImporter.ImportedSnapshot.toChainsSnapshot(): ChainsSnapshot {
        return ChainsSnapshot(
            chains = chains,
            activeChainRun = activeChainRun,
        )
    }

    private fun com.example.multitimetracker.export.CsvImporter.ImportedSnapshot.toSnapshotStoreSnapshot(
        installAtMs: Long,
    ): SnapshotStore.Snapshot {
        val runtime = runtimeSnapshot
        return SnapshotStore.Snapshot(
            tasks = tasks,
            tags = tags,
            closedSessions = closedSessions,
            tagSessions = tagSessions,
            lifePeriods = lifePeriods,
            timeFenceRules = timeFenceRules,
            installAtMs = installAtMs,
            appUsageMs = appUsageMs,
            activeSessionStart = runtime?.activeSessionStart.orEmpty(),
            activeTagStart = runtime?.activeTagStart.orEmpty().map { (sessionId, tagId, startTs) ->
                SnapshotStore.ActiveTag(sessionId = sessionId, tagId = tagId, startTs = startTs)
            },
            tagParents = flattenTagParents(tagParentsByChild),
            chains = chains,
            activeChainRun = activeChainRun,
            quickEventTemplates = quickEventTemplates,
            quickEventEntries = quickEventEntries,
            quickEventFieldDefinitions = quickEventFieldDefinitions,
            quickEventFieldValues = quickEventFieldValues,
            quickEventMacros = quickEventMacros,
            quickEventMacroActions = quickEventMacroActions,
        )
    }

    private fun restoreLastPersistedSnapshotInMemory() {
        val snap = lastPersistedSnapshot ?: return
        val context = appContext() ?: return
        applySnapshot(
            context = context,
            snap = snap,
            loadMode = SnapshotLoadMode.STANDARD,
            installAtMsPolicy = InstallAtMsPolicy.FROM_SNAPSHOT,
            rememberPersisted = false,
        )
        scheduleSessionsRefresh(context, System.currentTimeMillis())
    }

    private fun buildTagParentsByChild(edges: List<SnapshotStore.TagParentEdge>): Map<Long, Set<Long>> {
        if (edges.isEmpty()) return emptyMap()
        return edges
            .asSequence()
            .filter { it.childId > 0L && it.parentId > 0L && it.childId != it.parentId }
            .groupBy { it.childId }
            .mapValues { (_, value) -> value.map { it.parentId }.toSet() }
    }

    private fun flattenTagParents(map: Map<Long, Set<Long>>): List<SnapshotStore.TagParentEdge> {
        if (map.isEmpty()) return emptyList()
        val out = ArrayList<SnapshotStore.TagParentEdge>()
        map.forEach { (child, parents) ->
            parents.forEach { parent ->
                if (child > 0L && parent > 0L && child != parent) {
                    out.add(SnapshotStore.TagParentEdge(childId = child, parentId = parent))
                }
            }
        }
        return out
    }
}
