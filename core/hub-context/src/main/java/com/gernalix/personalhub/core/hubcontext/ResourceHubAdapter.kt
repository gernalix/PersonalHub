package com.gernalix.personalhub.core.hubcontext

import android.content.Context
import android.net.Uri
import com.gernalix.personalhub.contracts.database.*
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import java.time.Instant
import java.util.UUID

class ResourceHubAdapter(private val context: Context) : HubEntityAdapter {
    override val moduleId = "hub"
    override val entityKind = "resource"
    override val capabilities = setOf("resource", "external_information")
    private val dao get() = PersonalHubDatabase.get(context).hubResourceDao()

    override suspend fun exists(canonicalId: String) = dao.resource(canonicalId) != null
    override suspend fun lifecycle(canonicalId: String) = if (exists(canonicalId)) HubEntityLifecycle.ACTIVE else HubEntityLifecycle.DELETED
    override suspend fun summaries(canonicalIds: Set<String>) = if (canonicalIds.isEmpty()) emptyMap() else
        dao.resources(canonicalIds.toList()).associate { it.id to it.summary() }
    override suspend fun search(query: String, limit: Int) = dao.search(query.trim(), limit.coerceIn(1, 100)).map { it.summary() }
    override suspend fun openTarget(canonicalId: String): HubOpenTarget? {
        val resource = dao.resource(canonicalId) ?: return null
        return if (resource.kind == HubResourceKinds.NOTE) null else HubOpenTarget(resource.value)
    }
    override suspend fun create(request: HubCreateRequest): HubEntitySummary? {
        val kind = request.extras["kind"] ?: HubResourceKinds.NOTE
        val value = request.extras["value"]?.trim().orEmpty()
        if (value.isBlank() || kind !in setOf(HubResourceKinds.WEB_URL, HubResourceKinds.ANDROID_URI, HubResourceKinds.NOTE, HubResourceKinds.SAF_DOCUMENT)) return null
        val uri = if (kind == HubResourceKinds.NOTE) null else runCatching { Uri.parse(value) }.getOrNull()
        if (kind == HubResourceKinds.WEB_URL && uri?.scheme !in setOf("http", "https")) return null
        if (kind == HubResourceKinds.ANDROID_URI && uri?.scheme.isNullOrBlank()) return null
        if (kind == HubResourceKinds.SAF_DOCUMENT && uri?.scheme != "content") return null
        val now = Instant.now().toString()
        val resource = HubResource(UUID.randomUUID().toString(), kind, request.suggestedLabel?.trim()?.takeIf(String::isNotBlank), value, request.extras["persisted_permission"] == "true", now, now)
        dao.insert(resource)
        return resource.summary()
    }

    suspend fun delete(canonicalId: String) {
        dao.delete(canonicalId)
        HubContextRuntime.canonicalDeletedIfInitialized(HubEntityRef(moduleId, entityKind, canonicalId))
    }

    private fun HubResource.summary() = HubEntitySummary(
        HubEntityRef(moduleId, entityKind, id),
        title ?: value.lineSequence().first().take(80),
        when (kind) { HubResourceKinds.NOTE -> context.getString(R.string.hub_resource_note); else -> value },
        attributes = mapOf("resource_kind" to kind, "value" to value, "persisted_permission" to persistedPermission.toString()),
    )
}
