package com.gernalix.sostanze.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object SubstanceTypes {
    const val FARMACO = "farmaco"
    const val INTEGRATORE = "integratore"
}

object InteractionTargetKinds {
    const val SPECIFIC = "SPECIFIC"
    const val ALL_PRESENT_AND_FUTURE = "ALL_PRESENT_AND_FUTURE"
}

object InteractionEnforcement {
    const val BLOCK = "BLOCK"
    const val WARN = "WARN"
}

@Entity(
    tableName = "substances",
    indices = [Index("name"), Index(value = ["canonical_name"], unique = true), Index("archived"), Index("type")]
)
data class SubstanceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "canonical_name", defaultValue = "''") val canonicalName: String = "",
    val type: String,
    @ColumnInfo(name = "stock_current") val stockCurrent: Double,
    @ColumnInfo(name = "stock_unit") val stockUnit: String,
    @ColumnInfo(name = "dose_per_intake") val dosePerIntake: Double,
    @ColumnInfo(name = "dose_unit") val doseUnit: String,
    @ColumnInfo(name = "daily_frequency") val dailyFrequency: Int,
    @ColumnInfo(name = "start_epoch_day") val startEpochDay: Long,
    @ColumnInfo(name = "end_epoch_day") val endEpochDay: Long? = null,
    val forever: Boolean = true,
    val archived: Boolean = false,
    val prn: Boolean = false,
    @ColumnInfo(name = "dose_times_csv", defaultValue = "''") val doseTimesCsv: String = "",
    @ColumnInfo(name = "days_mask", defaultValue = "127") val daysMask: Int = 127,
)

@Entity(
    tableName = "intake_events",
    foreignKeys = [
        ForeignKey(
            entity = SubstanceEntity::class,
            parentColumns = ["id"],
            childColumns = ["substance_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("substance_id"), Index("timestamp_ms"), Index("prescription_id")]
)
data class IntakeEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "substance_id") val substanceId: Long,
    @ColumnInfo(name = "timestamp_ms") val timestampMs: Long,
    @ColumnInfo(name = "timestamp_utc", defaultValue = "0") val timestampUtc: Long = 0,
    val dose: Double,
    @ColumnInfo(name = "dose_unit") val doseUnit: String,
    @ColumnInfo(name = "tap_group_id") val tapGroupId: String? = null,
    @ColumnInfo(name = "quantity", defaultValue = "1.0") val quantity: Double = 1.0,
    @ColumnInfo(name = "applied_stock_delta", defaultValue = "0.0") val appliedStockDelta: Double = 0.0,
    @ColumnInfo(name = "prescription_id") val prescriptionId: Long? = null,
)

@Entity(
    tableName = "stock_adjustments",
    foreignKeys = [
        ForeignKey(
            entity = SubstanceEntity::class,
            parentColumns = ["id"],
            childColumns = ["substance_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("substance_id"), Index("timestamp_ms")]
)
data class StockAdjustmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "substance_id") val substanceId: Long,
    @ColumnInfo(name = "timestamp_ms") val timestampMs: Long,
    @ColumnInfo(name = "timestamp_utc", defaultValue = "0") val timestampUtc: Long = 0,
    val delta: Double,
    val note: String? = null,
    @ColumnInfo(name = "resulting_stock") val resultingStock: Double,
)

@Entity(
    tableName = "prescriptions",
    foreignKeys = [
        ForeignKey(
            entity = SubstanceEntity::class,
            parentColumns = ["id"],
            childColumns = ["substance_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("substance_id"), Index("prescription_epoch_day"), Index("order_epoch_day"), Index("doctor_contact_id"), Index("finance_transaction_id")]
)
data class PrescriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "substance_id") val substanceId: Long,
    @ColumnInfo(name = "prescription_epoch_day") val prescriptionEpochDay: Long,
    @ColumnInfo(name = "prescription_date_utc", defaultValue = "") val prescriptionDateUtc: String = "",
    @ColumnInfo(name = "quantity_prescribed") val quantityPrescribed: Double,
    @ColumnInfo(name = "refill_every_months") val refillEveryMonths: Int,
    @ColumnInfo(name = "alert_refill") val alertRefill: Boolean = false,
    @ColumnInfo(name = "order_epoch_day", defaultValue = "0") val orderEpochDay: Long = 0,
    @ColumnInfo(name = "package_dose_count", defaultValue = "0") val packageDoseCount: Int = 0,
    @ColumnInfo(name = "remaining_doses", defaultValue = "0") val remainingDoses: Int = 0,
    @ColumnInfo(name = "dose_mg", defaultValue = "0.0") val doseMg: Double = 0.0,
    @ColumnInfo(name = "frequency_period", defaultValue = "'DAY'") val frequencyPeriod: String = "DAY",
    @ColumnInfo(name = "frequency_count", defaultValue = "1") val frequencyCount: Int = 1,
    @ColumnInfo(name = "doctor_contact_id") val doctorContactId: Long? = null,
    @ColumnInfo(name = "finance_transaction_id") val financeTransactionId: Long? = null,
)

@Entity(
    tableName = "interaction_rules",
    foreignKeys = [
        ForeignKey(
            entity = SubstanceEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_substance_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("source_substance_id")]
)
data class InteractionRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "source_substance_id") val sourceSubstanceId: Long,
    @ColumnInfo(name = "avoid_before_hours") val avoidBeforeHours: Double,
    @ColumnInfo(name = "avoid_after_hours") val avoidAfterHours: Double,
    val enforcement: String = InteractionEnforcement.BLOCK,
)

@Entity(
    tableName = "interaction_targets",
    foreignKeys = [
        ForeignKey(
            entity = InteractionRuleEntity::class,
            parentColumns = ["id"],
            childColumns = ["rule_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = SubstanceEntity::class,
            parentColumns = ["id"],
            childColumns = ["target_substance_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("rule_id"), Index("target_substance_id")]
)
data class InteractionTargetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "rule_id") val ruleId: Long,
    @ColumnInfo(name = "target_substance_id") val targetSubstanceId: Long? = null,
    @ColumnInfo(name = "target_kind") val targetKind: String = InteractionTargetKinds.SPECIFIC,
)

@Entity(
    tableName = "notification_state",
    indices = [Index(value = ["kind", "entity_id", "scheduled_for_ms"], unique = true)]
)
data class NotificationStateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    @ColumnInfo(name = "entity_id") val entityId: Long,
    @ColumnInfo(name = "scheduled_for_ms") val scheduledForMs: Long,
    @ColumnInfo(name = "scheduled_for_utc", defaultValue = "0") val scheduledForUtc: Long = 0,
    @ColumnInfo(name = "sent_at_ms") val sentAtMs: Long? = null,
    @ColumnInfo(name = "sent_at_utc") val sentAtUtc: Long? = null,
)

@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Entity(tableName = "macros", indices = [Index("name"), Index("archived")])
data class MacroEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val archived: Boolean = false,
)

@Entity(
    tableName = "macro_items",
    foreignKeys = [
        ForeignKey(
            entity = MacroEntity::class,
            parentColumns = ["id"],
            childColumns = ["macro_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = SubstanceEntity::class,
            parentColumns = ["id"],
            childColumns = ["substance_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("macro_id"), Index("substance_id")]
)
data class MacroItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "macro_id") val macroId: Long,
    @ColumnInfo(name = "substance_id") val substanceId: Long,
)
