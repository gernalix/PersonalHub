package com.gernalix.personalhub

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gernalix.luoghi.data.CheckInAttemptCandidateEntity
import com.gernalix.luoghi.data.PlaceGeofenceConfigEntity
import com.gernalix.luoghi.data.PlaceRepository
import com.gernalix.luoghi.capsules.routedistance.GoogleRoutesClient
import com.gernalix.luoghi.capsules.routedistance.GoogleRouteDistance
import com.gernalix.luoghi.capsules.routedistance.RouteCoordinates
import com.gernalix.luoghi.capsules.routedistance.RouteDistanceRepository
import com.gernalix.luoghi.capsules.geofence.PlaceGeofenceReceiver
import com.gernalix.luoghi.capsules.geofence.PlaceGeofenceTransition
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceCapsule
import com.gernalix.personalhub.core.database.capsules.soldi.TransactionDraft
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlacePersistenceDeviceTest {
    @TableProbe("place_geofence_transition_log")
    @Test fun geofenceTransitionPersistsThroughReceiver() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "com.gernalix.personalhub.qa")
        val owner = PersonalHubDatabase.get(context)
        val places = PlaceRepository(context)
        val marker = "QA914263Geofence${UUID.randomUUID()}"
        val placeId = places.savePlace(null, marker, null, 55.0, 12.0, 75.0, null, marker)
        try {
            places.saveGeofenceConfig(PlaceGeofenceConfigEntity(placeUuid = placeId, enabled = true))
            val at = System.currentTimeMillis()
            val first = PlaceGeofenceReceiver.handleTransition(context, placeId, PlaceGeofenceTransition.ENTER, at)
            assertEquals(true, first is com.gernalix.luoghi.capsules.geofence.PlaceGeofenceResult.Success)
            val second = PlaceGeofenceReceiver.handleTransition(context, placeId, PlaceGeofenceTransition.ENTER, at)
            assertEquals(true, second is com.gernalix.luoghi.capsules.geofence.PlaceGeofenceResult.DuplicateTransition)
            owner.openHelper.readableDatabase.query("SELECT count(*) FROM place_geofence_transition_log WHERE place_uuid=?", arrayOf(placeId)).use {
                it.moveToFirst(); assertEquals(1L, it.getLong(0))
            }
        } finally {
            owner.openHelper.writableDatabase.execSQL("DELETE FROM place_geofence_transition_log WHERE place_uuid=?", arrayOf(placeId))
            owner.openHelper.writableDatabase.execSQL("DELETE FROM place_geofence_configs WHERE place_uuid=?", arrayOf(placeId))
            places.deletePlace(placeId)
            context.getSystemService(android.app.NotificationManager::class.java)?.cancelAll()
        }
    }

    @TableProbe("route_distance_cache")
    @Test fun routeCachePersistsThroughDistanceRepository() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "com.gernalix.personalhub.qa")
        val name = "qa914263-route-${UUID.randomUUID()}.db"
        val owner = PersonalHubDatabase.openTemporary(context, name)
        try {
            val places = PlaceRepository(context, owner)
            val originId = places.savePlace(null, "QA914263Origin", null, 55.0, 12.0, 75.0, null, "QA914263")
            val destinationId = places.savePlace(null, "QA914263Destination", null, 55.1, 12.1, 75.0, null, "QA914263")
            val origin = requireNotNull(owner.placeDao().getPlace(originId))
            val destination = requireNotNull(owner.placeDao().getPlace(destinationId))
            var providerCalls = 0
            val client = object : GoogleRoutesClient {
                override val isConfigured = true
                override suspend fun bicycleRoute(origin: RouteCoordinates, destination: RouteCoordinates): GoogleRouteDistance {
                    providerCalls++
                    return GoogleRouteDistance(12_345, 1_234)
                }
            }
            val repo = RouteDistanceRepository(owner.placeDao(), client)
            assertEquals(12_345L, repo.getDistance(origin, destination).distanceMeters)
            assertEquals(12_345L, repo.getDistance(origin, destination).distanceMeters)
            assertEquals(1, providerCalls)
            owner.openHelper.readableDatabase.query(
                "SELECT distance_meters FROM route_distance_cache WHERE origin_place_id=? AND destination_place_id=?",
                arrayOf(originId, destinationId),
            ).use { assertEquals(true, it.moveToFirst()); assertEquals(12_345L, it.getLong(0)) }
        } finally {
            owner.close()
            context.deleteDatabase(name)
        }
    }

    @TableProbe("place_events", "global_stats_state", "history_audit_log", "history_actions")
    @Test fun placeEventAndHistoryEditPersistThroughRepository() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "com.gernalix.personalhub.qa")
        val owner = PersonalHubDatabase.get(context)
        val places = PlaceRepository(context)
        val marker = "QA914263History${UUID.randomUUID()}"
        val placeId = places.savePlace(null, marker, null, null, null, 75.0, null, "QA914263")
        var eventId = 0L
        var eventUuid: String? = null
        fun count(table: String, key: String, value: Any) = owner.openHelper.readableDatabase.query(
            "SELECT count(*) FROM $table WHERE $key=?", arrayOf(value),
        ).use { it.moveToFirst(); it.getLong(0) }
        try {
            val at = System.currentTimeMillis() - 20_000
            eventId = places.recordPlaceEvent(placeId, "CHECK_IN", null, marker, marker, at)
            val event = requireNotNull(owner.placeDao().getEvent(eventId))
            eventUuid = event.eventUuid
            assertEquals(marker, event.notes)
            assertEquals(1L, count("history_audit_log", "entity_id", event.eventUuid))
            val edited = places.editHistoryEvent(eventId, at + 1_000, "${marker}Edited", source = marker)
            assertEquals(true, edited is com.gernalix.luoghi.capsules.checkin.HistoryMutationResult.Success)
            assertEquals("${marker}Edited", owner.placeDao().getEvent(eventId)?.notes)
            assertEquals(1L, count("history_actions", "entity_id", event.eventUuid))
            // This singleton is recalculated by the same event writer.
            assertEquals(1L, owner.openHelper.readableDatabase.query("SELECT count(*) FROM global_stats_state").use {
                it.moveToFirst(); it.getLong(0)
            })
        } finally {
            eventUuid?.let { owner.placeDao().deleteEventByUuid(it) }
            owner.placeDao().recalculateStatsBaselines()
            owner.openHelper.writableDatabase.execSQL("DELETE FROM history_actions WHERE source=?", arrayOf(marker))
            owner.openHelper.writableDatabase.execSQL("DELETE FROM history_audit_log WHERE source=?", arrayOf(marker))
            places.deletePlace(placeId)
            assertEquals(0L, count("place_events", "id", eventId))
            assertEquals(0L, count("places", "uuid", placeId))
        }
    }

    @TableProbe("places", "place_aliases", "place_links", "place_tags", "place_tag_cross_ref", "check_in_attempts", "check_in_attempt_candidates", "place_geofence_configs", "finance_stores")
    @Test fun placeRelationsPersistThroughRepositories() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "com.gernalix.personalhub.qa")
        val owner = PersonalHubDatabase.get(context)
        val places = PlaceRepository(context)
        val finance = FinanceCapsule(owner)
        val marker = "QA914263Place${UUID.randomUUID()}"
        var placeId: String? = null
        var aliasId = 0L
        var linkId = 0L
        var transactionId = 0L
        var attemptId: String? = null
        fun count(table: String, key: String, value: Any) = owner.openHelper.readableDatabase.query(
            "SELECT count(*) FROM $table WHERE $key=?", arrayOf(value),
        ).use { it.moveToFirst(); it.getLong(0) }
        try {
            placeId = places.savePlace(null, marker, null, null, null, 75.0, null, "QA914263")
            assertEquals(1L, count("places", "uuid", placeId))
            places.savePlace(placeId, "${marker}Edited", null, null, null, 75.0, null, "QA914263")
            assertEquals(1L, count("places", "nickname", "${marker}Edited"))
            aliasId = places.createAlias(placeId, marker, "QA914263")
            assertEquals(1L, count("place_aliases", "id", aliasId))
            places.updateAlias(aliasId, placeId, "${marker}Edited", "QA914263")
            assertEquals(1L, count("place_aliases", "alias", "${marker}Edited"))
            linkId = places.createLink(placeId, "qa914263", "record", marker)
            assertEquals(1L, count("place_links", "id", linkId))
            places.updateLink(linkId, placeId, "qa914263", "record", "${marker}Edited")
            assertEquals(1L, count("place_links", "owner_id", "${marker}Edited"))
            places.setPlaceTags(placeId, listOf(marker))
            assertEquals(1, places.tagsForPlace(placeId).size)
            assertEquals(1L, count("place_tag_cross_ref", "place_uuid", placeId))
            places.setPlaceTags(placeId, listOf("${marker}Edited"))
            assertEquals("${marker}Edited", places.tagsForPlace(placeId).single().name)
            val attempt = places.beginCheckInAttempt(marker)
            attemptId = attempt.id
            places.replaceCheckInAttemptCandidates(attempt.id, listOf(CheckInAttemptCandidateEntity(
                attemptId = attempt.id, placeId = placeId, distanceM = 5.0,
                thresholdM = 75.0, rank = 1, result = "MATCH",
            )))
            assertEquals(1, places.checkInAttemptCandidates(attempt.id).size)
            places.finishCheckInAttempt(attempt.id, "MATCHED", "FINISHED", matchedPlaceId = placeId)
            assertEquals(1L, count("check_in_attempts", "id", attempt.id))
            val configId = places.saveGeofenceConfig(PlaceGeofenceConfigEntity(placeUuid = placeId, enabled = true))
            assertEquals(1L, count("place_geofence_configs", "id", configId))
            places.saveGeofenceConfig(PlaceGeofenceConfigEntity(placeUuid = placeId, enabled = false))
            assertEquals(1L, count("place_geofence_configs", "place_uuid", placeId))
            transactionId = finance.saveTransaction(TransactionDraft(
                title = marker, amount = "-1", chain = marker, placeId = placeId,
            ))
            assertEquals(1L, count("finance_stores", "placeId", placeId))
        } finally {
            if (transactionId != 0L) finance.deleteTransaction(transactionId)
            if (linkId != 0L) places.deleteLink(linkId)
            if (aliasId != 0L) places.deleteAlias(aliasId)
            placeId?.let { places.setPlaceTags(it, emptyList()) }
            attemptId?.let { owner.openHelper.writableDatabase.execSQL("DELETE FROM check_in_attempts WHERE id=?", arrayOf(it)) }
            placeId?.let {
                owner.openHelper.writableDatabase.execSQL("DELETE FROM finance_stores WHERE placeId=?", arrayOf(it))
                owner.openHelper.writableDatabase.execSQL("DELETE FROM place_geofence_configs WHERE place_uuid=?", arrayOf(it))
                places.deletePlace(it)
                assertEquals(0L, count("places", "uuid", it))
            }
            owner.openHelper.writableDatabase.execSQL("DELETE FROM place_tags WHERE name IN (?,?)", arrayOf(marker, "${marker}Edited"))
            owner.openHelper.writableDatabase.execSQL("DELETE FROM finance_chains WHERE name=?", arrayOf(marker))
            owner.openHelper.writableDatabase.execSQL("DELETE FROM finance_titles WHERE name=?", arrayOf(marker))
        }
    }
}
