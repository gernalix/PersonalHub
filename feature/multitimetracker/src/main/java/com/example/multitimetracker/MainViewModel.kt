// v471
// v470
package com.example.multitimetracker

import com.example.multitimetracker.util.CapsuleWriteApi

import com.example.multitimetracker.R
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.multitimetracker.core.contracts.TaggedSessionRecord
import com.example.multitimetracker.core.contracts.ClosedSessionRecord
import com.example.multitimetracker.capsules.alerts.controller.AlertsCapsuleViewModel
import com.example.multitimetracker.capsules.alerts.public.TimeFenceEvent
import com.example.multitimetracker.capsules.alerts.state.AlertsHostState
import com.example.multitimetracker.capsules.chains.public.ChainsSnapshot
import com.example.multitimetracker.capsules.chains.state.ChainsHostState
import com.example.multitimetracker.capsules.now.controller.NowCapsuleViewModel
import com.example.multitimetracker.capsules.now.state.NowUiState
import com.example.multitimetracker.capsules.quickevents.public.QuickEventsSnapshot
import com.example.multitimetracker.capsules.quickevents.state.QuickEventsHostState
import com.example.multitimetracker.capsules.sessions.public.SessionsRuntimeState
import com.example.multitimetracker.capsules.sincewhen.state.SinceWhenHostState
import com.example.multitimetracker.capsules.system.CapsuleRuntimeChange
import com.example.multitimetracker.capsules.system.CapsuleRuntimeParticipant
import com.example.multitimetracker.capsules.tags.controller.TagsCapsuleViewModel
import com.example.multitimetracker.capsules.tags.state.TagsHostState
import com.example.multitimetracker.capsules.tags.state.TagsUiState
import com.example.multitimetracker.capsules.timeline.state.TimelineUiState
import com.example.multitimetracker.model.LifePeriodDisplayUnit
import com.example.multitimetracker.model.DEFAULT_LIFE_PERIOD_COLOR_ARGB
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.TaskChainStep
import com.example.multitimetracker.model.Task
import com.example.multitimetracker.model.TimeEngine
import com.example.multitimetracker.model.HomeLoadState
import com.example.multitimetracker.model.UiState
import com.example.multitimetracker.model.TimeFenceTrigger
import com.example.multitimetracker.model.TimeFenceScope
import com.example.multitimetracker.model.TimeFenceMatchMode
import com.example.multitimetracker.model.TimeFenceDelivery
import com.example.multitimetracker.model.QuickEventFieldDefinition
import com.example.multitimetracker.model.QuickEventFieldValue
import com.example.multitimetracker.model.QuickEventMacroAction
import com.example.multitimetracker.model.TimedTagNotificationType
import com.example.multitimetracker.model.homeLoadStateFor
import com.example.multitimetracker.persistence.SnapshotSqlite
import com.example.multitimetracker.persistence.SnapshotStore
import com.example.multitimetracker.persistence.UiPrefsStore
import com.example.multitimetracker.persistence.DataIntegrityGate
import com.example.multitimetracker.persistence.PersistentSaveOrigin
import com.example.multitimetracker.ui.util.UiEventLogger
import com.gernalix.personalhub.contracts.database.HubTagNamespaces
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import org.json.JSONObject

internal fun shouldEmitStartForRunningSessionMetaUpdate(
    before: com.example.multitimetracker.model.SessionUi?,
    newTitle: String,
    newTagIds: Set<Long>
): Boolean {
    val runningBefore = before?.takeIf { it.endMs == null } ?: return false
    val startedBlank = runningBefore.title.isBlank() && runningBefore.tagIds.isEmpty()
    if (!startedBlank) return false
    return newTitle.trim().isNotBlank() || newTagIds.isNotEmpty()
}

internal fun newSessionSubmitKey(title: String, startMs: Long, tagIds: Set<Long>): String =
    "new-session|" + title.trim().lowercase(Locale.ROOT) + "|" + startMs + "|" + tagIds.sorted().joinToString(",")

internal fun chainSubmitKey(name: String, steps: List<TaskChainStep>): String =
    buildString {
        append("chain|")
        append(name.trim().lowercase(Locale.ROOT))
        steps.forEach { step ->
            append("|")
            append(step.name.trim().lowercase(Locale.ROOT))
            append("|")
            append(step.link.trim())
            append("|")
            append(step.tagIds.sorted().joinToString(","))
        }
    }

@OptIn(CapsuleWriteApi::class)
class MainViewModel : ViewModel() {
    // FEATURE CAPSULE: Session Core Bridge — START
    // All session-only table access must go through SessionCore (interface), never SessionRepository directly.
    private fun sessionCore(ctx: android.content.Context): com.example.multitimetracker.core.session.SessionCore =
        com.example.multitimetracker.core.session.DefaultSessionCore(ctx)
    // FEATURE CAPSULE: Session Core Bridge — END

    private fun quickEventCore(ctx: android.content.Context): com.example.multitimetracker.core.quickevent.QuickEventCore =
        com.example.multitimetracker.core.quickevent.DefaultQuickEventCore(ctx)


// v201
// === FEATURE CAPSULE: AppVersionAudit (ViewModel) START ===
private fun logAppVersionIfNeeded(context: Context) {
    val hostVersion = HostAppVersion.current(context)
    val currentCode = hostVersion.code
    val currentName = hostVersion.name
    val lastLogged = UiPrefsStore.getLastLoggedAppVersionCode(context)

    if (lastLogged != null && lastLogged == currentCode) return

    UiPrefsStore.setLastLoggedAppVersionCode(context, currentCode)
}
// === FEATURE CAPSULE: AppVersionAudit (ViewModel) END ===

