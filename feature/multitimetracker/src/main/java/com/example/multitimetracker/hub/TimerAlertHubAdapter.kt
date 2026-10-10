package com.example.multitimetracker.hub

import android.content.Context
import com.example.multitimetracker.persistence.SnapshotStore
import com.gernalix.personalhub.contracts.database.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read-only binding for Timer's existing snapshot-backed alert rules. */
class TimerAlertHubAdapter(private val context: Context) : HubEntityAdapter {
    override val moduleId = "timer"
    override val entityKind = "alert"
    private val identities = com.gernalix.personalhub.core.database.capsules.identity.CanonicalIdentityCapsule(context)
    private fun canonical(id: Long) = identities.canonicalId("snapshot.timeFenceRules", id)
    override val capabilities = setOf("alert", "contextual")
    private suspend fun rules() = withContext(Dispatchers.IO) {
        SnapshotStore.load(context.applicationContext)?.timeFenceRules.orEmpty().filterNot { it.isDeleted }
    }
    private suspend fun rule(id: String) = rules().firstOrNull { canonical(it.id) == id }
    override suspend fun exists(canonicalId: String) = rule(canonicalId) != null
    override suspend fun lifecycle(canonicalId: String) =
        if (exists(canonicalId)) HubEntityLifecycle.ACTIVE else HubEntityLifecycle.DELETED
    override suspend fun summaries(canonicalIds: Set<String>) = rules().filter { canonical(it.id) in canonicalIds }
        .associate { canonical(it.id) to HubEntitySummary(HubEntityRef(moduleId, entityKind, canonical(it.id)), it.message) }
    override suspend fun search(query: String, limit: Int) = rules().asSequence()
        .filter { it.message.contains(query.trim(), ignoreCase = true) }.take(limit)
        .map { HubEntitySummary(HubEntityRef(moduleId, entityKind, canonical(it.id)), it.message) }.toList()
    override suspend fun openTarget(canonicalId: String): HubOpenTarget? =
        if (exists(canonicalId)) HubOpenTarget(HubDeepLinkContract.moduleUri("timer").toString(),
            "com.example.multitimetracker.MainActivity") else null
}
