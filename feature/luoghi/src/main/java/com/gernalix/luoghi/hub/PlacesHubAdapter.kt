package com.gernalix.luoghi.hub

import android.content.Context
import com.gernalix.luoghi.data.LuoghiDatabase
import com.gernalix.luoghi.data.PlaceEntity
import com.gernalix.luoghi.data.PlaceRepository
import com.gernalix.personalhub.contracts.database.*
import com.gernalix.personalhub.core.hubcontext.*

class PlacesHubAdapter(private val context: Context) : HubEntityAdapter, HubTemporalProvider, HubPlaceSuggestionProvider {
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
    override suspend fun search(query: String, limit: Int) =
        dao.searchForHub(query.trim(), limit.coerceIn(1, 100)).map { it.summary() }
    override suspend fun openTarget(canonicalId: String) = HubOpenTarget("personalhub://module/places?placeId=${android.net.Uri.encode(canonicalId)}", "com.gernalix.luoghi.MainActivity")
    override suspend fun queryTemporal(query: HubTemporalQuery): HubTemporalPage {
        val offset = query.cursor?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val rows = dao.temporalVisits(query.fromMs, query.toMs, query.limit + 1, offset)
        return HubTemporalPage(rows.take(query.limit).map { row ->
            HubTemporalRecord(moduleId, "visit", row.stableId, HubTemporalKind.INTERVAL, row.startMs, row.endMs, row.placeName, entityRef = HubEntityRef(moduleId, entityKind, row.placeId))
        }, if (rows.size > query.limit) (offset + query.limit).toString() else null)
    }
    override suspend fun suggestPlaces(latitude: Double?, longitude: Double?, limit: Int): HubPlaceSuggestions {
        if (latitude == null || longitude == null) return HubPlaceSuggestions(null, search("", limit))
        val ranked = dao.placesWithCoordinates(200).mapNotNull { place ->
            val lat = place.lat ?: return@mapNotNull null
            val lon = place.lon ?: return@mapNotNull null
            place to distanceMeters(latitude, longitude, lat, lon)
        }.sortedWith(compareBy<Pair<PlaceEntity, Double>> { it.second }.thenBy { it.first.uuid })
        val inside = ranked.filter { (place, distance) -> distance <= (place.radiusM ?: 75.0) }
        val candidates = (if (inside.isNotEmpty()) inside else ranked).take(limit.coerceIn(1, 5)).map { it.first.summary() }
        return HubPlaceSuggestions(if (inside.size == 1) candidates.first() else null, candidates)
    }

    private fun distanceMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dLat = Math.toRadians(bLat - aLat)
        val dLon = Math.toRadians(bLon - aLon)
        val x = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) + kotlin.math.cos(Math.toRadians(aLat)) * kotlin.math.cos(Math.toRadians(bLat)) * kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        return 6_371_000.0 * 2 * kotlin.math.atan2(kotlin.math.sqrt(x), kotlin.math.sqrt(1 - x))
    }
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
