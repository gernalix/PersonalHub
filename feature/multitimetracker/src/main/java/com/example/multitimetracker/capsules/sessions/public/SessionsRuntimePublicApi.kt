package com.example.multitimetracker.capsules.sessions.public

import android.content.Context
import com.example.multitimetracker.core.contracts.ClosedSessionRecord
import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.Task
import com.example.multitimetracker.model.TimeEngine
import com.example.multitimetracker.persistence.SnapshotStore
import kotlinx.coroutines.flow.StateFlow

interface SessionsRuntimePublicApi {
    val runtimeState: StateFlow<SessionsRuntimeState>
    fun runtimeStateValue(): SessionsRuntimeState
    fun replaceRuntimeState(state: SessionsRuntimeState)
    fun updateRuntimeState(transform: (SessionsRuntimeState) -> SessionsRuntimeState)
    fun prepareSnapshotReadModel(
        context: Context,
        snapshot: SnapshotStore.Snapshot,
        tags: List<Tag>,
    ): SessionsSnapshotReadModel

    fun readAuthoritativeSnapshotReadModel(
        context: Context,
        tags: List<Tag>,
        persistedSnapshotTags: List<Tag> = emptyList(),
    ): SessionsSnapshotReadModel?

    fun resolveSnapshotReadModelTags(
        readModel: SessionsSnapshotReadModel,
        tags: List<Tag>,
    ): SessionsSnapshotReadModel

    fun buildRuntimeSnapshotFromRunningSessions(runningSessions: List<SessionUi>): TimeEngine.RuntimeSnapshot

    fun bootstrapTablesIfEmpty(
        context: Context,
        legacyTasks: List<Task>,
        tags: List<Tag>,
        legacyClosedSessionRecords: List<ClosedSessionRecord>,
        nowMs: Long,
        currentAppVersionCode: Long,
    )
}
