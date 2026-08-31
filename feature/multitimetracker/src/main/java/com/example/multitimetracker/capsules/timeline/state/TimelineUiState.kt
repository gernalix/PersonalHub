package com.example.multitimetracker.capsules.timeline.state

import com.example.multitimetracker.model.QuickEventEntry
import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.TemporalContext

data class TimelineUiState(
    val tags: List<Tag>,
    val chronologySessions: List<SessionUi>,
    val runningSessions: List<SessionUi>,
    val quickEventEntries: List<QuickEventEntry>,
    val tagLastUsedMsByTagId: Map<Long, Long>,
    val tagParentsByChild: Map<Long, Set<Long>>,
    val nowMs: Long,
    val timeMachineTargetMs: Long?,
    val isReadOnly: Boolean,
) {
    fun effectiveTimeContext() = TemporalContext(timeMachineTargetMs).effectiveTime(nowMs)
}
