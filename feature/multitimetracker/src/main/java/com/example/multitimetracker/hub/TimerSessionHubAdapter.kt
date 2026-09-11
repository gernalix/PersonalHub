package com.example.multitimetracker.hub

import android.content.Context
import com.example.multitimetracker.core.session.DefaultSessionCore
import com.example.multitimetracker.model.SessionUi
import com.gernalix.personalhub.contracts.database.*
import com.gernalix.personalhub.core.hubcontext.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TimerSessionHubAdapter(private val context: Context) : HubEntityAdapter, HubTemporalProvider {
    override val moduleId = "timer"
    override val entityKind = "session"
    override val capabilities = setOf("time_interval", "activity")
    private val sessions = DefaultSessionCore(context.applicationContext)

    override suspend fun exists(canonicalId: String) = canonicalId.toLongOrNull()?.let(sessions::readSessionById) != null
    override suspend fun lifecycle(canonicalId: String) = if (exists(canonicalId)) HubEntityLifecycle.ACTIVE else HubEntityLifecycle.DELETED
    override suspend fun summaries(canonicalIds: Set<String>) = canonicalIds.mapNotNull { id -> id.toLongOrNull()?.let(sessions::readSessionById)?.let { id to it.summary() } }.toMap()
    override suspend fun search(query: String, limit: Int) =
        sessions.searchSessions(query, limit.coerceIn(1, 100)).map { it.summary() }
    override suspend fun openTarget(canonicalId: String) = HubOpenTarget("personalhub://module/timer?sessionId=$canonicalId", "com.example.multitimetracker.MainActivity")

    override suspend fun queryTemporal(query: HubTemporalQuery): HubTemporalPage = withContext(Dispatchers.IO) {
        val cursor = decodeHubTemporalCursor(query.cursor)
        val cursorId = cursor?.stableId?.toLongOrNull()
        val rows = sessions.readTemporalSessionsKeyset(
            query.fromMs,
            query.toMs,
            query.limit + 1,
            if (cursorId != null) cursor.sortMs else null,
            cursorId,
        )
        val page = rows.take(query.limit).map { it.temporal() }
        HubTemporalPage(
            page,
            if (rows.size > query.limit) page.lastOrNull()?.let { encodeHubTemporalCursor(it.startMs, it.stableId) } else null,
        )
    }

    private fun SessionUi.temporal() = HubTemporalRecord(moduleId, entityKind, id.toString(), HubTemporalKind.INTERVAL, startMs, endMs, title.ifBlank { context.getString(com.example.multitimetracker.R.string.hub_session_fallback, id) }, entityRef = HubEntityRef(moduleId, entityKind, id.toString()))

    private fun SessionUi.summary() = HubEntitySummary(
        HubEntityRef(moduleId, entityKind, id.toString()),
        title.ifBlank { context.getString(com.example.multitimetracker.R.string.hub_session_fallback, id) },
        attributes = buildMap {
            put("start_ms", startMs.toString())
            endMs?.let { put("end_ms", it.toString()) }
        },
    )
}