    companion object {
        private const val LOG_TAG = "MT_IMPORT"
    }

    /* FEATURE CAPSULE: Reinforcement toast (ViewModel) — START */
// --- Reinforcement toast (randomizzazione vincolata) ---
    // Small positive-feedback toast shown occasionally after creating a new session.
    // Invariants:
    // - Never show two reinforcement toasts consecutively.
    // - The message is single-purpose (one piece of info).
    private fun prefs() = appContext?.getSharedPreferences("mt_rewards", Context.MODE_PRIVATE)

    private fun dayKey(cal: Calendar): Int {
        return cal.get(Calendar.YEAR) * 10000 + (cal.get(Calendar.MONTH) + 1) * 100 + cal.get(Calendar.DAY_OF_MONTH)
    }

    private fun hourKey(cal: Calendar): Int {
        return dayKey(cal) * 100 + cal.get(Calendar.HOUR_OF_DAY)
    }

    private fun maybeShowReinforcementToast(afterSessionCreatedMs: Long) {
        val ctx = appContext ?: return
        val sp = prefs() ?: return

        val cal = Calendar.getInstance().apply { timeInMillis = afterSessionCreatedMs }
        val dKey = dayKey(cal)
        val hKey = hourKey(cal)

        // Reset/advance daily counter
        val prevDayKey = sp.getInt("dayKey", -1)
        val todayCount = if (prevDayKey == dKey) sp.getInt("dayCount", 0) + 1 else 1

        // Reset/advance hourly counter
        val prevHourKey = sp.getInt("hourKey", -1)
        val hourCount = if (prevHourKey == hKey) sp.getInt("hourCount", 0) + 1 else 1

        // Constraint: never two toasts in a row
        val lastWasToast = sp.getBoolean("lastWasToast", false)

        // Base probability ~25%
        val baseProb = 0.25f
        val shouldToast = (!lastWasToast) && (Random.Default.nextFloat() < baseProb)

        if (shouldToast) {
            val variant = Random.Default.nextInt(3)
            val msg = when (variant) {
                0 -> ctx.getString(R.string.reward_toast_today_count, todayCount)
                1 -> ctx.getString(R.string.reward_toast_hour_count, hourCount)
                else -> ctx.getString(R.string.reward_toast_generic_good, todayCount)
            }
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }

        sp.edit()
            .putInt("dayKey", dKey)
            .putInt("dayCount", todayCount)
            .putInt("hourKey", hKey)
            .putInt("hourCount", hourCount)
            .putBoolean("lastWasToast", shouldToast)
            .apply()
    }
/* FEATURE CAPSULE: Reinforcement toast (ViewModel) — END */

    private val engine = TimeEngine()

    private data class UndoStopInfo(val sessionIndex: Int)
    private val lastStopByTaskId = mutableMapOf<Long, UndoStopInfo>()


    private var appContext: Context? = null
    private var timerSharedTagBridge: TimerSharedTagBridge? = null
    private val timerEventTags = MutableStateFlow<List<Tag>>(emptyList())
    private val timerSinceWhenTags = MutableStateFlow<List<Tag>>(emptyList())

    private fun bindTimerSharedTags(context: Context) {
        if (timerSharedTagBridge != null) return
        val bridge = TimerSharedTagBridge(PersonalHubDatabase.get(context.applicationContext))
        timerSharedTagBridge = bridge
        viewModelScope.launch {
            bridge.observe(HubTagNamespaces.TIMER_EVENTS).collect { timerEventTags.value = it }
        }
        viewModelScope.launch {
            bridge.observe(HubTagNamespaces.TIMER_SINCE_WHEN).collect { timerSinceWhenTags.value = it }
        }
    }

