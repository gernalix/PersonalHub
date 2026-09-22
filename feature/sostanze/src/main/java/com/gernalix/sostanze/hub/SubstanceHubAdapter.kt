package com.gernalix.sostanze.hub

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import com.gernalix.personalhub.contracts.database.*
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.hubcontext.*
import com.gernalix.sostanze.data.SubstanceEntity
import com.gernalix.sostanze.data.IntakeHubView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SubstanceHubAdapter(private val context: Context) : HubEntityAdapter {
    override val moduleId = "substances"
    override val entityKind = "substance"
    override val capabilities = setOf("substance", "health")
    private val dao get() = PersonalHubDatabase.get(context).dao()

    override suspend fun exists(canonicalId: String) = canonicalId.toLongOrNull()?.let { dao.substanceById(it) } != null
    override suspend fun lifecycle(canonicalId: String) = when (canonicalId.toLongOrNull()?.let { dao.substanceById(it) }?.archived) {
        null -> HubEntityLifecycle.DELETED
        true -> HubEntityLifecycle.ARCHIVED
        false -> HubEntityLifecycle.ACTIVE
    }
    override suspend fun summaries(canonicalIds: Set<String>) = if (canonicalIds.isEmpty()) emptyMap() else
        dao.substancesByIds(canonicalIds.mapNotNull(String::toLongOrNull)).associate { it.id.toString() to it.summary() }
    override suspend fun search(query: String, limit: Int) = dao.searchSubstances(query.trim(), limit.coerceIn(1, 100)).map { it.summary() }
    override suspend fun openTarget(canonicalId: String) = HubOpenTarget(HubDeepLinkContract.moduleUri("substances", "substanceId" to canonicalId).toString(), "com.gernalix.sostanze.MainActivity")

    private fun SubstanceEntity.summary() = HubEntitySummary(
        HubEntityRef(moduleId, entityKind, id.toString()), name,
        "$dosePerIntake $doseUnit · $stockCurrent $stockUnit",
        if (archived) HubEntityLifecycle.ARCHIVED else HubEntityLifecycle.ACTIVE,
    )
}

class SubstanceIntakeHubAdapter(private val context: Context) : HubEntityAdapter, HubTemporalProvider {
    override val moduleId = "substances"
    override val entityKind = "intake"
    override val capabilities = setOf("health", "intake", "time_point")
    private val database get() = PersonalHubDatabase.get(context)
    private val dao get() = database.dao()

    override suspend fun exists(canonicalId: String) = canonicalId.toLongOrNull()?.let { dao.intakeById(it) } != null
    override suspend fun lifecycle(canonicalId: String) = if (exists(canonicalId)) HubEntityLifecycle.ACTIVE else HubEntityLifecycle.DELETED
    override suspend fun summaries(canonicalIds: Set<String>) = dao.intakeHubViews(canonicalIds.mapNotNull(String::toLongOrNull)).associate { it.intake.id.toString() to it.summary() }
    override suspend fun search(query: String, limit: Int) = dao.searchIntakeHubViews(query.trim(), limit.coerceIn(1, 100)).map { it.summary() }
    override suspend fun openTarget(canonicalId: String) = HubOpenTarget(HubDeepLinkContract.moduleUri("substances").toString(), "com.gernalix.sostanze.MainActivity")

    override suspend fun queryTemporal(query: HubTemporalQuery): HubTemporalPage = withContext(Dispatchers.IO) {
        val decoded = decodeHubTemporalCursor(query.cursor)
        val cursorId = decoded?.stableId?.toLongOrNull()
        val cursor = decoded?.takeIf { cursorId != null }
        val args = mutableListOf<Any?>(query.fromMs, query.toMs)
        val cursorClause = if (cursor != null) {
            args += cursor.sortMs
            args += cursor.sortMs
            args += cursorId
            "AND (i.timestamp_ms < ? OR (i.timestamp_ms = ? AND i.id < ?))"
        } else ""
        args += query.limit + 1
        val sql = """
            SELECT i.id, i.timestamp_ms, s.name, i.dose, i.dose_unit
            FROM intake_events i
            JOIN substances s ON s.id = i.substance_id
            WHERE i.timestamp_ms >= ? AND i.timestamp_ms < ?
              $cursorClause
            ORDER BY i.timestamp_ms DESC, i.id DESC
            LIMIT ?
        """.trimIndent()
        val rows = database.openHelper.readableDatabase.query(SimpleSQLiteQuery(sql, args.toTypedArray())).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val timestampMs = c.getLong(1)
                    val name = c.getString(2)
                    val dose = c.getDouble(3)
                    val unit = c.getString(4)
                    add(
                        HubTemporalRecord(
                            moduleId,
                            entityKind,
                            id.toString(),
                            HubTemporalKind.POINT,
                            timestampMs,
                            title = name,
                            subtitle = "$dose $unit",
                            entityRef = HubEntityRef(moduleId, entityKind, id.toString()),
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

    private fun IntakeHubView.summary() = HubEntitySummary(HubEntityRef(moduleId, entityKind, intake.id.toString()), substanceName, "${intake.dose} ${intake.doseUnit}", attributes = mapOf("time_ms" to intake.timestampMs.toString()))
}
