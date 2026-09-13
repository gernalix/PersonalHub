package com.example.multitimetracker.capsules.sessions.controller

import android.content.Context
import android.util.Log
import com.example.multitimetracker.R
import com.example.multitimetracker.RandomTimerStore
import com.example.multitimetracker.SingleSubmitGuard
import com.example.multitimetracker.canonicalizeTaggedSessionRecords
import com.example.multitimetracker.capsules.alerts.public.TimeFenceEvent
import com.example.multitimetracker.capsules.sessions.public.SessionOwnerPublicApi
import com.example.multitimetracker.capsules.sessions.public.SessionsSnapshotReadModel
import com.example.multitimetracker.capsules.sessions.public.SessionsRuntimePublicApi
import com.example.multitimetracker.capsules.sessions.public.SessionsRuntimeState
import com.example.multitimetracker.capsules.system.SessionOwnerCapsuleAccess
import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.Task
import com.example.multitimetracker.model.TimeEngine
import com.example.multitimetracker.model.TimeFenceTrigger
import com.example.multitimetracker.export.buildSessionOnlyRuntimeTasks
import com.example.multitimetracker.core.session.SessionMirrorCore
import com.example.multitimetracker.newSessionSubmitKey
import com.example.multitimetracker.persistence.AuthoritativeSessionCache
import com.example.multitimetracker.persistence.AuthoritativeSessionCacheBuilder
import com.example.multitimetracker.persistence.AuditLogSqlite
import com.example.multitimetracker.persistence.SnapshotStore
import com.example.multitimetracker.persistence.UiPrefsStore
import com.example.multitimetracker.requireNoLegacyRuntimeBootstrap
import com.example.multitimetracker.shouldEmitStartForRunningSessionMetaUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class SessionOwnerCapsuleViewModel(
    private val access: SessionOwnerCapsuleAccess
) : SessionOwnerPublicApi, SessionsRuntimePublicApi {
    private val newSessionSubmitGuard = SingleSubmitGuard()
    private val liveRuntimeState = MutableStateFlow(SessionsRuntimeState())
    private val timeMachineRuntimeState = MutableStateFlow<SessionsRuntimeState?>(null)
    override val runtimeState: StateFlow<SessionsRuntimeState> =
        combine(liveRuntimeState, timeMachineRuntimeState) { live, projected ->
            projected ?: live
        }.stateIn(
            access.runtimeScope(),
            SharingStarted.Eagerly,
            SessionsRuntimeState(),
        )

    override fun runtimeStateValue(): SessionsRuntimeState = runtimeState.value

    override fun replaceRuntimeState(state: SessionsRuntimeState) {
        liveRuntimeState.value = state
    }

    override fun updateRuntimeState(transform: (SessionsRuntimeState) -> SessionsRuntimeState) {
        liveRuntimeState.update(transform)
    }

    override fun prepareSnapshotReadModel(
        context: Context,
        snapshot: SnapshotStore.Snapshot,
        tags: List<Tag>,
    ): SessionsSnapshotReadModel {
        val authoritative = ensureAuthoritativeSessionCache(
            context = context,
            snapshot = snapshot,
            tags = tags,
        )
        return authoritative.toSnapshotReadModel(tags = authoritative.tags)
    }

    override fun readAuthoritativeSnapshotReadModel(
        context: Context,
        tags: List<Tag>,
        persistedSnapshotTags: List<Tag>,
    ): SessionsSnapshotReadModel? {
        val authoritative = buildAuthoritativeSessionCache(
            context = context,
            tags = tags,
            persistedSnapshotTags = persistedSnapshotTags,
        ) ?: return null
        return authoritative.toSnapshotReadModel(tags = authoritative.tags)
    }

    override fun resolveSnapshotReadModelTags(
        readModel: SessionsSnapshotReadModel,
        tags: List<Tag>,
    ): SessionsSnapshotReadModel {
        val sessionTitlesById = (readModel.runtimeState.chronologySessions + readModel.runtimeState.runningSessions)
            .distinctBy { it.id }
            .associate { it.id to it.title }
        return readModel.copy(
            tags = tags,
            runtimeState = readModel.runtimeState.copy(
                tagSessions = canonicalizeTaggedSessionRecords(
                    tagSessions = readModel.runtimeState.tagSessions,
                    tags = tags,
                    sessionTitlesById = sessionTitlesById,
                ),
            ),
        )
    }

    override fun buildRuntimeSnapshotFromRunningSessions(
        runningSessions: List<SessionUi>,
    ): TimeEngine.RuntimeSnapshot {
        val activeSessionStart = runningSessions
            .filter { it.deletedAtMs == null && it.endMs == null }
            .associate { session -> session.id to session.startMs }
        val activeTagStart = runningSessions
            .asSequence()
            .filter { it.deletedAtMs == null && it.endMs == null }
            .flatMap { session ->
                session.tagIds.asSequence().map { tagId ->
                    Triple(session.id, tagId, session.startMs)
                }
            }
            .sortedRuntimeActiveTags()
        return TimeEngine.RuntimeSnapshot(
            activeSessionStart = activeSessionStart,
            activeTagStart = activeTagStart,
        )
    }

    override fun bootstrapTablesIfEmpty(
        context: Context,
        legacyTasks: List<Task>,
        tags: List<Tag>,
        legacyClosedSessionRecords: List<com.example.multitimetracker.core.contracts.ClosedSessionRecord>,
        nowMs: Long,
        currentAppVersionCode: Long,
    ) {
        try {
            val session = access.sessionCore(context)
            val alreadyDone = UiPrefsStore.getSessionsBootstrapDone(context)
            if (alreadyDone) return

            val lastLogged = UiPrefsStore.getLastLoggedAppVersionCode(context)
            UiPrefsStore.ensureFirstInstalledAppVersionCode(context, currentAppVersionCode)
            val actualFirstInstallTime = UiPrefsStore.currentFirstInstallTimeMs(context)
            val storedFirstInstallTime = UiPrefsStore.getStoredFirstInstallTimeMs(context)
            if (storedFirstInstallTime == null) {
                UiPrefsStore.setStoredFirstInstallTimeMs(context, actualFirstInstallTime)
            }
            val installTimeMismatch =
                (storedFirstInstallTime != null) && (storedFirstInstallTime != actualFirstInstallTime)
            if (installTimeMismatch) {
                UiPrefsStore.setSessionsBootstrapDone(context, true)
                UiPrefsStore.setStoredFirstInstallTimeMs(context, actualFirstInstallTime)
                return
            }
            val firstInstalled = UiPrefsStore.getFirstInstalledAppVersionCode(context)
            val isFreshInstall = (lastLogged == null) && (firstInstalled == currentAppVersionCode)
            if (isFreshInstall) {
                UiPrefsStore.setSessionsBootstrapDone(context, true)
                return
            }

            if (session.readAllSessions().isNotEmpty()) {
                UiPrefsStore.setSessionsBootstrapDone(context, true)
                return
            }

            val hasLegacySnapshotData =
                legacyTasks.isNotEmpty() || tags.isNotEmpty() || legacyClosedSessionRecords.isNotEmpty()
            if (hasLegacySnapshotData) {
                SessionMirrorCore.mirrorFromLegacySnapshot(
                    context = context,
                    tasks = legacyTasks,
                    tags = tags,
                    closedSessions = legacyClosedSessionRecords,
                    nowMs = nowMs,
                )
            }

            UiPrefsStore.setSessionsBootstrapDone(context, true)
        } catch (_: Throwable) {
            // Best-effort only.
        }
    }

    fun showTimeMachineRuntimeState(state: SessionsRuntimeState) {
        timeMachineRuntimeState.value = state
    }

    fun showTimeMachineSnapshot(
        snapshot: SnapshotStore.Snapshot,
        tags: List<Tag>,
        activeTagStartByTagId: Map<Long, Long>,
    ): SessionsRuntimeState {
        val runtime = buildTimeMachineRuntimeState(
            snapshot = snapshot,
            tags = tags,
            activeTagStartByTagId = activeTagStartByTagId,
        )
        showTimeMachineRuntimeState(runtime)
        return runtime
    }

    fun clearTimeMachineRuntimeState() {
        timeMachineRuntimeState.value = null
    }

    fun toggleSession(sessionId: Long) {
        if (access.blockWriteIfNeeded()) return
        val ctx = access.appContextOrNull() ?: return
        val now = System.currentTimeMillis()

        access.requireSessionOnlyMode()
        val session = access.sessionCore(ctx)
        val running = runtimeStateValue().runningSessions.firstOrNull { it.id == sessionId }

        if (running != null && running.endMs == null) {
            access.launchIo("stop sessionId=$sessionId") {
                runCatching {
                    stopSessionWithPolicies(ctx = ctx, sessionId = sessionId, endMs = now)
                }.onFailure { err ->
                    Log.e("SessionOwnerCapsule", "stopSessionWithPolicies failed", err)
                }
            }
            return
        }

        val base = runtimeStateValue().chronologySessions.firstOrNull { it.id == sessionId }
            ?: session.readSessionById(sessionId = sessionId)
            ?: return

        access.launchIo("start existing sessionId=$sessionId") {
            runCatching {
                val newSessionId = session.insertSession(
                    title = base.title,
                    startMs = now,
                    endMs = null,
                    tagIds = base.tagIds
                )

                val displayTitle = base.title.ifBlank { deriveSessionNameFromTags(base.tagIds, access.tags()) }
                access.logSystemEvent(
                    action = "ALERT_EVENT_EMITTED",
                    entityType = "SESSION",
                    entityId = newSessionId,
                    summary = "TimeFence event emitted",
                    payload = JSONObject()
                        .put("trigger", TimeFenceTrigger.ON_START.name)
                        .put("sessionId", newSessionId)
                        .put("title", displayTitle)
                        .put("tagIds", JSONArray(base.tagIds.toList().sorted()))
                )
                access.logUserEvent(
                    action = "SESSION_START",
                    entityType = "SESSION",
                    entityId = newSessionId,
                    summary = ctx.getString(R.string.audit_session_started, displayTitle.ifBlank { newSessionId.toString() }),
                    payload = JSONObject()
                        .put("sessionId", newSessionId)
                        .put("startMs", now)
                        .put("tagIds", JSONArray(base.tagIds.toList().sorted())),
                    undoable = false
                )
                access.handleTimeFenceEvents(
                    events = listOf(
                        TimeFenceEvent(
                            trigger = TimeFenceTrigger.ON_START,
                            sessionId = newSessionId,
                            sessionTitle = displayTitle,
                            sessionTagIds = base.tagIds
                        )
                    ),
                    nowMs = now
                )
                access.scheduleSessionsRefresh(ctx, now)
                access.scheduleAutoBackup()
            }.onFailure { err ->
                Log.e("SessionOwnerCapsule", "start session failed (sessionId=$sessionId)", err)
            }
        }
    }

    fun addSession(title: String, tagIds: Set<Long>) {
        if (access.blockWriteIfNeeded()) return
        access.requireSessionOnlyMode()
        val ctx = access.appContextOrNull() ?: return

        val now = System.currentTimeMillis()
        val session = access.sessionCore(ctx)
        val sessionId = session.insertSession(title = title, startMs = now, endMs = null, tagIds = tagIds)
        access.handleTimeFenceEvents(
            events = listOf(
                TimeFenceEvent(
                    trigger = TimeFenceTrigger.ON_START,
                    sessionId = sessionId,
                    sessionTitle = title.trim(),
                    sessionTagIds = tagIds
                )
            ),
            nowMs = now
        )

        access.scheduleSessionsRefresh(ctx, now)
        access.scheduleAutoBackup()
    }

    fun updateSession(sessionId: Long, newTitle: String, newTagIds: Set<Long>) {
        if (access.blockWriteIfNeeded()) return
        access.requireSessionOnlyMode()
        val ctx = access.appContextOrNull() ?: return
        val now = System.currentTimeMillis()
        access.sessionCore(ctx).updateSessionMeta(sessionId = sessionId, title = newTitle, tagIds = newTagIds)
        access.scheduleSessionsRefresh(ctx, now)
        access.scheduleAutoBackup()
    }

    fun updateSessionTags(sessionId: Long, newTagIds: Set<Long>) {
        val currentRuntime = runtimeStateValue()
        val curName = currentRuntime.chronologySessions.firstOrNull { it.id == sessionId }?.title
            ?: currentRuntime.runningSessions.firstOrNull { it.id == sessionId }?.title
            ?: ""
        updateSession(sessionId = sessionId, newTitle = curName, newTagIds = newTagIds)
    }

    fun deleteSession(sessionId: Long) {
        if (access.blockWriteIfNeeded()) return
        access.requireSessionOnlyMode()
        val ctx = access.appContextOrNull() ?: return
        val now = System.currentTimeMillis()
        val session = access.sessionCore(ctx)
        val runningSession = runtimeStateValue().runningSessions.firstOrNull { it.id == sessionId }
        if (runningSession != null && runningSession.endMs == null) {
            access.launchIo("delete running sessionId=$sessionId") {
                stopSessionWithPolicies(ctx = ctx, sessionId = sessionId, endMs = now)
                session.softDeleteSession(sessionId = sessionId)
                access.scheduleSessionsRefresh(ctx, now)
                access.scheduleAutoBackup()
            }
            return
        }

        session.softDeleteSession(sessionId = sessionId)
        access.scheduleSessionsRefresh(ctx, now)
        access.scheduleAutoBackup()
    }

    override fun createRunningSession(
        title: String,
        startMs: Long,
        tagIds: Set<Long>,
        onCreated: (SessionUi) -> Unit
    ) {
        if (access.blockWriteIfNeeded()) return
        val ctx = access.appContextOrNull() ?: return
        val now = System.currentTimeMillis()
        val cleanTitle = title.trim()
        val cleanTagIds = tagIds.filter { it > 0L }.toSet()
        if (!newSessionSubmitGuard.tryAccept(newSessionSubmitKey(cleanTitle, startMs, cleanTagIds))) return

        access.launchIo("create running session") {
            runCatching {
                val repo = access.sessionCore(ctx)
                val newId = repo.insertSession(
                    title = cleanTitle,
                    startMs = startMs,
                    endMs = null,
                    tagIds = cleanTagIds
                )
                val created = repo.readSessionById(sessionId = newId)
                    ?: SessionUi(
                        id = newId,
                        title = cleanTitle,
                        startMs = startMs,
                        endMs = null,
                        tagIds = cleanTagIds,
                        deletedAtMs = null
                    )

                updateRuntimeState { cur ->
                    val runningDedup = (listOf(created) + cur.runningSessions).distinctBy { it.id }
                    val chronologyDedup = (listOf(created) + cur.chronologySessions).distinctBy { it.id }
                    cur.copy(
                        runningSessions = runningDedup,
                        chronologySessions = chronologyDedup,
                    )
                }

                withContext(Dispatchers.Main.immediate) {
                    access.onCreatedOnMain(created, onCreated)
                    if (cleanTitle.isNotBlank() || cleanTagIds.isNotEmpty()) {
                        val displayTitle = cleanTitle.ifBlank { deriveSessionNameFromTags(cleanTagIds, access.tags()) }
                        access.handleTimeFenceEvents(
                            events = listOf(
                                TimeFenceEvent(
                                    trigger = TimeFenceTrigger.ON_START,
                                    sessionId = newId,
                                    sessionTitle = displayTitle,
                                    sessionTagIds = cleanTagIds
                                )
                            ),
                            nowMs = now
                        )
                    }
                }

                access.persist()
                access.scheduleSessionsRefresh(ctx, now)
            }.onFailure { err ->
                Log.e("SessionOwnerCapsule", "createRunningSession failed", err)
                withContext(Dispatchers.Main) {
                    access.showSessionWriteFailed(ctx)
                }
            }
        }
    }

    override fun createRandomTimerSession(
        startMs: Long,
        targetMinutes: Int,
        onCreated: (SessionUi) -> Unit,
    ) {
        if (access.blockWriteIfNeeded()) return
        val ctx = access.appContextOrNull() ?: return
        val cleanTarget = targetMinutes.coerceAtLeast(1)
        val expectedEndMs = startMs + cleanTarget.toLong() * 60_000L
        access.launchIo("create random timer") {
            runCatching {
                val repo = access.sessionCore(ctx)
                val newId = repo.insertSession(
                    title = "",
                    startMs = startMs,
                    endMs = null,
                    tagIds = emptySet(),
                    expectedEndMsOverride = expectedEndMs,
                )
                RandomTimerStore.saveRun(ctx, newId, cleanTarget)
                val created = repo.readSessionById(newId)
                    ?: SessionUi(
                        id = newId,
                        title = "",
                        startMs = startMs,
                        endMs = null,
                        expectedEndMs = expectedEndMs,
                        tagIds = emptySet(),
                        deletedAtMs = null,
                    )
                updateRuntimeState { cur ->
                    cur.copy(
                        runningSessions = (listOf(created) + cur.runningSessions).distinctBy { it.id },
                        chronologySessions = (listOf(created) + cur.chronologySessions).distinctBy { it.id },
                    )
                }
                withContext(Dispatchers.Main.immediate) {
                    access.onCreatedOnMain(created, onCreated)
                }
                access.persist()
                access.scheduleSessionsRefresh(ctx, startMs)
            }.onFailure { err ->
                Log.e("SessionOwnerCapsule", "createRandomTimerSession failed", err)
                withContext(Dispatchers.Main) {
                    access.showSessionWriteFailed(ctx)
                }
            }
        }
    }

    override fun updateChronologySession(sessionId: Long, title: String, tagIds: Set<Long>) {
        if (access.blockWriteIfNeeded()) return
        val ctx = access.appContextOrNull() ?: return
        access.launchIo("update chronology sessionId=$sessionId") {
            runCatching {
                val session = access.sessionCore(ctx)
                val before = session.readSessionById(sessionId)
                val shouldEmitStart = shouldEmitStartForRunningSessionMetaUpdate(
                    before = before,
                    newTitle = title,
                    newTagIds = tagIds
                )
                session.updateSessionMeta(sessionId, title, tagIds)

                access.logUserEvent(
                    action = "SESSION_EDIT_META",
                    entityType = "SESSION",
                    entityId = sessionId,
                    summary = ctx.getString(R.string.audit_session_meta_updated, title.trim().ifBlank { sessionId.toString() }),
                    payload = JSONObject()
                        .put("sessionId", sessionId)
                        .put("title", title)
                        .put("tagIds", JSONArray(tagIds.toList().sorted())),
                    undoable = true
                )

                if (shouldEmitStart) {
                    val displayTitle = title.trim().ifBlank { deriveSessionNameFromTags(tagIds, access.tags()) }
                    withContext(Dispatchers.Main.immediate) {
                        access.handleTimeFenceEvents(
                            events = listOf(
                                TimeFenceEvent(
                                    trigger = TimeFenceTrigger.ON_START,
                                    sessionId = sessionId,
                                    sessionTitle = displayTitle,
                                    sessionTagIds = tagIds
                                )
                            ),
                            nowMs = System.currentTimeMillis()
                        )
                    }
                }
            }.onFailure { err ->
                Log.e("SessionOwnerCapsule", "updateChronologySession failed (sessionId=$sessionId)", err)
                withContext(Dispatchers.Main) {
                    access.showSessionWriteFailed(ctx)
                }
            }
            access.scheduleSessionsRefresh(ctx, System.currentTimeMillis())
        }
    }

    override fun updateChronologySessionTimes(sessionId: Long, startMs: Long, endMs: Long?) {
        if (access.blockWriteIfNeeded()) return
        val ctx = access.appContextOrNull() ?: return
        access.launchIo("update chronology times sessionId=$sessionId") {
            runCatching {
                val session = access.sessionCore(ctx)
                val before = session.readSessionById(sessionId)

                if (before != null && before.endMs == null && endMs != null) {
                    stopSessionWithPolicies(ctx = ctx, sessionId = sessionId, endMs = endMs)
                } else {
                    session.updateSessionTimes(sessionId, startMs, endMs)
                }
                if (before != null && !(before.endMs == null && endMs != null)) {
                    access.logUserEvent(
                        action = "SESSION_EDIT_TIME",
                        entityType = "SESSION",
                        entityId = sessionId,
                        summary = ctx.getString(R.string.audit_session_time_updated, before.title.ifBlank { sessionId.toString() }),
                        payload = JSONObject()
                            .put("sessionId", sessionId)
                            .put("beforeStartMs", before.startMs)
                            .put("beforeEndMs", before.endMs)
                            .put("afterStartMs", startMs)
                            .put("afterEndMs", endMs),
                        undoable = true
                    )
                }
            }.onFailure { err ->
                Log.e("SessionOwnerCapsule", "updateChronologySessionTimes failed (sessionId=$sessionId)", err)
                withContext(Dispatchers.Main) {
                    access.showSessionWriteFailed(ctx)
                }
            }
            access.scheduleSessionsRefresh(ctx, System.currentTimeMillis())
        }
    }

    override fun deleteChronologySession(sessionId: Long) {
        if (access.blockWriteIfNeeded()) return
        val ctx = access.appContextOrNull() ?: return
        access.launchIo("delete chronology sessionId=$sessionId") {
            runCatching {
                val session = access.sessionCore(ctx)
                val s = session.readSessionById(sessionId)
                session.softDeleteSession(sessionId)

                if (s != null) {
                    access.logUserEvent(
                        action = "SESSION_DELETE",
                        entityType = "SESSION",
                        entityId = sessionId,
                        summary = ctx.getString(R.string.audit_session_deleted, s.title.ifBlank { sessionId.toString() }),
                        payload = JSONObject().put("sessionId", sessionId),
                        undoable = true
                    )
                }
            }.onFailure { err ->
                Log.e("SessionOwnerCapsule", "deleteChronologySession failed (sessionId=$sessionId)", err)
                withContext(Dispatchers.Main) {
                    access.showSessionDeleteFailed(ctx)
                }
            }
            access.scheduleSessionsRefresh(ctx, System.currentTimeMillis())
        }
    }

    fun restoreStoppedSessionFromAudit(sessionId: Long, startMs: Long, endMs: Long, eventId: Long) {
        val ctx = access.appContextOrNull() ?: return
        val session = access.sessionCore(ctx)
        val cur = session.readSessionById(sessionId)
        if (cur != null && cur.endMs != null) {
            if (!AuditLogSqlite.hasLaterEventsForEntity(ctx, entityType = "SESSION", entityId = sessionId, afterId = eventId)) {
                session.updateSessionTimes(sessionId = sessionId, startMs = startMs, endMs = null)
                access.scheduleSessionsRefresh(ctx, System.currentTimeMillis())
                access.scheduleAutoBackup()
                access.persist()
            }
        }
    }

    suspend fun stopSessionWithPolicies(ctx: Context, sessionId: Long, endMs: Long) {
        val session = access.sessionCore(ctx)
        val before = session.readSessionById(sessionId) ?: return
        if (before.endMs != null) return

        val ignoreEnabled = UiPrefsStore.getIgnoreShortSessions(ctx)
        val thresholdSecs = UiPrefsStore.getIgnoreShortSessionsThresholdSecs(ctx)
        val durMs = (endMs - before.startMs).coerceAtLeast(0L)
        val isShort = ignoreEnabled && thresholdSecs > 0 && durMs < (thresholdSecs.toLong() * 1000L)

        if (isShort) {
            session.softDeleteSession(sessionId)
            access.logSystemEvent(
                action = "SESSION_IGNORED_SHORT",
                entityType = "SESSION",
                entityId = sessionId,
                summary = ctx.getString(R.string.audit_session_ignored_short),
                payload = JSONObject()
                    .put("sessionId", sessionId)
                    .put("durationMs", durMs)
                    .put("thresholdSecs", thresholdSecs)
            )
        } else {
            session.updateSessionTimes(sessionId = sessionId, startMs = before.startMs, endMs = endMs)

            val displayTitle = before.title.ifBlank { deriveSessionNameFromTags(before.tagIds, access.tags()) }
            access.logSystemEvent(
                action = "ALERT_EVENT_EMITTED",
                entityType = "SESSION",
                entityId = sessionId,
                summary = "TimeFence event emitted",
                payload = JSONObject()
                    .put("trigger", TimeFenceTrigger.ON_STOP.name)
                    .put("sessionId", sessionId)
                    .put("title", displayTitle)
                    .put("tagIds", JSONArray(before.tagIds.toList().sorted()))
            )

            withContext(Dispatchers.Main.immediate) {
                access.handleTimeFenceEvents(
                    events = listOf(
                        TimeFenceEvent(
                            trigger = TimeFenceTrigger.ON_STOP,
                            sessionId = sessionId,
                            sessionTitle = displayTitle,
                            sessionTagIds = before.tagIds
                        )
                    ),
                    nowMs = endMs
                )
            }

            access.logUserEvent(
                action = "SESSION_STOP",
                entityType = "SESSION",
                entityId = sessionId,
                summary = ctx.getString(R.string.audit_session_stopped, displayTitle.ifBlank { sessionId.toString() }),
                payload = JSONObject()
                    .put("sessionId", sessionId)
                    .put("startMs", before.startMs)
                    .put("endMs", endMs)
                    .put("tagIds", JSONArray(before.tagIds.toList().sorted())),
                undoable = true
            )
        }

        access.advanceChainOnSessionStop(stoppedSessionId = sessionId, nowMs = endMs, context = ctx)
        access.scheduleSessionsRefresh(ctx, endMs)
        access.scheduleAutoBackup()
        access.persist()
    }

    private fun deriveSessionNameFromTags(tagIds: Set<Long>, tags: List<Tag>): String {
        if (tagIds.isEmpty()) return ""
        val tagsById = tags.associateBy { it.id }
        return tagIds.toList()
            .sorted()
            .mapNotNull { id -> tagsById[id]?.name?.trim()?.takeIf { it.isNotEmpty() } }
            .joinToString(" · ")
            .trim()
    }

    private fun buildAuthoritativeSessionCache(
        context: Context,
        tags: List<Tag>,
        persistedSnapshotTags: List<Tag> = emptyList(),
    ): AuthoritativeSessionCache? {
        return runCatching {
            val session = access.sessionCore(context)
            val chronologySessions = session.readAllSessions()
            val runningSessions = chronologySessions.filter { it.endMs == null && it.deletedAtMs == null }
            val requiredTagIds = buildSet<Long> {
                chronologySessions.forEach { addAll(it.tagIds) }
            }
            fun Tag.hasResolvedRuntimeName(): Boolean {
                val trimmed = name.trim()
                return trimmed.isNotEmpty() && trimmed != "tag_$id"
            }
            val merged = LinkedHashMap<Long, Tag>()
            persistedSnapshotTags.forEach { merged[it.id] = it }
            tags.forEach { tag ->
                val existing = merged[tag.id]
                val shouldOverride =
                    existing == null ||
                        tag.hasResolvedRuntimeName() ||
                        !existing.hasResolvedRuntimeName()
                if (shouldOverride) {
                    merged[tag.id] = tag
                }
            }
            val resolvedTags =
                if (requiredTagIds.isEmpty() || merged.isEmpty()) {
                    tags
                } else {
                    merged.values.toList()
                }
            val canonicalizedTags = normalizeTagsAgainstCanonicalDefinitions(
                tags = resolvedTags,
                canonicalTags = persistedSnapshotTags + tags,
            )

            normalizeAuthoritativeTagSessions(
                AuthoritativeSessionCacheBuilder.fromReadModel(
                    tags = canonicalizedTags,
                    chronologySessions = chronologySessions,
                    runningSessions = runningSessions,
                )
            )
        }.getOrNull()
    }

    private fun ensureAuthoritativeSessionCache(
        context: Context,
        snapshot: SnapshotStore.Snapshot,
        tags: List<Tag>,
    ): AuthoritativeSessionCache {
        val hasLegacySnapshotData = hasLegacyChronologySnapshotData(snapshot)
        val initial = buildAuthoritativeSessionCache(
            context = context,
            tags = tags,
            persistedSnapshotTags = snapshot.tags,
        )
        if (initial != null) {
            val hasAuthoritativeChronology =
                initial.chronologySessions.isNotEmpty() || initial.runningSessions.isNotEmpty()
            if (!hasAuthoritativeChronology && hasLegacySnapshotData) {
                val bootstrapped = bootstrapAuthoritativeSessionCacheFromSnapshot(
                    context = context,
                    snapshot = snapshot,
                    tags = tags,
                )
                if (bootstrapped != null) {
                    val hasBootstrappedChronology =
                        bootstrapped.chronologySessions.isNotEmpty() || bootstrapped.runningSessions.isNotEmpty()
                    requireNoLegacyRuntimeBootstrap(
                        hasAuthoritativeChronology = hasBootstrappedChronology,
                        hasLegacySnapshotData = hasLegacySnapshotData,
                    )
                    return bootstrapped
                }
            }
            requireNoLegacyRuntimeBootstrap(
                hasAuthoritativeChronology = hasAuthoritativeChronology,
                hasLegacySnapshotData = hasLegacySnapshotData,
            )
            return initial
        }
        val bootstrapped = bootstrapAuthoritativeSessionCacheFromSnapshot(
            context = context,
            snapshot = snapshot,
            tags = tags,
        )
        if (bootstrapped != null) {
            val hasBootstrappedChronology =
                bootstrapped.chronologySessions.isNotEmpty() || bootstrapped.runningSessions.isNotEmpty()
            requireNoLegacyRuntimeBootstrap(
                hasAuthoritativeChronology = hasBootstrappedChronology,
                hasLegacySnapshotData = hasLegacySnapshotData,
            )
            return bootstrapped
        }
        requireNoLegacyRuntimeBootstrap(
            hasAuthoritativeChronology = false,
            hasLegacySnapshotData = hasLegacySnapshotData,
        )
        return normalizeAuthoritativeTagSessions(
            AuthoritativeSessionCacheBuilder.fromReadModel(
                tags = tags,
                chronologySessions = emptyList(),
                runningSessions = emptyList(),
            )
        )
    }

    private fun bootstrapAuthoritativeSessionCacheFromSnapshot(
        context: Context,
        snapshot: SnapshotStore.Snapshot,
        tags: List<Tag>,
    ): AuthoritativeSessionCache? {
        if (!hasLegacyChronologySnapshotData(snapshot)) return null
        return runCatching {
            SessionMirrorCore.mirrorFromLegacySnapshot(
                context = context,
                tasks = snapshot.tasks,
                tags = tags,
                closedSessions = snapshot.closedSessions,
                nowMs = System.currentTimeMillis(),
            )
            buildAuthoritativeSessionCache(
                context = context,
                tags = tags,
                persistedSnapshotTags = snapshot.tags,
            )
        }.getOrNull()
    }

    private fun hasLegacyChronologySnapshotData(snapshot: SnapshotStore.Snapshot): Boolean {
        return snapshot.tasks.isNotEmpty() ||
            snapshot.closedSessions.isNotEmpty() ||
            snapshot.tagSessions.isNotEmpty() ||
            snapshot.activeSessionStart.isNotEmpty() ||
            snapshot.activeTagStart.isNotEmpty()
    }

    private fun normalizeAuthoritativeTagSessions(
        cache: AuthoritativeSessionCache,
    ): AuthoritativeSessionCache {
        val resolvedNamesById = cache.tags.associateBy({ it.id }, { it.name.trim() })
        val normalizedTagSessions = cache.tagSessions.map { record ->
            val resolvedName = resolvedNamesById[record.tagId]
            if (resolvedName.isNullOrEmpty() || resolvedName == "tag_${record.tagId}") {
                record
            } else {
                record.copy(tagName = resolvedName)
            }
        }
        return cache.copy(tagSessions = normalizedTagSessions)
    }

    private fun normalizeTagsAgainstCanonicalDefinitions(
        tags: List<Tag>,
        canonicalTags: List<Tag>,
    ): List<Tag> {
        if (tags.isEmpty() || canonicalTags.isEmpty()) return tags
        val canonicalNamesById = LinkedHashMap<Long, String>()
        canonicalTags.forEach { tag ->
            val trimmed = tag.name.trim()
            if (trimmed.isNotEmpty() && trimmed != "tag_${tag.id}") {
                canonicalNamesById[tag.id] = trimmed
            }
        }
        if (canonicalNamesById.isEmpty()) return tags
        return tags.map { tag ->
            val canonicalName = canonicalNamesById[tag.id] ?: return@map tag
            if (canonicalName == tag.name) tag else tag.copy(name = canonicalName)
        }
    }

    private fun AuthoritativeSessionCache.toSnapshotReadModel(tags: List<Tag>): SessionsSnapshotReadModel {
        val sessionTitlesById = (chronologySessions + runningSessions)
            .distinctBy { it.id }
            .associate { it.id to it.title }
        val runtime = SessionsRuntimeState(
            tasks = buildSessionOnlyRuntimeTasks(runningSessions),
            closedSessions = closedSessions,
            tagSessions = canonicalizeTaggedSessionRecords(
                tagSessions = tagSessions,
                tags = tags,
                sessionTitlesById = sessionTitlesById,
            ),
            chronologySessions = chronologySessions,
            runningSessions = runningSessions,
            activeTagTotalsMsByTagId = activeTagTotalsMsByTagId,
            runningMinStartByTagId = runningMinStartByTagId,
            tagTotalsMsByTagId = tagTotalsMsByTagId,
            tagLastUsedMsByTagId = tagLastUsedMsByTagId,
        )
        return SessionsSnapshotReadModel(
            runtimeState = runtime,
            runtimeSnapshot = buildRuntimeSnapshotFromRunningSessions(runningSessions),
            activeTagStartByTagId = activeTagStartByTagId,
            tags = tags,
        )
    }

    private fun Sequence<Triple<Long, Long, Long>>.sortedRuntimeActiveTags(): List<Triple<Long, Long, Long>> {
        return sortedWith(compareBy({ it.first }, { it.second }, { it.third })).toList()
    }

    private fun buildTimeMachineRuntimeState(
        snapshot: SnapshotStore.Snapshot,
        tags: List<Tag>,
        activeTagStartByTagId: Map<Long, Long>,
    ): SessionsRuntimeState {
        val hasSnapshotSessionReadModel =
            snapshot.chronologySessions.isNotEmpty() || snapshot.runningSessions.isNotEmpty()
        if (hasSnapshotSessionReadModel) {
            val readModel = AuthoritativeSessionCacheBuilder.fromReadModel(
                tags = tags,
                chronologySessions = snapshot.chronologySessions,
                runningSessions = snapshot.runningSessions,
            )
            val sessionTitlesById = (readModel.chronologySessions + readModel.runningSessions)
                .distinctBy { it.id }
                .associate { it.id to it.title }
            return SessionsRuntimeState(
                tasks = snapshot.tasks,
                closedSessions = snapshot.closedSessions,
                tagSessions = canonicalizeTaggedSessionRecords(
                    tagSessions = snapshot.tagSessions,
                    tags = readModel.tags,
                    sessionTitlesById = sessionTitlesById,
                ),
                chronologySessions = readModel.chronologySessions,
                runningSessions = readModel.runningSessions,
                activeTagTotalsMsByTagId = readModel.activeTagTotalsMsByTagId,
                runningMinStartByTagId = readModel.runningMinStartByTagId,
                tagTotalsMsByTagId = readModel.tagTotalsMsByTagId,
                tagLastUsedMsByTagId = readModel.tagLastUsedMsByTagId,
            )
        }

        fun unionTotalMs(intervals: List<Pair<Long, Long>>): Long {
            if (intervals.isEmpty()) return 0L
            val sorted = intervals.sortedBy { it.first }
            var total = 0L
            var curStart = sorted[0].first
            var curEnd = sorted[0].second
            for (i in 1 until sorted.size) {
                val (start, end) = sorted[i]
                if (end <= start) continue
                if (start <= curEnd) {
                    if (end > curEnd) curEnd = end
                } else {
                    total += curEnd - curStart
                    curStart = start
                    curEnd = end
                }
            }
            total += curEnd - curStart
            return total.coerceAtLeast(0L)
        }

        val tagLastUsedMsByTagId = snapshot.tagSessions
            .groupBy { it.tagId }
            .mapValues { (_, sessions) -> sessions.maxOfOrNull { it.startTs } ?: 0L }
            .filterValues { it > 0L }

        val activeTagTotalsMsByTagId = activeTagStartByTagId.keys.associateWith { tagId ->
            unionTotalMs(
                snapshot.tagSessions
                    .filter { it.tagId == tagId }
                    .map { it.startTs to it.endTs }
            )
        }

        return SessionsRuntimeState(
            tasks = snapshot.tasks,
            closedSessions = snapshot.closedSessions,
            tagSessions = snapshot.tagSessions,
            chronologySessions = snapshot.chronologySessions,
            runningSessions = snapshot.runningSessions,
            activeTagTotalsMsByTagId = activeTagTotalsMsByTagId,
            runningMinStartByTagId = activeTagStartByTagId,
            tagTotalsMsByTagId = tags.associate { it.id to it.totalMs },
            tagLastUsedMsByTagId = tagLastUsedMsByTagId,
        )
    }
}
