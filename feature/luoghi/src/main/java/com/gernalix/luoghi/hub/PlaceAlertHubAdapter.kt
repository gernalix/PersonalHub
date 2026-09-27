package com.gernalix.luoghi.hub

import android.content.Context
import com.gernalix.personalhub.contracts.database.*
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.alerts.AlertDomain

/** Binds canonical Places alerts to the shared Hub capability. */
class PlaceAlertHubAdapter(context: Context) : HubEntityAdapter {
    override val moduleId = "places"
    override val entityKind = "alert"
    override val capabilities = setOf("alert", "contextual")
    private val dao = PersonalHubDatabase.get(context.applicationContext).alertDao()

    private suspend fun live(id: String) = dao.getRule(id)?.takeIf { it.domain == AlertDomain.PLACE.moduleId && it.deletedAt == null }
    override suspend fun exists(canonicalId: String) = live(canonicalId) != null
    override suspend fun lifecycle(canonicalId: String) =
        if (exists(canonicalId)) HubEntityLifecycle.ACTIVE else HubEntityLifecycle.DELETED
    override suspend fun summaries(canonicalIds: Set<String>) = canonicalIds.mapNotNull { id ->
        live(id)?.let { id to HubEntitySummary(HubEntityRef(moduleId, entityKind, id), it.message) }
    }.toMap()
    override suspend fun search(query: String, limit: Int) =
        dao.listRules(AlertDomain.PLACE.moduleId).asSequence().filter { it.deletedAt == null && it.message.contains(query, true) }
            .take(limit).map { HubEntitySummary(HubEntityRef(moduleId, entityKind, it.id), it.message) }.toList()
    override suspend fun openTarget(canonicalId: String): HubOpenTarget? =
        if (exists(canonicalId)) HubOpenTarget(HubDeepLinkContract.moduleUri("places").toString(),
            "com.gernalix.luoghi.MainActivity") else null
}
