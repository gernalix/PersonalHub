package com.gernalix.personalhub.core.hubcontext

import android.content.Context
import com.gernalix.personalhub.contracts.database.*
import com.gernalix.personalhub.core.database.PersonalHubDatabase

/** Existing Hub contexts are first-class anchors for shared links. */
class HubContextHubAdapter(context: Context) : HubEntityAdapter {
    override val moduleId = "hub"
    override val entityKind = "context"
    override val capabilities = setOf("contextual", "context")
    private val dao = PersonalHubDatabase.get(context.applicationContext).hubContextDao()
    override suspend fun exists(canonicalId: String) = dao.context(canonicalId) != null
    override suspend fun lifecycle(canonicalId: String) =
        if (exists(canonicalId)) HubEntityLifecycle.ACTIVE else HubEntityLifecycle.DELETED
    override suspend fun summaries(canonicalIds: Set<String>) = canonicalIds.mapNotNull { id ->
        dao.context(id)?.let { row ->
            id to HubEntitySummary(HubEntityRef(moduleId, entityKind, id), row.title ?: "Context")
        }
    }.toMap()
    override suspend fun search(query: String, limit: Int) = dao.titledContexts()
        .asSequence().filter { it.title.orEmpty().contains(query.trim(), ignoreCase = true) }
        .take(limit).map { HubEntitySummary(HubEntityRef(moduleId, entityKind, it.id),
            it.title ?: "Context") }.toList()
    override suspend fun openTarget(canonicalId: String): HubOpenTarget? =
        if (exists(canonicalId)) HubOpenTarget(HubDeepLinkContract.contextUri(canonicalId).toString()) else null
}
