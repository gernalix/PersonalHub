package com.example.multitimetracker.hub

import android.content.Context
import com.example.multitimetracker.core.session.DefaultSessionCore
import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.persistence.SnapshotStore
import com.gernalix.personalhub.contracts.database.*
import com.gernalix.personalhub.core.hubcontext.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
        val page = rows.take(query.limit).map { row ->
            (sessions.readSessionById(row.id) ?: row).temporal()
        }
        HubTemporalPage(
            page,
            if (rows.size > query.limit) page.lastOrNull()?.let { encodeHubTemporalCursor(it.startMs, it.stableId) } else null,
        )
    }

    private val fallbackFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private fun SessionUi.temporal() = HubTemporalRecord(
        moduleId = moduleId,
        source = entityKind,
        stableId = id.toString(),
        kind = HubTemporalKind.INTERVAL,
        startMs = startMs,
        endMs = endMs,
        title = hubLabel(),
        subtitle = tagNames().takeIf { it.isNotEmpty() }?.joinToString(", "),
        entityRef = HubEntityRef(moduleId, entityKind, id.toString()),
    )

    private fun SessionUi.summary() = HubEntitySummary(
        HubEntityRef(moduleId, entityKind, id.toString()),
        hubLabel(),
        description = tagNames().takeIf { it.isNotEmpty() }?.joinToString(", "),
        attributes = buildMap {
            put("start_ms", startMs.toString())
            endMs?.let { put("end_ms", it.toString()) }
        },
    )

    private fun SessionUi.hubLabel(): String {
        title.trim().takeIf(String::isNotEmpty)?.let { return it }
        tagNames().takeIf { it.isNotEmpty() }?.let { return it.joinToString(", ") }
        return mergedString("hub_session_time_fallback", formatSessionStart())
    }

    private fun SessionUi.tagNames(): List<String> {
        if (tagIds.isEmpty()) return emptyList()
        val namesById = SnapshotStore.load(context.applicationContext).orEmptyTags()
            .associate { it.id to it.name.trim() }
        return tagIds.sorted().mapNotNull { id -> namesById[id]?.takeIf(String::isNotEmpty) }
    }

    private fun SnapshotStore.Snapshot?.orEmptyTags() = this?.tags.orEmpty()

    private fun SessionUi.formatSessionStart(): String =
        Instant.ofEpochMilli(startMs).atZone(ZoneId.systemDefault()).format(fallbackFormatter)

    private fun mergedString(name: String, value: String): String {
        val appContext = context.applicationContext
        val id = appContext.resources.getIdentifier(name, "string", appContext.packageName)
        return if (id != 0) appContext.getString(id, value) else "Session on $value"
    }
}