    // --- Capsule Gateway (architectural lock)
    private val capsuleGateway by lazy {
        com.example.multitimetracker.capsules.system.CapsuleGateway(
        runtimeScope = viewModelScope,
        alertsAccess = object : com.example.multitimetracker.capsules.system.AlertsCapsuleAccess {
            override fun hostStateFlow(): StateFlow<AlertsHostState> = alertsHostState
            override fun appContextOrNull(): Context? = appContext
            override fun resolveSessionStartAtMs(sessionId: Long, nowMs: Long): Long? {
                requireSessionOnlyMode(true)
                sessionOwnerCapsule.runtimeStateValue().runningSessions
                    .firstOrNull { it.id == sessionId && it.endMs == null }
                    ?.startMs
                    ?.let { return it }
                val ctx = appContext ?: return null
                return runCatching {
                    sessionCore(ctx)
                        .readSessionById(sessionId = sessionId)
                        ?.takeIf { it.endMs == null }
                        ?.startMs
                }.getOrNull()
            }
            override fun tags(): List<Tag> = tagsCapsule.tags()
            override fun runningSessions(): List<com.example.multitimetracker.model.SessionUi> =
                sessionOwnerCapsule.runtimeStateValue().runningSessions
            override fun persist(): Unit = this@MainViewModel.persist()
            override fun scheduleAutoBackup(): Unit = this@MainViewModel.scheduleAutoBackup()

            override fun persistAsync(): Unit = this@MainViewModel.persistAsync()

            override fun logUserEvent(
                action: String,
                entityType: String,
                entityId: Long,
                summary: String,
                payload: org.json.JSONObject?,
                undoable: Boolean
            ) {
                // Canonical database triggers feed the PersonalHub activity register.
            }

            override fun logSystemEvent(
                action: String,
                entityType: String?,
                entityId: Long?,
                summary: String,
                payload: org.json.JSONObject?
            ) {
                // Canonical database triggers feed the PersonalHub activity register.
            }
        },
        chainsAccess = object : com.example.multitimetracker.capsules.system.ChainsCapsuleAccess {
            override fun hostStateFlow(): StateFlow<ChainsHostState> = chainsHostState
            override fun appContextOrNull(): Context? = appContext
            override fun blockWriteIfNeeded(): Boolean = this@MainViewModel.blockWriteIfNeeded()
            override fun requireSessionOnlyMode() {
                requireSessionOnlyMode(true)
            }
            override fun sessionCore(context: Context): com.example.multitimetracker.core.session.SessionCore =
                this@MainViewModel.sessionCore(context)
            override fun launchIo(reason: String, block: suspend () -> Unit) {
                viewModelScope.launch(Dispatchers.IO) { block() }
            }
            override fun handleTimeFenceEvents(events: List<TimeFenceEvent>, nowMs: Long) {
                this@MainViewModel.handleTimeFenceEvents(events, nowMs)
            }
            override fun logUserEvent(
                action: String,
                entityType: String?,
                entityId: Long?,
                summary: String,
                payload: JSONObject?,
                undoable: Boolean
            ) {
                // Canonical database triggers feed the PersonalHub activity register.
            }
            override fun persist() = this@MainViewModel.persist()
            override fun scheduleAutoBackup() = this@MainViewModel.scheduleAutoBackup()
            override fun scheduleSessionsRefresh(context: Context, nowMs: Long) {
                this@MainViewModel.scheduleSessionsRefresh(context, nowMs)
            }
        },
        nowAccess = object : com.example.multitimetracker.capsules.system.NowCapsuleAccess {
            override fun uiStateFlow(): StateFlow<NowUiState> = nowFeatureState

            override fun addTag(name: String) {
                tagsCapsule.addTag(name)
            }
        },
        tagsAccess = object : com.example.multitimetracker.capsules.system.TagsCapsuleAccess {
            override fun hostStateFlow(): StateFlow<TagsHostState> = tagsHostState
            override fun runtimeScope() = viewModelScope
            override fun tags(): List<Tag> = tagsCapsule.tags()
            override fun runningSessions(): List<com.example.multitimetracker.model.SessionUi> =
                sessionOwnerCapsule.runtimeStateValue().runningSessions
            override fun chronologySessions(): List<com.example.multitimetracker.model.SessionUi> =
                sessionOwnerCapsule.runtimeStateValue().chronologySessions
            override fun tasks(): List<Task> = sessionOwnerCapsule.runtimeStateValue().tasks
            override fun sessionsRuntime() = sessionOwnerCapsule

            override fun appContextOrNull(): Context? = appContext
            override fun blockWriteIfNeeded(): Boolean = this@MainViewModel.blockWriteIfNeeded()
            override fun createTagRecord(name: String): Tag = engine.createTag(name)
            override fun renameTagRecords(tags: List<Tag>, tagId: Long, newName: String): List<Tag> =
                engine.renameTag(tags, tagId, newName)
            override fun deleteTagRecords(
                tasks: List<Task>,
                tags: List<Tag>,
                tagId: Long,
                deleteAssociatedTasks: Boolean,
                nowMs: Long
            ): TimeEngine.EngineResult = engine.deleteTag(tasks, tags, tagId, deleteAssociatedTasks, nowMs)
            override fun restoreTagRecords(
                tasks: List<Task>,
                tags: List<Tag>,
                tagId: Long,
                nowMs: Long
            ): TimeEngine.EngineResult = engine.restoreTag(tasks, tags, tagId, nowMs)
            override fun purgeTagRecords(tasks: List<Task>, tags: List<Tag>, tagId: Long): TimeEngine.EngineResult =
                engine.purgeTag(tasks, tags, tagId)
            override fun closedSessionRecords(): List<ClosedSessionRecord> = engine.getClosedSessionRecords()
            override fun taggedSessionRecords(): List<TaggedSessionRecord> = engine.getTaggedSessionRecords()
            override fun activeTagStartByTagId(): Map<Long, Long> = computeActiveTagStartByTagId()
            override fun sessionCore(context: Context): com.example.multitimetracker.core.session.SessionCore =
                this@MainViewModel.sessionCore(context)
            override fun launchIo(reason: String, block: suspend () -> Unit) {
                viewModelScope.launch(Dispatchers.IO) {
                    block()
                }
            }
            override fun showTagAlreadyExists(context: Context) {
                Toast.makeText(context, context.getString(R.string.tag_already_exists), Toast.LENGTH_SHORT).show()
            }
            override fun showTagHierarchyCycleNotAllowed(context: Context) {
                Toast.makeText(context, context.getString(R.string.tag_hierarchy_cycle_not_allowed), Toast.LENGTH_LONG).show()
            }
            override fun showSessionWriteFailed(context: Context) {
                Toast.makeText(context, context.getString(R.string.session_write_failed), Toast.LENGTH_SHORT).show()
            }
            override fun logUserEvent(
                action: String,
                entityType: String,
                entityId: Long,
                summary: String,
                payload: JSONObject?,
                undoable: Boolean
            ) {
                // Canonical database triggers feed the PersonalHub activity register.
            }
            override fun rememberCurrentStateAsPersisted(): Unit = this@MainViewModel.rememberCurrentStateAsPersisted()
            override fun clearPersistenceFailureReport() {
                _persistenceFailureReport.value = null
            }
            override fun persist(): Unit = this@MainViewModel.persist()
            override fun scheduleAutoBackup(): Unit = this@MainViewModel.scheduleAutoBackup()
        },
        timelineAccess = object : com.example.multitimetracker.capsules.system.TimelineCapsuleAccess {
            override fun uiStateFlow(): StateFlow<TimelineUiState> = timelineFeatureState

            override fun addTag(name: String) {
                tagsCapsule.addTag(name)
            }
        },
        quickEventsAccess = object : com.example.multitimetracker.capsules.system.QuickEventsCapsuleAccess {
            override fun hostStateFlow(): StateFlow<QuickEventsHostState> = quickEventsHostState
            override fun appContextOrNull(): Context? = appContext
            override fun blockWriteIfNeeded(): Boolean = this@MainViewModel.blockWriteIfNeeded()
            override fun quickEventCore(context: Context): com.example.multitimetracker.core.quickevent.QuickEventCore =
                this@MainViewModel.quickEventCore(context)
            override fun launchIo(reason: String, block: suspend () -> Unit) {
                viewModelScope.launch(Dispatchers.IO) { block() }
            }
            override fun logUserEvent(
                action: String,
                entityType: String?,
                entityId: Long?,
                summary: String,
                payload: JSONObject?,
                undoable: Boolean
            ) {
                // Canonical database triggers feed the PersonalHub activity register.
            }
            override fun persist() = this@MainViewModel.persist()
            override fun scheduleAutoBackup() = this@MainViewModel.scheduleAutoBackup()
            override fun showWriteFailed(context: Context) {
                viewModelScope.launch(Dispatchers.Main) {
                    Toast.makeText(context, context.getString(R.string.quick_event_write_failed), Toast.LENGTH_SHORT).show()
                }
            }
            override fun showDeleteFailed(context: Context) {
                viewModelScope.launch(Dispatchers.Main) {
                    Toast.makeText(context, context.getString(R.string.quick_event_delete_failed), Toast.LENGTH_SHORT).show()
                }
            }
            override fun showTargetRecorded(context: Context, title: String) {
                viewModelScope.launch(Dispatchers.Main) {
                    Toast.makeText(context, context.getString(R.string.quick_event_recorded, title), Toast.LENGTH_SHORT).show()
                }
            }
            override fun addTag(name: String) {
                val bridge = timerSharedTagBridge ?: return
                viewModelScope.launch(Dispatchers.IO) { bridge.add(HubTagNamespaces.TIMER_EVENTS, name) }
            }
            override fun syncSharedTagAssignments(snapshot: QuickEventsSnapshot) {
                val bridge = timerSharedTagBridge ?: return
                viewModelScope.launch(Dispatchers.IO) { bridge.syncEvents(snapshot) }
            }
        },
        sessionOwnerAccess = object : com.example.multitimetracker.capsules.system.SessionOwnerCapsuleAccess {
            override fun runtimeScope() = viewModelScope
            override fun tags(): List<Tag> = tagsCapsule.tags()
            override fun appContextOrNull(): Context? = appContext
            override fun blockWriteIfNeeded(): Boolean = this@MainViewModel.blockWriteIfNeeded()
            override fun requireSessionOnlyMode() {
                requireSessionOnlyMode(true)
            }
            override fun sessionCore(context: Context): com.example.multitimetracker.core.session.SessionCore =
                this@MainViewModel.sessionCore(context)
            override fun launchIo(reason: String, block: suspend () -> Unit) {
                viewModelScope.launch(Dispatchers.IO) { block() }
            }
            override fun handleTimeFenceEvents(events: List<TimeFenceEvent>, nowMs: Long) {
                this@MainViewModel.handleTimeFenceEvents(events, nowMs)
            }
            override suspend fun advanceChainOnSessionStop(stoppedSessionId: Long, nowMs: Long, context: Context) {
                chainsCapsule.advanceOnSessionStop(stoppedSessionId, nowMs, context)
            }
            override fun logUserEvent(
                action: String,
                entityType: String?,
                entityId: Long?,
                summary: String,
                payload: JSONObject?,
                undoable: Boolean
            ) {
                // Canonical database triggers feed the PersonalHub activity register.
            }
            override fun logSystemEvent(
                action: String,
                entityType: String?,
                entityId: Long?,
                summary: String,
                payload: JSONObject?
            ) {
                // Canonical database triggers feed the PersonalHub activity register.
            }
            override fun scheduleSessionsRefresh(context: Context, nowMs: Long) {
                this@MainViewModel.scheduleSessionsRefresh(context, nowMs)
            }
            override fun scheduleAutoBackup() = this@MainViewModel.scheduleAutoBackup()
            override fun persist() = this@MainViewModel.persist()
            override fun showSessionWriteFailed(context: Context) {
                Toast.makeText(context, context.getString(R.string.session_write_failed), Toast.LENGTH_SHORT).show()
            }
            override fun showSessionDeleteFailed(context: Context) {
                Toast.makeText(context, context.getString(R.string.session_delete_failed), Toast.LENGTH_SHORT).show()
            }
            override fun onCreatedOnMain(
                created: com.example.multitimetracker.model.SessionUi,
                callback: (com.example.multitimetracker.model.SessionUi) -> Unit
            ) {
                callback(created)
            }
        },
        sinceWhenAccess = object : com.example.multitimetracker.capsules.system.SinceWhenCapsuleAccess {
            override fun hostState(): SinceWhenHostState = sinceWhenHostState.value
            override fun observeHostState(onChanged: (SinceWhenHostState) -> Unit) {
                viewModelScope.launch {
                    sinceWhenHostState.collect(onChanged)
                }
            }
            override fun blockWriteIfNeeded(): Boolean = this@MainViewModel.blockWriteIfNeeded()
            override fun touchNow() {
                _state.update { it.copy(nowMs = System.currentTimeMillis()) }
            }
            override fun persist() = this@MainViewModel.persist()
            override fun scheduleAutoBackup() = this@MainViewModel.scheduleAutoBackup()
            override fun syncSharedTagAssignments(periods: List<com.example.multitimetracker.model.LifePeriod>) {
                val bridge = timerSharedTagBridge ?: return
                viewModelScope.launch(Dispatchers.IO) { bridge.syncSinceWhen(periods) }
            }
        }

    )
    }

