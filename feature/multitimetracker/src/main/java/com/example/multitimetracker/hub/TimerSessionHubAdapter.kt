package com.example.multitimetracker.hub

import android.content.Context
import com.example.multitimetracker.core.session.DefaultSessionCore
import com.example.multitimetracker.model.SessionUi
import com.gernalix.personalhub.contracts.database.*

class TimerSessionHubAdapter(private val context: Context) : HubEntityAdapter {
    override val moduleId = "timer"
    override val entityKind = "session"
    override val capabilities = setOf("time_interval", "activity")
    private val sessions = DefaultSessionCore(context.applicationContext)

    override suspend fun exists(canonicalId: String) = canonicalId.toLongOrNull()?.let(sessions::readSessionById) != null
    override suspend fun lifecycle(canonicalId: String) = if (exists(canonicalId)) HubEntityLifecycle.ACTIVE else HubEntityLifecycle.DELETED
    override suspend fun summaries(canonicalIds: Set<String>) = canonicalIds.mapNotNull { id -> id.toLongOrNull()?.let(sessions::readSessionById)?.let { id to it.summary() } }.toMap()
    override suspend fun search(query: String, limit: Int) = sessions.readAllSessions().asSequence()
        .filter { query.isBlank() || it.title.contains(query, true) || it.id.toString() == query }
        .take(limit.coerceIn(1, 100)).map { it.summary() }.toList()
    override suspend fun openTarget(canonicalId: String) = HubOpenTarget("personalhub://module/timer?sessionId=$canonicalId", "com.example.multitimetracker.MainActivity")

    private fun SessionUi.summary() = HubEntitySummary(
        HubEntityRef(moduleId, entityKind, id.toString()),
        title.ifBlank { context.getString(com.example.multitimetracker.R.string.hub_session_fallback, id) },
        attributes = buildMap {
            put("start_ms", startMs.toString())
            endMs?.let { put("end_ms", it.toString()) }
        },
    )
}
