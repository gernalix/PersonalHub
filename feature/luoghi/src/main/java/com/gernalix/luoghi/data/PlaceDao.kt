package com.gernalix.luoghi.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaceDao {
    @Query("SELECT * FROM places WHERE archived = 0 ORDER BY updated_at DESC, nickname COLLATE NOCASE ASC")
    fun observePlaces(): Flow<List<PlaceEntity>>

    @Query("SELECT * FROM places WHERE archived = 0 ORDER BY updated_at DESC, nickname COLLATE NOCASE ASC")
    suspend fun listPlaces(): List<PlaceEntity>

    @Query("SELECT * FROM places WHERE archived = 0 ORDER BY updated_at DESC, nickname COLLATE NOCASE ASC")
    fun listPlacesBlocking(): List<PlaceEntity>

    @Query("SELECT * FROM places ORDER BY archived ASC, updated_at DESC, nickname COLLATE NOCASE ASC")
    fun listAllPlacesBlocking(): List<PlaceEntity>

    @Query("SELECT * FROM places ORDER BY uuid ASC")
    suspend fun listAllPlacesForBackup(): List<PlaceEntity>

    @Query("SELECT * FROM places WHERE uuid = :uuid LIMIT 1")
    suspend fun getPlace(uuid: String): PlaceEntity?

    @Query("SELECT * FROM places WHERE uuid = :uuid LIMIT 1")
    fun getPlaceBlocking(uuid: String): PlaceEntity?

    @Query("UPDATE places SET first_check_in_at_place = :timestamp WHERE uuid = :uuid AND first_check_in_at_place IS NULL")
    suspend fun setPlaceFirstCheckInAtIfMissing(uuid: String, timestamp: Long): Int

    @Query("UPDATE places SET first_check_in_at_place = :timestamp WHERE uuid = :uuid AND first_check_in_at_place IS NULL")
    fun setPlaceFirstCheckInAtIfMissingBlocking(uuid: String, timestamp: Long): Int

    @Query(
        """
        SELECT * FROM places
        WHERE archived = 0
          AND lat IS NOT NULL AND lon IS NOT NULL
          AND ((lat - :lat) * (lat - :lat) + (lon - :lon) * (lon - :lon)) <= (:degreeRadius * :degreeRadius)
        ORDER BY updated_at DESC
        """,
    )
    fun nearbyBlocking(lat: Double, lon: Double, degreeRadius: Double): List<PlaceEntity>

    @Query("SELECT * FROM places WHERE uuid IN (:uuids)")
    fun placesByUuidsBlocking(uuids: List<String>): List<PlaceEntity>

    @Query(
        """
        SELECT * FROM places
        WHERE archived = 0
          AND lat IS NOT NULL AND lon IS NOT NULL
          AND lat BETWEEN :minLat AND :maxLat
          AND lon BETWEEN :minLon AND :maxLon
        ORDER BY nickname COLLATE NOCASE ASC, updated_at DESC
        LIMIT :limit
        """,
    )
    fun placesInBoundingBoxBlocking(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
        limit: Int,
    ): List<PlaceEntity>

    @Query(
        """
        SELECT * FROM places
        WHERE archived = 0
          AND lat IS NOT NULL AND lon IS NOT NULL
        ORDER BY nickname COLLATE NOCASE ASC, updated_at DESC
        LIMIT :limit
        """,
    )
    fun placesWithCoordinatesBlocking(limit: Int): List<PlaceEntity>

    @Upsert
    suspend fun upsertPlace(place: PlaceEntity)

    @Upsert
    fun upsertPlaceBlocking(place: PlaceEntity)

    @Delete
    suspend fun deletePlace(place: PlaceEntity)

    @Query("DELETE FROM places WHERE uuid = :uuid")
    suspend fun deletePlaceByUuid(uuid: String)

    @Query("UPDATE places SET archived = :archived, updated_at = :updatedAt WHERE uuid = :uuid")
    suspend fun setPlaceArchived(uuid: String, archived: Boolean, updatedAt: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlias(alias: PlaceAliasEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAliasBlocking(alias: PlaceAliasEntity): Long

    @Query("SELECT * FROM place_aliases ORDER BY updated_at DESC, alias COLLATE NOCASE")
    fun listAliasesBlocking(): List<PlaceAliasEntity>

    @Query("SELECT * FROM place_aliases ORDER BY id ASC")
    suspend fun listAliasesForBackup(): List<PlaceAliasEntity>

    @Query("SELECT * FROM place_aliases WHERE id = :id LIMIT 1")
    fun getAliasBlocking(id: Long): PlaceAliasEntity?

    @Query("SELECT * FROM place_aliases WHERE place_uuid = :placeUuid ORDER BY alias COLLATE NOCASE")
    suspend fun aliasesForPlace(placeUuid: String): List<PlaceAliasEntity>

    @Query("DELETE FROM place_aliases WHERE id = :id")
    fun deleteAliasByIdBlocking(id: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLink(link: PlaceLinkEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertLinkBlocking(link: PlaceLinkEntity): Long

    @Query("SELECT * FROM place_links ORDER BY updated_at DESC")
    fun listLinksBlocking(): List<PlaceLinkEntity>

    @Query("SELECT * FROM place_links ORDER BY id ASC")
    suspend fun listLinksForBackup(): List<PlaceLinkEntity>

    @Query("SELECT * FROM place_links WHERE id = :id LIMIT 1")
    fun getLinkBlocking(id: Long): PlaceLinkEntity?

    @Query("SELECT * FROM place_links WHERE place_uuid = :placeUuid ORDER BY updated_at DESC")
    suspend fun linksForPlace(placeUuid: String): List<PlaceLinkEntity>

    @Query("DELETE FROM place_links WHERE id = :id")
    fun deleteLinkByIdBlocking(id: Long): Int

    @Query("SELECT COUNT(*) FROM places")
    fun countPlacesBlocking(): Int

    @Query("SELECT COUNT(*) FROM place_aliases")
    fun countAliasesBlocking(): Int

    @Query("SELECT COUNT(*) FROM place_links")
    fun countLinksBlocking(): Int

    @Query(
        """
        SELECT * FROM route_distance_cache
        WHERE origin_place_id = :originPlaceId
          AND destination_place_id = :destinationPlaceId
          AND travel_mode = :travelMode
          AND provider = :provider
        LIMIT 1
        """
    )
    suspend fun getRouteDistanceCache(
        originPlaceId: String,
        destinationPlaceId: String,
        travelMode: String,
        provider: String,
    ): RouteDistanceCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRouteDistanceCache(cache: RouteDistanceCacheEntity): Long

    @Query("SELECT COUNT(*) FROM route_distance_cache")
    fun countRouteDistanceCacheBlocking(): Int

    @Query("SELECT * FROM route_distance_cache ORDER BY id ASC")
    suspend fun listRouteDistanceCacheForBackup(): List<RouteDistanceCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: PlaceEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertEventBlocking(event: PlaceEventEntity): Long

    @Update
    suspend fun updateEvent(event: PlaceEventEntity): Int

    @Transaction
    suspend fun insertEventAndInitializeStats(event: PlaceEventEntity): Long {
        val id = insertEvent(event)
        recalculateStatsBaselines()
        return id
    }

    @Transaction
    fun insertEventAndInitializeStatsBlocking(event: PlaceEventEntity): Long {
        val id = insertEventBlocking(event)
        recalculateStatsBaselinesBlocking()
        return id
    }

    @Query("SELECT * FROM place_events ORDER BY timestamp DESC, id DESC")
    fun observeEvents(): Flow<List<PlaceEventEntity>>

    @Query("SELECT * FROM place_events ORDER BY timestamp DESC, id DESC")
    suspend fun listEvents(): List<PlaceEventEntity>

    @Query("SELECT * FROM place_events ORDER BY timestamp DESC, id DESC")
    fun listEventsBlocking(): List<PlaceEventEntity>

    @Query("SELECT * FROM place_events ORDER BY id ASC")
    suspend fun listEventsForBackup(): List<PlaceEventEntity>

    @Query("SELECT * FROM place_events WHERE id = :id LIMIT 1")
    fun getEventBlocking(id: Long): PlaceEventEntity?

    @Query("SELECT * FROM place_events WHERE id = :id LIMIT 1")
    suspend fun getEvent(id: Long): PlaceEventEntity?

    @Query("SELECT * FROM place_events WHERE event_uuid = :eventUuid LIMIT 1")
    suspend fun getEventByUuid(eventUuid: String): PlaceEventEntity?

    @Query("SELECT * FROM place_events WHERE session_uuid = :sessionUuid ORDER BY timestamp ASC, id ASC")
    suspend fun eventsForSession(sessionUuid: String): List<PlaceEventEntity>

    @Query("DELETE FROM place_events WHERE event_uuid = :eventUuid")
    suspend fun deleteEventByUuid(eventUuid: String): Int

    @Query("DELETE FROM place_events WHERE session_uuid = :sessionUuid")
    suspend fun deleteEventsBySessionUuid(sessionUuid: String): Int

    @Query(
        """
        SELECT * FROM place_events AS checkins
        WHERE checkins.event_type = 'CHECK_IN'
          AND NOT EXISTS (
            SELECT 1 FROM place_events AS checkouts
            WHERE checkouts.place_id = checkins.place_id
              AND checkouts.event_type = 'CHECK_OUT'
              AND checkouts.timestamp > checkins.timestamp
          )
        ORDER BY checkins.timestamp DESC, checkins.id DESC
        LIMIT 1
        """
    )
    suspend fun getActiveVisitEvent(): PlaceEventEntity?

    @Query("SELECT COUNT(*) FROM place_events")
    fun countEventsBlocking(): Int

    @Query("SELECT COUNT(*) FROM history_audit_log")
    fun countHistoryAuditLogBlocking(): Int

    @Query("SELECT COUNT(*) FROM history_actions")
    fun countHistoryActionsBlocking(): Int

    @Query("SELECT * FROM global_stats_state WHERE id = 1 LIMIT 1")
    fun observeGlobalStatsState(): Flow<GlobalStatsStateEntity?>

    @Query("SELECT * FROM global_stats_state WHERE id = 1 LIMIT 1")
    fun getGlobalStatsStateBlocking(): GlobalStatsStateEntity?

    @Query("SELECT * FROM global_stats_state ORDER BY id ASC")
    suspend fun listGlobalStatsForBackup(): List<GlobalStatsStateEntity>

    @Query("SELECT * FROM history_audit_log ORDER BY id ASC")
    suspend fun listHistoryAuditLogForBackup(): List<HistoryAuditLogEntity>

    @Query("SELECT * FROM history_actions ORDER BY id ASC")
    suspend fun listHistoryActionsForBackup(): List<HistoryActionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun restorePlaces(rows: List<PlaceEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun restoreAliases(rows: List<PlaceAliasEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun restoreLinks(rows: List<PlaceLinkEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun restoreEvents(rows: List<PlaceEventEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun restoreGlobalStats(rows: List<GlobalStatsStateEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun restoreRouteDistanceCache(rows: List<RouteDistanceCacheEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun restoreHistoryAuditLog(rows: List<HistoryAuditLogEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun restoreHistoryActions(rows: List<HistoryActionEntity>)

    @Query("DELETE FROM route_distance_cache")
    suspend fun clearRouteDistanceCacheForRestore()

    @Query("DELETE FROM place_aliases")
    suspend fun clearAliasesForRestore()

    @Query("DELETE FROM place_links")
    suspend fun clearLinksForRestore()

    @Query("DELETE FROM place_events")
    suspend fun clearEventsForRestore()

    @Query("DELETE FROM global_stats_state")
    suspend fun clearGlobalStatsForRestore()

    @Query("DELETE FROM history_audit_log")
    suspend fun clearHistoryAuditLogForRestore()

    @Query("DELETE FROM history_actions")
    suspend fun clearHistoryActionsForRestore()

    @Query("DELETE FROM places")
    suspend fun clearPlacesForRestore()

    @Transaction
    suspend fun readSnapshot(): LuoghiSnapshot = LuoghiSnapshot(
        places = listAllPlacesForBackup(),
        aliases = listAliasesForBackup(),
        links = listLinksForBackup(),
        events = listEventsForBackup(),
        globalStats = listGlobalStatsForBackup(),
        routeDistanceCache = listRouteDistanceCacheForBackup(),
        historyAuditLog = listHistoryAuditLogForBackup(),
        historyActions = listHistoryActionsForBackup(),
    )

    @Transaction
    suspend fun replaceSnapshot(snapshot: LuoghiSnapshot) {
        clearRouteDistanceCacheForRestore()
        clearAliasesForRestore()
        clearLinksForRestore()
        clearEventsForRestore()
        clearGlobalStatsForRestore()
        clearHistoryAuditLogForRestore()
        clearHistoryActionsForRestore()
        clearPlacesForRestore()

        restorePlaces(snapshot.places)
        restoreGlobalStats(snapshot.globalStats)
        restoreEvents(snapshot.events)
        restoreAliases(snapshot.aliases)
        restoreLinks(snapshot.links)
        restoreRouteDistanceCache(snapshot.routeDistanceCache)
        restoreHistoryAuditLog(snapshot.historyAuditLog)
        restoreHistoryActions(snapshot.historyActions)
    }

    @Query("INSERT OR IGNORE INTO global_stats_state(id, first_check_in_at_global) VALUES(1, :timestamp)")
    suspend fun insertGlobalFirstCheckInAtIfMissing(timestamp: Long)

    @Query("INSERT OR IGNORE INTO global_stats_state(id, first_check_in_at_global) VALUES(1, :timestamp)")
    fun insertGlobalFirstCheckInAtIfMissingBlocking(timestamp: Long)

    @Query(
        """
        UPDATE places
        SET first_check_in_at_place = (
            SELECT MIN(timestamp)
            FROM place_events
            WHERE place_events.place_id = places.uuid
              AND place_events.event_type = 'CHECK_IN'
        )
        """
    )
    suspend fun refreshPlaceFirstCheckInBaselines()

    @Query(
        """
        UPDATE places
        SET first_check_in_at_place = (
            SELECT MIN(timestamp)
            FROM place_events
            WHERE place_events.place_id = places.uuid
              AND place_events.event_type = 'CHECK_IN'
        )
        """
    )
    fun refreshPlaceFirstCheckInBaselinesBlocking()

    @Query("DELETE FROM global_stats_state")
    suspend fun clearGlobalStatsState()

    @Query("DELETE FROM global_stats_state")
    fun clearGlobalStatsStateBlocking()

    @Query(
        """
        INSERT INTO global_stats_state(id, first_check_in_at_global)
        SELECT 1, first_check_in_at_global
        FROM (
            SELECT MIN(timestamp) AS first_check_in_at_global
            FROM place_events
            WHERE event_type = 'CHECK_IN'
        )
        WHERE first_check_in_at_global IS NOT NULL
        """
    )
    suspend fun rebuildGlobalStatsState()

    @Query(
        """
        INSERT INTO global_stats_state(id, first_check_in_at_global)
        SELECT 1, first_check_in_at_global
        FROM (
            SELECT MIN(timestamp) AS first_check_in_at_global
            FROM place_events
            WHERE event_type = 'CHECK_IN'
        )
        WHERE first_check_in_at_global IS NOT NULL
        """
    )
    fun rebuildGlobalStatsStateBlocking()

    @Transaction
    suspend fun recalculateStatsBaselines() {
        refreshPlaceFirstCheckInBaselines()
        clearGlobalStatsState()
        rebuildGlobalStatsState()
    }

    @Transaction
    fun recalculateStatsBaselinesBlocking() {
        refreshPlaceFirstCheckInBaselinesBlocking()
        clearGlobalStatsStateBlocking()
        rebuildGlobalStatsStateBlocking()
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistoryAction(action: HistoryActionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAuditLog(entry: HistoryAuditLogEntity): Long

    @Query("SELECT * FROM history_actions WHERE status = 'APPLIED' ORDER BY created_at DESC, id DESC LIMIT 1")
    fun observeLatestUndoableAction(): Flow<HistoryActionEntity?>

    @Query("SELECT * FROM history_actions WHERE status = 'UNDONE' ORDER BY updated_at DESC, id DESC LIMIT 1")
    fun observeLatestRedoableAction(): Flow<HistoryActionEntity?>

    @Query("SELECT * FROM history_actions WHERE status = 'APPLIED' ORDER BY created_at DESC, id DESC LIMIT 1")
    suspend fun latestUndoableAction(): HistoryActionEntity?

    @Query("SELECT * FROM history_actions WHERE status = 'UNDONE' ORDER BY updated_at DESC, id DESC LIMIT 1")
    suspend fun latestRedoableAction(): HistoryActionEntity?

    @Query("UPDATE history_actions SET status = :status, updated_at = :updatedAt WHERE action_uuid = :actionUuid")
    suspend fun updateHistoryActionStatus(actionUuid: String, status: String, updatedAt: Long): Int
}
