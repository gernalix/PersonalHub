package com.gernalix.sostanze.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SostanzeDao {
    @Query("SELECT * FROM substances ORDER BY archived ASC, prn ASC, name COLLATE NOCASE ASC")
    fun observeSubstances(): Flow<List<SubstanceEntity>>

    @Query("SELECT * FROM intake_events ORDER BY timestamp_ms DESC LIMIT 500")
    fun observeRecentIntakes(): Flow<List<IntakeEventEntity>>

    @Query("SELECT * FROM intake_events ORDER BY timestamp_ms DESC")
    fun observeAllIntakes(): Flow<List<IntakeEventEntity>>

    @Query("SELECT * FROM stock_adjustments ORDER BY timestamp_ms DESC LIMIT 500")
    fun observeStockAdjustments(): Flow<List<StockAdjustmentEntity>>

    @Query("SELECT * FROM prescriptions ORDER BY prescription_epoch_day DESC, id DESC")
    fun observePrescriptions(): Flow<List<PrescriptionEntity>>

    @Query("SELECT * FROM interaction_rules ORDER BY id ASC")
    fun observeInteractionRules(): Flow<List<InteractionRuleEntity>>

    @Query("SELECT * FROM interaction_targets ORDER BY id ASC")
    fun observeInteractionTargets(): Flow<List<InteractionTargetEntity>>

    @Query("SELECT * FROM notification_state ORDER BY scheduled_for_ms ASC")
    fun observeNotificationState(): Flow<List<NotificationStateEntity>>

    @Query("SELECT * FROM macros WHERE archived = 0 ORDER BY name COLLATE NOCASE ASC")
    fun observeMacros(): Flow<List<MacroEntity>>

    @Query("SELECT * FROM macro_items ORDER BY macro_id ASC, id ASC")
    fun observeMacroItems(): Flow<List<MacroItemEntity>>

    @Query("SELECT * FROM substances WHERE id = :id")
    suspend fun substanceById(id: Long): SubstanceEntity?

    @Query("SELECT COUNT(*) FROM substances")
    suspend fun substanceCount(): Int

    @Query("SELECT COUNT(*) FROM macros")
    suspend fun macroCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSubstance(substance: SubstanceEntity): Long

    @Update
    suspend fun updateSubstance(substance: SubstanceEntity)

    @Insert
    suspend fun insertIntake(event: IntakeEventEntity): Long

    @Delete
    suspend fun deleteIntake(event: IntakeEventEntity)

    @Query("SELECT * FROM intake_events WHERE id = :id")
    suspend fun intakeById(id: Long): IntakeEventEntity?

    @Query("SELECT * FROM intake_events WHERE substance_id = :substanceId ORDER BY timestamp_ms DESC LIMIT 1")
    suspend fun lastIntakeFor(substanceId: Long): IntakeEventEntity?

    @Query("SELECT * FROM intake_events WHERE id IN (:ids)")
    suspend fun intakesByIds(ids: List<Long>): List<IntakeEventEntity>

    @Query("SELECT * FROM intake_events WHERE timestamp_ms BETWEEN :startMs AND :endMs ORDER BY timestamp_ms DESC")
    suspend fun intakesBetween(startMs: Long, endMs: Long): List<IntakeEventEntity>

    @Query("UPDATE substances SET stock_current = :stock WHERE id = :substanceId")
    suspend fun updateStock(substanceId: Long, stock: Double)

    @Query("UPDATE substances SET archived = 1 WHERE id = :substanceId")
    suspend fun archiveSubstance(substanceId: Long)

    @Insert
    suspend fun insertStockAdjustment(adjustment: StockAdjustmentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPrescription(prescription: PrescriptionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInteractionRule(rule: InteractionRuleEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInteractionTarget(target: InteractionTargetEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNotificationState(state: NotificationStateEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSetting(setting: SettingEntity)

    @Query("SELECT value FROM settings WHERE `key` = :key")
    suspend fun settingValue(key: String): String?

    @Query("SELECT * FROM settings ORDER BY `key` ASC")
    suspend fun allSettings(): List<SettingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSettings(items: List<SettingEntity>)

    @Query("DELETE FROM settings")
    suspend fun deleteAllSettings()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMacro(macro: MacroEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMacroItem(item: MacroItemEntity): Long

    @Query("SELECT * FROM macro_items WHERE macro_id = :macroId ORDER BY id ASC")
    suspend fun macroItems(macroId: Long): List<MacroItemEntity>

    @Query("DELETE FROM intake_events")
    suspend fun deleteAllIntakes()

    @Query("DELETE FROM stock_adjustments")
    suspend fun deleteAllStockAdjustments()

    @Query("DELETE FROM prescriptions")
    suspend fun deleteAllPrescriptions()

    @Query("DELETE FROM interaction_targets")
    suspend fun deleteAllInteractionTargets()

    @Query("DELETE FROM interaction_rules")
    suspend fun deleteAllInteractionRules()

    @Query("DELETE FROM notification_state")
    suspend fun deleteAllNotificationState()

    @Query("DELETE FROM macro_items")
    suspend fun deleteAllMacroItems()

    @Query("DELETE FROM macros")
    suspend fun deleteAllMacros()

    @Query("DELETE FROM substances")
    suspend fun deleteAllSubstances()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubstances(items: List<SubstanceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIntakes(items: List<IntakeEventEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStockAdjustments(items: List<StockAdjustmentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrescriptions(items: List<PrescriptionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInteractionRules(items: List<InteractionRuleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInteractionTargets(items: List<InteractionTargetEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotificationState(items: List<NotificationStateEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMacros(items: List<MacroEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMacroItems(items: List<MacroItemEntity>)
}
