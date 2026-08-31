// v539
package com.example.multitimetracker.capsules.auditlog.controller

import androidx.lifecycle.ViewModel
import com.example.multitimetracker.capsules.system.AuditLogCapsuleAccess
import com.example.multitimetracker.capsules.system.CapsuleRuntimeChange
import com.example.multitimetracker.capsules.system.CapsuleRuntimeParticipant
import com.example.multitimetracker.failLegacyTaskPath
import com.example.multitimetracker.model.AuditEventUi
import com.example.multitimetracker.persistence.AuditEventRow
import com.example.multitimetracker.persistence.AuditLogSqlite
import com.example.multitimetracker.persistence.UiPrefsStore
import com.example.multitimetracker.util.CapsuleWriteApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

internal enum class AuditLogCategory {
    TASKS,
    TAGS,
    ALERTS,
    CHAINS,
    SYSTEM
}

internal data class AuditLogFilterState(
    val tasks: Boolean,
    val tags: Boolean,
    val alerts: Boolean,
    val chains: Boolean,
    val systemEvents: Boolean,
    val undone: Boolean,
    val undoEnabled: Boolean
)

internal fun auditLogCategory(action: String, isSystemRow: Boolean): AuditLogCategory {
    if (isSystemRow) return AuditLogCategory.SYSTEM
    return when {
        action.startsWith("SESSION") -> AuditLogCategory.TASKS
        action.startsWith("TASK") -> AuditLogCategory.TASKS
        action.startsWith("TAG") -> AuditLogCategory.TAGS
        action.startsWith("ALERT") || action.startsWith("TIME_FENCE") -> AuditLogCategory.ALERTS
        action.startsWith("CHAIN") -> AuditLogCategory.CHAINS
        else -> AuditLogCategory.SYSTEM
    }
}

internal fun auditEventUiOrNull(
    row: AuditEventRow,
    filters: AuditLogFilterState,
    timeMachineTargetMs: Long?
): AuditEventUi? {
    if (timeMachineTargetMs != null && row.tsMs > timeMachineTargetMs) return null

    val isUndone = if (timeMachineTargetMs != null) {
        row.undoneAtMs != null && row.undoneAtMs <= timeMachineTargetMs
    } else {
        row.undoneAtMs != null
    }

    val allowed = when (auditLogCategory(row.action, row.isSystem)) {
        AuditLogCategory.TASKS -> filters.tasks
        AuditLogCategory.TAGS -> filters.tags
        AuditLogCategory.ALERTS -> filters.alerts
        AuditLogCategory.CHAINS -> filters.chains
        AuditLogCategory.SYSTEM -> filters.systemEvents
    }
    if (!allowed) return null
    if (!filters.undone && isUndone) return null

    val payloadObj = row.payloadJson?.let { json ->
        runCatching { JSONObject(json) }.getOrNull()
    }
    val undoable = filters.undoEnabled &&
        !row.isSystem &&
        !isUndone &&
        (payloadObj?.optBoolean("undoable", false) == true)

    return AuditEventUi(
        id = row.id,
        tsMs = row.tsMs,
        isSystem = row.isSystem,
        action = row.action,
        summary = row.summary,
        payloadJson = row.payloadJson,
        undoable = undoable,
        isUndone = isUndone
    )
}

