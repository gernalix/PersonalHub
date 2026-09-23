package com.gernalix.luoghi.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "places",
    indices = [
        Index("uuid"),
        Index(value = ["lat", "lon"])
    ]
)
data class PlaceEntity(
    @PrimaryKey val uuid: String = UUID.randomUUID().toString(),
    val nickname: String = "",
    val address: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    @ColumnInfo(name = "radius_m") val radiusM: Double? = null,
    val notes: String? = null,
    /** Canonical original reference. Shared UI derives square previews without modifying it. */
    @ColumnInfo(name = "photo_uri") val photoUri: String? = null,
    @ColumnInfo(name = "source_app") val sourceApp: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    val archived: Boolean = false,
    @ColumnInfo(name = "first_check_in_at_place") val firstCheckInAtPlace: Long? = null,
)

@Entity(
    tableName = "route_distance_cache",
    foreignKeys = [
        ForeignKey(
            entity = PlaceEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["origin_place_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = PlaceEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["destination_place_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("origin_place_id"),
        Index("destination_place_id"),
        Index(
            value = ["origin_place_id", "destination_place_id", "travel_mode", "provider"],
            unique = true,
        )
    ]
)
data class RouteDistanceCacheEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "origin_place_id") val originPlaceId: String,
    @ColumnInfo(name = "destination_place_id") val destinationPlaceId: String,
    @ColumnInfo(name = "travel_mode") val travelMode: String,
    val provider: String,
    @ColumnInfo(name = "distance_meters") val distanceMeters: Long,
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Long,
    @ColumnInfo(name = "computed_at") val computedAt: Long,
    @ColumnInfo(name = "origin_latitude_snapshot") val originLatitudeSnapshot: Double?,
    @ColumnInfo(name = "origin_longitude_snapshot") val originLongitudeSnapshot: Double?,
    @ColumnInfo(name = "destination_latitude_snapshot") val destinationLatitudeSnapshot: Double?,
    @ColumnInfo(name = "destination_longitude_snapshot") val destinationLongitudeSnapshot: Double?,
    @ColumnInfo(name = "origin_version") val originVersion: Long?,
    @ColumnInfo(name = "destination_version") val destinationVersion: Long?,
    val metadata: String? = null,
)

@Entity(tableName = "global_stats_state")
data class GlobalStatsStateEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "first_check_in_at_global") val firstCheckInAtGlobal: Long,
)

@Entity(
    tableName = "place_events",
    foreignKeys = [
        ForeignKey(
            entity = PlaceEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["place_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("event_uuid", unique = true),
        Index("session_uuid"),
        Index("place_id"),
        Index("event_type"),
        Index("timestamp")
    ]
)
data class PlaceEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "event_uuid") val eventUuid: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "session_uuid") val sessionUuid: String = eventUuid,
    @ColumnInfo(name = "place_id") val placeId: String,
    @ColumnInfo(name = "event_type") val eventType: String,
    val timestamp: Long = System.currentTimeMillis(),
    val lat: Double? = null,
    val lon: Double? = null,
    @ColumnInfo(name = "accuracy_m") val accuracyM: Double? = null,
    val source: String = "Luoghi",
    val notes: String? = null,
)

@Entity(
    tableName = "check_in_attempts",
    indices = [
        Index("started_at"),
        Index("finished_at"),
        Index("outcome"),
        Index("matched_place_id"),
    ],
)
data class CheckInAttemptEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "started_at") val startedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "finished_at") val finishedAt: Long? = null,
    val source: String = "Luoghi",
    val stage: String = "STARTED",
    val outcome: String = "IN_PROGRESS",
    val lat: Double? = null,
    val lon: Double? = null,
    @ColumnInfo(name = "accuracy_m") val accuracyM: Double? = null,
    @ColumnInfo(name = "selected_place_id") val selectedPlaceId: String? = null,
    @ColumnInfo(name = "matched_place_id") val matchedPlaceId: String? = null,
    @ColumnInfo(name = "error_code") val errorCode: String? = null,
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
)

