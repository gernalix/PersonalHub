package com.example.multitimetracker.capsules.sessions.public

import com.example.multitimetracker.core.contracts.ClosedSessionRecord
import com.example.multitimetracker.core.contracts.TaggedSessionRecord
import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.model.Task

data class SessionsRuntimeState(
    val tasks: List<Task> = emptyList(),
    val closedSessions: List<ClosedSessionRecord> = emptyList(),
    val tagSessions: List<TaggedSessionRecord> = emptyList(),
    val chronologySessions: List<SessionUi> = emptyList(),
    val runningSessions: List<SessionUi> = emptyList(),
    val activeTagTotalsMsByTagId: Map<Long, Long> = emptyMap(),
    val runningMinStartByTagId: Map<Long, Long> = emptyMap(),
    val tagTotalsMsByTagId: Map<Long, Long> = emptyMap(),
    val tagLastUsedMsByTagId: Map<Long, Long> = emptyMap(),
)
