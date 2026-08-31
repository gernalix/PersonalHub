// v384
package com.example.multitimetracker.capsules.system

import android.content.Context
import com.example.multitimetracker.capsules.sessions.public.SessionsRuntimePublicApi
import com.example.multitimetracker.capsules.tags.state.TagsHostState
import com.example.multitimetracker.core.contracts.ClosedSessionRecord
import com.example.multitimetracker.core.contracts.TaggedSessionRecord
import com.example.multitimetracker.core.session.SessionCore
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.Task
import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.model.TimeEngine
import com.example.multitimetracker.model.TimedTagNotificationType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Tags capsule boundary contract.
 *
 * UI must NOT call MainViewModel directly; it talks to the capsule, and the capsule talks to this contract.
 * The contract exposes owner primitives only; tag CRUD policy lives in TagsCapsuleViewModel.
 */
interface TagsCapsuleAccess {
    fun hostStateFlow(): StateFlow<TagsHostState>
    fun runtimeScope(): CoroutineScope
    fun tags(): List<Tag>
    fun runningSessions(): List<SessionUi>
    fun chronologySessions(): List<SessionUi>
    fun tasks(): List<Task>
    fun sessionsRuntime(): SessionsRuntimePublicApi

    fun appContextOrNull(): Context?
    fun blockWriteIfNeeded(): Boolean

    fun createTagRecord(name: String): Tag
    fun renameTagRecords(tags: List<Tag>, tagId: Long, newName: String): List<Tag>
    fun deleteTagRecords(
        tasks: List<Task>,
        tags: List<Tag>,
        tagId: Long,
        deleteAssociatedTasks: Boolean,
        nowMs: Long
    ): TimeEngine.EngineResult

    fun restoreTagRecords(
        tasks: List<Task>,
        tags: List<Tag>,
        tagId: Long,
        nowMs: Long
    ): TimeEngine.EngineResult

    fun purgeTagRecords(tasks: List<Task>, tags: List<Tag>, tagId: Long): TimeEngine.EngineResult
    fun closedSessionRecords(): List<ClosedSessionRecord>
    fun taggedSessionRecords(): List<TaggedSessionRecord>
    fun activeTagStartByTagId(): Map<Long, Long>

    fun sessionCore(context: Context): SessionCore
    fun launchIo(reason: String, block: suspend () -> Unit)

    fun showTagAlreadyExists(context: Context)
    fun showTagHierarchyCycleNotAllowed(context: Context)
    fun showSessionWriteFailed(context: Context)

    fun logUserEvent(action: String, entityType: String, entityId: Long, summary: String, payload: JSONObject?, undoable: Boolean)
    fun rememberCurrentStateAsPersisted()
    fun clearPersistenceFailureReport()
    fun persist()
    fun scheduleAutoBackup()
}