@OptIn(CapsuleWriteApi::class)
class AuditLogCapsuleViewModel(
    private val access: AuditLogCapsuleAccess
) : ViewModel(), CapsuleRuntimeParticipant {
    override val capsuleId: String = "auditlog"
    val uiState = access.uiStateFlow()

    override fun onCapsuleRuntimeChanged(context: android.content.Context?, change: CapsuleRuntimeChange) {
        if (context != null) refreshEvents()
    }

    private val _events = MutableStateFlow<List<AuditEventUi>>(emptyList())
    val events: StateFlow<List<AuditEventUi>> = _events

    private val _filterTasks = MutableStateFlow(true)
    val filterTasks: StateFlow<Boolean> = _filterTasks

    private val _filterTags = MutableStateFlow(true)
    val filterTags: StateFlow<Boolean> = _filterTags

    private val _filterAlerts = MutableStateFlow(true)
    val filterAlerts: StateFlow<Boolean> = _filterAlerts

    private val _filterChains = MutableStateFlow(true)
    val filterChains: StateFlow<Boolean> = _filterChains

    private val _filterSystemEvents = MutableStateFlow(true)
    val filterSystemEvents: StateFlow<Boolean> = _filterSystemEvents

    private val _filterUndone = MutableStateFlow(true)
    val filterUndone: StateFlow<Boolean> = _filterUndone

    private val _undoEnabled = MutableStateFlow(false)
    val undoEnabled: StateFlow<Boolean> = _undoEnabled

    private var suppressAuditLog: Boolean = false

    fun initializeFromPrefs() {
        val ctx = access.appContextOrNull() ?: return
        _filterSystemEvents.value = UiPrefsStore.getAuditFilterSystem(ctx)
        _filterTasks.value = UiPrefsStore.getAuditFilterTasks(ctx)
        _filterTags.value = UiPrefsStore.getAuditFilterTags(ctx)
        _filterAlerts.value = UiPrefsStore.getAuditFilterAlerts(ctx)
        _filterChains.value = UiPrefsStore.getAuditFilterChains(ctx)
        _undoEnabled.value = UiPrefsStore.getAuditUndoEnabled(ctx)
        _filterUndone.value = UiPrefsStore.getAuditFilterUndone(ctx)
        refreshEvents()
    }

    fun setFilterTasks(value: Boolean) = updateFilter(value, _filterTasks, UiPrefsStore::setAuditFilterTasks)
    fun setFilterTags(value: Boolean) = updateFilter(value, _filterTags, UiPrefsStore::setAuditFilterTags)
    fun setFilterAlerts(value: Boolean) = updateFilter(value, _filterAlerts, UiPrefsStore::setAuditFilterAlerts)
    fun setFilterChains(value: Boolean) = updateFilter(value, _filterChains, UiPrefsStore::setAuditFilterChains)
    fun setFilterSystemEvents(value: Boolean) = updateFilter(value, _filterSystemEvents, UiPrefsStore::setAuditFilterSystem)
    fun setFilterUndone(value: Boolean) = updateFilter(value, _filterUndone, UiPrefsStore::setAuditFilterUndone)

    fun setUndoEnabled(value: Boolean) {
        val ctx = access.appContextOrNull() ?: return
        _undoEnabled.value = value
        UiPrefsStore.setAuditUndoEnabled(ctx, value)
        refreshEvents()
    }

    fun refreshEvents() {
        val ctx = access.appContextOrNull() ?: return
        val filters = currentFilters()
        val timeMachineTargetMs = access.timeMachineTargetMs()
        val rows = AuditLogSqlite.listRecent(
            context = ctx,
            limit = 300,
            includeSystem = filters.systemEvents,
            includeUndone = if (timeMachineTargetMs != null) true else filters.undone
        )
        _events.value = rows.mapNotNull { row ->
            auditEventUiOrNull(row, filters, timeMachineTargetMs)
        }
    }

    fun undoEvent(auditId: Long) {
        val ctx = access.appContextOrNull() ?: return
        if (!_undoEnabled.value) return
        if (access.blockWriteIfNeeded()) return

        val row = AuditLogSqlite.getById(ctx, auditId) ?: return
        if (row.isSystem || row.undoneAtMs != null) return

        val payload = row.payloadJson?.let { json -> runCatching { JSONObject(json) }.getOrNull() }
        if (payload?.optBoolean("undoable", false) != true) return

        suppressAuditLog = true
        try {
            when (row.action) {
                "TASK_CREATE" -> {
                    val sessionId = payload.optLong("sessionId", -1L)
                    if (sessionId > 0) access.deleteSession(sessionId)
                }
                "TAG_CREATE" -> {
                    val tagId = payload.optLong("tagId", -1L)
                    if (tagId > 0) access.deleteTag(tagId)
                }
                "TAG_PARENTS_CHANGE" -> {
                    val tagId = payload.optLong("tagId", -1L)
                    val oldArr = payload.optJSONArray("oldParentIds")
                    if (tagId > 0 && oldArr != null) {
                        val oldSet = (0 until oldArr.length())
                            .asSequence()
                            .map { idx -> oldArr.optLong(idx, -1L) }
                            .filter { it > 0L }
                            .toSet()
                        access.setTagParents(tagId, oldSet)
                    }
                }
                "TASK_SESSION_EDIT" -> failLegacyTaskPath()
                "TASK_START_NEW", "TASK_START_RESUME" -> failLegacyTaskPath()
                "SESSION_STOP" -> {
                    val sessionId = payload.optLong("sessionId", -1L)
                    val startMs = payload.optLong("startMs", -1L)
                    val endMs = payload.optLong("endMs", -1L)
                    if (sessionId > 0 && startMs > 0 && endMs > 0) {
                        access.restoreStoppedSessionFromAudit(sessionId, startMs, endMs, auditId)
                    }
                }
                else -> return
            }

            AuditLogSqlite.markUndone(ctx, auditId)
            access.refreshRuntimeAfterUndo()
            refreshEvents()
        } finally {
            suppressAuditLog = false
        }
    }

    fun clearLog() {
        val ctx = access.appContextOrNull() ?: return
        if (access.blockWriteIfNeeded()) return
        AuditLogSqlite.clearAll(ctx)
        refreshEvents()
    }

    fun logUserEvent(
        action: String,
        entityType: String? = null,
        entityId: Long? = null,
        summary: String,
        payload: JSONObject? = null,
        undoable: Boolean
    ) {
        val ctx = access.appContextOrNull() ?: return
        if (suppressAuditLog) return
        val p = (payload ?: JSONObject()).put("undoable", undoable)
        AuditLogSqlite.insert(
            context = ctx,
            isSystem = false,
            action = action,
            entityType = entityType,
            entityId = entityId,
            summary = summary,
            payload = p
        )
        refreshEvents()
    }

    fun logSystemEvent(
        action: String,
        entityType: String? = null,
        entityId: Long? = null,
        summary: String,
        payload: JSONObject? = null
    ) {
        val ctx = access.appContextOrNull() ?: return
        if (suppressAuditLog) return
        val p = (payload ?: JSONObject()).put("undoable", false)
        AuditLogSqlite.insert(
            context = ctx,
            isSystem = true,
            action = action,
            entityType = entityType,
            entityId = entityId,
            summary = summary,
            payload = p
        )
        if (_filterSystemEvents.value) refreshEvents()
    }

    private fun updateFilter(
        value: Boolean,
        flow: MutableStateFlow<Boolean>,
        persistPref: (android.content.Context, Boolean) -> Unit
    ) {
        val ctx = access.appContextOrNull() ?: return
        flow.value = value
        persistPref(ctx, value)
        refreshEvents()
    }

    private fun currentFilters(): AuditLogFilterState {
        return AuditLogFilterState(
            tasks = _filterTasks.value,
            tags = _filterTags.value,
            alerts = _filterAlerts.value,
            chains = _filterChains.value,
            systemEvents = _filterSystemEvents.value,
            undone = _filterUndone.value,
            undoEnabled = _undoEnabled.value
        )
    }
}
