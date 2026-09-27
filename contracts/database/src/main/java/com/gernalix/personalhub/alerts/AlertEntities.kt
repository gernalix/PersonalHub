package com.gernalix.personalhub.alerts

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Canonical alert-rule persistence shared by every PersonalHub module that supports alerts.
 *
 * Domain-specific options live in [configJson]. Tag/selector targets are normalized in
 * [AlertRuleTargetEntity]. All successful deliveries are appended to [AlertFiringEntity].
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
    @ColumnInfo(name = "config_json", defaultValue = "'{}'") val configJson: String = "{}",
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)

@Entity(
    tableName = "alert_rule_targets",
    primaryKeys = ["rule_id", "target_id"],
    foreignKeys = [
        ForeignKey(
            entity = AlertRuleEntity::class,
            parentColumns = ["id"],
            childColumns = ["rule_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("rule_id"), Index("target_id")],
)
data class AlertRuleTargetEntity(
    @ColumnInfo(name = "rule_id") val ruleId: String,
    @ColumnInfo(name = "target_id") val targetId: String,
)

@Entity(
    tableName = "alert_firings",
    primaryKeys = ["id"],
    indices = [
        Index("rule_id"),
        Index("domain"),
        Index("entity_id"),
        Index(value = ["fired_at", "id"], orders = [Index.Order.DESC, Index.Order.DESC]),
    ],
)
data class AlertFiringEntity(
    val id: String,
    @ColumnInfo(name = "rule_id") val ruleId: String,
    val domain: String,
    val trigger: String,
    @ColumnInfo(name = "entity_id") val entityId: String? = null,
    @ColumnInfo(name = "entity_label") val entityLabel: String? = null,
    @ColumnInfo(name = "tag_names") val tagNames: String = "",
    val delivery: String,
    val message: String,
    @ColumnInfo(name = "fired_at") val firedAt: Long,
)
