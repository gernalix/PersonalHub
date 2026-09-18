package com.gernalix.luoghi

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gernalix.luoghi.capsules.checkin.CheckInMatchDecision
import com.gernalix.luoghi.capsules.checkin.CheckInAttemptOutcomes
import com.gernalix.luoghi.capsules.checkin.CheckInPolicy
import com.gernalix.luoghi.capsules.checkin.HistoryMutationResult
import com.gernalix.luoghi.capsules.checkin.HistoryValidationError
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
import com.gernalix.luoghi.data.CheckInAttemptCandidateEntity
import com.gernalix.luoghi.data.CheckInAttemptDiagnostic
import com.gernalix.luoghi.data.CheckInAttemptWithPlaceName
import com.gernalix.luoghi.data.PlaceDeleteResult
import com.gernalix.luoghi.data.PlaceEntity
import com.gernalix.luoghi.data.PlaceEventEntity
import com.gernalix.luoghi.data.PlaceGeofenceConfigEntity
import com.gernalix.luoghi.ui.home.diagnosticReport
import com.gernalix.luoghi.ui.home.filteredDiagnosticAttempts
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
    fun overlappingNearbyPlacesPreferClearlySeparatedNearestCandidate() {
        val near = place("near", "Near", 0.0, 0.0, radiusM = 2_000.0)
        val far = place("far", "Far", 0.0, 0.015, radiusM = 2_000.0)
        val decision = CheckInPolicy.choosePlace(
            places = listOf(near, far),
            location = LocationSample(0.0, 0.0, accuracyM = 4.0),
        )
        assertTrue(decision is CheckInMatchDecision.Matched)
        assertEquals("near", (decision as CheckInMatchDecision.Matched).candidate.place.uuid)
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
    fun unrelatedHistoricalOverlapDoesNotPoisonIndependentMutations() = runBlocking {
        val db = LuoghiDatabase.get(context)
        val dao = db.placeDao()
        val repository = com.gernalix.luoghi.data.PlaceRepository(context)
        dao.upsertPlace(place("legacy-a", "Legacy A"))
        dao.upsertPlace(place("legacy-b", "Legacy B"))
        dao.upsertPlace(place("candidate", "Candidate"))
        dao.upsertPlace(place("active", "Active"))
        dao.upsertPlace(place("edit", "Edit"))
        dao.upsertPlace(place("geofence", "Geofence", 45.0, 9.0))
        seedVisit(dao, "legacy-a", 1_000L, 5_000L, "legacy-a-session")
        seedVisit(dao, "legacy-b", 3_000L, 7_000L, "legacy-b-session")
        seedVisit(dao, "edit", 20_000L, 22_000L, "edit-session")
        val unrelatedBefore = legacyEvents(dao)

        assertEquals(
            HistoryMutationResult.Success,
            repository.recordManualVisit("candidate", 10_000L, 12_000L, source = "QA"),
        )

        assertEquals(
            HistoryMutationResult.Success,
            repository.recordManualCheckIn("active", 30_000L, source = "QA"),
        )
        assertEquals(
            HistoryMutationResult.Success,
            repository.closeCanonicalVisit("active", 32_000L, source = "QA"),
        )

        val editCheckOut = dao.eventsForSession("edit-session").first { it.eventType == PlaceEventTypes.CHECK_OUT }
        assertEquals(
            HistoryMutationResult.Success,
            repository.editHistoryEvent(editCheckOut.id, 23_000L, notes = null, source = "QA"),
        )

        assertEquals(
            HistoryMutationResult.Failure(HistoryValidationError.OVERLAP),
            repository.recordManualVisit("candidate", 11_000L, 13_000L, source = "QA"),
        )
        assertEquals(
            HistoryMutationResult.Failure(HistoryValidationError.OVERLAP),
            repository.editHistoryEvent(editCheckOut.id, 31_000L, notes = null, source = "QA"),
        )
        assertEquals(unrelatedBefore, legacyEvents(dao))

        val legacyBCheckOut = dao.eventsForSession("legacy-b-session").first { it.eventType == PlaceEventTypes.CHECK_OUT }
        assertEquals(
            HistoryMutationResult.Success,
            repository.editHistoryEvent(legacyBCheckOut.id, 6_000L, notes = null, source = "QA"),
        )
    }

    @Test
    fun automaticGeofenceMutationsAllowUnrelatedHistoricalOverlapButRejectIntroducedOverlap() = runBlocking {
        val db = LuoghiDatabase.get(context)
        val dao = db.placeDao()
        dao.upsertPlace(place("legacy-a", "Legacy A"))
        dao.upsertPlace(place("legacy-b", "Legacy B"))
        dao.upsertPlace(place("conflict", "Conflict"))
        dao.upsertPlace(place("geofence", "Geofence", 45.0, 9.0))
        seedVisit(dao, "legacy-a", 1_000L, 5_000L, "legacy-a-session")
        seedVisit(dao, "legacy-b", 3_000L, 7_000L, "legacy-b-session")
        dao.upsertGeofenceConfig(
            PlaceGeofenceConfigEntity(
                placeUuid = "geofence",
                enabled = true,
                enterEnabled = true,
                exitEnabled = true,
                enterAction = PlaceGeofenceAction.AUTOMATIC_VISIT.name,
                exitAction = PlaceGeofenceAction.AUTOMATIC_VISIT.name,
            )
        )

        assertEquals(
            PlaceGeofenceResult.Success,
            PlaceGeofenceReceiver.handleTransition(context, "geofence", PlaceGeofenceTransition.ENTER, 10_000L),
        )
        assertEquals(
            PlaceGeofenceResult.Success,
            PlaceGeofenceReceiver.handleTransition(context, "geofence", PlaceGeofenceTransition.EXIT, 12_000L),
        )
        seedVisit(dao, "conflict", 200_000L, 300_000L, "conflict-session")
        assertEquals(
            PlaceGeofenceResult.VisitRejected("OVERLAP"),
            PlaceGeofenceReceiver.handleTransition(context, "geofence", PlaceGeofenceTransition.ENTER, 220_000L),
        )
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

    @Test
    fun checkInAttemptJournalRecordsCandidatesAndDoesNotCreateVisitForFailure() = runBlocking {
        val db = LuoghiDatabase.get(context)
        val dao = db.placeDao()
        val repository = com.gernalix.luoghi.data.PlaceRepository(context)
        dao.upsertPlace(place("near", "Near", 0.0, 0.0, radiusM = 100.0))
        dao.upsertPlace(place("far", "Far", 0.0, 0.00005, radiusM = 100.0))

        val attempt = repository.beginCheckInAttempt()
        val attemptLocation = LocationSample(0.0, 0.0, accuracyM = 4.0)
        val candidates = CheckInPolicy.choosePlace(
            dao.listPlaces(),
            attemptLocation,
        ) as CheckInMatchDecision.Ambiguous
        repository.updateCheckInAttemptLocation(attempt.id, attemptLocation)
        repository.replaceCheckInAttemptCandidates(
            attempt.id,
            candidates.candidates.mapIndexed { index, candidate ->
                CheckInAttemptCandidateEntity(
                    attemptId = attempt.id,
                    placeId = candidate.place.uuid,
                    placeNameSnapshot = candidate.place.nickname,
                    distanceM = candidate.distanceM,
                    thresholdM = CheckInPolicy.matchThresholdM(candidate.place, attemptLocation),
                    rank = index + 1,
                    result = "AMBIGUOUS",
                )
            },
        )
        repository.finishCheckInAttempt(
            attempt.id,
            CheckInAttemptOutcomes.AMBIGUOUS,
            stage = "AMBIGUOUS",
            errorCode = "AMBIGUOUS_MATCH",
            errorMessage = "Multiple places matched",
        )

        assertEquals(1, dao.countCheckInAttemptsByOutcome(CheckInAttemptOutcomes.AMBIGUOUS))
        assertEquals(2, dao.countCheckInAttemptCandidates(attempt.id))
        val savedCandidates = dao.checkInAttemptCandidates(attempt.id)
        assertEquals(listOf("Near", "Far"), savedCandidates.map { it.placeNameSnapshot })
        val diagnostic = CheckInAttemptDiagnostic(
            attempt = CheckInAttemptWithPlaceName(
                id = attempt.id,
                startedAt = attempt.startedAt,
                finishedAt = attempt.finishedAt,
                source = attempt.source,
                stage = "AMBIGUOUS",
                outcome = CheckInAttemptOutcomes.AMBIGUOUS,
                lat = 0.0,
                lon = 0.0,
                accuracyM = 4.0,
                selectedPlaceId = null,
                matchedPlaceId = null,
                placeName = null,
                errorCode = "AMBIGUOUS_MATCH",
                errorMessage = "Multiple places matched",
            ),
            candidates = savedCandidates,
        )
        assertEquals(listOf(diagnostic), filteredDiagnosticAttempts(listOf(diagnostic), "ambiguous", "far"))
        val report = diagnosticReport(diagnostic)
        assertTrue(report.contains("Far distanceM="))
        assertTrue(report.contains("thresholdM=104.0"))
        assertTrue(report.contains("rank=2"))
        assertTrue(report.contains("result=AMBIGUOUS"))
        assertEquals(PlaceDeleteResult.Deleted, repository.deletePlace("far"))
        val afterDelete = dao.checkInAttemptCandidates(attempt.id)
        assertEquals(2, afterDelete.size)
        assertEquals("Far", afterDelete.first { it.placeId == "far" }.placeNameSnapshot)
        assertEquals(0, dao.listEvents().size)
    }

    @Test
    fun checkInAttemptJournalRecordsSuccessAlongsideRealVisit() = runBlocking {
        val db = LuoghiDatabase.get(context)
        val dao = db.placeDao()
        val repository = com.gernalix.luoghi.data.PlaceRepository(context)
        dao.upsertPlace(place("home", "Home", 0.0, 0.0))

        val attempt = repository.beginCheckInAttempt()
        val location = LocationSample(0.0, 0.0, accuracyM = 3.0)
        repository.updateCheckInAttemptLocation(attempt.id, location)
        assertEquals(HistoryMutationResult.Success, repository.recordManualCheckIn("home", location = location))
        repository.finishCheckInAttempt(
            attempt.id,
            CheckInAttemptOutcomes.SUCCESS,
            stage = "MATCHED_CHECKED_IN",
            selectedPlaceId = "home",
            matchedPlaceId = "home",
        )

        assertEquals(1, dao.countCheckInAttemptsByOutcome(CheckInAttemptOutcomes.SUCCESS))
        assertEquals(listOf(PlaceEventTypes.CHECK_IN), dao.listEvents().map { it.eventType })
    }

    @Test
    fun checkInAttemptRecoveryMarksOnlyInProgressRowsOnce() = runBlocking {
        val db = LuoghiDatabase.get(context)
        val dao = db.placeDao()
        val repository = com.gernalix.luoghi.data.PlaceRepository(context)
        val pending = repository.beginCheckInAttempt()
        repository.markCheckInAttemptStage(
            pending.id,
            stage = "NO_MATCH_FORM_OPEN",
            errorCode = "NO_MATCH",
            errorMessage = "No saved place matched",
        )

        assertEquals(1, repository.recoverInterruptedCheckInAttempts())
        assertEquals(0, repository.recoverInterruptedCheckInAttempts())
        assertEquals(1, dao.countCheckInAttemptsByOutcome(CheckInAttemptOutcomes.INTERRUPTED))
        val recovered = requireNotNull(dao.checkInAttempt(pending.id))
        assertEquals("NO_MATCH_INTERRUPTED", recovered.stage)
        assertEquals("NO_MATCH", recovered.errorCode)
        assertEquals(0, dao.listEvents().size)
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

    private suspend fun seedVisit(
        dao: com.gernalix.luoghi.data.PlaceDao,
        placeId: String,
        checkInAt: Long,
        checkOutAt: Long,
        sessionUuid: String,
    ) {
        dao.insertEvent(event(placeId, PlaceEventTypes.CHECK_IN, checkInAt, sessionUuid))
        dao.insertEvent(event(placeId, PlaceEventTypes.CHECK_OUT, checkOutAt, sessionUuid))
    }

    private suspend fun legacyEvents(
        dao: com.gernalix.luoghi.data.PlaceDao,
    ): Map<String, List<PlaceEventEntity>> =
        listOf("legacy-a-session", "legacy-b-session").associateWith { sessionUuid ->
            dao.eventsForSession(sessionUuid).map { it.copy(id = 0) }
        }
}