    private val alertsCapsuleVm: AlertsCapsuleViewModel get() = capsuleGateway.alerts
    val alertsCapsule: AlertsCapsuleViewModel get() = capsuleGateway.alerts
    val nowCapsule: NowCapsuleViewModel get() = capsuleGateway.now
    val sessionOwnerCapsule: com.example.multitimetracker.capsules.sessions.controller.SessionOwnerCapsuleViewModel get() = capsuleGateway.sessionOwner
    val sinceWhenCapsule: com.example.multitimetracker.capsules.sincewhen.controller.SinceWhenCapsuleViewModel get() = capsuleGateway.sinceWhen
    val tagsCapsule: TagsCapsuleViewModel get() = capsuleGateway.tags
    val timelineCapsule: com.example.multitimetracker.capsules.timeline.controller.TimelineCapsuleViewModel get() = capsuleGateway.timeline
    val quickEventsCapsule: com.example.multitimetracker.capsules.quickevents.controller.QuickEventsCapsuleViewModel get() = capsuleGateway.quickEvents
    val chainsCapsule: com.example.multitimetracker.capsules.chains.controller.ChainsCapsuleViewModel get() = capsuleGateway.chains

    private fun capsuleRuntimeParticipants(): List<CapsuleRuntimeParticipant> = listOf(
        nowCapsule,
        sessionOwnerCapsule,
        sinceWhenCapsule,
        tagsCapsule,
        timelineCapsule,
        quickEventsCapsule,
        chainsCapsule,
        alertsCapsule,
    ).filterIsInstance<CapsuleRuntimeParticipant>()

