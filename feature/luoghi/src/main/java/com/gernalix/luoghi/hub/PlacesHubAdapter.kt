package com.gernalix.luoghi.hub

import android.content.Context
import com.gernalix.luoghi.data.LuoghiDatabase
import com.gernalix.luoghi.data.PlaceEntity
import com.gernalix.luoghi.data.PlaceRepository
import com.gernalix.personalhub.contracts.database.*

class PlacesHubAdapter(private val context: Context) : HubEntityAdapter {
    override val moduleId = "places"
    override val entityKind = "place"
    override val capabilities = setOf("place", "location")
    private val database get() = LuoghiDatabase.get(context)
    private val dao get() = database.placeDao()

    override suspend fun exists(canonicalId: String) = dao.getPlace(canonicalId) != null
    override suspend fun lifecycle(canonicalId: String) = when (dao.getPlace(canonicalId)?.archived) {
        null -> HubEntityLifecycle.DELETED
        true -> HubEntityLifecycle.ARCHIVED
        false -> HubEntityLifecycle.ACTIVE
    }
    override suspend fun summaries(canonicalIds: Set<String>) =
        if (canonicalIds.isEmpty()) emptyMap() else dao.placesByUuids(canonicalIds.toList()).associate { it.uuid to it.summary() }
    override suspend fun search(query: String, limit: Int) = dao.listPlaces()
        .asSequence()
        .filter { query.isBlank() || it.nickname.contains(query, true) || it.address.orEmpty().contains(query, true) }
        .take(limit.coerceIn(1, 100)).map { it.summary() }.toList()
    override suspend fun openTarget(canonicalId: String) = HubOpenTarget("personalhub://module/places?placeId=${android.net.Uri.encode(canonicalId)}", "com.gernalix.luoghi.MainActivity")
    override suspend fun create(request: HubCreateRequest): HubEntitySummary? {
        val name = request.suggestedLabel?.trim().orEmpty()
        if (name.isBlank()) return null
        val id = PlaceRepository(context, database, dao).savePlace(null, name, null, null, null, 75.0, null, "HubContext")
        return requireNotNull(dao.getPlace(id)).summary()
    }

    private fun PlaceEntity.summary() = HubEntitySummary(
        HubEntityRef(moduleId, entityKind, uuid),
        nickname.ifBlank { address.orEmpty().ifBlank { context.getString(com.gernalix.luoghi.R.string.unnamed_place) } },
        address,
        if (archived) HubEntityLifecycle.ARCHIVED else HubEntityLifecycle.ACTIVE,
        mapOf("radius_m" to (radiusM ?: 75.0).toString()),
    )
}
