package com.example.multitimetracker.capsules.sessions.public

import com.example.multitimetracker.model.SessionUi

interface SessionOwnerPublicApi {
    fun updateChronologySession(sessionId: Long, title: String, tagIds: Set<Long>)
    fun updateChronologySessionTimes(sessionId: Long, startMs: Long, endMs: Long?)
    fun deleteChronologySession(sessionId: Long)
    fun createRunningSession(
        title: String,
        startMs: Long,
        tagIds: Set<Long>,
        onCreated: (SessionUi) -> Unit = {},
    )
}
