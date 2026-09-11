package com.gernalix.sostanze.hub

import android.content.Context
import com.gernalix.personalhub.contracts.database.*
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.hubcontext.*
import com.gernalix.sostanze.data.SubstanceEntity
import com.gernalix.sostanze.data.IntakeHubView

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
    override suspend fun openTarget(canonicalId: String) = HubOpenTarget("personalhub://module/substances?substanceId=$canonicalId", "com.gernalix.sostanze.MainActivity")

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
    private val dao get() = PersonalHubDatabase.get(context).dao()

    override suspend fun exists(canonicalId: String) = canonicalId.toLongOrNull()?.let { dao.intakeById(it) } != null
    override suspend fun lifecycle(canonicalId: String) = if (exists(canonicalId)) HubEntityLifecycle.ACTIVE else HubEntityLifecycle.DELETED
    override suspend fun summaries(canonicalIds: Set<String>) = dao.intakeHubViews(canonicalIds.mapNotNull(String::toLongOrNull)).associate { it.intake.id.toString() to it.summary() }
    override suspend fun search(query: String, limit: Int) = dao.searchIntakeHubViews(query.trim(), limit.coerceIn(1, 100)).map { it.summary() }
    override suspend fun openTarget(canonicalId: String) = HubOpenTarget("personalhub://module/substances", "com.gernalix.sostanze.MainActivity")

    override suspend fun queryTemporal(query: HubTemporalQuery): HubTemporalPage {
        val offset = query.cursor?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val rows = dao.temporalIntakeHubViews(query.fromMs, query.toMs, query.limit + 1, offset)
        return HubTemporalPage(rows.take(query.limit).map { it.temporal() }, if (rows.size > query.limit) (offset + query.limit).toString() else null)
    }

    private fun IntakeHubView.summary() = HubEntitySummary(HubEntityRef(moduleId, entityKind, intake.id.toString()), substanceName, "${intake.dose} ${intake.doseUnit}", attributes = mapOf("time_ms" to intake.timestampMs.toString()))
    private fun IntakeHubView.temporal() = HubTemporalRecord(moduleId, entityKind, intake.id.toString(), HubTemporalKind.POINT, intake.timestampMs, title = substanceName, subtitle = "${intake.dose} ${intake.doseUnit}", entityRef = HubEntityRef(moduleId, entityKind, intake.id.toString()))
}
