package com.gernalix.personalhub.alerts

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.gernalix.luoghi.data.PlaceTagEntity

/**
 * Canonical cross-module alert rule.
 *
 * Tag targets stay domain-specific: Places references PlaceTagEntity while Timer keeps its
 * independent Timer tag identifiers. Equal numeric IDs across the two domains are never equivalent.
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
            entity = PlaceTagEntity::class,
            parentColumns = ["id"],
            childColumns = ["place_tag_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("rule_id"), Index("place_tag_id")],
)
data class AlertPlaceTagTargetEntity(
    @ColumnInfo(name = "rule_id") val ruleId: String,
    @ColumnInfo(name = "place_tag_id") val placeTagId: Long,
)
