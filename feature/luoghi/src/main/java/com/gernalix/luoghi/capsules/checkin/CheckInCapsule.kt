package com.gernalix.luoghi.capsules.checkin

import com.gernalix.luoghi.capsules.location.LocationSample
import com.gernalix.luoghi.capsules.places.PlaceMutation
import com.gernalix.luoghi.data.CheckInAttemptCandidateEntity
import com.gernalix.luoghi.data.CheckInAttemptDiagnostic
import com.gernalix.luoghi.data.CheckInAttemptEntity
import com.gernalix.luoghi.data.PlaceEntity
import com.gernalix.luoghi.data.PlaceEventEntity
import com.gernalix.luoghi.data.PlaceRepository
import com.gernalix.personalhub.core.alerts.AlertTrigger
import com.gernalix.personalhub.core.alerts.PlaceAlertEngine
import kotlinx.coroutines.flow.Flow

class CheckInCapsule(
    private val repository: PlaceRepository,
    private val alertEngine: PlaceAlertEngine? = null,
) {
    val events: Flow<List<PlaceEventEntity>> = repository.events
    val recentAttempts: Flow<List<CheckInAttemptDiagnostic>> = repository.recentCheckInAttempts
    val latestUndoableHistoryAction = repository.latestUndoableHistoryAction
    val latestRedoableHistoryAction = repository.latestRedoableHistoryAction

    fun choosePlace(places: List<PlaceEntity>, location: LocationSample): CheckInMatchDecision =
        CheckInPolicy.choosePlace(places, location)

    suspend fun checkIn(placeUuid: String, location: LocationSample, source: String = "Luoghi"): Long {
        val id = repository.recordPlaceEvent(
            placeUuid = placeUuid,
            eventType = PlaceEventTypes.CHECK_IN,
            location = location,
            source = source,
        )
        alertEngine?.onPlaceEvent(placeUuid, AlertTrigger.PLACE_CHECK_IN)
        return id
    }

    suspend fun manualCheckIn(
        placeUuid: String,
        timestamp: Long = System.currentTimeMillis(),
        location: LocationSample? = null,
        source: String = "Luoghi manual",
    ): HistoryMutationResult {
        val result = repository.recordManualCheckIn(placeUuid, timestamp, location, source)
        if (result is HistoryMutationResult.Success) {
            alertEngine?.onPlaceEvent(placeUuid, AlertTrigger.PLACE_CHECK_IN, timestamp)
        }
        return result
    }

    suspend fun checkOut(placeUuid: String, location: LocationSample?, source: String = "Luoghi"): Long {
        val id = repository.recordPlaceEvent(
            placeUuid = placeUuid,
            eventType = PlaceEventTypes.CHECK_OUT,
            location = location,
            source = source,
        )
        alertEngine?.onPlaceEvent(placeUuid, AlertTrigger.PLACE_CHECK_OUT)
        return id
    }

    suspend fun manualCheckOut(
        placeUuid: String,
        timestamp: Long = System.currentTimeMillis(),
        location: LocationSample? = null,
        source: String = "Luoghi manual",
    ): HistoryMutationResult {
        val result = repository.closeCanonicalVisit(placeUuid, timestamp, location, source)
        if (result is HistoryMutationResult.Success) {
            alertEngine?.onPlaceEvent(placeUuid, AlertTrigger.PLACE_CHECK_OUT, timestamp)
        }
        return result
    }

    suspend fun manualVisit(
        placeUuid: String,
        checkInAt: Long,
        checkOutAt: Long?,
        notes: String? = null,
    ): HistoryMutationResult =
        repository.recordManualVisit(placeUuid, checkInAt, checkOutAt, notes = notes)

    suspend fun checkInNewPlace(mutation: PlaceMutation, location: LocationSample): String {
        val uuid = repository.savePlace(
            uuid = mutation.uuid,
            nickname = mutation.nickname,
            address = mutation.address,
            lat = mutation.lat ?: location.latitude,
            lon = mutation.lon ?: location.longitude,
            radiusM = mutation.radiusM ?: CheckInPolicy.DEFAULT_RADIUS_M,
            notes = mutation.notes,
            sourceApp = mutation.sourceApp,
        )
        repository.setPlaceTags(uuid, mutation.tagNames)
        checkIn(uuid, location)
        return uuid
    }

    suspend fun recoverInterruptedAttempts(): Int = repository.recoverInterruptedCheckInAttempts()

    suspend fun beginAttempt(source: String = "Luoghi"): CheckInAttemptEntity =
        repository.beginCheckInAttempt(source)

    suspend fun updateAttemptLocation(attemptId: String, location: LocationSample) =
        repository.updateCheckInAttemptLocation(attemptId, location)

    suspend fun markAttemptStage(
        attemptId: String,
        stage: String,
        outcome: String = CheckInAttemptOutcomes.IN_PROGRESS,
        errorCode: String? = null,
        errorMessage: String? = null,
    ) = repository.markCheckInAttemptStage(
        attemptId = attemptId,
        stage = stage,
        outcome = outcome,
        errorCode = errorCode,
        errorMessage = errorMessage,
    )

    suspend fun finishAttempt(
        attemptId: String,
        outcome: String,
        stage: String,
        selectedPlaceId: String? = null,
        matchedPlaceId: String? = null,
        errorCode: String? = null,
        errorMessage: String? = null,
    ) = repository.finishCheckInAttempt(
        attemptId = attemptId,
        outcome = outcome,
        stage = stage,
        selectedPlaceId = selectedPlaceId,
        matchedPlaceId = matchedPlaceId,
        errorCode = errorCode,
        errorMessage = errorMessage,
    )

    suspend fun replaceAttemptCandidates(
        attemptId: String,
        candidates: List<CheckInAttemptCandidateEntity>,
    ) = repository.replaceCheckInAttemptCandidates(attemptId, candidates)

    suspend fun candidatesForAttempt(attemptId: String): List<CheckInAttemptCandidateEntity> =
        repository.checkInAttemptCandidates(attemptId)

    suspend fun editEvent(eventId: Long, timestamp: Long, notes: String?, reason: String? = null): HistoryMutationResult =
        repository.editHistoryEvent(
            eventId = eventId,
            newTimestamp = timestamp,
            notes = notes,
            reason = reason,
        )

    suspend fun deleteEvent(eventId: Long, reason: String? = null): HistoryMutationResult =
        repository.deleteHistoryEvent(eventId = eventId, reason = reason)

    suspend fun deleteSession(sessionUuid: String, reason: String? = null): HistoryMutationResult =
        repository.deleteHistorySession(sessionUuid = sessionUuid, reason = reason)

    suspend fun undoHistory(): HistoryMutationResult = repository.undoLatestHistoryAction()

    suspend fun redoHistory(): HistoryMutationResult = repository.redoLatestHistoryAction()
}
