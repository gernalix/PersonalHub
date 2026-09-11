package com.wordpulse.app.hub

import android.content.Context
import com.gernalix.personalhub.contracts.database.*
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.hubcontext.*
import com.wordpulse.app.data.WordSession
import java.time.Instant

class WordSessionHubAdapter(private val context: Context) : HubEntityAdapter, HubTemporalProvider {
    override val moduleId = "wordpulse"
    override val entityKind = "word_session"
    override val capabilities = setOf("wordpulse", "time_interval", "activity")
    private val dao get() = PersonalHubDatabase.get(context).wordPulseDao()

    override suspend fun exists(canonicalId: String) = dao.getSession(canonicalId) != null
    override suspend fun lifecycle(canonicalId: String) = if (exists(canonicalId)) HubEntityLifecycle.ACTIVE else HubEntityLifecycle.DELETED
    override suspend fun summaries(canonicalIds: Set<String>) = if (canonicalIds.isEmpty()) emptyMap() else
        dao.getSessionsByIds(canonicalIds.toList()).associate { it.id to it.summary() }
    override suspend fun search(query: String, limit: Int) = dao.searchSessions(query.trim(), limit.coerceIn(1, 100)).map { it.summary() }
    override suspend fun openTarget(canonicalId: String) = HubOpenTarget("personalhub://module/wordpulse?sessionId=${android.net.Uri.encode(canonicalId)}", "com.wordpulse.app.MainActivity")

    override suspend fun queryTemporal(query: HubTemporalQuery): HubTemporalPage {
        val offset = query.cursor?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val rows = dao.temporalSessions(query.fromMs, query.toMs, query.limit + 1, offset)
        return HubTemporalPage(rows.take(query.limit).map { it.temporal() }, if (rows.size > query.limit) (offset + query.limit).toString() else null)
    }

    private fun WordSession.temporal() = HubTemporalRecord(moduleId, entityKind, id, HubTemporalKind.INTERVAL, startedAtUtcMs, endedAtUtcMs, "WordPulse · ${Instant.ofEpochMilli(startedAtUtcMs)}", entityRef = HubEntityRef(moduleId, entityKind, id))

    private fun WordSession.summary() = HubEntitySummary(
        HubEntityRef(moduleId, entityKind, id),
        "WordPulse · ${Instant.ofEpochMilli(startedAtUtcMs)}",
        endedAtUtcMs?.let { "${Instant.ofEpochMilli(startedAtUtcMs)} — ${Instant.ofEpochMilli(it)}" },
        attributes = buildMap { put("start_ms", startedAtUtcMs.toString()); endedAtUtcMs?.let { put("end_ms", it.toString()) } },
    )
}
