// v318
package com.example.multitimetracker.capsules.timeline.controller

import androidx.lifecycle.ViewModel
import com.example.multitimetracker.capsules.sessions.public.SessionOwnerPublicApi
import com.example.multitimetracker.capsules.system.CapsuleRuntimeChange
import com.example.multitimetracker.capsules.system.CapsuleRuntimeParticipant
import com.example.multitimetracker.capsules.system.TimelineCapsuleAccess
import com.example.multitimetracker.capsules.timeline.state.TimelineUiState
import kotlinx.coroutines.flow.StateFlow

class TimelineCapsuleViewModel(
    private val access: TimelineCapsuleAccess,
    private val sessionOwner: SessionOwnerPublicApi
) : ViewModel(), CapsuleRuntimeParticipant {
    override val capsuleId: String = "timeline"

    val uiState: StateFlow<TimelineUiState> = access.uiStateFlow()

    override fun onCapsuleRuntimeChanged(context: android.content.Context?, change: CapsuleRuntimeChange) = Unit

    fun updateSession(sessionId: Long, title: String, tagIds: Set<Long>) {
        sessionOwner.updateChronologySession(sessionId, title, tagIds)
    }

    fun updateSessionTimes(sessionId: Long, startMs: Long, endMsOrNull: Long?) {
        sessionOwner.updateChronologySessionTimes(sessionId, startMs, endMsOrNull)
    }

    fun deleteSession(sessionId: Long) {
        sessionOwner.deleteChronologySession(sessionId)
    }

    fun addTag(name: String) {
        access.addTag(name)
    }
}
