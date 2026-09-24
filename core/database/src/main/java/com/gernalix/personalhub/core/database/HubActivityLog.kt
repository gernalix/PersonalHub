package com.gernalix.personalhub.core.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

object HubActivityStatus {
    const val ACTIVE = "ACTIVE"
    const val REVERTED = "REVERTED"
    const val CONFLICT = "CONFLICT"
    const val NON_REVERSIBLE = "NON_REVERSIBLE"
}

object HubActivityPayloadKind {
    const val ROW_V1 = "row-v1"
    const val PEOPLE_EVENT_V1 = "people-event-v1"
    const val TIMER_AUDIT_V1 = "timer-audit-v1"
    const val PLACES_AUDIT_V1 = "places-audit-v1"
}

@Entity(
    tableName = "hub_activity_log",
    indices = [
        Index(value = ["occurred_at", "id"]),
        Index(value = ["module_id", "occurred_at"]),
        Index(value = ["module_id", "entity_kind", "entity_id"]),
        Index(value = ["status", "occurred_at"]),
        Index(value = ["group_id", "occurred_at"]),
        Index(value = ["reverts_activity_id"]),
    ],
)
data class HubActivityEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "module_id") val moduleId: String,
    @ColumnInfo(name = "action") val action: String,
    @ColumnInfo(name = "entity_kind") val entityKind: String? = null,
    @ColumnInfo(name = "entity_id") val entityId: String? = null,
    @ColumnInfo(name = "entity_label") val entityLabel: String? = null,
    @ColumnInfo(name = "detail_key") val detailKey: String? = null,
    @ColumnInfo(name = "detail_value") val detailValue: String? = null,
    @ColumnInfo(name = "origin") val origin: String = "user",
    @ColumnInfo(name = "is_system") val isSystem: Boolean = false,
    @ColumnInfo(name = "source_table") val sourceTable: String,
    @ColumnInfo(name = "source_row_key") val sourceRowKey: String? = null,
    @ColumnInfo(name = "payload_kind") val payloadKind: String? = null,
    @ColumnInfo(name = "payload_columns") val payloadColumns: String? = null,
    @ColumnInfo(name = "before_payload") val beforePayload: String? = null,
    @ColumnInfo(name = "after_payload") val afterPayload: String? = null,
    @ColumnInfo(name = "payload_version") val payloadVersion: Int = 1,
    @ColumnInfo(name = "app_version") val appVersion: Long = 0,
    @ColumnInfo(name = "group_id") val groupId: String? = null,
    @ColumnInfo(name = "reversible") val reversible: Boolean = false,
    @ColumnInfo(name = "status") val status: String = HubActivityStatus.ACTIVE,
    @ColumnInfo(name = "reverted_at") val revertedAt: Long? = null,
    @ColumnInfo(name = "reverts_activity_id") val revertsActivityId: String? = null,
)

@Dao
interface HubActivityDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(activity: HubActivityEntity)

    @Query(
        """
        SELECT * FROM hub_activity_log
        WHERE (:moduleId IS NULL OR module_id = :moduleId)
          AND (:includeSystem = 1 OR is_system = 0)
          AND (
            :beforeOccurredAt IS NULL
            OR occurred_at < :beforeOccurredAt
            OR (occurred_at = :beforeOccurredAt AND id < COALESCE(:beforeId, ''))
          )
        ORDER BY occurred_at DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun page(
        moduleId: String?,
        includeSystem: Int,
        beforeOccurredAt: Long?,
        beforeId: String?,
        limit: Int,
    ): List<HubActivityEntity>

    @Query(
        """
        SELECT * FROM hub_activity_log
        WHERE (:allModules = 1 OR module_id IN (:moduleIds))
          AND (:includeSystem = 1 OR is_system = 0)
          AND (:fromMs IS NULL OR occurred_at >= :fromMs)
          AND (:toMs IS NULL OR occurred_at <= :toMs)
          AND (:entityKind IS NULL OR entity_kind = :entityKind)
          AND (:entityId IS NULL OR entity_id = :entityId)
        ORDER BY occurred_at DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun search(
        moduleIds: List<String>,
        allModules: Int,
        includeSystem: Int,
        fromMs: Long?,
        toMs: Long?,
        entityKind: String?,
        entityId: String?,
        limit: Int,
    ): List<HubActivityEntity>

    @Query("SELECT * FROM hub_activity_log WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): HubActivityEntity?

    @Query(
        """
        UPDATE hub_activity_log
        SET status = :status, reverted_at = :revertedAt
        WHERE id = :id
        """,
    )
    suspend fun updateStatus(id: String, status: String, revertedAt: Long?)

    @Query("UPDATE hub_activity_log SET entity_label = :label WHERE id = :id AND entity_label IS NULL")
    suspend fun cacheLabel(id: String, label: String)
}
