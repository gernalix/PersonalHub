package com.example.multitimetracker.capsules.sessions.public

import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.TimeEngine

data class SessionsSnapshotReadModel(
    val runtimeState: SessionsRuntimeState,
    val runtimeSnapshot: TimeEngine.RuntimeSnapshot,
    val activeTagStartByTagId: Map<Long, Long>,
    val tags: List<Tag>,
)
