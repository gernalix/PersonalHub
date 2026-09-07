package com.gernalix.luoghi.capsules.checkin

import com.gernalix.luoghi.data.PlaceEventEntity

enum class HistoryValidationError {
    EVENT_NOT_FOUND,
    INVALID_TIMESTAMP,
    CHECKOUT_BEFORE_CHECKIN,
    OVERLAP,
    DUPLICATE,
    ORPHAN_CHECKOUT,
    SESSION_NOT_FOUND,
    NOTHING_TO_UNDO,
    NOTHING_TO_REDO,
}

sealed interface HistoryMutationResult {
    data object Success : HistoryMutationResult
    data class Failure(val error: HistoryValidationError) : HistoryMutationResult
}

enum class HistorySessionAnomaly {
    OPEN_CHECK_IN,
    MISSING_CHECK_IN,
    NEGATIVE_DURATION,
    MULTIPLE_CHECK_INS,
    MULTIPLE_CHECK_OUTS,
    OVERLAP,
}

data class PlaceHistorySession(
    val sessionUuid: String,
    val placeId: String,
    val checkIn: PlaceEventEntity?,
    val checkOut: PlaceEventEntity?,
    val anomalies: Set<HistorySessionAnomaly> = emptySet(),
) {
    val startMs: Long? = checkIn?.timestamp
    val endMs: Long? = checkOut?.timestamp
}

object HistorySessionCalculator {
    fun sessions(events: List<PlaceEventEntity>, nowMs: Long): List<PlaceHistorySession> {
        val sessions = events
            .groupBy { it.sessionUuid.ifBlank { it.eventUuid.ifBlank { it.id.toString() } } }
            .map { (sessionUuid, groupedEvents) ->
                val sorted = groupedEvents.sortedWith(compareBy<PlaceEventEntity> { it.timestamp }.thenBy { it.id })
                val checkIns = sorted.filter { it.eventType == PlaceEventTypes.CHECK_IN }
                val checkOuts = sorted.filter { it.eventType == PlaceEventTypes.CHECK_OUT }
                val checkIn = checkIns.firstOrNull()
                val checkOut = if (checkIn == null) {
                    checkOuts.firstOrNull()
                } else {
                    checkOuts.firstOrNull { it.timestamp > checkIn.timestamp } ?: checkOuts.firstOrNull()
                }
                val placeId = checkIn?.placeId ?: checkOut?.placeId ?: sorted.first().placeId
                val anomalies = buildSet {
                    if (checkIn == null) add(HistorySessionAnomaly.MISSING_CHECK_IN)
                    if (checkIn != null && checkOut == null) add(HistorySessionAnomaly.OPEN_CHECK_IN)
                    if (checkIn != null && checkOut != null && checkOut.timestamp <= checkIn.timestamp) {
                        add(HistorySessionAnomaly.NEGATIVE_DURATION)
                    }
                    if (checkIns.size > 1) add(HistorySessionAnomaly.MULTIPLE_CHECK_INS)
                    if (checkOuts.size > 1) add(HistorySessionAnomaly.MULTIPLE_CHECK_OUTS)
                }
                PlaceHistorySession(
                    sessionUuid = sessionUuid,
                    placeId = placeId,
                    checkIn = checkIn,
                    checkOut = checkOut,
                    anomalies = anomalies,
                )
            }

        val overlapSessions = markOverlaps(sessions.filter { it.checkIn != null }, nowMs)
            .associateBy { it.sessionUuid }

        return sessions
            .map { overlapSessions[it.sessionUuid] ?: it }
            .sortedWith(compareByDescending<PlaceHistorySession> { it.startMs ?: it.endMs ?: 0L }.thenByDescending { it.sessionUuid })
    }

    fun hasOverlap(events: List<PlaceEventEntity>, placeId: String, nowMs: Long): Boolean =
        sessions(events, nowMs).any {
            it.placeId == placeId && HistorySessionAnomaly.OVERLAP in it.anomalies
        }

    fun hasAnyOverlap(events: List<PlaceEventEntity>, nowMs: Long): Boolean =
        sessions(events, nowMs).any { HistorySessionAnomaly.OVERLAP in it.anomalies }

    private fun markOverlaps(sessions: List<PlaceHistorySession>, nowMs: Long): List<PlaceHistorySession> {
        val mutable = sessions.associateBy { it.sessionUuid }.toMutableMap()
        val sorted = sessions
            .filter { it.startMs != null }
            .sortedWith(compareBy<PlaceHistorySession> { it.startMs ?: Long.MAX_VALUE }.thenBy { it.sessionUuid })
        var previous: PlaceHistorySession? = null
        for (session in sorted) {
            val prev = previous
            if (prev != null) {
                val previousEnd = prev.endMs ?: nowMs
                val currentStart = session.startMs ?: Long.MAX_VALUE
                if (currentStart < previousEnd) {
                    mutable[prev.sessionUuid] = prev.withAnomaly(HistorySessionAnomaly.OVERLAP)
                    mutable[session.sessionUuid] = session.withAnomaly(HistorySessionAnomaly.OVERLAP)
                }
                val currentEnd = session.endMs ?: nowMs
                if (currentEnd > previousEnd) {
                    previous = session
                }
            } else {
                previous = session
            }
        }
        return mutable.values.toList()
    }

    private fun PlaceHistorySession.withAnomaly(anomaly: HistorySessionAnomaly): PlaceHistorySession =
        copy(anomalies = anomalies + anomaly)
}
