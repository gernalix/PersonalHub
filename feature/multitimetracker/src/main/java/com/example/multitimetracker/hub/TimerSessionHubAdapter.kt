package com.example.multitimetracker.hub

import android.content.Context
import com.example.multitimetracker.core.session.DefaultSessionCore
import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.persistence.SnapshotStore
import com.gernalix.personalhub.contracts.database.*
import com.gernalix.personalhub.core.hubcontext.*
import com.gernalix.personalhub.core.ui.HubTimeFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TimerSessionHubAdapter(private val context: Context) : HubEntityAdapter, HubTemporalProvider {
    override val moduleId = "timer"
    override val entityKind = "session"
    override val capabilities = setOf("time_interval", "activity")
    private val sessions = DefaultSessionCore(context.applicationContext)
    private val appContext = context.applicationContext
    private val fallbackStringId by lazy(LazyThreadSafetyMode.NONE) {
        appContext.resources.getIdentifier("hub_session_time_fallback", "string", appContext.packageName)
    }

    override suspend fun exists(canonicalId: String) = canonicalId.toLongOrNull()?.let(sessions::readSessionById) != null
    override suspend fun lifecycle(canonicalId: String) = if (exists(canonicalId)) HubEntityLifecycle.ACTIVE else HubEntityLifecycle.DELETED

    override suspend fun summaries(canonicalIds: Set<String>): Map<String, HubEntitySummary> {
        val tagNamesById = loadTagNamesById()
        return canonicalIds.mapNotNull { id ->
            id.toLongOrNull()?.let(sessions::readSessionById)?.let { id to it.summary(tagNamesById) }
        }.toMap()
    }

    override suspend fun search(query: String, limit: Int): List<HubEntitySummary> {
        val tagNamesById = loadTagNamesById()
        return sessions.searchSessions(query, limit.coerceIn(1, 100)).map { it.summary(tagNamesById) }
    }

    override suspend fun openTarget(canonicalId: String) = HubOpenTarget(HubDeepLinkContract.moduleUri("timer", "sessionId" to canonicalId).toString(), "com.example.multitimetracker.MainActivity")

    override suspend fun queryTemporal(query: HubTemporalQuery): HubTemporalPage = withContext(Dispatchers.IO) {
        val tagNamesById = loadTagNamesById()
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
            (sessions.readSessionById(row.id) ?: row).temporal(tagNamesById)
        }
        HubTemporalPage(
            page,
            if (rows.size > query.limit) page.lastOrNull()?.let { encodeHubTemporalCursor(it.startMs, it.stableId) } else null,
        )
    }

    private fun SessionUi.temporal(tagNamesById: Map<Long, String>) = HubTemporalRecord(
        moduleId = moduleId,
        source = entityKind,
        stableId = id.toString(),
        kind = HubTemporalKind.INTERVAL,
        startMs = startMs,
        endMs = endMs,
        title = hubLabel(tagNamesById),
        subtitle = tagNames(tagNamesById).takeIf { it.isNotEmpty() }?.joinToString(", "),
        entityRef = HubEntityRef(moduleId, entityKind, id.toString()),
    )

    private fun SessionUi.summary(tagNamesById: Map<Long, String>) = HubEntitySummary(
        HubEntityRef(moduleId, entityKind, id.toString()),
        hubLabel(tagNamesById),
        description = tagNames(tagNamesById).takeIf { it.isNotEmpty() }?.joinToString(", "),
        attributes = buildMap {
            put("start_ms", startMs.toString())
            endMs?.let { put("end_ms", it.toString()) }
        },
    )

    private fun SessionUi.hubLabel(tagNamesById: Map<Long, String>): String {
        title.trim().takeIf(String::isNotEmpty)?.let { return it }
        tagNames(tagNamesById).takeIf { it.isNotEmpty() }?.let { return it.joinToString(", ") }
        return mergedFallbackString(formatSessionStart())
    }

    private fun SessionUi.tagNames(tagNamesById: Map<Long, String>): List<String> =
        tagIds.sorted().mapNotNull { id -> tagNamesById[id] }

    private suspend fun loadTagNamesById(): Map<Long, String> = withContext(Dispatchers.IO) {
        SnapshotStore.load(appContext).orEmptyTags()
            .mapNotNull { tag -> tag.name.trim().takeIf(String::isNotEmpty)?.let { tag.id to it } }
            .toMap()
    }

    private fun SnapshotStore.Snapshot?.orEmptyTags() = this?.tags.orEmpty()

    private fun SessionUi.formatSessionStart(): String =
        HubTimeFormat.pattern(startMs, "yyyy-MM-dd HH:mm")

    private fun mergedFallbackString(value: String): String =
        if (fallbackStringId != 0) appContext.getString(fallbackStringId, value) else "Session on $value"
}
