package com.gernalix.luoghi

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gernalix.luoghi.capsules.checkin.CheckInMatchDecision
import com.gernalix.luoghi.capsules.checkin.CheckInPolicy
import com.gernalix.luoghi.capsules.checkin.PlaceEventTypes
import com.gernalix.luoghi.capsules.checkin.WhereWasIQuery
import com.gernalix.luoghi.capsules.checkin.WhereWasIResult
import com.gernalix.luoghi.capsules.geofence.PlaceGeofenceAction
import com.gernalix.luoghi.capsules.geofence.PlaceGeofenceRegistrar
import com.gernalix.luoghi.capsules.geofence.PlaceGeofenceReceiver
import com.gernalix.luoghi.capsules.geofence.PlaceGeofenceResult
import com.gernalix.luoghi.capsules.geofence.PlaceGeofenceTransition
import com.gernalix.luoghi.capsules.location.LocationSample
import com.gernalix.luoghi.capsules.places.PlaceListLocation
import com.gernalix.luoghi.capsules.places.PlaceListUiMapper
import com.gernalix.luoghi.capsules.places.PlaceSortCriterion
import com.gernalix.luoghi.capsules.places.PlaceSortDirection
import com.gernalix.luoghi.capsules.places.PlaceSortState
import com.gernalix.luoghi.capsules.visits.VisitMapper
import com.gernalix.luoghi.data.LuoghiDatabase
import com.gernalix.luoghi.data.PlaceEntity
import com.gernalix.luoghi.data.PlaceEventEntity
import com.gernalix.luoghi.data.PlaceGeofenceConfigEntity
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlacesHistoryMapGeofencingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        PersonalHubDatabase.resetForTests()
        context.deleteDatabase(PersonalHubDatabase.DATABASE_NAME)
    }

    @After
    fun tearDown() {
        PersonalHubDatabase.resetForTests()
        context.deleteDatabase(PersonalHubDatabase.DATABASE_NAME)
    }

    @Test
    fun overlappingNearbyPlacesAreAlwaysAmbiguousEvenWhenOneIsMuchNearer() {
        val near = place("near", "Near", 0.0, 0.0, radiusM = 2_000.0)
        val far = place("far", "Far", 0.0, 0.015, radiusM = 2_000.0)
        val decision = CheckInPolicy.choosePlace(
            places = listOf(near, far),
            location = LocationSample(0.0, 0.0, accuracyM = 4.0),
        )
        assertTrue(decision is CheckInMatchDecision.Ambiguous)
        assertEquals(listOf("near", "far"), (decision as CheckInMatchDecision.Ambiguous).candidates.map { it.place.uuid })
    }

    @Test
    fun whereWasIHandlesInsideBoundariesBetweenAndMissingSides() {
        val home = place("home", "Home")
        val office = place("office", "Office")
        val visits = VisitMapper.map(
            events = listOf(
                event("home", PlaceEventTypes.CHECK_IN, 1_000L, "a"),
                event("home", PlaceEventTypes.CHECK_OUT, 2_000L, "a"),
                event("office", PlaceEventTypes.CHECK_IN, 4_000L, "b"),
                event("office", PlaceEventTypes.CHECK_OUT, 5_000L, "b"),
            ),
            places = listOf(home, office),
            nowMs = 6_000L,
        )

        assertTrue(WhereWasIQuery.at(visits, listOf(home, office), 1_500L) is WhereWasIResult.Inside)
        assertTrue(WhereWasIQuery.at(visits, listOf(home, office), 1_000L) is WhereWasIResult.Inside)
        assertTrue(WhereWasIQuery.at(visits, listOf(home, office), 2_000L) is WhereWasIResult.Inside)
        assertTrue(WhereWasIQuery.at(visits, listOf(home, office), 3_000L) is WhereWasIResult.Between)
        assertTrue(WhereWasIQuery.at(visits, listOf(home, office), 6_000L) is WhereWasIResult.OnlyPrevious)
        assertTrue(WhereWasIQuery.at(visits, listOf(home, office), 500L) is WhereWasIResult.OnlyNext)
        assertTrue(WhereWasIQuery.at(emptyList(), listOf(home, office), 3_000L) is WhereWasIResult.NoData)
    }

    @Test
    fun sortKeepsUnknownDistanceAndNeverVisitedAfterKnownValuesInBothDirections() {
        val near = place("near", "Near", 0.0, 0.0)
        val far = place("far", "Far", 0.0, 0.02)
        val unknown = place("unknown", "Unknown", lat = null, lon = null)
        val visits = VisitMapper.map(
            events = listOf(
                event("near", PlaceEventTypes.CHECK_IN, 1_000L, "a"),
                event("near", PlaceEventTypes.CHECK_OUT, 2_000L, "a"),
                event("far", PlaceEventTypes.CHECK_IN, 3_000L, "b"),
                event("far", PlaceEventTypes.CHECK_OUT, 7_000L, "b"),
            ),
            places = listOf(near, far, unknown),
            nowMs = 8_000L,
        )

        val byDistanceAsc = PlaceListUiMapper.map(
            places = listOf(unknown, far, near),
            stats = emptyList(),
            visits = visits,
            sort = PlaceSortState(PlaceSortCriterion.DISTANCE, PlaceSortDirection.ASC),
            currentLocation = PlaceListLocation(0.0, 0.0),
        )
        assertEquals(listOf("near", "far", "unknown"), byDistanceAsc.map { it.place.uuid })

        val byLastVisitAsc = PlaceListUiMapper.map(
            places = listOf(unknown, far, near),
            stats = emptyList(),
            visits = visits,
            sort = PlaceSortState(PlaceSortCriterion.LAST_VISIT, PlaceSortDirection.ASC),
        )
        assertEquals(listOf("near", "far", "unknown"), byLastVisitAsc.map { it.place.uuid })
    }

    @Test
    fun geofenceNotifyOnlyDoesNotMutateVisitsAndAutomaticEnterExitMutatesOnce() = runBlocking {
        val db = LuoghiDatabase.get(context)
        val dao = db.placeDao()
        dao.upsertPlace(place("home", "Home", 45.0, 9.0))
        dao.upsertGeofenceConfig(
            PlaceGeofenceConfigEntity(
                placeUuid = "home",
                enabled = true,
                enterEnabled = true,
                exitEnabled = true,
                enterAction = PlaceGeofenceAction.NOTIFY.name,
                exitAction = PlaceGeofenceAction.NOTIFY.name,
            )
        )
        assertEquals(
            PlaceGeofenceResult.Success,
            PlaceGeofenceReceiver.handleTransition(context, "home", PlaceGeofenceTransition.ENTER, 10_000L),
        )
        assertEquals(0, dao.listEvents().size)

        dao.upsertGeofenceConfig(
            PlaceGeofenceConfigEntity(
                placeUuid = "home",
                enabled = true,
                enterEnabled = true,
                exitEnabled = true,
                enterAction = PlaceGeofenceAction.AUTOMATIC_VISIT.name,
                exitAction = PlaceGeofenceAction.AUTOMATIC_VISIT.name,
            )
        )
        assertEquals(
            PlaceGeofenceResult.Success,
            PlaceGeofenceReceiver.handleTransition(context, "home", PlaceGeofenceTransition.ENTER, 200_000L),
        )
        assertEquals(
            PlaceGeofenceResult.DuplicateTransition,
            PlaceGeofenceReceiver.handleTransition(context, "home", PlaceGeofenceTransition.ENTER, 201_000L),
        )
        assertEquals(
            PlaceGeofenceResult.Success,
            PlaceGeofenceReceiver.handleTransition(context, "home", PlaceGeofenceTransition.EXIT, 500_000L),
        )
        assertEquals(listOf(PlaceEventTypes.CHECK_OUT, PlaceEventTypes.CHECK_IN), dao.listEvents().map { it.eventType })
    }

    @Test
    fun geofenceAutomaticEnterRejectsOverlappingOpenVisit() = runBlocking {
        val db = LuoghiDatabase.get(context)
        val dao = db.placeDao()
        dao.upsertPlace(place("home", "Home", 45.0, 9.0))
        dao.upsertPlace(place("office", "Office", 45.1, 9.1))
        dao.upsertGeofenceConfig(
            PlaceGeofenceConfigEntity(
                placeUuid = "office",
                enabled = true,
                enterEnabled = true,
                exitEnabled = true,
                enterAction = PlaceGeofenceAction.AUTOMATIC_VISIT.name,
                exitAction = PlaceGeofenceAction.AUTOMATIC_VISIT.name,
            )
        )
        dao.insertEvent(event("home", PlaceEventTypes.CHECK_IN, 100_000L, "home-session"))

        assertEquals(
            PlaceGeofenceResult.VisitRejected("OVERLAP"),
            PlaceGeofenceReceiver.handleTransition(context, "office", PlaceGeofenceTransition.ENTER, 200_000L),
        )
        assertEquals(listOf("home"), dao.listEvents().map { it.placeId })
    }

    @Test
    fun geofenceReconcileReturnsPermissionMissingWhenBackgroundLocationIsUnavailable() = runBlocking {
        val db = LuoghiDatabase.get(context)
        val dao = db.placeDao()
        dao.upsertPlace(place("home", "Home", 45.0, 9.0))
        dao.upsertGeofenceConfig(
            PlaceGeofenceConfigEntity(
                placeUuid = "home",
                enabled = true,
                enterEnabled = true,
                exitEnabled = true,
                enterAction = PlaceGeofenceAction.NOTIFY.name,
                exitAction = PlaceGeofenceAction.NOTIFY.name,
            )
        )

        assertEquals(
            PlaceGeofenceResult.PermissionMissing,
            PlaceGeofenceRegistrar(context, dao).reconcile(),
        )
    }

    private fun place(
        uuid: String,
        nickname: String,
        lat: Double? = 0.0,
        lon: Double? = 0.0,
        radiusM: Double? = 75.0,
    ) = PlaceEntity(uuid = uuid, nickname = nickname, lat = lat, lon = lon, radiusM = radiusM)

    private fun event(placeId: String, eventType: String, timestamp: Long, sessionUuid: String) =
        PlaceEventEntity(placeId = placeId, eventType = eventType, timestamp = timestamp, sessionUuid = sessionUuid)
}