    private fun notifyCapsuleRuntimeChanged(context: Context?, change: CapsuleRuntimeChange) {
        capsuleRuntimeParticipants().forEach { participant ->
            runCatching {
                when (change) {
                    CapsuleRuntimeChange.SNAPSHOT_RELOAD ->
                        context?.let(participant::refreshAfterSnapshot)
                }
            }.onFailure { error ->
                Log.e("MainViewModel", "capsule runtime callback failed capsule=${participant.capsuleId} change=$change", error)
            }
        }
    }

private var initialized = false
    // Foreground app usage tracking (UI only). Persisted into snapshot + CSV.
    private var appForegroundStartMs: Long? = null

    /**
     * Allows MainActivity to provide an application context before the main initialize()
     * (useful for first-run setup/import flows).
     */
    fun bindContext(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
        bindTimerSharedTags(context)
    }

    private val _persistenceFailureReport = MutableStateFlow<String?>(null)
    val persistenceFailureReport: StateFlow<String?> = _persistenceFailureReport
    fun setPersistenceFailureReport(report: String?) {
        _persistenceFailureReport.value = report
    }

    private val _state = MutableStateFlow(
        UiState(
            appUsageMs = 0L,
            installAtMs = System.currentTimeMillis(),
            nowMs = System.currentTimeMillis()
        )
    )
    val state: StateFlow<UiState> = _state

