package com.gernalix.luoghi.capsules.checkin

import com.gernalix.luoghi.data.PlaceEntity
import com.gernalix.luoghi.data.PlaceEventEntity

object PlaceEventTypes {
    const val CHECK_IN = "CHECK_IN"
    const val CHECK_OUT = "CHECK_OUT"
}

data class CheckInCandidate(
    val place: PlaceEntity,
    val distanceM: Double,
)

object CheckInAttemptOutcomes {
    const val IN_PROGRESS = "IN_PROGRESS"
    const val SUCCESS = "SUCCESS"
    const val PERMISSION_DENIED = "PERMISSION_DENIED"
    const val LOCATION_UNAVAILABLE = "LOCATION_UNAVAILABLE"
    const val NO_MATCH = "NO_MATCH"
    const val AMBIGUOUS = "AMBIGUOUS"
    const val USER_CANCELLED = "USER_CANCELLED"
    const val PERSISTENCE_FAILED = "PERSISTENCE_FAILED"
    const val INTERRUPTED = "INTERRUPTED"
}

sealed interface CheckInMatchDecision {
    data class Matched(val candidate: CheckInCandidate) : CheckInMatchDecision
    data class Ambiguous(val candidates: List<CheckInCandidate>) : CheckInMatchDecision
    data object UnknownPlace : CheckInMatchDecision
}

data class ActiveVisit(
    val checkInEvent: PlaceEventEntity,
    val place: PlaceEntity?,
)
