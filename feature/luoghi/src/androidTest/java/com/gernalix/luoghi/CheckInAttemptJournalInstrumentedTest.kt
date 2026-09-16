package com.gernalix.luoghi

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gernalix.luoghi.capsules.checkin.CheckInAttemptOutcomes
import com.gernalix.luoghi.capsules.checkin.CheckInMatchDecision
import com.gernalix.luoghi.capsules.checkin.CheckInPolicy
import com.gernalix.luoghi.capsules.checkin.HistoryMutationResult
import com.gernalix.luoghi.capsules.checkin.PlaceEventTypes
import com.gernalix.luoghi.capsules.location.LocationSample
import com.gernalix.luoghi.data.CheckInAttemptCandidateEntity
import com.gernalix.luoghi.data.CheckInAttemptDiagnostic
import com.gernalix.luoghi.data.CheckInAttemptWithPlaceName
import com.gernalix.luoghi.data.LuoghiDatabase
import com.gernalix.luoghi.data.PlaceDeleteResult
import com.gernalix.luoghi.data.PlaceEntity
import com.gernalix.luoghi.data.PlaceRepository
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

@RunWith(AndroidJUnit4::class)
class CheckInAttemptJournalInstrumentedTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        PersonalHubDatabase.resetForTests()
        context.deleteDatabase(PersonalHubDatabase.DATABASE_NAME)
    }

    @After
    fun tearDown() {
        PersonalHubDatabase.resetForTests()
        context.deleteDatabase(PersonalHubDatabase.DATABASE_NAME)
    }

    @Test
    fun permissionDeniedUnknownAmbiguousAndSuccessAreJournaledWithoutPollutingFailures() = runBlocking {
        val db = LuoghiDatabase.get(context)
        val dao = db.placeDao()
        val repository = PlaceRepository(context)
        dao.upsertPlace(place("home", "Home", 45.0, 9.0, 75.0))
        dao.upsertPlace(place("desk", "Desk", 45.0, 9.0001, 75.0))
        assertEquals(2, dao.listPlaces().size)

        val denied = repository.beginCheckInAttempt()
        repository.finishCheckInAttempt(
            denied.id,
            CheckInAttemptOutcomes.PERMISSION_DENIED,
            stage = "PERMISSION_DENIED",
            errorCode = "LOCATION_PERMISSION_DENIED",
            errorMessage = "Location permission denied",
        )
        assertEquals(0, dao.listEvents().size)

        val unknown = repository.beginCheckInAttempt()
        repository.updateCheckInAttemptLocation(unknown.id, LocationSample(46.0, 10.0, accuracyM = 5.0))
        repository.markCheckInAttemptStage(
            unknown.id,
            stage = "NO_MATCH_FORM_OPEN",
            errorCode = "NO_MATCH",
            errorMessage = "No saved place matched",
        )
        repository.finishCheckInAttempt(
            unknown.id,
            CheckInAttemptOutcomes.USER_CANCELLED,
            stage = "USER_CANCELLED",
            errorCode = "USER_CANCELLED",
            errorMessage = "User cancelled",
        )
        assertEquals(0, dao.listEvents().size)

        val ambiguous = repository.beginCheckInAttempt()
        val location = LocationSample(45.0, 9.0, accuracyM = 4.0)
        repository.updateCheckInAttemptLocation(ambiguous.id, location)
        val decision = CheckInPolicy.choosePlace(dao.listPlaces(), location)
        assertTrue(decision is CheckInMatchDecision.Ambiguous)
        val candidates = (decision as CheckInMatchDecision.Ambiguous).candidates
        repository.replaceCheckInAttemptCandidates(
            ambiguous.id,
            candidates.mapIndexed { index, candidate ->
                CheckInAttemptCandidateEntity(
                    attemptId = ambiguous.id,
                    placeId = candidate.place.uuid,
                    placeNameSnapshot = candidate.place.nickname,
                    distanceM = candidate.distanceM,
                    thresholdM = CheckInPolicy.effectiveRadiusM(candidate.place),
                    rank = index + 1,
                    result = "AMBIGUOUS",
                )
            },
        )
        repository.finishCheckInAttempt(
            ambiguous.id,
            CheckInAttemptOutcomes.AMBIGUOUS,
            stage = "AMBIGUOUS",
            errorCode = "AMBIGUOUS_MATCH",
            errorMessage = "Multiple places matched",
        )
        assertEquals(0, dao.listEvents().size)
        val ambiguousCandidates = dao.checkInAttemptCandidates(ambiguous.id)
        assertEquals(2, ambiguousCandidates.size)
        assertTrue(ambiguousCandidates.any { it.placeId == "desk" && it.placeNameSnapshot == "Desk" })
        val diagnostic = CheckInAttemptDiagnostic(
            attempt = CheckInAttemptWithPlaceName(
                id = ambiguous.id,
                startedAt = ambiguous.startedAt,
                finishedAt = ambiguous.finishedAt,
                source = ambiguous.source,
                stage = "AMBIGUOUS",
                outcome = CheckInAttemptOutcomes.AMBIGUOUS,
                lat = location.latitude,
                lon = location.longitude,
                accuracyM = location.accuracyM,
                selectedPlaceId = null,
                matchedPlaceId = null,
                placeName = null,
                errorCode = "AMBIGUOUS_MATCH",
                errorMessage = "Multiple places matched",
            ),
            candidates = ambiguousCandidates,
        )
        assertEquals(listOf(diagnostic), filteredDiagnosticAttempts(listOf(diagnostic), "ambiguous", "desk"))
        assertTrue(diagnosticReport(diagnostic).contains("Desk distanceM="))
        assertEquals(PlaceDeleteResult.Deleted, repository.deletePlace("desk"))
        val candidatesAfterDelete = dao.checkInAttemptCandidates(ambiguous.id)
        assertEquals(2, candidatesAfterDelete.size)
        assertEquals("Desk", candidatesAfterDelete.first { it.placeId == "desk" }.placeNameSnapshot)

        val success = repository.beginCheckInAttempt()
        repository.updateCheckInAttemptLocation(success.id, location)
        assertEquals(HistoryMutationResult.Success, repository.recordManualCheckIn("home", location = location))
        repository.finishCheckInAttempt(
            success.id,
            CheckInAttemptOutcomes.SUCCESS,
            stage = "MATCHED_CHECKED_IN",
            selectedPlaceId = "home",
            matchedPlaceId = "home",
        )

        assertEquals(1, dao.countCheckInAttemptsByOutcome(CheckInAttemptOutcomes.PERMISSION_DENIED))
        assertEquals(1, dao.countCheckInAttemptsByOutcome(CheckInAttemptOutcomes.USER_CANCELLED))
        assertEquals(1, dao.countCheckInAttemptsByOutcome(CheckInAttemptOutcomes.AMBIGUOUS))
        assertEquals(1, dao.countCheckInAttemptsByOutcome(CheckInAttemptOutcomes.SUCCESS))
        assertEquals(2, dao.countCheckInAttemptCandidates(ambiguous.id))
        assertEquals(listOf(PlaceEventTypes.CHECK_IN), dao.listEvents().map { it.eventType })
        assertEquals(listOf("home"), dao.listPlaces().map { it.uuid }.sorted())
    }

    private fun place(uuid: String, nickname: String, lat: Double, lon: Double, radiusM: Double) =
        PlaceEntity(uuid = uuid, nickname = nickname, lat = lat, lon = lon, radiusM = radiusM)
}