@Entity(
    tableName = "check_in_attempt_candidates",
    foreignKeys = [
        ForeignKey(
            entity = CheckInAttemptEntity::class,
            parentColumns = ["id"],
            childColumns = ["attempt_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("attempt_id"),
        Index("place_id"),
        Index(value = ["attempt_id", "place_id"], unique = true),
    ],
)
data class CheckInAttemptCandidateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "attempt_id") val attemptId: String,
    @ColumnInfo(name = "place_id") val placeId: String,
    @ColumnInfo(name = "place_name_snapshot") val placeNameSnapshot: String? = null,
    @ColumnInfo(name = "distance_m") val distanceM: Double,
    @ColumnInfo(name = "threshold_m") val thresholdM: Double,
    val rank: Int,
    val result: String,
)

@Entity(
    tableName = "history_audit_log",
    indices = [
        Index("action"),
        Index("entity_type"),
        Index("entity_id"),
        Index("session_uuid"),
        Index("operated_at"),
    ]
)
data class HistoryAuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "audit_uuid") val auditUuid: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "action_uuid") val actionUuid: String? = null,
    val action: String,
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "session_uuid") val sessionUuid: String? = null,
    @ColumnInfo(name = "operated_at") val operatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "before_json") val beforeJson: String? = null,
    @ColumnInfo(name = "after_json") val afterJson: String? = null,
    val source: String = "Luoghi",
    val reason: String? = null,
)

@Entity(
    tableName = "history_actions",
    indices = [
        Index("action_uuid", unique = true),
        Index("action_type"),
        Index("entity_id"),
        Index("session_uuid"),
        Index("status"),
        Index("created_at"),
    ]
)
data class HistoryActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "action_uuid") val actionUuid: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "action_type") val actionType: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "session_uuid") val sessionUuid: String? = null,
    @ColumnInfo(name = "before_json") val beforeJson: String,
    @ColumnInfo(name = "after_json") val afterJson: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = createdAt,
    val status: String = "APPLIED",
    val source: String = "Luoghi",
    val reason: String? = null,
)

@Entity(
    tableName = "place_aliases",
    foreignKeys = [
        ForeignKey(
            entity = PlaceEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["place_uuid"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("place_uuid"),
        Index("alias"),
        Index("app_scope")
    ]
)
data class PlaceAliasEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "place_uuid") val placeUuid: String,
    val alias: String,
    @ColumnInfo(name = "app_scope") val appScope: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "place_links",
    foreignKeys = [
        ForeignKey(
            entity = PlaceEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["place_uuid"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("place_uuid"),
        Index("owner_app"),
        Index("owner_id"),
        Index(value = ["owner_app", "owner_type", "owner_id"])
    ]
)
data class PlaceLinkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "place_uuid") val placeUuid: String,
    @ColumnInfo(name = "owner_app") val ownerApp: String,
    @ColumnInfo(name = "owner_type") val ownerType: String,
    @ColumnInfo(name = "owner_id") val ownerId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "place_geofence_configs",
    foreignKeys = [
        ForeignKey(
            entity = PlaceEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["place_uuid"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("place_uuid", unique = true),
        Index("enabled"),
        Index("updated_at")
    ]
)
data class PlaceGeofenceConfigEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "place_uuid") val placeUuid: String,
    val enabled: Boolean = false,
    @ColumnInfo(name = "enter_enabled") val enterEnabled: Boolean = true,
    @ColumnInfo(name = "exit_enabled") val exitEnabled: Boolean = true,
    @ColumnInfo(name = "enter_action") val enterAction: String = "NOTIFY",
    @ColumnInfo(name = "exit_action") val exitAction: String = "NOTIFY",
    @ColumnInfo(name = "last_enter_at") val lastEnterAt: Long? = null,
    @ColumnInfo(name = "last_exit_at") val lastExitAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "place_geofence_transition_log",
    indices = [
        Index(value = ["place_uuid", "transition", "bucket"], unique = true),
        Index("created_at")
    ]
)
data class PlaceGeofenceTransitionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "place_uuid") val placeUuid: String,
    val transition: String,
    val bucket: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
)
