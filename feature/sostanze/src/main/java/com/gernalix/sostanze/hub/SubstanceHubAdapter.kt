package com.gernalix.sostanze.hub

import android.content.Context
import com.gernalix.personalhub.core.database.capsules.identity.CanonicalIdentityCapsule
import androidx.sqlite.db.SimpleSQLiteQuery
import com.gernalix.personalhub.contracts.database.*
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.hubcontext.*
import com.gernalix.sostanze.data.SubstanceEntity
import com.gernalix.sostanze.data.IntakeHubView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first

class SubstanceHubAdapter(private val context: Context) : HubEntityAdapter {
    override val moduleId = "substances"
    override val entityKind = "substance"
    override val capabilities = setOf("substance", "health")
    private val identities = CanonicalIdentityCapsule(context)
    private fun local(id: String) = identities.localKey("$moduleId/$entityKind", id)?.toLongOrNull()
    private val dao get() = PersonalHubDatabase.get(context).dao()
    private val database get() = PersonalHubDatabase.get(context)

    override suspend fun exists(canonicalId: String) = local(canonicalId)?.let { dao.substanceById(it) } != null
    override suspend fun lifecycle(canonicalId: String) = when (local(canonicalId)?.let { dao.substanceById(it) }?.archived) {
        null -> HubEntityLifecycle.DELETED
        true -> HubEntityLifecycle.ARCHIVED
        false -> HubEntityLifecycle.ACTIVE
    }
    override suspend fun summaries(canonicalIds: Set<String>) = if (canonicalIds.isEmpty()) emptyMap() else
        dao.substancesByIds(canonicalIds.mapNotNull(::local)).associate { it.canonicalId to it.summary() }
    override suspend fun search(query: String, limit: Int) = dao.searchSubstances(query.trim(), limit.coerceIn(1, 100)).map { it.summary() }
    override suspend fun openTarget(canonicalId: String) = HubOpenTarget(HubDeepLinkContract.moduleUri("substances", "substanceId" to canonicalId).toString(), "com.gernalix.sostanze.MainActivity")

    override suspend fun sinceWhenSource(canonicalId: String): SinceWhenSourceDescriptor? {
        val substanceId = local(canonicalId) ?: return null
        val substance = dao.substanceById(substanceId) ?: return null
        val createdAt = database.openHelper.readableDatabase.query(
            SimpleSQLiteQuery(
                "SELECT min(occurred_at) FROM hub_activity_log WHERE module_id = 'substances' AND entity_kind = 'substance' AND entity_id = ? AND action = 'substance_created'",
                arrayOf(canonicalId),
            ),
        ).use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null }
        val sources = createdAt?.let { listOf(SinceWhenTimestampSource("added_at", context.getString(com.gernalix.sostanze.R.string.since_when_added_to_ph), it, true)) }.orEmpty()
        return SinceWhenSourceDescriptor("$moduleId/$entityKind", canonicalId, substance.name, sources)
    }

    private fun SubstanceEntity.summary() = HubEntitySummary(
        HubEntityRef(moduleId, entityKind, canonicalId), name,
        "$dosePerIntake $doseUnit · $stockCurrent $stockUnit",
        if (archived) HubEntityLifecycle.ARCHIVED else HubEntityLifecycle.ACTIVE,
    )
}

class SubstanceIntakeHubAdapter(private val context: Context) : HubEntityAdapter, HubTemporalProvider {
    override val moduleId = "substances"
    override val entityKind = "intake"
    override val capabilities = setOf("health", "intake", "time_point")
    private val database get() = PersonalHubDatabase.get(context)
    private val identities = CanonicalIdentityCapsule(context)
    private fun local(id: String) = identities.localKey("$moduleId/$entityKind", id)?.toLongOrNull()
    private val dao get() = database.dao()

    override suspend fun exists(canonicalId: String) = local(canonicalId)?.let { dao.intakeById(it) } != null
    override suspend fun lifecycle(canonicalId: String) = if (exists(canonicalId)) HubEntityLifecycle.ACTIVE else HubEntityLifecycle.DELETED
    override suspend fun summaries(canonicalIds: Set<String>) = dao.intakeHubViews(canonicalIds.mapNotNull(::local)).associate { it.intake.canonicalId to it.summary() }
    override suspend fun search(query: String, limit: Int) = dao.searchIntakeHubViews(query.trim(), limit.coerceIn(1, 100)).map { it.summary() }
    override suspend fun openTarget(canonicalId: String) = HubOpenTarget(HubDeepLinkContract.moduleUri("substances").toString(), "com.gernalix.sostanze.MainActivity")

    override suspend fun queryTemporal(query: HubTemporalQuery): HubTemporalPage = withContext(Dispatchers.IO) {
        val decoded = decodeHubTemporalCursor(query.cursor)
        val cursorId = decoded?.stableId?.let(::local)
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
                            identities.canonicalId("intake_events", id),
                            HubTemporalKind.POINT,
                            timestampMs,
                            title = name,
                            subtitle = "$dose $unit",
                            entityRef = HubEntityRef(moduleId, entityKind, identities.canonicalId("intake_events", id)),
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

    private fun IntakeHubView.summary() = HubEntitySummary(HubEntityRef(moduleId, entityKind, intake.canonicalId), substanceName, "${intake.dose} ${intake.doseUnit}", attributes = mapOf("time_ms" to intake.timestampMs.toString()))
}

class PrescriptionHubAdapter(private val context: Context) : HubEntityAdapter {
    override val moduleId = "substances"
    override val entityKind = "prescription"
    override val capabilities = setOf("health", "prescription", "contextual")
    private val identities = CanonicalIdentityCapsule(context)
    private fun local(id: String) = identities.localKey("$moduleId/$entityKind", id)?.toLongOrNull()
    private val dao get() = PersonalHubDatabase.get(context).dao()

    override suspend fun exists(canonicalId: String) = local(canonicalId)?.let { dao.prescriptionById(it) } != null
    override suspend fun lifecycle(canonicalId: String) =
        if (exists(canonicalId)) HubEntityLifecycle.ACTIVE else HubEntityLifecycle.DELETED

    override suspend fun summaries(canonicalIds: Set<String>): Map<String, HubEntitySummary> =
        canonicalIds.mapNotNull { raw ->
            val prescription = local(raw)?.let { dao.prescriptionById(it) } ?: return@mapNotNull null
            val substance = dao.substanceById(prescription.substanceId) ?: return@mapNotNull null
            raw to HubEntitySummary(HubEntityRef(moduleId, entityKind, raw), substance.name,
                "${prescription.doseMg} mg")
        }.toMap()

    override suspend fun search(query: String, limit: Int): List<HubEntitySummary> {
        val rows = dao.observePrescriptions().first()
        return summaries(rows.map { it.canonicalId }.toSet()).values
            .filter { it.label.contains(query.trim(), ignoreCase = true) }.take(limit)
    }

    override suspend fun openTarget(canonicalId: String): HubOpenTarget? =
        if (exists(canonicalId)) HubOpenTarget(HubDeepLinkContract.moduleUri("substances").toString(),
            "com.gernalix.sostanze.MainActivity") else null
}
