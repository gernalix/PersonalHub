package com.gernalix.personalhub.hub

import android.content.Context
import com.gernalix.personalhub.core.database.capsules.identity.CanonicalIdentityCapsule
import com.gernalix.personalhub.SinceWhenActivity
import com.gernalix.personalhub.contracts.database.HubEntityAdapter
import com.gernalix.personalhub.contracts.database.HubEntityLifecycle
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.contracts.database.HubEntitySummary
import com.gernalix.personalhub.contracts.database.HubOpenTarget
import com.gernalix.personalhub.core.database.PersonalHubDatabase

class SinceWhenCounterHubAdapter(context: Context) : HubEntityAdapter {
    private val appContext = context.applicationContext
    private val identities = CanonicalIdentityCapsule(context)
    private fun local(id: String) = identities.localKey("since_when/counter", id)?.toLongOrNull()
    private val dao = PersonalHubDatabase.get(appContext).sinceWhenCounterDao()
    override val moduleId: String = "since_when"
    override val entityKind: String = "counter"
    override val capabilities: Set<String> = setOf("searchable", "timestamped", "contextual")

    override suspend fun exists(canonicalId: String): Boolean {
        val id = local(canonicalId) ?: return false
        return dao.get(id) != null
    }
    override suspend fun lifecycle(canonicalId: String): String = if (exists(canonicalId)) HubEntityLifecycle.ACTIVE else HubEntityLifecycle.DELETED

    override suspend fun summaries(canonicalIds: Set<String>): Map<String, HubEntitySummary> {
        return canonicalIds.mapNotNull { rawId ->
            val id = local(rawId) ?: return@mapNotNull null
            dao.get(id)?.let { row ->
                rawId to HubEntitySummary(
                    HubEntityRef(moduleId, entityKind, rawId),
                    row.title,
                    row.description.takeIf(String::isNotBlank),
                    attributes = mapOf("initial_timestamp" to row.initialTimestamp.toString()),
                )
            }
        }.toMap()
    }

    override suspend fun search(query: String, limit: Int): List<HubEntitySummary> {
        val needle = query.trim().lowercase()
        return dao.all().asSequence()
            .filter { needle.isEmpty() || it.title.lowercase().contains(needle) || it.description.lowercase().contains(needle) }
            .take(limit)
            .map { row -> HubEntitySummary(HubEntityRef(moduleId, entityKind, row.canonicalId), row.title, row.description.takeIf(String::isNotBlank)) }
            .toList()
    }

    override suspend fun openTarget(canonicalId: String): HubOpenTarget? {
        val id = local(canonicalId) ?: return null
        if (dao.get(id) == null) return null
        return HubOpenTarget("personalhub://sincewhen/v1/counter/${android.net.Uri.encode(canonicalId)}", SinceWhenActivity::class.java.name)
    }
}
