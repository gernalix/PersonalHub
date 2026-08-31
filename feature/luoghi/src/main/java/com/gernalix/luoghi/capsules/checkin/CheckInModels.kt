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

sealed interface CheckInMatchDecision {
    data class Matched(val candidate: CheckInCandidate) : CheckInMatchDecision
    data class Ambiguous(val candidates: List<CheckInCandidate>) : CheckInMatchDecision
    data object UnknownPlace : CheckInMatchDecision
}

data class ActiveVisit(
    val checkInEvent: PlaceEventEntity,
    val place: PlaceEntity?,
)
