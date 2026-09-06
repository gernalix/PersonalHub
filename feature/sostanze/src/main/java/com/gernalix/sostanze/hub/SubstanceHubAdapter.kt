package com.gernalix.sostanze.hub

import android.content.Context
import com.gernalix.personalhub.contracts.database.*
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.sostanze.data.SubstanceEntity

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
