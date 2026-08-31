// v318
package com.example.multitimetracker.capsules.system

import android.content.Context
import com.example.multitimetracker.capsules.auditlog.state.AuditLogUiState
import kotlinx.coroutines.flow.StateFlow

/**
 * AuditLog capsule access contract.
 */
interface AuditLogCapsuleAccess {
    fun uiStateFlow(): StateFlow<AuditLogUiState>
    fun appContextOrNull(): Context?
    fun blockWriteIfNeeded(): Boolean
    fun timeMachineTargetMs(): Long?

    fun deleteSession(sessionId: Long)
    fun deleteTag(tagId: Long)
    fun setTagParents(tagId: Long, parentIds: Set<Long>)
    fun restoreStoppedSessionFromAudit(sessionId: Long, startMs: Long, endMs: Long, eventId: Long)
    fun refreshRuntimeAfterUndo()
}