    private val tagsHostState: StateFlow<TagsHostState> = combine(state, timerEventTags, timerSinceWhenTags) { source, eventTags, sinceTags ->
        TagsHostState(
            nowMs = source.nowMs,
            effectiveNowMs = source.nowMs,
            isReadOnly = source.isReadOnly,
            eventTags = eventTags,
            sinceWhenTags = sinceTags,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TagsHostState(
        nowMs = _state.value.nowMs,
        effectiveNowMs = _state.value.nowMs,
        isReadOnly = false,
    ))

    private val nowFeatureState: StateFlow<NowUiState> = combine(state, tagsCapsule.uiState) { source, tagsState ->
        NowUiState(
            tags = tagsState.tags,
            chronologySessions = tagsState.chronologySessions,
            runningSessions = tagsState.runningSessions,
            activeTagTotalsMsByTagId = tagsState.activeTagTotalsMsByTagId,
            runningMinStartByTagId = tagsState.runningMinStartByTagId,
            tagLastUsedMsByTagId = tagsState.tagLastUsedMsByTagId,
            tagParentsByChild = tagsState.tagParentsByChild,
            nowMs = source.nowMs,
            isReadOnly = source.isReadOnly,
            homeLoadState = source.homeLoadState,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, NowUiState(
        tags = emptyList(),
        chronologySessions = emptyList(),
        runningSessions = emptyList(),
        activeTagTotalsMsByTagId = emptyMap(),
        runningMinStartByTagId = emptyMap(),
        tagLastUsedMsByTagId = emptyMap(),
        tagParentsByChild = emptyMap(),
        nowMs = _state.value.nowMs,
        isReadOnly = false,
        homeLoadState = HomeLoadState.Loading,
    ))

    private val quickEventsHostState: StateFlow<QuickEventsHostState> = combine(state, timerEventTags) { source, eventTags ->
        QuickEventsHostState(
            tags = eventTags,
            tagLastUsedMsByTagId = sessionOwnerCapsule.runtimeStateValue().tagLastUsedMsByTagId,
            nowMs = source.nowMs,
            isReadOnly = source.isReadOnly,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, QuickEventsHostState(
        tags = emptyList(),
        tagLastUsedMsByTagId = emptyMap(),
        nowMs = _state.value.nowMs,
        isReadOnly = false,
    ))

    private val timelineFeatureState: StateFlow<TimelineUiState> = combine(state, quickEventsCapsule.uiState, tagsCapsule.uiState) { source, quickEvents, tagsState ->
        TimelineUiState(
            tags = tagsState.tags,
            chronologySessions = tagsState.chronologySessions,
            runningSessions = tagsState.runningSessions,
            quickEventEntries = quickEvents.quickEventEntries,
            tagLastUsedMsByTagId = tagsState.tagLastUsedMsByTagId,
            tagParentsByChild = tagsState.tagParentsByChild,
            nowMs = source.nowMs,
            isReadOnly = source.isReadOnly,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TimelineUiState(
        tags = emptyList(),
        chronologySessions = emptyList(),
        runningSessions = emptyList(),
        quickEventEntries = emptyList(),
        tagLastUsedMsByTagId = emptyMap(),
        tagParentsByChild = emptyMap(),
        nowMs = _state.value.nowMs,
        isReadOnly = false,
    ))

    private val sinceWhenHostState: StateFlow<SinceWhenHostState> = combine(state, timerSinceWhenTags) { source, sinceTags ->
        SinceWhenHostState(
            tags = sinceTags,
            nowMs = source.nowMs,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SinceWhenHostState(
        tags = emptyList(),
        nowMs = _state.value.nowMs,
    ))

    private val alertsHostState: StateFlow<AlertsHostState> = state.map { source ->
        AlertsHostState(
            tags = tagsCapsule.tags(),
            tagLastUsedMsByTagId = sessionOwnerCapsule.runtimeStateValue().tagLastUsedMsByTagId,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AlertsHostState(
        tags = emptyList(),
        tagLastUsedMsByTagId = emptyMap(),
    ))

    private val chainsHostState: StateFlow<ChainsHostState> = state.map { source ->
        ChainsHostState(
            tags = tagsCapsule.tags(),
            isReadOnly = source.isReadOnly,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ChainsHostState(
        tags = emptyList(),
        isReadOnly = false,
    ))

    // v423: FAIL-FAST data integrity gate (blocks the app if DB is inconsistent).
    private val _integrityBlock = MutableStateFlow<DataIntegrityGate.GateResult?>(null)
    val integrityBlock: StateFlow<DataIntegrityGate.GateResult?> = _integrityBlock

    private val snapshotCoordinator by lazy {
        MainViewModelSnapshotCoordinator(
            currentAppVersionCodeLong = { context -> HostAppVersion.current(context).code },
            autoConsistencyRevision = AppPatchVersion.AUTO_CONSISTENCY_REVISION,
            viewModelScope = viewModelScope,
            appContext = { appContext },
            readState = { _state.value },
            updateState = { transform -> _state.update(transform) },
            sessionCore = ::sessionCore,
            importRuntimeSnapshot = { tasks, tags, closedSessions, tagSessions, runtime ->
                engine.importRuntimeSnapshot(
                    tasks = tasks,
                    tags = tags,
                    closedSessionsSnapshot = closedSessions,
                    tagSessionsSnapshot = tagSessions,
                    snapshot = runtime,
                )
            },
            exportRuntimeSnapshot = engine::exportRuntimeSnapshot,
            readLifePeriods = { sinceWhenCapsule.lifePeriods() },
            replaceLifePeriods = { periods -> sinceWhenCapsule.replaceLifePeriods(periods) },
            readTimeFenceRules = { alertsCapsule.rules() },
            replaceTimeFenceRules = { rules -> alertsCapsule.replaceTimeFenceRules(rules) },
            readQuickEvents = { quickEventsCapsule.snapshot() },
            replaceQuickEvents = { snapshot -> quickEventsCapsule.replaceSnapshot(snapshot) },
            readChains = { chainsCapsule.snapshot() },
            replaceChains = { snapshot -> chainsCapsule.replaceSnapshot(snapshot) },
            readSessionsRuntime = { sessionOwnerCapsule.runtimeStateValue() },
            replaceSessionsRuntime = { runtime -> sessionOwnerCapsule.replaceRuntimeState(runtime) },
            readTags = { tagsCapsule.tags() },
            replaceTags = { tags -> tagsCapsule.replaceTags(tags) },
            prepareSnapshotTags = { snapshot -> tagsCapsule.prepareSnapshotTags(snapshot) },
            mergeSnapshotTags = { canonicalTags, runtimeTags ->
                tagsCapsule.mergeSnapshotTags(canonicalTags, runtimeTags)
            },
            selectCanonicalTags = { currentTags, persistedCanonicalTags ->
                tagsCapsule.selectCanonicalTags(currentTags, persistedCanonicalTags)
            },
            selectTagsForAuthoritativeRead = { context, currentTags, persistedCanonicalTags ->
                tagsCapsule.selectTagsForAuthoritativeRead(context, currentTags, persistedCanonicalTags)
            },
            rememberRuntimeCanonicalTags = { tags -> tagsCapsule.rememberRuntimeCanonicalTags(tags) },
            replaceRuntimeCanonicalTags = { tags -> tagsCapsule.replaceRuntimeCanonicalTags(tags) },
            repairAutoDerivedSessionTitlesFromPersistedSnapshot = { context, tags ->
                tagsCapsule.repairAutoDerivedSessionTitlesFromPersistedSnapshot(context, tags)
            },
            repairPersistedTagNames = { context, tags -> tagsCapsule.repairPersistedTagNames(context, tags) },
            withResolvedTagDefinitions = { snapshot, canonicalTags ->
                tagsCapsule.withResolvedTagDefinitions(snapshot, canonicalTags)
            },
            withResolvedTagSessionNames = { snapshot -> tagsCapsule.withResolvedTagSessionNames(snapshot) },
            prepareSessionsSnapshotReadModel = { context, snapshot, tags ->
                sessionOwnerCapsule.prepareSnapshotReadModel(context, snapshot, tags)
            },
            readAuthoritativeSessionsSnapshotReadModel = { context, tags, persistedSnapshotTags ->
                sessionOwnerCapsule.readAuthoritativeSnapshotReadModel(context, tags, persistedSnapshotTags)
            },
            resolveSessionsSnapshotReadModelTags = { readModel, tags ->
                sessionOwnerCapsule.resolveSnapshotReadModelTags(readModel, tags)
            },
            bootstrapSessionsTablesIfEmpty = { context, legacyTasks, tags, legacyClosedSessionRecords, nowMs, appVersion ->
                sessionOwnerCapsule.bootstrapTablesIfEmpty(
                    context = context,
                    legacyTasks = legacyTasks,
                    tags = tags,
                    legacyClosedSessionRecords = legacyClosedSessionRecords,
                    nowMs = nowMs,
                    currentAppVersionCode = appVersion,
                )
            },
            readTagParentsByChild = { tagsCapsule.tagParentsByChild() },
            replaceTagParentsByChild = { parents -> tagsCapsule.replaceTagParentsByChild(parents) },
            reconcileRuntimeAlarms = { context, rules, sessions, tags, nowMs ->
                alertsCapsuleVm.reconcileSnapshotRuntimeAlarms(context, rules, sessions, tags, nowMs)
            },
            setPersistenceFailureReport = { report -> _persistenceFailureReport.value = report },
            onSnapshotReloaded = { context -> notifyCapsuleRuntimeChanged(context, CapsuleRuntimeChange.SNAPSHOT_RELOAD) },
            showPersistenceFailureToast = { ctx ->
                Toast.makeText(ctx, ctx.getString(R.string.session_write_failed), Toast.LENGTH_LONG).show()
            },
        )
    }

    private val recoveryCoordinator by lazy {
        MainViewModelRecoveryCoordinator(
            snapshotCoordinator = snapshotCoordinator,
            isInitialized = { initialized },
            setInitialized = { initialized = it },
            integrityBlock = { _integrityBlock.value },
            setIntegrityBlock = { _integrityBlock.value = it },
            resetToFreshInstallState = { nowMs ->
                sinceWhenCapsule.replaceLifePeriods(emptyList())
                tagsCapsule.replaceTags(emptyList())
                tagsCapsule.replaceTagParentsByChild(emptyMap())
                sessionOwnerCapsule.replaceRuntimeState(SessionsRuntimeState())
                _state.update {
                    it.copy(
                        appUsageMs = 0L,
                        nowMs = nowMs,
                        homeLoadState = HomeLoadState.ReadyEmpty,
                    )
                }
            },
            setHomeLoadState = { loadState ->
                _state.update { it.copy(homeLoadState = loadState) }
            },
            appForegroundStartMs = { appForegroundStartMs },
            stateAppUsageRunningSinceMs = { _state.value.appUsageRunningSinceMs },
            clearForegroundUsageTracking = { appForegroundStartMs = null },
            clearRunningUsageWithoutPersist = {
                _state.update { it.copy(appUsageRunningSinceMs = null) }
            },
            applyBackgroundUsageDelta = { delta ->
                _state.update {
                    it.copy(
                        appUsageMs = it.appUsageMs + delta,
                        appUsageRunningSinceMs = null,
                    )
                }
                if (delta > 0L) {
                    persistAsync(PersistentSaveOrigin.APP_BACKGROUND)
                    scheduleAutoBackup()
                }
            },
        )
    }

    init {
        // Tick per aggiornare SOLO la UI (non salva nulla ogni secondo).
        viewModelScope.launch {
            engine.uiTickerFlow().collect { now ->
                _state.update { it.copy(nowMs = now) }
            }
        }
    }

    private fun blockWriteIfNeeded(): Boolean = false

    fun onAppForeground(context: Context, nowMs: Long = System.currentTimeMillis()) {
        if (appForegroundStartMs != null) return
        logAppVersionIfNeeded(context)
        appForegroundStartMs = nowMs
        _state.update { it.copy(appUsageRunningSinceMs = nowMs) }
    }
    fun onAppBackground(nowMs: Long = System.currentTimeMillis()) {
        recoveryCoordinator.onAppBackground(nowMs)
    }

    /**
     * Must be called once at app start (MainActivity does this).
     * Restores persisted snapshot and AUTO-RESUMES any running tasks after process death.
     */
    fun initialize(context: Context, fastHomeAlreadyApplied: Boolean = false) {
        appContext = context.applicationContext
        bindTimerSharedTags(context)
        recoveryCoordinator.initialize(
            context = context,
            fastHomeAlreadyApplied = fastHomeAlreadyApplied,
        )
        notifyCapsuleRuntimeChanged(context, CapsuleRuntimeChange.SNAPSHOT_RELOAD)
    }

    fun loadStartupHomeFromSessionTables(context: Context): Boolean {
        bindContext(context)
        return snapshotCoordinator.loadStartupHomeFromSessionTables(context)
    }

    fun clearIntegrityBlock() {
        recoveryCoordinator.clearIntegrityBlock()
    }


/**
 * Reloads the persisted snapshot from disk and applies it to the in-memory state.
 * Used when an external component (e.g. the widget) mutates the snapshot while the app is open.
 */
fun reloadFromSnapshot(context: Context) {
    bindContext(context)
    snapshotCoordinator.reloadFromSnapshot(context)
}
    fun hasPersistedInternalData(context: Context): Boolean {
        bindContext(context)
        return SnapshotStore.load(context) != null
    }

    fun hasPersistedInternalDataFast(context: Context): Boolean {
        bindContext(context)
        return runCatching { SnapshotSqlite.hasSnapshot(context) }.getOrDefault(false)
    }

    private fun persist() {
        snapshotCoordinator.persist()
        syncTimerNowTags()
    }

    private fun persistOrThrow(showFailureUi: Boolean) = snapshotCoordinator.persistOrThrow(showFailureUi)

    private fun persistAsync(
        origin: PersistentSaveOrigin = PersistentSaveOrigin.STATE_MUTATION,
    ) = snapshotCoordinator.persistAsync(origin)

    private fun rememberCurrentStateAsPersisted() = snapshotCoordinator.rememberCurrentStateAsPersisted()

    private fun scheduleAutoBackup() = snapshotCoordinator.scheduleAutoBackup()
    private fun syncTimerNowTags() {
        val bridge = timerSharedTagBridge ?: return
        val sessions = sessionOwnerCapsule.runtimeStateValue().let { it.chronologySessions + it.runningSessions }
        viewModelScope.launch(Dispatchers.IO) { bridge.syncNow(tagsCapsule.tags(), sessions) }
    }
    private fun scheduleSessionsRefresh(context: Context, nowMs: Long) {
        snapshotCoordinator.scheduleSessionsRefresh(context, nowMs)
    }

    private fun computeActiveTagStartByTagId(): Map<Long, Long> {
        requireSessionOnlyMode(true)
        return sessionOwnerCapsule.runtimeStateValue().runningMinStartByTagId
            .filterValues { it > 0L }
    }

    private fun handleTimeFenceEvents(events: List<TimeFenceEvent>, nowMs: Long) {
        alertsCapsuleVm.handleTimeFenceEvents(events = events, nowMs = nowMs)
    }

    /**
     * Lightweight refresh used after owner-capsule undo operations.
     * Most mutations already update [_state], but some undo paths rely on a refresh.
     */
    private fun refreshRuntimeAfterUndo() {
        val now = System.currentTimeMillis()
        sessionOwnerCapsule.updateRuntimeState { s ->
            s.copy(
                closedSessions = engine.getClosedSessionRecords(),
                tagSessions = engine.getTaggedSessionRecords(),
            )
        }
        _state.update { it.copy(nowMs = now) }
        persist()
    }

    /**
     * Backwards-compatible wrapper used by audit undo payloads.
     */
    private fun editClosedSessionRecordTimeRange(sessionId: Long, sessionIndex: Int, startTs: Long, endTs: Long) {
        failLegacyTaskPath()
    }
}
