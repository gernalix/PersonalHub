package com.gernalix.luoghi.data

import com.gernalix.personalhub.core.database.HubAutoExport
import com.gernalix.personalhub.core.database.PersonalHubDatabase

import android.content.Context
import androidx.room.withTransaction
import com.gernalix.personalhub.contracts.database.PlaceReferenceReader
import com.gernalix.luoghi.capsules.checkin.HistoryMutationResult
import com.gernalix.luoghi.capsules.checkin.HistorySessionAnomaly
import com.gernalix.luoghi.capsules.checkin.HistorySessionCalculator
import com.gernalix.luoghi.capsules.checkin.HistoryValidationError
import com.gernalix.luoghi.capsules.checkin.CheckInAttemptOutcomes
import com.gernalix.luoghi.capsules.checkin.PlaceEventTypes
import com.gernalix.luoghi.capsules.location.LocationSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.Locale

sealed interface PlaceDeleteResult {
    data object Deleted : PlaceDeleteResult
    data object ArchivedBecauseReferenced : PlaceDeleteResult
    data object NotFound : PlaceDeleteResult
}

class PlaceRepository(
    private val context: Context,
    private val database: PersonalHubDatabase = PersonalHubDatabase.get(context),
    private val dao: PlaceDao = database.placeDao(),
    private val placeReferences: PlaceReferenceReader = database,
) {
    val places: Flow<List<PlaceEntity>> = dao.observePlaces()
    val placeTags: Flow<List<PlaceTagEntity>> = dao.observePlaceTags()
    val events: Flow<List<PlaceEventEntity>> = dao.observeEvents()
    val recentCheckInAttempts: Flow<List<CheckInAttemptDiagnostic>> = dao.observeRecentCheckInAttemptDiagnostics(8)
    val geofenceConfigs: Flow<List<PlaceGeofenceConfigEntity>> = dao.observeGeofenceConfigs()
    val globalStatsState: Flow<GlobalStatsStateEntity?> = dao.observeGlobalStatsState()
    val latestUndoableHistoryAction: Flow<HistoryActionEntity?> = dao.observeLatestUndoableAction()
    val latestRedoableHistoryAction: Flow<HistoryActionEntity?> = dao.observeLatestRedoableAction()

    suspend fun listPlaceTags(): List<PlaceTagEntity> = dao.listPlaceTags()

    suspend fun tagsForPlace(placeUuid: String): List<PlaceTagEntity> = dao.tagsForPlace(placeUuid)

    suspend fun setPlaceTags(placeUuid: String, names: Collection<String>) {
        val normalizedNames = names
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinctBy { it.lowercase(Locale.ROOT) }
            .toList()

        DatabaseMutationCoordinator.mutex.withLock {
            database.withTransaction {
                val refs = normalizedNames.map { displayName ->
                    val normalized = displayName.lowercase(Locale.ROOT)
                    val existing = dao.getPlaceTagByNormalizedName(normalized)
                    val tagId = existing?.id ?: run {
                        val now = System.currentTimeMillis()
                        val inserted = dao.insertPlaceTag(
                            PlaceTagEntity(
                                name = displayName,
                                normalizedName = normalized,
                                createdAt = now,
                                updatedAt = now,
                            )
                        )
                        if (inserted > 0L) inserted
                        else requireNotNull(dao.getPlaceTagByNormalizedName(normalized)).id
                    }
                    PlaceTagCrossRef(placeUuid = placeUuid, tagId = tagId)
                }
                dao.clearPlaceTagCrossRefs(placeUuid)
                if (refs.isNotEmpty()) dao.insertPlaceTagCrossRefs(refs)
            }
        }
        HubAutoExport.request(context)
    }

    suspend fun savePlace(
        uuid: String?,
        nickname: String,
        address: String?,
        lat: Double?,
        lon: Double?,
        radiusM: Double?,
        notes: String?,
        sourceApp: String?,
    ): String {
        val resolvedUuid = DatabaseMutationCoordinator.mutex.withLock {
            val now = System.currentTimeMillis()
            val existing = uuid?.takeIf { it.isNotBlank() }?.let { dao.getPlace(it) }
            val resolved = existing?.uuid ?: uuid?.takeIf { isValidUuid(it) } ?: UUID.randomUUID().toString()
            dao.upsertPlace(
                PlaceEntity(
                    uuid = resolved,
                    nickname = nickname.trim(),
                    address = address.cleanNullable(),
                    lat = lat,
                    lon = lon,
                    radiusM = radiusM,
                    notes = notes.cleanNullable(),
                    sourceApp = sourceApp.cleanNullable(),
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                    archived = existing?.archived ?: false,
                    firstCheckInAtPlace = existing?.firstCheckInAtPlace,
                ),
            )
            resolved
        }
        HubAutoExport.request(context)
        return resolvedUuid
    }

    suspend fun beginCheckInAttempt(source: String = "Luoghi"): CheckInAttemptEntity {
        val attempt = CheckInAttemptEntity(source = source)
        dao.upsertCheckInAttempt(attempt)
        HubAutoExport.request(context)
        return attempt
    }

    suspend fun updateCheckInAttemptLocation(attemptId: String, location: LocationSample) {
        val current = dao.checkInAttempt(attemptId) ?: return
        dao.upsertCheckInAttempt(
            current.copy(
                stage = "LOCATION_CAPTURED",
                lat = location.latitude,
                lon = location.longitude,
                accuracyM = location.accuracyM,
            )
        )
        HubAutoExport.request(context)
    }

    suspend fun markCheckInAttemptStage(
        attemptId: String,
        stage: String,
        outcome: String = CheckInAttemptOutcomes.IN_PROGRESS,
        errorCode: String? = null,
        errorMessage: String? = null,
    ) {
        val current = dao.checkInAttempt(attemptId) ?: return
        dao.upsertCheckInAttempt(
            current.copy(
                stage = stage,
                outcome = outcome,
                errorCode = errorCode.cleanNullable(),
                errorMessage = sanitizeAttemptError(errorMessage).cleanNullable(),
            )
        )
        HubAutoExport.request(context)
    }

    suspend fun replaceCheckInAttemptCandidates(
        attemptId: String,
        candidates: List<CheckInAttemptCandidateEntity>,
    ) {
        database.withTransaction {
            dao.deleteCheckInAttemptCandidates(attemptId)
            if (candidates.isNotEmpty()) dao.insertCheckInAttemptCandidates(candidates)
        }
        HubAutoExport.request(context)
    }

    suspend fun checkInAttemptCandidates(attemptId: String): List<CheckInAttemptCandidateEntity> =
        dao.checkInAttemptCandidates(attemptId)

    suspend fun finishCheckInAttempt(
        attemptId: String,
        outcome: String,
        stage: String,
        selectedPlaceId: String? = null,
        matchedPlaceId: String? = null,
        errorCode: String? = null,
        errorMessage: String? = null,
    ) {
        val current = dao.checkInAttempt(attemptId) ?: return
        dao.upsertCheckInAttempt(
            current.copy(
                finishedAt = System.currentTimeMillis(),
                stage = stage,
                outcome = outcome,
                selectedPlaceId = selectedPlaceId ?: current.selectedPlaceId,
                matchedPlaceId = matchedPlaceId ?: current.matchedPlaceId,
                errorCode = errorCode.cleanNullable(),
                errorMessage = sanitizeAttemptError(errorMessage).cleanNullable(),
            )
        )
        HubAutoExport.request(context)
    }

    suspend fun recoverInterruptedCheckInAttempts(): Int {
        val stale = dao.inProgressCheckInAttempts()
        stale.forEach { attempt ->
            val priorErrorCode = attempt.errorCode?.takeIf { it.isNotBlank() }
            val interruptedStage = when {
                attempt.stage.startsWith("AMBIGUOUS") -> "AMBIGUOUS_INTERRUPTED"
                attempt.stage.startsWith("NO_MATCH") -> "NO_MATCH_INTERRUPTED"
                else -> "RECOVERY"
            }
            val interruptedMessage = when (priorErrorCode) {
                "AMBIGUOUS_MATCH" -> "Ambiguous check-in interrupted before place selection"
                "NO_MATCH" -> "No-match check-in interrupted before completion"
                else -> "Attempt interrupted before completion"
            }
            dao.upsertCheckInAttempt(
                attempt.copy(
                    finishedAt = attempt.finishedAt ?: System.currentTimeMillis(),
                    stage = interruptedStage,
                    outcome = CheckInAttemptOutcomes.INTERRUPTED,
                    errorCode = priorErrorCode ?: "PROCESS_INTERRUPTED",
                    errorMessage = interruptedMessage,
                )
            )
        }
        if (stale.isNotEmpty()) HubAutoExport.request(context)
        return stale.size
    }

    suspend fun deletePlace(uuid: String): PlaceDeleteResult {
        val result = DatabaseMutationCoordinator.mutex.withLock {
            database.withTransaction {
                val place = dao.getPlace(uuid) ?: return@withTransaction PlaceDeleteResult.NotFound
                if (placeReferences.referenceCount(uuid) > 0) {
                    dao.setPlaceArchived(uuid, archived = true, updatedAt = System.currentTimeMillis())
                    PlaceDeleteResult.ArchivedBecauseReferenced
                } else if (dao.deletePlaceByUuid(place.uuid) > 0) {
                    PlaceDeleteResult.Deleted
                } else {
                    PlaceDeleteResult.NotFound
                }
            }
        }
        when (result) {
            PlaceDeleteResult.Deleted -> {
                HubAutoExport.request(context)
                com.gernalix.personalhub.core.hubcontext.HubContextRuntime.canonicalDeletedIfInitialized(
                    com.gernalix.personalhub.contracts.database.HubEntityRef("places", "place", uuid),
                )
            }
            PlaceDeleteResult.ArchivedBecauseReferenced -> HubAutoExport.request(context)
            PlaceDeleteResult.NotFound -> Unit
        }
        return result
    }

    suspend fun archivePlace(uuid: String, archived: Boolean = true): Int {
        val updated = DatabaseMutationCoordinator.mutex.withLock {
            dao.setPlaceArchived(uuid, archived, System.currentTimeMillis())
        }
        if (updated > 0) HubAutoExport.request(context)
        return updated
    }

    suspend fun saveGeofenceConfig(config: PlaceGeofenceConfigEntity): Long {
        val now = System.currentTimeMillis()
        val existing = dao.geofenceConfig(config.placeUuid)
        val saved = config.copy(
            id = existing?.id ?: config.id,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        val id = DatabaseMutationCoordinator.mutex.withLock {
            dao.upsertGeofenceConfig(saved)
        }
        HubAutoExport.request(context)
        return id
    }

    suspend fun recordPlaceEvent(
        placeUuid: String,
        eventType: String,
        location: LocationSample?,
        source: String = "Luoghi",
        notes: String? = null,
        timestamp: Long = System.currentTimeMillis(),
    ): Long = recordPlaceEventEntity(
        event = PlaceEventEntity(
            placeId = placeUuid,
            eventType = eventType,
            timestamp = timestamp,
            lat = location?.latitude,
            lon = location?.longitude,
            accuracyM = location?.accuracyM,
            source = source,
            notes = notes.cleanNullable(),
        ),
        mutationSource = "place_events.${eventType.lowercase()}",
    )

    suspend fun recordPlaceEventEntity(
        event: PlaceEventEntity,
        mutationSource: String = "place_events.${event.eventType.lowercase()}",
    ): Long {
        val inserted = DatabaseMutationCoordinator.mutex.withLock {
            val normalized = normalizedEventForInsert(event)
            database.withTransaction {
                val id = dao.insertEvent(normalized)
                val saved = normalized.copy(id = id)
                dao.recalculateStatsBaselines()
                dao.insertAuditLog(
                    HistoryAuditLogEntity(
                        action = AUDIT_EVENT_CREATED,
                        entityType = ENTITY_EVENT,
                        entityId = saved.eventUuid,
                        sessionUuid = saved.sessionUuid,
                        afterJson = eventJson(saved).toString(),
                        source = saved.source,
                    )
                )
                id
            }
        }
        HubAutoExport.request(context)
        return inserted
    }

    suspend fun recordManualCheckIn(
        placeUuid: String,
        timestamp: Long = System.currentTimeMillis(),
        location: LocationSample? = null,
        source: String = "Luoghi manual",
        notes: String? = null,
    ): HistoryMutationResult =
        recordCanonicalVisit(
            placeUuid = placeUuid,
            checkInAt = timestamp,
            checkOutAt = null,
            checkInLocation = location,
            checkOutLocation = null,
            source = source,
            notes = notes,
            mutationSource = "place_events.manual_check_in",
        )

    suspend fun recordManualVisit(
        placeUuid: String,
        checkInAt: Long,
        checkOutAt: Long?,
        source: String = "Luoghi manual",
        notes: String? = null,
    ): HistoryMutationResult =
        recordCanonicalVisit(
            placeUuid = placeUuid,
            checkInAt = checkInAt,
            checkOutAt = checkOutAt,
            checkInLocation = null,
            checkOutLocation = null,
            source = source,
            notes = notes,
            mutationSource = "place_events.manual_visit",
        )

    suspend fun closeCanonicalVisit(
        placeUuid: String,
        timestamp: Long = System.currentTimeMillis(),
        location: LocationSample? = null,
        source: String = "Luoghi manual",
        notes: String? = null,
    ): HistoryMutationResult {
        val result = DatabaseMutationCoordinator.mutex.withLock {
            database.withTransaction {
                val active = dao.getActiveVisitEvent()
                    ?.takeIf { it.placeId == placeUuid }
                    ?: return@withTransaction HistoryMutationResult.Failure(HistoryValidationError.SESSION_NOT_FOUND)
                if (timestamp <= active.timestamp) {
                    return@withTransaction HistoryMutationResult.Failure(HistoryValidationError.CHECKOUT_BEFORE_CHECKIN)
                }
                val checkout = PlaceEventEntity(
                    sessionUuid = active.sessionUuid,
                    placeId = placeUuid,
                    eventType = PlaceEventTypes.CHECK_OUT,
                    timestamp = timestamp,
                    lat = location?.latitude,
                    lon = location?.longitude,
                    accuracyM = location?.accuracyM,
                    source = source,
                    notes = notes.cleanNullable(),
                )
                validateNewEvents(listOf(checkout))?.let { return@withTransaction HistoryMutationResult.Failure(it) }
                val id = dao.insertEvent(checkout)
                val saved = checkout.copy(id = id)
                dao.recalculateStatsBaselines()
                dao.insertAuditLog(
                    HistoryAuditLogEntity(
                        action = AUDIT_EVENT_CREATED,
                        entityType = ENTITY_EVENT,
                        entityId = saved.eventUuid,
                        sessionUuid = saved.sessionUuid,
                        afterJson = eventJson(saved).toString(),
                        source = saved.source,
                    )
                )
                HistoryMutationResult.Success
            }
        }
        if (result is HistoryMutationResult.Success) {
            HubAutoExport.request(context)
        }
        return result
    }

    private suspend fun recordCanonicalVisit(
        placeUuid: String,
        checkInAt: Long,
        checkOutAt: Long?,
        checkInLocation: LocationSample?,
        checkOutLocation: LocationSample?,
        source: String,
        notes: String?,
        mutationSource: String,
    ): HistoryMutationResult {
        if (checkInAt <= 0L) return HistoryMutationResult.Failure(HistoryValidationError.INVALID_TIMESTAMP)
        if (checkOutAt != null && checkOutAt <= checkInAt) {
            return HistoryMutationResult.Failure(HistoryValidationError.CHECKOUT_BEFORE_CHECKIN)
        }
        val result = DatabaseMutationCoordinator.mutex.withLock {
            database.withTransaction {
                dao.getPlace(placeUuid)
                    ?: return@withTransaction HistoryMutationResult.Failure(HistoryValidationError.EVENT_NOT_FOUND)
                val sessionUuid = UUID.randomUUID().toString()
                val checkIn = PlaceEventEntity(
                    sessionUuid = sessionUuid,
                    placeId = placeUuid,
                    eventType = PlaceEventTypes.CHECK_IN,
                    timestamp = checkInAt,
                    lat = checkInLocation?.latitude,
                    lon = checkInLocation?.longitude,
                    accuracyM = checkInLocation?.accuracyM,
                    source = source,
                    notes = notes.cleanNullable(),
                )
                val newEvents = buildList {
                    add(checkIn)
                    if (checkOutAt != null) {
                        add(
                            PlaceEventEntity(
                                sessionUuid = sessionUuid,
                                placeId = placeUuid,
                                eventType = PlaceEventTypes.CHECK_OUT,
                                timestamp = checkOutAt,
                                lat = checkOutLocation?.latitude,
                                lon = checkOutLocation?.longitude,
                                accuracyM = checkOutLocation?.accuracyM,
                                source = source,
                                notes = notes.cleanNullable(),
                            )
                        )
                    }
                }
                validateNewEvents(newEvents)?.let { return@withTransaction HistoryMutationResult.Failure(it) }
                val saved = newEvents.map { event ->
                    val id = dao.insertEvent(event)
                    event.copy(id = id)
                }
                dao.recalculateStatsBaselines()
                saved.forEach { event ->
                    dao.insertAuditLog(
                        HistoryAuditLogEntity(
                            action = AUDIT_EVENT_CREATED,
                            entityType = ENTITY_EVENT,
                            entityId = event.eventUuid,
                            sessionUuid = event.sessionUuid,
                            afterJson = eventJson(event).toString(),
                            source = event.source,
                        )
                    )
                }
                HistoryMutationResult.Success
            }
        }
        if (result is HistoryMutationResult.Success) HubAutoExport.request(context)
        return result
    }

    private suspend fun validateNewEvents(newEvents: List<PlaceEventEntity>): HistoryValidationError? {
        val existing = dao.listEvents()
        val now = System.currentTimeMillis()
        val existingSessions = HistorySessionCalculator.sessions(existing, now)
        val newCheckIn = newEvents.firstOrNull { it.eventType == PlaceEventTypes.CHECK_IN }
        val newCheckOut = newEvents.firstOrNull { it.eventType == PlaceEventTypes.CHECK_OUT }
        if (newCheckIn != null) {
            val duplicate = existingSessions.any { session ->
                session.placeId == newCheckIn.placeId &&
                    session.startMs == newCheckIn.timestamp &&
                    session.endMs == newCheckOut?.timestamp
            }
            if (duplicate) return HistoryValidationError.DUPLICATE
        }
        val candidateEvents = existing + newEvents
        val candidateSessions = HistorySessionCalculator.sessions(candidateEvents, now)
        if (candidateSessions.any { HistorySessionAnomaly.NEGATIVE_DURATION in it.anomalies }) {
            return HistoryValidationError.CHECKOUT_BEFORE_CHECKIN
        }
        if (candidateSessions.count { it.startMs != null && it.endMs == null } > 1) {
            return HistoryValidationError.OVERLAP
        }
        val newSessionUuids = newEvents.map { it.sessionUuid }.toSet()
        if (overlapScoreForSessions(candidateSessions, newSessionUuids, now) > 0L) {
            return HistoryValidationError.OVERLAP
        }
        return null
    }

    suspend fun editHistoryEvent(
        eventId: Long,
        newTimestamp: Long,
        notes: String?,
        reason: String? = null,
        source: String = "Luoghi",
    ): HistoryMutationResult {
        if (newTimestamp <= 0L) return HistoryMutationResult.Failure(HistoryValidationError.INVALID_TIMESTAMP)
        val result = DatabaseMutationCoordinator.mutex.withLock {
            database.withTransaction {
                val before = dao.getEvent(eventId)
                ?: return@withTransaction HistoryMutationResult.Failure(HistoryValidationError.EVENT_NOT_FOUND)
                val after = before.copy(
                timestamp = newTimestamp,
                notes = notes.cleanNullable(),
            )
                validateEventReplacement(before, after)?.let { error ->
                    return@withTransaction HistoryMutationResult.Failure(error)
                }
                dao.updateEvent(after)
                val action = HistoryActionEntity(
                actionType = ACTION_EVENT_UPDATE,
                entityId = before.eventUuid,
                sessionUuid = before.sessionUuid,
                beforeJson = eventJson(before).toString(),
                afterJson = eventJson(after).toString(),
                source = source,
                reason = reason.cleanNullable(),
            )
                dao.insertHistoryAction(action)
                dao.insertAuditLog(
                HistoryAuditLogEntity(
                    actionUuid = action.actionUuid,
                    action = AUDIT_EVENT_MODIFIED,
                    entityType = ENTITY_EVENT,
                    entityId = before.eventUuid,
                    sessionUuid = before.sessionUuid,
                    beforeJson = action.beforeJson,
                    afterJson = action.afterJson,
                    source = source,
                    reason = reason.cleanNullable(),
                )
            )
                dao.recalculateStatsBaselines()
                HistoryMutationResult.Success
            }
        }
        if (result is HistoryMutationResult.Success) {
            HubAutoExport.request(context)
        }
        return result
    }

    suspend fun deleteHistoryEvent(
        eventId: Long,
        reason: String? = null,
        source: String = "Luoghi",
    ): HistoryMutationResult {
        val result = DatabaseMutationCoordinator.mutex.withLock {
            database.withTransaction {
                val before = dao.getEvent(eventId)
                ?: return@withTransaction HistoryMutationResult.Failure(HistoryValidationError.EVENT_NOT_FOUND)
            val sessionEvents = dao.eventsForSession(before.sessionUuid)
            val wouldOrphanCheckout = (before.eventType == PlaceEventTypes.CHECK_IN) &&
                sessionEvents.any { it.eventType == PlaceEventTypes.CHECK_OUT }
            if (wouldOrphanCheckout) {
                return@withTransaction HistoryMutationResult.Failure(HistoryValidationError.ORPHAN_CHECKOUT)
            }
            val action = HistoryActionEntity(
                actionType = ACTION_EVENT_DELETE,
                entityId = before.eventUuid,
                sessionUuid = before.sessionUuid,
                beforeJson = eventJson(before).toString(),
                afterJson = null,
                source = source,
                reason = reason.cleanNullable(),
            )
            val deleted = dao.deleteEventByUuid(before.eventUuid)
            if (deleted == 0) {
                return@withTransaction HistoryMutationResult.Failure(HistoryValidationError.EVENT_NOT_FOUND)
            }
            dao.insertHistoryAction(action)
            dao.insertAuditLog(
                HistoryAuditLogEntity(
                    actionUuid = action.actionUuid,
                    action = AUDIT_EVENT_DELETED,
                    entityType = ENTITY_EVENT,
                    entityId = before.eventUuid,
                    sessionUuid = before.sessionUuid,
                    beforeJson = action.beforeJson,
                    source = source,
                    reason = reason.cleanNullable(),
                )
            )
            dao.recalculateStatsBaselines()
                HistoryMutationResult.Success
            }
        }
        if (result is HistoryMutationResult.Success) {
            HubAutoExport.request(context)
        }
        return result
    }

    suspend fun deleteHistorySession(
        sessionUuid: String,
        reason: String? = null,
        source: String = "Luoghi",
    ): HistoryMutationResult {
        val result = DatabaseMutationCoordinator.mutex.withLock {
            database.withTransaction {
                val before = dao.eventsForSession(sessionUuid)
            if (before.isEmpty()) {
                return@withTransaction HistoryMutationResult.Failure(HistoryValidationError.SESSION_NOT_FOUND)
            }
            val action = HistoryActionEntity(
                actionType = ACTION_SESSION_DELETE,
                entityId = sessionUuid,
                sessionUuid = sessionUuid,
                beforeJson = eventsJson(before).toString(),
                afterJson = null,
                source = source,
                reason = reason.cleanNullable(),
            )
            val deleted = dao.deleteEventsBySessionUuid(sessionUuid)
            if (deleted == 0) {
                return@withTransaction HistoryMutationResult.Failure(HistoryValidationError.SESSION_NOT_FOUND)
            }
            dao.insertHistoryAction(action)
            dao.insertAuditLog(
                HistoryAuditLogEntity(
                    actionUuid = action.actionUuid,
                    action = AUDIT_SESSION_DELETED,
                    entityType = ENTITY_SESSION,
                    entityId = sessionUuid,
                    sessionUuid = sessionUuid,
                    beforeJson = action.beforeJson,
                    source = source,
                    reason = reason.cleanNullable(),
                )
            )
            dao.recalculateStatsBaselines()
                HistoryMutationResult.Success
            }
        }
        if (result is HistoryMutationResult.Success) {
            HubAutoExport.request(context)
        }
        return result
    }

    suspend fun undoLatestHistoryAction(source: String = "Luoghi"): HistoryMutationResult {
        val result = DatabaseMutationCoordinator.mutex.withLock {
            database.withTransaction {
                val action = dao.latestUndoableAction()
                ?: return@withTransaction HistoryMutationResult.Failure(HistoryValidationError.NOTHING_TO_UNDO)
            when (action.actionType) {
                ACTION_EVENT_UPDATE -> dao.insertEvent(eventFromJson(action.beforeJson))
                ACTION_EVENT_DELETE -> dao.insertEvent(eventFromJson(action.beforeJson))
                ACTION_SESSION_DELETE -> eventsFromJson(action.beforeJson).forEach { dao.insertEvent(it) }
                else -> return@withTransaction HistoryMutationResult.Failure(HistoryValidationError.NOTHING_TO_UNDO)
            }
            dao.updateHistoryActionStatus(action.actionUuid, ACTION_STATUS_UNDONE, System.currentTimeMillis())
            dao.insertAuditLog(
                HistoryAuditLogEntity(
                    actionUuid = action.actionUuid,
                    action = AUDIT_UNDO,
                    entityType = action.entityTypeForAudit(),
                    entityId = action.entityId,
                    sessionUuid = action.sessionUuid,
                    beforeJson = action.afterJson,
                    afterJson = action.beforeJson,
                    source = source,
                    reason = action.reason,
                )
            )
            dao.recalculateStatsBaselines()
                HistoryMutationResult.Success
            }
        }
        if (result is HistoryMutationResult.Success) {
            HubAutoExport.request(context)
        }
        return result
    }

    suspend fun redoLatestHistoryAction(source: String = "Luoghi"): HistoryMutationResult {
        val result = DatabaseMutationCoordinator.mutex.withLock {
            database.withTransaction {
                val action = dao.latestRedoableAction()
                ?: return@withTransaction HistoryMutationResult.Failure(HistoryValidationError.NOTHING_TO_REDO)
            when (action.actionType) {
                ACTION_EVENT_UPDATE -> dao.insertEvent(eventFromJson(requireNotNull(action.afterJson)))
                ACTION_EVENT_DELETE -> dao.deleteEventByUuid(action.entityId)
                ACTION_SESSION_DELETE -> dao.deleteEventsBySessionUuid(requireNotNull(action.sessionUuid))
                else -> return@withTransaction HistoryMutationResult.Failure(HistoryValidationError.NOTHING_TO_REDO)
            }
            dao.updateHistoryActionStatus(action.actionUuid, ACTION_STATUS_APPLIED, System.currentTimeMillis())
            dao.insertAuditLog(
                HistoryAuditLogEntity(
                    actionUuid = action.actionUuid,
                    action = AUDIT_REDO,
                    entityType = action.entityTypeForAudit(),
                    entityId = action.entityId,
                    sessionUuid = action.sessionUuid,
                    beforeJson = action.beforeJson,
                    afterJson = action.afterJson,
                    source = source,
                    reason = action.reason,
                )
            )
            dao.recalculateStatsBaselines()
                HistoryMutationResult.Success
            }
        }
        if (result is HistoryMutationResult.Success) {
            HubAutoExport.request(context)
        }
        return result
    }

    private suspend fun normalizedEventForInsert(event: PlaceEventEntity): PlaceEventEntity {
        val eventUuid = event.eventUuid.ifBlank { UUID.randomUUID().toString() }
        val sessionUuid = when (event.eventType) {
            PlaceEventTypes.CHECK_IN -> event.sessionUuid.takeUnless { it.isBlank() } ?: eventUuid
            PlaceEventTypes.CHECK_OUT -> {
                event.sessionUuid.takeIf { (it.isNotBlank()) && (it != event.eventUuid) }
                    ?: dao.getActiveVisitEvent()
                        ?.takeIf { it.placeId == event.placeId }
                        ?.sessionUuid
                    ?: eventUuid
            }
            else -> event.sessionUuid.takeUnless { it.isBlank() } ?: eventUuid
        }
        return event.copy(eventUuid = eventUuid, sessionUuid = sessionUuid)
    }

    private suspend fun validateEventReplacement(
        before: PlaceEventEntity,
        after: PlaceEventEntity,
    ): HistoryValidationError? {
        val existingEvents = dao.listEvents()
        val candidateEvents = existingEvents.map { if (it.eventUuid == before.eventUuid) after else it }
        val now = System.currentTimeMillis()
        val candidateSession = HistorySessionCalculator.sessions(candidateEvents, now)
            .firstOrNull { it.sessionUuid == after.sessionUuid }
        val anomalies = candidateSession?.anomalies.orEmpty()
        if (HistorySessionAnomaly.NEGATIVE_DURATION in anomalies) {
            return HistoryValidationError.CHECKOUT_BEFORE_CHECKIN
        }
        val affectedSessions = setOf(before.sessionUuid, after.sessionUuid)
        val beforeScore = overlapScoreForSessions(
            sessions = HistorySessionCalculator.sessions(existingEvents, now),
            sessionUuids = affectedSessions,
            nowMs = now,
        )
        val afterScore = overlapScoreForSessions(
            sessions = HistorySessionCalculator.sessions(candidateEvents, now),
            sessionUuids = affectedSessions,
            nowMs = now,
        )
        if (afterScore > beforeScore) {
            return HistoryValidationError.OVERLAP
        }
        return null
    }

    private fun overlapScoreForSessions(
        sessions: List<com.gernalix.luoghi.capsules.checkin.PlaceHistorySession>,
        sessionUuids: Set<String>,
        nowMs: Long,
    ): Long {
        val targetSessions = sessions.filter { it.sessionUuid in sessionUuids && it.startMs != null }
        if (targetSessions.isEmpty()) return 0L
        return targetSessions.sumOf { target ->
            sessions
                .filter { it.sessionUuid != target.sessionUuid && it.startMs != null }
                .sumOf { other -> overlapDuration(target.startMs!!, target.endMs ?: nowMs, other.startMs!!, other.endMs ?: nowMs) }
        }
    }

    private fun overlapDuration(startA: Long, endA: Long, startB: Long, endB: Long): Long =
        (minOf(endA, endB) - maxOf(startA, startB)).coerceAtLeast(0L)

    private fun HistoryActionEntity.entityTypeForAudit(): String =
        if (actionType == ACTION_SESSION_DELETE) ENTITY_SESSION else ENTITY_EVENT

    private fun eventJson(event: PlaceEventEntity): JSONObject =
        JSONObject()
            .put("id", event.id)
            .put("event_uuid", event.eventUuid)
            .put("session_uuid", event.sessionUuid)
            .put("place_id", event.placeId)
            .put("event_type", event.eventType)
            .put("timestamp", event.timestamp)
            .putNullable("lat", event.lat)
            .putNullable("lon", event.lon)
            .putNullable("accuracy_m", event.accuracyM)
            .put("source", event.source)
            .putNullable("notes", event.notes)

    private fun eventsJson(events: List<PlaceEventEntity>): JSONArray =
        JSONArray().apply {
            events.sortedWith(compareBy<PlaceEventEntity> { it.timestamp }.thenBy { it.id })
                .forEach { put(eventJson(it)) }
        }

    private fun eventFromJson(json: String): PlaceEventEntity {
        val obj = JSONObject(json)
        return PlaceEventEntity(
            id = obj.optLong("id", 0L),
            eventUuid = obj.optNullableString("event_uuid") ?: UUID.randomUUID().toString(),
            sessionUuid = obj.optNullableString("session_uuid") ?: UUID.randomUUID().toString(),
            placeId = obj.getString("place_id"),
            eventType = obj.getString("event_type"),
            timestamp = obj.getLong("timestamp"),
            lat = obj.optNullableDouble("lat"),
            lon = obj.optNullableDouble("lon"),
            accuracyM = obj.optNullableDouble("accuracy_m"),
            source = obj.optNullableString("source") ?: "Luoghi",
            notes = obj.optNullableString("notes"),
        )
    }

    private fun eventsFromJson(json: String): List<PlaceEventEntity> {
        val array = JSONArray(json)
        return (0 until array.length()).map { index -> eventFromJson(array.getJSONObject(index).toString()) }
    }

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject =
        put(key, value ?: JSONObject.NULL)

    private fun JSONObject.optNullableString(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null

    private fun JSONObject.optNullableDouble(key: String): Double? =
        if (has(key) && !isNull(key)) getDouble(key) else null

    suspend fun createAlias(placeUuid: String, alias: String, appScope: String?): Long {
        val now = System.currentTimeMillis()
        val id = DatabaseMutationCoordinator.mutex.withLock {
            dao.insertAlias(
                PlaceAliasEntity(
                    placeUuid = placeUuid,
                    alias = alias.trim(),
                    appScope = appScope.cleanNullable(),
                    createdAt = now,
                    updatedAt = now,
                )
            )
        }
        HubAutoExport.request(context)
        return id
    }

    suspend fun updateAlias(id: Long, placeUuid: String, alias: String, appScope: String?): Long {
        val now = System.currentTimeMillis()
        val rowId = DatabaseMutationCoordinator.mutex.withLock {
            dao.insertAlias(
                PlaceAliasEntity(
                    id = id,
                    placeUuid = placeUuid,
                    alias = alias.trim(),
                    appScope = appScope.cleanNullable(),
                    createdAt = now,
                    updatedAt = now,
                )
            )
        }
        HubAutoExport.request(context)
        return rowId
    }

    fun deleteAlias(id: Long): Int {
        val deleted = runBlocking(Dispatchers.IO) {
            DatabaseMutationCoordinator.mutex.withLock { dao.deleteAliasByIdBlocking(id) }
        }
        if (deleted > 0) HubAutoExport.request(context)
        return deleted
    }

    suspend fun createLink(placeUuid: String, ownerApp: String, ownerType: String, ownerId: String): Long {
        val now = System.currentTimeMillis()
        val id = DatabaseMutationCoordinator.mutex.withLock {
            dao.insertLink(
                PlaceLinkEntity(
                    placeUuid = placeUuid,
                    ownerApp = ownerApp,
                    ownerType = ownerType,
                    ownerId = ownerId,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        }
        HubAutoExport.request(context)
        return id
    }

    suspend fun updateLink(id: Long, placeUuid: String, ownerApp: String, ownerType: String, ownerId: String): Long {
        val now = System.currentTimeMillis()
        val rowId = DatabaseMutationCoordinator.mutex.withLock {
            dao.insertLink(
                PlaceLinkEntity(
                    id = id,
                    placeUuid = placeUuid,
                    ownerApp = ownerApp,
                    ownerType = ownerType,
                    ownerId = ownerId,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        }
        HubAutoExport.request(context)
        return rowId
    }

    fun deleteLink(id: Long): Int {
        val deleted = runBlocking(Dispatchers.IO) {
            DatabaseMutationCoordinator.mutex.withLock { dao.deleteLinkByIdBlocking(id) }
        }
        if (deleted > 0) HubAutoExport.request(context)
        return deleted
    }

    companion object {
        private const val ACTION_EVENT_UPDATE = "EVENT_UPDATE"
        private const val ACTION_EVENT_DELETE = "EVENT_DELETE"
        private const val ACTION_SESSION_DELETE = "SESSION_DELETE"
        private const val ACTION_STATUS_APPLIED = "APPLIED"
        private const val ACTION_STATUS_UNDONE = "UNDONE"
        private const val AUDIT_EVENT_CREATED = "EVENT_CREATED"
        private const val AUDIT_EVENT_MODIFIED = "EVENT_MODIFIED"
        private const val AUDIT_EVENT_DELETED = "EVENT_DELETED"
        private const val AUDIT_SESSION_DELETED = "SESSION_DELETED"
        private const val AUDIT_UNDO = "UNDO"
        private const val AUDIT_REDO = "REDO"
        private const val ENTITY_EVENT = "EVENT"
        private const val ENTITY_SESSION = "SESSION"

        fun isValidUuid(value: String): Boolean =
            runCatching { UUID.fromString(value) }.isSuccess
    }
}

private fun String?.cleanNullable(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private fun sanitizeAttemptError(message: String?): String? =
    message
        ?.lineSequence()
        ?.firstOrNull()
        ?.replace(Regex("""[/?][^\s:]+"""), "<path>")
        ?.take(160)
