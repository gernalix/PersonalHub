package com.wordpulse.app.hub

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import com.gernalix.personalhub.contracts.database.*
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.hubcontext.*
import com.wordpulse.app.data.WordSession
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WordSessionHubAdapter(private val context: Context) : HubEntityAdapter, HubTemporalProvider {
    override val moduleId = "wordpulse"
    override val entityKind = "word_session"
    override val capabilities = setOf("wordpulse", "time_interval", "activity")
    private val database get() = PersonalHubDatabase.get(context)
    private val dao get() = database.wordPulseDao()

    override suspend fun exists(canonicalId: String) = dao.getSession(canonicalId) != null
    override suspend fun lifecycle(canonicalId: String) = if (exists(canonicalId)) HubEntityLifecycle.ACTIVE else HubEntityLifecycle.DELETED
    override suspend fun summaries(canonicalIds: Set<String>) = if (canonicalIds.isEmpty()) emptyMap() else
        dao.getSessionsByIds(canonicalIds.toList()).associate { it.id to it.summary() }
    override suspend fun search(query: String, limit: Int) = dao.searchSessions(query.trim(), limit.coerceIn(1, 100)).map { it.summary() }
    override suspend fun openTarget(canonicalId: String) = HubOpenTarget("personalhub://module/wordpulse?sessionId=${android.net.Uri.encode(canonicalId)}", "com.wordpulse.app.MainActivity")

    override suspend fun queryTemporal(query: HubTemporalQuery): HubTemporalPage = withContext(Dispatchers.IO) {
        val cursor = decodeHubTemporalCursor(query.cursor)
        val args = mutableListOf<Any?>(query.toMs, query.fromMs)
        val cursorClause = if (cursor != null) {
            args += cursor.sortMs
            args += cursor.sortMs
            args += cursor.stableId
            "AND (started_at_utc_ms < ? OR (started_at_utc_ms = ? AND id < ?))"
        } else ""
        args += query.limit + 1
        val sql = """
            SELECT id, started_at_utc_ms, ended_at_utc_ms
            FROM wordpulse_sessions
            WHERE started_at_utc_ms < ? AND (ended_at_utc_ms IS NULL OR ended_at_utc_ms > ?)
              $cursorClause
            ORDER BY started_at_utc_ms DESC, id DESC
            LIMIT ?
        """.trimIndent()
        val rows = database.openHelper.readableDatabase.query(SimpleSQLiteQuery(sql, args.toTypedArray())).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val startMs = c.getLong(1)
                    val endMs = if (c.isNull(2)) null else c.getLong(2)
                    add(
                        HubTemporalRecord(
                            moduleId,
                            entityKind,
                            id,
                            HubTemporalKind.INTERVAL,
                            startMs,
                            endMs,
                            "WordPulse · ${Instant.ofEpochMilli(startMs)}",
                            entityRef = HubEntityRef(moduleId, entityKind, id),
                        ),
                    )
                }
            }
        }
        val page = rows.take(query.limit)
        HubTemporalPage(
            page,
            if (rows.size > query.limit) page.lastOrNull()?.let { encodeHubTemporalCursor(it.startMs, it.stableId) } else null,
        )
    }

    private fun WordSession.summary() = HubEntitySummary(
        HubEntityRef(moduleId, entityKind, id),
        "WordPulse · ${Instant.ofEpochMilli(startedAtUtcMs)}",
        endedAtUtcMs?.let { "${Instant.ofEpochMilli(startedAtUtcMs)} — ${Instant.ofEpochMilli(it)}" },
        attributes = buildMap { put("start_ms", startedAtUtcMs.toString()); endedAtUtcMs?.let { put("end_ms", it.toString()) } },
    )
}
