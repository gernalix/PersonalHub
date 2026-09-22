package com.gernalix.personalhub.salute.hub

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import com.gernalix.personalhub.contracts.database.*
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.hubcontext.*

/** Canonical, read-only Hub seam for Health. Patches and imports write through the shared DB. */
class HealthHubAdapter(private val context: Context, override val entityKind: String) : HubEntityAdapter, HubTemporalProvider {
    init { require(entityKind in setOf("event", "sample", "measurement", "journal")) }
    override val moduleId = "salute"
    override val capabilities = setOf("health", "time_point")
    private val db get() = PersonalHubDatabase.get(context).openHelper.readableDatabase
    private val source get() = when (entityKind) {
        "event" -> "health_events"
        "sample" -> "health_samples"
        "measurement" -> "health_measurements"
        else -> "health_journal_entries"
    }
    private val labelSql get() = when (entityKind) {
        "event" -> "COALESCE(title_it,event_kind)"
        "sample" -> "COALESCE(material_it,sample_kind)"
        "measurement" -> "COALESCE((SELECT display_name_it FROM health_examinations WHERE id=examination_id),'Esame')"
        else -> "COALESCE(title_it,'Nota clinica')"
    }

    override suspend fun exists(canonicalId: String): Boolean = run {
        db.query(SimpleSQLiteQuery("SELECT 1 FROM $source WHERE id=? LIMIT 1",arrayOf(canonicalId))).use { it.moveToFirst() }
    }
    override suspend fun lifecycle(canonicalId: String) = if (exists(canonicalId)) HubEntityLifecycle.ACTIVE else HubEntityLifecycle.DELETED
    override suspend fun summaries(canonicalIds: Set<String>): Map<String,HubEntitySummary> = run {
        if (canonicalIds.isEmpty()) return@run emptyMap()
        val values=canonicalIds.toList()
        db.query(SimpleSQLiteQuery("SELECT id,$labelSql FROM $source WHERE id IN (${values.joinToString(",") { "?" }})",values.toTypedArray())).use { c ->
            buildMap { while(c.moveToNext()) put(c.getString(0),HubEntitySummary(HubEntityRef(moduleId,entityKind,c.getString(0)),c.getString(1))) }
        }
    }
    override suspend fun search(query: String, limit: Int): List<HubEntitySummary> = run {
        db.query(SimpleSQLiteQuery("SELECT id,$labelSql FROM $source WHERE $labelSql LIKE ? ORDER BY id LIMIT ?",arrayOf("%${query.trim()}%",limit.coerceIn(1,100)))).use { c ->
            buildList { while(c.moveToNext()) add(HubEntitySummary(HubEntityRef(moduleId,entityKind,c.getString(0)),c.getString(1))) }
        }
    }
    override suspend fun openTarget(canonicalId: String) = HubOpenTarget(HubDeepLinkContract.moduleUri("salute").toString(), "com.gernalix.personalhub.salute.SaluteActivity")

    override suspend fun queryTemporal(query: HubTemporalQuery): HubTemporalPage = run {
        if (entityKind != "event") return@run HubTemporalPage(emptyList())
        val cursor=decodeHubTemporalCursor(query.cursor)
        val args=mutableListOf<Any?>(query.fromMs,query.toMs)
        val after=if(cursor==null) "" else {
            args+=cursor.sortMs;args+=cursor.sortMs;args+=cursor.stableId
            "AND (occurred_at_ms < ? OR (occurred_at_ms = ? AND id < ?))"
        }
        args+=query.limit+1
        val records=db.query(SimpleSQLiteQuery("SELECT id,occurred_at_ms,COALESCE(title_it,event_kind),event_kind FROM health_events WHERE occurred_at_ms>=? AND occurred_at_ms<? $after ORDER BY occurred_at_ms DESC,id DESC LIMIT ?",args.toTypedArray())).use { c ->
            buildList { while(c.moveToNext()) add(HubTemporalRecord(moduleId,entityKind,c.getString(0),HubTemporalKind.POINT,c.getLong(1),title=c.getString(2),subtitle=c.getString(3),entityRef=HubEntityRef(moduleId,entityKind,c.getString(0)))) }
        }
        val page=records.take(query.limit)
        HubTemporalPage(page,if(records.size>query.limit) page.lastOrNull()?.let { encodeHubTemporalCursor(it.startMs,it.stableId) } else null)
    }
}
