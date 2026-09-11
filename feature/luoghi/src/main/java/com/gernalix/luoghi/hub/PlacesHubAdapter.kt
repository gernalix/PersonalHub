package com.gernalix.luoghi.hub

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import com.gernalix.luoghi.data.LuoghiDatabase
import com.gernalix.luoghi.data.PlaceEntity
import com.gernalix.luoghi.data.PlaceRepository
import com.gernalix.personalhub.contracts.database.*
import com.gernalix.personalhub.core.hubcontext.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    override suspend fun queryTemporal(query: HubTemporalQuery): HubTemporalPage = withContext(Dispatchers.IO) {
        val cursor = decodeHubTemporalCursor(query.cursor)
        val args = mutableListOf<Any?>(query.fromMs, query.toMs, query.fromMs, query.fromMs, query.toMs, query.fromMs)
        val cursorClause = if (cursor != null) {
            args += cursor.sortMs
            args += cursor.sortMs
            args += cursor.stableId
            "AND (v.start_ms < ? OR (v.start_ms = ? AND v.stable_id < ?))"
        } else ""
        args += query.limit + 1
        val sql = """
            WITH candidate_sessions AS (
                SELECT session_uuid, place_id
                FROM place_events
                WHERE session_uuid != '' AND event_type = 'CHECK_IN' AND timestamp >= ? AND timestamp < ?
                UNION
                SELECT session_uuid, place_id
                FROM place_events
                WHERE session_uuid != '' AND event_type = 'CHECK_OUT' AND timestamp > ?
                UNION
                SELECT ci.session_uuid, ci.place_id
                FROM place_events ci
                WHERE ci.session_uuid != '' AND ci.event_type = 'CHECK_IN' AND ci.timestamp < ?
                  AND NOT EXISTS (
                      SELECT 1 FROM place_events co
                      WHERE co.session_uuid = ci.session_uuid
                        AND co.place_id = ci.place_id
                        AND co.event_type = 'CHECK_OUT'
                  )
            ),
            visits AS (
                SELECT cs.session_uuid AS stable_id,
                       cs.place_id AS place_id,
                       MIN(CASE WHEN e.event_type = 'CHECK_IN' THEN e.timestamp END) AS start_ms,
                       MIN(CASE WHEN e.event_type = 'CHECK_OUT' THEN e.timestamp END) AS end_ms
                FROM candidate_sessions cs
                JOIN place_events e ON e.session_uuid = cs.session_uuid AND e.place_id = cs.place_id
                GROUP BY cs.session_uuid, cs.place_id
            )
            SELECT v.stable_id, v.place_id,
                   COALESCE(NULLIF(p.nickname, ''), p.address, '') AS place_name,
                   v.start_ms, v.end_ms
            FROM visits v
            JOIN places p ON p.uuid = v.place_id
            WHERE v.start_ms IS NOT NULL
              AND v.start_ms < ?
              AND (v.end_ms IS NULL OR v.end_ms > ?)
              $cursorClause
            ORDER BY v.start_ms DESC, v.stable_id DESC
            LIMIT ?
        """.trimIndent()
        val rows = database.openHelper.readableDatabase.query(SimpleSQLiteQuery(sql, args.toTypedArray())).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val stableId = c.getString(0)
                    val placeId = c.getString(1)
                    val rawName = c.getString(2).orEmpty()
                    val startMs = c.getLong(3)
                    val endMs = if (c.isNull(4)) null else c.getLong(4)
                    add(
                        HubTemporalRecord(
                            moduleId,
                            "visit",
                            stableId,
                            HubTemporalKind.INTERVAL,
                            startMs,
                            endMs,
                            rawName.ifBlank { context.getString(com.gernalix.luoghi.R.string.unnamed_place) },
                            entityRef = HubEntityRef(moduleId, entityKind, placeId),
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
