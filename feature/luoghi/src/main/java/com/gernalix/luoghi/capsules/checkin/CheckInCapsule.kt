package com.gernalix.luoghi.capsules.checkin

import com.gernalix.luoghi.capsules.location.LocationSample
import com.gernalix.luoghi.capsules.places.PlaceMutation
import com.gernalix.luoghi.data.PlaceEntity
import com.gernalix.luoghi.data.PlaceEventEntity
import com.gernalix.luoghi.data.PlaceRepository
import kotlinx.coroutines.flow.Flow

class CheckInCapsule(
    private val repository: PlaceRepository,
) {
    val events: Flow<List<PlaceEventEntity>> = repository.events
    val latestUndoableHistoryAction = repository.latestUndoableHistoryAction
    val latestRedoableHistoryAction = repository.latestRedoableHistoryAction

    fun choosePlace(places: List<PlaceEntity>, location: LocationSample): CheckInMatchDecision =
        CheckInPolicy.choosePlace(places, location)

    suspend fun checkIn(placeUuid: String, location: LocationSample, source: String = "Luoghi"): Long =
        repository.recordPlaceEvent(
            placeUuid = placeUuid,
            eventType = PlaceEventTypes.CHECK_IN,
            location = location,
            source = source,
        )

    suspend fun manualCheckIn(
        placeUuid: String,
        timestamp: Long = System.currentTimeMillis(),
        location: LocationSample? = null,
        source: String = "Luoghi manual",
    ): HistoryMutationResult =
        repository.recordManualCheckIn(placeUuid, timestamp, location, source)

    suspend fun checkOut(placeUuid: String, location: LocationSample?, source: String = "Luoghi"): Long =
        repository.recordPlaceEvent(
            placeUuid = placeUuid,
            eventType = PlaceEventTypes.CHECK_OUT,
            location = location,
            source = source,
        )

    suspend fun manualCheckOut(
        placeUuid: String,
        timestamp: Long = System.currentTimeMillis(),
        location: LocationSample? = null,
        source: String = "Luoghi manual",
    ): HistoryMutationResult =
        repository.closeCanonicalVisit(placeUuid, timestamp, location, source)

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
        checkIn(uuid, location)
        return uuid
    }

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
