package com.example.multitimetracker.capsules.quickevents.controller

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.multitimetracker.R
import com.example.multitimetracker.capsules.quickevents.public.QuickEventsSnapshot
import com.example.multitimetracker.capsules.quickevents.state.QuickEventsHostState
import com.example.multitimetracker.capsules.quickevents.state.QuickEventsUiState
import com.example.multitimetracker.capsules.system.CapsuleRuntimeChange
import com.example.multitimetracker.capsules.system.CapsuleRuntimeParticipant
import com.example.multitimetracker.capsules.system.QuickEventsCapsuleAccess
import com.example.multitimetracker.core.quickevent.QuickEventExecutionResult
import com.example.multitimetracker.core.quickevent.QuickEventExecutor
import com.example.multitimetracker.core.quickevent.QuickEventTarget
import com.example.multitimetracker.model.QuickEventDefaults
import com.example.multitimetracker.model.QuickEventFieldDefinition
import com.example.multitimetracker.model.QuickEventFieldValue
import com.example.multitimetracker.model.QuickEventMacroAction
import com.example.multitimetracker.perf.StartupPerfTrace
import com.example.multitimetracker.persistence.QuickEventRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.json.JSONArray
import org.json.JSONObject

class QuickEventsCapsuleViewModel(
    private val access: QuickEventsCapsuleAccess
) : ViewModel(), CapsuleRuntimeParticipant {
    override val capsuleId: String = "quickevents"
    private val liveSnapshot = MutableStateFlow(QuickEventsSnapshot.EMPTY)
    val uiState: StateFlow<QuickEventsUiState> = combine(
        access.hostStateFlow(),
        liveSnapshot,
    ) { host, live ->
        buildUiState(host = host, snapshot = live)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        buildUiState(host = access.hostStateFlow().value, snapshot = QuickEventsSnapshot.EMPTY),
    )

    override fun onCapsuleRuntimeChanged(context: android.content.Context?, change: CapsuleRuntimeChange) {
        if (context != null) refreshFromDb(context.applicationContext)
    }

    fun snapshot(): QuickEventsSnapshot = liveSnapshot.value

    fun replaceSnapshot(snapshot: QuickEventsSnapshot) {
        liveSnapshot.value = snapshot
    }

    fun createTemplate(
        title: String,
        tagIds: Set<Long>,
        sortOrder: Int,
        isArchived: Boolean = false,
        fields: List<QuickEventFieldDefinition> = emptyList()
    ) {
        if (access.blockWriteIfNeeded()) return
        val ctx = access.appContextOrNull() ?: return
        access.launchIo("create quick event template") {
            runCatching {
                val cleanTitle = title.trim()
                val cleanTagIds = tagIds.filter { it > 0L }.toSet()
                val templateId = access.quickEventCore(ctx).insertTemplate(
                    title = cleanTitle,
                    tagIds = cleanTagIds,
                    sortOrder = sortOrder,
                    isArchived = isArchived,
                    fields = fields.map { it.copy(label = it.label.trim(), defaultValue = it.defaultValue.trim()) }
                        .filter { it.label.isNotBlank() },
                )
                access.logUserEvent(
                    action = "QUICK_EVENT_TEMPLATE_CREATE",
                    entityType = "QUICK_EVENT_TEMPLATE",
                    entityId = templateId,
                    summary = ctx.getString(R.string.audit_quick_event_template_created, cleanTitle.ifBlank { templateId.toString() }),
                    payload = JSONObject()
                        .put("templateId", templateId)
                        .put("tagIds", JSONArray(cleanTagIds.toList().sorted())),
                    undoable = true
                )
                refreshFromDb(ctx)
                access.persist()
                access.scheduleAutoBackup()
            }.onFailure { err ->
                Log.e("QuickEventsCapsule", "createTemplate failed", err)
                access.showWriteFailed(ctx)
            }
        }
    }

    fun updateTemplate(
        templateId: Long,
        title: String,
        tagIds: Set<Long>,
        sortOrder: Int,
        isArchived: Boolean,
        fields: List<QuickEventFieldDefinition> = emptyList()
    ) {
        if (access.blockWriteIfNeeded()) return
        val ctx = access.appContextOrNull() ?: return
        access.launchIo("update quick event templateId=$templateId") {
            runCatching {
                val cleanTitle = title.trim()
                val cleanTagIds = tagIds.filter { it > 0L }.toSet()
                access.quickEventCore(ctx).updateTemplate(
                    templateId = templateId,
                    title = cleanTitle,
                    tagIds = cleanTagIds,
                    sortOrder = sortOrder,
                    isArchived = isArchived,
                    fields = fields.map { it.copy(templateId = templateId, label = it.label.trim(), defaultValue = it.defaultValue.trim()) }
                        .filter { it.label.isNotBlank() },
                )
                access.logUserEvent(
                    action = "QUICK_EVENT_TEMPLATE_EDIT",
                    entityType = "QUICK_EVENT_TEMPLATE",
                    entityId = templateId,
                    summary = ctx.getString(R.string.audit_quick_event_template_updated, cleanTitle.ifBlank { templateId.toString() }),
                    payload = JSONObject()
                        .put("templateId", templateId)
                        .put("tagIds", JSONArray(cleanTagIds.toList().sorted())),
                    undoable = true
                )
                refreshFromDb(ctx)
                access.persist()
                access.scheduleAutoBackup()
            }.onFailure { err ->
                Log.e("QuickEventsCapsule", "updateTemplate failed (templateId=$templateId)", err)
                access.showWriteFailed(ctx)
            }
        }
    }

    fun deleteTemplate(templateId: Long) {
        if (access.blockWriteIfNeeded()) return
        val ctx = access.appContextOrNull() ?: return
        access.launchIo("delete quick event templateId=$templateId") {
            runCatching {
                val quickEvents = access.quickEventCore(ctx)
                val before = quickEvents.readTemplateById(templateId)
                quickEvents.softDeleteTemplate(templateId)
                if (before != null) {
                    access.logUserEvent(
                        action = "QUICK_EVENT_TEMPLATE_DELETE",
                        entityType = "QUICK_EVENT_TEMPLATE",
                        entityId = templateId,
                        summary = ctx.getString(R.string.audit_quick_event_template_deleted, before.title.ifBlank { templateId.toString() }),
                        payload = JSONObject().put("templateId", templateId),
                        undoable = true
                    )
                }
                refreshFromDb(ctx)
                access.persist()
                access.scheduleAutoBackup()
            }.onFailure { err ->
                Log.e("QuickEventsCapsule", "deleteTemplate failed (templateId=$templateId)", err)
                access.showDeleteFailed(ctx)
            }
        }
    }

    fun createEntryFromTemplate(templateId: Long, timestampMs: Long? = null) =
        createEntryFromTemplateInternal(templateId, timestampMs)

    private fun createEntryFromTemplateInternal(templateId: Long, timestampMs: Long?) {
        if (access.blockWriteIfNeeded()) return
        val ctx = access.appContextOrNull() ?: return
        access.launchIo("create quick event from templateId=$templateId") {
            runCatching {
                val result = quickEventExecutor(ctx).execute(QuickEventTarget.Template(templateId), timestampMs ?: System.currentTimeMillis())
                if (result is QuickEventExecutionResult.Executed) {
                    refreshFromDb(ctx)
                    access.showTargetRecorded(ctx, result.title)
                }
            }.onFailure { err ->
                Log.e("QuickEventsCapsule", "createEntryFromTemplate failed (templateId=$templateId)", err)
                access.showWriteFailed(ctx)
            }
        }
    }

    fun createCustomEntry(
        templateId: Long?,
        macroId: Long?,
        title: String,
        timestampMs: Long,
        tagIds: Set<Long>,
        fieldValues: List<QuickEventFieldValue> = emptyList(),
        onComplete: (Result<Long>) -> Unit = {},
    ) {
        if (access.blockWriteIfNeeded()) {
            onComplete(Result.failure(IllegalStateException("Writes are currently blocked")))
            return
        }
        val ctx = access.appContextOrNull() ?: run {
            onComplete(Result.failure(IllegalStateException("Application context is unavailable")))
            return
        }
        access.launchIo("create quick event entry") {
            var createdEntryId: Long? = null
            runCatching {
                val cleanTitle = title.trim()
                val cleanTagIds = tagIds.filter { it > 0L }.toSet()
                val entryId = access.quickEventCore(ctx).insertEntry(
                    templateId = templateId,
                    macroId = macroId,
                    title = cleanTitle,
                    timestampMs = timestampMs,
                    tagIds = cleanTagIds,
                    fieldValues = fieldValues,
                )
                createdEntryId = entryId
                withContext(Dispatchers.Main) { onComplete(Result.success(entryId)) }
                access.logUserEvent(
                    action = "QUICK_EVENT_ENTRY_CREATE",
                    entityType = "QUICK_EVENT_ENTRY",
                    entityId = entryId,
                    summary = ctx.getString(R.string.audit_quick_event_entry_created, cleanTitle.ifBlank { entryId.toString() }),
                    payload = JSONObject().put("entryId", entryId).put("timestampMs", timestampMs),
                    undoable = true
                )
                refreshFromDb(ctx)
                access.persist()
                access.scheduleAutoBackup()
            }.onFailure { err ->
                Log.e("QuickEventsCapsule", "createCustomEntry failed", err)
                access.showWriteFailed(ctx)
                if (createdEntryId == null) withContext(Dispatchers.Main) { onComplete(Result.failure(err)) }
            }
        }
    }

    fun createStandaloneEntry(
        title: String,
        timestampMs: Long,
        tagIds: Set<Long>,
        fieldValues: List<QuickEventFieldValue> = emptyList(),
        createReusableTemplate: Boolean = false,
        onComplete: (Result<Long>) -> Unit = {},
    ) {
        if (access.blockWriteIfNeeded()) {
            onComplete(Result.failure(IllegalStateException("Writes are currently blocked")))
            return
        }
        val ctx = access.appContextOrNull() ?: run {
            onComplete(Result.failure(IllegalStateException("Application context is unavailable")))
            return
        }
        access.launchIo("create standalone quick event entry") {
            var createdEntryId: Long? = null
            runCatching {
                val cleanTitle = title.trim()
                val cleanTagIds = tagIds.filter { it > 0L }.toSet()
                val quickEvents = access.quickEventCore(ctx)
                val result = if (createReusableTemplate) {
                    quickEvents.createStandaloneEntryWithReusableTemplate(
                        title = cleanTitle,
                        timestampMs = timestampMs,
                        tagIds = cleanTagIds,
                        fieldValues = fieldValues,
                    )
                } else {
                    QuickEventRepository.StandaloneEntryCreateResult(
                        entryId = quickEvents.createStandaloneEntry(
                            title = cleanTitle,
                            timestampMs = timestampMs,
                            tagIds = cleanTagIds,
                            fieldValues = fieldValues,
                        ),
                        templateId = null
                    )
                }
                val entryId = result.entryId
                createdEntryId = entryId
                withContext(Dispatchers.Main) { onComplete(Result.success(entryId)) }
                val createdTemplateId = result.templateId
                access.logUserEvent(
                    action = "QUICK_EVENT_ENTRY_CREATE",
                    entityType = "QUICK_EVENT_ENTRY",
                    entityId = entryId,
                    summary = ctx.getString(R.string.audit_quick_event_entry_created, cleanTitle.ifBlank { entryId.toString() }),
                    payload = JSONObject()
                        .put("entryId", entryId)
                        .put("templateId", JSONObject.NULL)
                        .put("createdTemplateId", createdTemplateId ?: JSONObject.NULL)
                        .put("timestampMs", timestampMs),
                    undoable = true
                )
                if (createdTemplateId != null) {
                    access.logUserEvent(
                        action = "QUICK_EVENT_TEMPLATE_CREATE",
                        entityType = "QUICK_EVENT_TEMPLATE",
                        entityId = createdTemplateId,
                        summary = ctx.getString(R.string.audit_quick_event_template_created, cleanTitle.ifBlank { createdTemplateId.toString() }),
                        payload = JSONObject()
                            .put("templateId", createdTemplateId)
                            .put("sourceEntryId", entryId)
                            .put("tagIds", JSONArray(cleanTagIds.toList().sorted())),
                        undoable = true
                    )
                }
                refreshFromDb(ctx)
                access.persist()
                access.scheduleAutoBackup()
                access.showTargetRecorded(ctx, cleanTitle)
            }.onFailure { err ->
                Log.e("QuickEventsCapsule", "createStandaloneEntry failed", err)
                access.showWriteFailed(ctx)
                if (createdEntryId == null) withContext(Dispatchers.Main) { onComplete(Result.failure(err)) }
            }
        }
    }

    fun updateEntry(entryId: Long, title: String, timestampMs: Long, tagIds: Set<Long>, fieldValues: List<QuickEventFieldValue> = emptyList()) =
        updateEntryInternal(entryId, title, timestampMs, tagIds, fieldValues)

    private fun updateEntryInternal(
        entryId: Long,
        title: String,
        timestampMs: Long,
        tagIds: Set<Long>,
        fieldValues: List<QuickEventFieldValue>
    ) {
        if (access.blockWriteIfNeeded()) return
        val ctx = access.appContextOrNull() ?: return
        access.launchIo("update quick event entryId=$entryId") {
            runCatching {
                val cleanTitle = title.trim()
                val cleanTagIds = tagIds.filter { it > 0L }.toSet()
                access.quickEventCore(ctx).updateEntry(entryId, cleanTitle, timestampMs, cleanTagIds, fieldValues)
                access.logUserEvent(
                    action = "QUICK_EVENT_ENTRY_EDIT",
                    entityType = "QUICK_EVENT_ENTRY",
                    entityId = entryId,
                    summary = ctx.getString(R.string.audit_quick_event_entry_updated, cleanTitle.ifBlank { entryId.toString() }),
                    payload = JSONObject().put("entryId", entryId).put("timestampMs", timestampMs),
                    undoable = true
                )
                refreshFromDb(ctx)
                access.persist()
                access.scheduleAutoBackup()
            }.onFailure { err ->
                Log.e("QuickEventsCapsule", "updateEntry failed (entryId=$entryId)", err)
                access.showWriteFailed(ctx)
            }
        }
    }

    fun deleteEntry(entryId: Long) {
        if (access.blockWriteIfNeeded()) return
        val ctx = access.appContextOrNull() ?: return
        access.launchIo("delete quick event entryId=$entryId") {
            runCatching {
                val quickEvents = access.quickEventCore(ctx)
                val before = quickEvents.readEntryById(entryId)
                quickEvents.softDeleteEntry(entryId)
                if (before != null) {
                    access.logUserEvent(
                        action = "QUICK_EVENT_ENTRY_DELETE",
                        entityType = "QUICK_EVENT_ENTRY",
                        entityId = entryId,
                        summary = ctx.getString(R.string.audit_quick_event_entry_deleted, before.title.ifBlank { entryId.toString() }),
                        payload = JSONObject().put("entryId", entryId),
                        undoable = true
                    )
                }
                refreshFromDb(ctx)
                access.persist()
                access.scheduleAutoBackup()
            }.onFailure { err ->
                Log.e("QuickEventsCapsule", "deleteEntry failed (entryId=$entryId)", err)
                access.showDeleteFailed(ctx)
            }
        }
    }

    fun createMacro(title: String, tagIds: Set<Long>, sortOrder: Int, isArchived: Boolean, actions: List<QuickEventMacroAction>) =
        createMacroInternal(title, tagIds, sortOrder, isArchived, actions)

    private fun createMacroInternal(
        title: String,
        tagIds: Set<Long>,
        sortOrder: Int,
        isArchived: Boolean,
        actions: List<QuickEventMacroAction>
    ) {
        if (access.blockWriteIfNeeded()) return
        val ctx = access.appContextOrNull() ?: return
        access.launchIo("create quick event macro") {
            runCatching {
                val cleanTitle = title.trim()
                val cleanTagIds = tagIds.filter { it > 0L }.toSet()
                val macroId = access.quickEventCore(ctx).insertMacro(
                    title = cleanTitle,
                    tagIds = cleanTagIds,
                    sortOrder = sortOrder,
                    isArchived = isArchived,
                    actions = actions.filter { it.templateId > 0L }
                )
                access.logUserEvent(
                    action = "QUICK_EVENT_MACRO_CREATE",
                    entityType = "QUICK_EVENT_MACRO",
                    entityId = macroId,
                    summary = cleanTitle.ifBlank { macroId.toString() },
                    payload = JSONObject().put("macroId", macroId),
                    undoable = true
                )
                refreshFromDb(ctx)
                access.persist()
                access.scheduleAutoBackup()
            }.onFailure { err ->
                Log.e("QuickEventsCapsule", "createMacro failed", err)
                access.showWriteFailed(ctx)
            }
        }
    }

    fun updateMacro(macroId: Long, title: String, tagIds: Set<Long>, sortOrder: Int, isArchived: Boolean, actions: List<QuickEventMacroAction>) =
        updateMacroInternal(macroId, title, tagIds, sortOrder, isArchived, actions)

    private fun updateMacroInternal(
        macroId: Long,
        title: String,
        tagIds: Set<Long>,
        sortOrder: Int,
        isArchived: Boolean,
        actions: List<QuickEventMacroAction>
    ) {
        if (access.blockWriteIfNeeded()) return
        val ctx = access.appContextOrNull() ?: return
        access.launchIo("update quick event macroId=$macroId") {
            runCatching {
                val cleanTitle = title.trim()
                val cleanTagIds = tagIds.filter { it > 0L }.toSet()
                access.quickEventCore(ctx).updateMacro(
                    macroId = macroId,
                    title = cleanTitle,
                    tagIds = cleanTagIds,
                    sortOrder = sortOrder,
                    isArchived = isArchived,
                    actions = actions.filter { it.templateId > 0L }
                )
                access.logUserEvent(
                    action = "QUICK_EVENT_MACRO_EDIT",
                    entityType = "QUICK_EVENT_MACRO",
                    entityId = macroId,
                    summary = cleanTitle.ifBlank { macroId.toString() },
                    payload = JSONObject().put("macroId", macroId),
                    undoable = true
                )
                refreshFromDb(ctx)
                access.persist()
                access.scheduleAutoBackup()
            }.onFailure { err ->
                Log.e("QuickEventsCapsule", "updateMacro failed (macroId=$macroId)", err)
                access.showWriteFailed(ctx)
            }
        }
    }

    fun deleteMacro(macroId: Long) {
        if (access.blockWriteIfNeeded()) return
        val ctx = access.appContextOrNull() ?: return
        access.launchIo("delete quick event macroId=$macroId") {
            runCatching {
                access.quickEventCore(ctx).softDeleteMacro(macroId)
                refreshFromDb(ctx)
                access.persist()
                access.scheduleAutoBackup()
            }.onFailure { err ->
                Log.e("QuickEventsCapsule", "deleteMacro failed (macroId=$macroId)", err)
                access.showDeleteFailed(ctx)
            }
        }
    }

    fun createEntriesFromMacro(macroId: Long, timestampMs: Long? = null) {
        if (access.blockWriteIfNeeded()) return
        val ctx = access.appContextOrNull() ?: return
        access.launchIo("create quick event macro entries macroId=$macroId") {
            runCatching {
                val result = quickEventExecutor(ctx).execute(QuickEventTarget.Macro(macroId), timestampMs ?: System.currentTimeMillis())
                if (result is QuickEventExecutionResult.Executed) {
                    refreshFromDb(ctx)
                    access.showTargetRecorded(ctx, result.title)
                }
            }.onFailure { err ->
                Log.e("QuickEventsCapsule", "createEntriesFromMacro failed (macroId=$macroId)", err)
                access.showWriteFailed(ctx)
            }
        }
    }

    private fun quickEventExecutor(ctx: android.content.Context): QuickEventExecutor =
        QuickEventExecutor(
            core = access.quickEventCore(ctx),
            audit = { action, entityType, entityId, summary, payload ->
                access.logUserEvent(action, entityType, entityId, summary, payload, true)
            },
            afterSuccessfulWrite = {
                access.persist()
                access.scheduleAutoBackup()
            }
        )

    fun addTag(name: String) = access.addTag(name)

    fun refreshScreenSnapshot(ctx: android.content.Context) {
        access.launchIo("refresh quick events screen") {
            refreshFromDb(ctx.applicationContext)
        }
    }

    fun refreshFromDb(ctx: android.content.Context) {
        val snapshot = StartupPerfTrace.section("quick_events_screen_snapshot") {
            access.quickEventCore(ctx).readSnapshot()
        }
        liveSnapshot.value = snapshot.toCapsuleSnapshot()
        access.syncSharedTagAssignments(liveSnapshot.value)
    }

    private fun buildUiState(host: QuickEventsHostState, snapshot: QuickEventsSnapshot): QuickEventsUiState {
        return QuickEventsUiState(
            tags = host.tags,
            quickEventTemplates = snapshot.templates,
            quickEventEntries = snapshot.entries,
            quickEventFieldDefinitions = snapshot.fieldDefinitions,
            quickEventFieldValues = snapshot.fieldValues,
            quickEventMacros = snapshot.macros,
            quickEventMacroActions = snapshot.macroActions,
            tagLastUsedMsByTagId = host.tagLastUsedMsByTagId,
            nowMs = host.nowMs,
            isReadOnly = host.isReadOnly,
        )
    }

    private fun QuickEventRepository.Snapshot.toCapsuleSnapshot(): QuickEventsSnapshot {
        return QuickEventsSnapshot(
            templates = templates,
            entries = entries,
            fieldDefinitions = fieldDefinitions,
            fieldValues = fieldValues,
            macros = macros,
            macroActions = macroActions,
        )
    }
}
