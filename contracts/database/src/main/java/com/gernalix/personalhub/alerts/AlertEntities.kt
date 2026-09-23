package com.gernalix.personalhub.alerts

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.gernalix.personalhub.contracts.database.HubTagEntity

/**
 * Canonical alert-rule persistence for domains stored in personalhub.db.
 *
 * Places uses this table today. Timer keeps its existing snapshot persistence for compatibility and
 * adapts those rules to the shared evaluator; Timer tags and Places tags therefore remain completely
 * separate namespaces.
 */
@Entity(
    tableName = "alert_rules",
    primaryKeys = ["id"],
    indices = [
        Index("domain"),
        Index("trigger"),
        Index("target_kind"),
        Index("entity_id"),
        Index("enabled"),
        Index("deleted_at"),
    ],
)
data class AlertRuleEntity(
    val id: String,
    val domain: String,
    val trigger: String,
    @ColumnInfo(name = "target_kind") val targetKind: String,
    @ColumnInfo(name = "entity_id") val entityId: String? = null,
    @ColumnInfo(name = "match_mode") val matchMode: String = "ALL",
    val message: String,
    val scope: String = "ALWAYS",
    val enabled: Boolean = true,
    @ColumnInfo(name = "cooldown_ms") val cooldownMs: Long = 0L,
    @ColumnInfo(name = "last_fired_at") val lastFiredAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)

@Entity(
    tableName = "alert_place_tag_targets",
    primaryKeys = ["rule_id", "place_tag_id"],
    foreignKeys = [
        ForeignKey(
            entity = AlertRuleEntity::class,
            parentColumns = ["id"],
            childColumns = ["rule_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = HubTagEntity::class,
            parentColumns = ["id"],
            childColumns = ["place_tag_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("rule_id"), Index("place_tag_id")],
)
data class AlertPlaceTagTargetEntity(
    @ColumnInfo(name = "rule_id") val ruleId: String,
    @ColumnInfo(name = "place_tag_id") val placeTagId: String,
)
