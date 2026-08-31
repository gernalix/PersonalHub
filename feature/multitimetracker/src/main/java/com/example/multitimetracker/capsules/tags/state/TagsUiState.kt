package com.example.multitimetracker.capsules.tags.state

import com.example.multitimetracker.core.contracts.ClosedSessionRecord
import com.example.multitimetracker.core.contracts.TaggedSessionRecord
import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.Task
import com.example.multitimetracker.model.TemporalContext

data class TagsUiState(
    val tags: List<Tag>,
    val tasks: List<Task>,
    val closedSessions: List<ClosedSessionRecord>,
    val tagSessions: List<TaggedSessionRecord>,
    val chronologySessions: List<SessionUi>,
    val runningSessions: List<SessionUi>,
    val activeTagStart: Map<Long, Long>,
    val activeTagTotalsMsByTagId: Map<Long, Long>,
    val runningMinStartByTagId: Map<Long, Long>,
    val tagLastUsedMsByTagId: Map<Long, Long>,
    val tagParentsByChild: Map<Long, Set<Long>>,
    val tagTotalsMsByTagId: Map<Long, Long>,
    val nowMs: Long,
    val effectiveNowMs: Long,
    val timeMachineTargetMs: Long?,
    val isReadOnly: Boolean,
) {
    fun effectiveTimeContext() = TemporalContext(timeMachineTargetMs).effectiveTime(nowMs)
}

data class TagsHostState(
    val nowMs: Long,
    val effectiveNowMs: Long,
    val timeMachineTargetMs: Long?,
    val isReadOnly: Boolean,
)
