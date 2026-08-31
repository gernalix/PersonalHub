package com.gernalix.luoghi.data

import android.content.Context
import androidx.room.withTransaction
import com.gernalix.luoghi.capsules.checkin.HistoryMutationResult
import com.gernalix.luoghi.capsules.checkin.HistorySessionAnomaly
import com.gernalix.luoghi.capsules.checkin.HistorySessionCalculator
import com.gernalix.luoghi.capsules.checkin.HistoryValidationError
import com.gernalix.luoghi.capsules.checkin.PlaceEventTypes
import com.gernalix.luoghi.capsules.location.LocationSample
import com.gernalix.luoghi.capsules.safexport.PersistentMutationTracker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class PlaceRepository(
    private val context: Context,
    private val database: LuoghiDatabase = LuoghiDatabase.get(context),
    private val dao: PlaceDao = database.placeDao(),
) {
    val places: Flow<List<PlaceEntity>> = dao.observePlaces()
    val events: Flow<List<PlaceEventEntity>> = dao.observeEvents()
    val globalStatsState: Flow<GlobalStatsStateEntity?> = dao.observeGlobalStatsState()
    val latestUndoableHistoryAction: Flow<HistoryActionEntity?> = dao.observeLatestUndoableAction()
    val latestRedoableHistoryAction: Flow<HistoryActionEntity?> = dao.observeLatestRedoableAction()

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
        PersistentMutationTracker.record(context, "places.save")
        return resolvedUuid
    }

    suspend fun deletePlace(uuid: String) {
        DatabaseMutationCoordinator.mutex.withLock { dao.deletePlaceByUuid(uuid) }
        PersistentMutationTracker.record(context, "places.delete")
    }

    suspend fun archivePlace(uuid: String, archived: Boolean = true): Int {
        val updated = DatabaseMutationCoordinator.mutex.withLock {
            dao.setPlaceArchived(uuid, archived, System.currentTimeMillis())
        }
        if (updated > 0) PersistentMutationTracker.record(context, "places.archive")
        return updated
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
        PersistentMutationTracker.record(context, mutationSource)
        return inserted
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
            PersistentMutationTracker.record(context, "place_events.edit")
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
            PersistentMutationTracker.record(context, "place_events.delete")
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
            PersistentMutationTracker.record(context, "place_sessions.delete")
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
            PersistentMutationTracker.record(context, "history.undo")
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
            PersistentMutationTracker.record(context, "history.redo")
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
        val candidateEvents = dao.listEvents()
            .map { if (it.eventUuid == before.eventUuid) after else it }
        val now = System.currentTimeMillis()
        val candidateSession = HistorySessionCalculator.sessions(candidateEvents, now)
            .firstOrNull { it.sessionUuid == after.sessionUuid }
        val anomalies = candidateSession?.anomalies.orEmpty()
        if (HistorySessionAnomaly.NEGATIVE_DURATION in anomalies) {
            return HistoryValidationError.CHECKOUT_BEFORE_CHECKIN
        }
        if (HistorySessionCalculator.hasOverlap(candidateEvents, after.placeId, now)) {
            return HistoryValidationError.OVERLAP
        }
        return null
    }

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
        PersistentMutationTracker.record(context, "aliases.create")
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
        PersistentMutationTracker.record(context, "aliases.update")
        return rowId
    }

    fun deleteAlias(id: Long): Int {
        val deleted = runBlocking(Dispatchers.IO) {
            DatabaseMutationCoordinator.mutex.withLock { dao.deleteAliasByIdBlocking(id) }
        }
        if (deleted > 0) PersistentMutationTracker.record(context, "aliases.delete")
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
        PersistentMutationTracker.record(context, "links.create")
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
        PersistentMutationTracker.record(context, "links.update")
        return rowId
    }

    fun deleteLink(id: Long): Int {
        val deleted = runBlocking(Dispatchers.IO) {
            DatabaseMutationCoordinator.mutex.withLock { dao.deleteLinkByIdBlocking(id) }
        }
        if (deleted > 0) PersistentMutationTracker.record(context, "links.delete")
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
