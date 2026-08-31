package com.example.multitimetracker.capsules.now.state

import com.example.multitimetracker.model.HomeLoadState
import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.TemporalContext

data class NowUiState(
    val tags: List<Tag>,
    val chronologySessions: List<SessionUi>,
    val runningSessions: List<SessionUi>,
    val activeTagTotalsMsByTagId: Map<Long, Long>,
    val runningMinStartByTagId: Map<Long, Long>,
    val tagLastUsedMsByTagId: Map<Long, Long>,
    val tagParentsByChild: Map<Long, Set<Long>>,
    val nowMs: Long,
    val timeMachineTargetMs: Long?,
    val isReadOnly: Boolean,
    val homeLoadState: HomeLoadState,
) {
    fun effectiveTimeContext() = TemporalContext(timeMachineTargetMs).effectiveTime(nowMs)
}
