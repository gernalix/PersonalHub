package com.gernalix.sostanze.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Embedded
import androidx.room.ColumnInfo
import kotlinx.coroutines.flow.Flow

data class DoctorChoice(val id: Long, val name: String)
data class CostChoice(val id: Long, val title: String, val amount: String, val occurredAt: Long)
data class PrescriptionDetail(
    @Embedded val prescription: PrescriptionEntity,
    @ColumnInfo(name = "doctor_name") val doctorName: String?,
    @ColumnInfo(name = "cost_amount") val costAmount: String?,
)
data class IntakeHubView(
    @Embedded val intake: IntakeEventEntity,
    @ColumnInfo(name = "substance_name") val substanceName: String,
)

@Dao
interface SostanzeDao {
    @Query("SELECT c.id AS id, f.value AS name FROM contacts c JOIN contact_fields f ON f.contact_id=c.id AND f.field_type='name' WHERE c.deleted_at IS NULL AND c.archived_at IS NULL AND f.value LIKE '%' || :query || '%' ORDER BY f.value COLLATE NOCASE LIMIT 20")
    suspend fun doctorChoices(query: String): List<DoctorChoice>

    @Query("SELECT t.id AS id, COALESCE(p.name,n.name,'') AS title, t.amount AS amount, CAST(t.occurredAt AS INTEGER) AS occurredAt FROM finance_transactions t LEFT JOIN finance_products p ON p.id=t.productId LEFT JOIN finance_titles n ON n.id=t.titleId WHERE lower(trim(COALESCE(p.name,n.name,'')))=lower(trim(:name)) ORDER BY t.occurredAt DESC,t.id DESC LIMIT 5")
    suspend fun recentMatchingCosts(name: String): List<CostChoice>

    @Query("SELECT value FROM contact_fields WHERE contact_id=:contactId AND field_type='name' ORDER BY is_primary DESC,position,id LIMIT 1")
    suspend fun doctorName(contactId: Long): String?

    @Query("SELECT amount FROM finance_transactions WHERE id=:transactionId")
    suspend fun costAmount(transactionId: Long): String?
    @Query("SELECT * FROM substances ORDER BY archived ASC, prn ASC, name COLLATE NOCASE ASC")
    fun observeSubstances(): Flow<List<SubstanceEntity>>

    @Query("SELECT * FROM substances ORDER BY id")
    suspend fun allSubstances(): List<SubstanceEntity>

    @Query("SELECT * FROM intake_events ORDER BY timestamp_ms DESC LIMIT 500")
    fun observeRecentIntakes(): Flow<List<IntakeEventEntity>>

    @Query("SELECT * FROM intake_events ORDER BY timestamp_ms DESC LIMIT 1000")
    suspend fun recentIntakes(): List<IntakeEventEntity>

    @Query("SELECT * FROM intake_events ORDER BY timestamp_ms DESC")
    fun observeAllIntakes(): Flow<List<IntakeEventEntity>>

    @Query("SELECT * FROM stock_adjustments ORDER BY timestamp_ms DESC LIMIT 500")
    fun observeStockAdjustments(): Flow<List<StockAdjustmentEntity>>

    @Query("SELECT * FROM prescriptions ORDER BY prescription_epoch_day DESC, id DESC")
    fun observePrescriptions(): Flow<List<PrescriptionEntity>>

    @Query("SELECT p.*, (SELECT value FROM contact_fields WHERE contact_id=p.doctor_contact_id AND field_type='name' ORDER BY is_primary DESC,position,id LIMIT 1) AS doctor_name, t.amount AS cost_amount FROM prescriptions p LEFT JOIN finance_transactions t ON t.id=p.finance_transaction_id ORDER BY p.prescription_epoch_day DESC,p.id DESC")
    fun observePrescriptionDetails(): Flow<List<PrescriptionDetail>>

    @Query("SELECT * FROM interaction_rules ORDER BY id ASC")
    fun observeInteractionRules(): Flow<List<InteractionRuleEntity>>

    @Query("SELECT * FROM interaction_rules ORDER BY id")
    suspend fun allInteractionRules(): List<InteractionRuleEntity>

    @Query("SELECT * FROM interaction_targets ORDER BY id ASC")
    fun observeInteractionTargets(): Flow<List<InteractionTargetEntity>>

    @Query("SELECT * FROM interaction_targets ORDER BY id")
    suspend fun allInteractionTargets(): List<InteractionTargetEntity>

    @Query("SELECT * FROM notification_state ORDER BY scheduled_for_ms ASC")
    fun observeNotificationState(): Flow<List<NotificationStateEntity>>

    @Query("SELECT * FROM notification_state ORDER BY scheduled_for_ms ASC")
    suspend fun allNotificationState(): List<NotificationStateEntity>

    @Query("DELETE FROM notification_state WHERE scheduled_for_ms <= :nowMs")
    suspend fun deleteExpiredNotificationState(nowMs: Long)

    @Query("SELECT * FROM macros WHERE archived = 0 ORDER BY name COLLATE NOCASE ASC")
    fun observeMacros(): Flow<List<MacroEntity>>

    @Query("SELECT * FROM macro_items ORDER BY macro_id ASC, id ASC")
    fun observeMacroItems(): Flow<List<MacroItemEntity>>

    @Query("SELECT * FROM substances WHERE id = :id")
    suspend fun substanceById(id: Long): SubstanceEntity?

    @Query("SELECT * FROM substances WHERE id IN (:ids)")
    suspend fun substancesByIds(ids: List<Long>): List<SubstanceEntity>

    @Query("SELECT * FROM substances WHERE name LIKE '%' || :query || '%' ORDER BY archived ASC, name COLLATE NOCASE LIMIT :limit")
    suspend fun searchSubstances(query: String, limit: Int): List<SubstanceEntity>

    @Query("SELECT * FROM substances WHERE lower(trim(name)) = :canonical ORDER BY archived ASC, id ASC")
    suspend fun substancesByCanonicalName(canonical: String): List<SubstanceEntity>

    @Query("SELECT COUNT(*) FROM substances")
    suspend fun substanceCount(): Int

    @Query("SELECT COUNT(*) FROM macros")
    suspend fun macroCount(): Int

    @Insert
    suspend fun insertSubstance(substance: SubstanceEntity): Long

    @Update
    suspend fun updateSubstance(substance: SubstanceEntity)

    @Query("UPDATE substances SET archived = 0 WHERE id = :substanceId")
    suspend fun restoreSubstance(substanceId: Long)

    @Insert
    suspend fun insertIntake(event: IntakeEventEntity): Long

    @Delete
    suspend fun deleteIntake(event: IntakeEventEntity)

    @Update
    suspend fun updateIntake(event: IntakeEventEntity)

    @Query("SELECT * FROM intake_events WHERE id = :id")
    suspend fun intakeById(id: Long): IntakeEventEntity?

    @Query("SELECT COUNT(*) FROM intake_events WHERE tap_group_id = :key")
    suspend fun intakeCountForKey(key: String): Int

    @Query("SELECT * FROM intake_events WHERE substance_id = :substanceId ORDER BY timestamp_ms DESC LIMIT 1")
    suspend fun lastIntakeFor(substanceId: Long): IntakeEventEntity?

    @Query("SELECT * FROM intake_events WHERE id IN (:ids)")
    suspend fun intakesByIds(ids: List<Long>): List<IntakeEventEntity>

    @Query("SELECT i.*,s.name AS substance_name FROM intake_events i JOIN substances s ON s.id=i.substance_id WHERE i.id IN (:ids) ORDER BY i.timestamp_ms DESC,i.id DESC")
    suspend fun intakeHubViews(ids: List<Long>): List<IntakeHubView>

    @Query("SELECT i.*,s.name AS substance_name FROM intake_events i JOIN substances s ON s.id=i.substance_id WHERE s.name LIKE '%' || :query || '%' ORDER BY i.timestamp_ms DESC,i.id DESC LIMIT :limit")
    suspend fun searchIntakeHubViews(query: String, limit: Int): List<IntakeHubView>

    @Query("SELECT i.*,s.name AS substance_name FROM intake_events i JOIN substances s ON s.id=i.substance_id WHERE i.timestamp_ms >= :fromMs AND i.timestamp_ms < :toMs ORDER BY i.timestamp_ms DESC,i.id DESC LIMIT :limit OFFSET :offset")
    suspend fun temporalIntakeHubViews(fromMs: Long, toMs: Long, limit: Int, offset: Int): List<IntakeHubView>

    @Query("SELECT * FROM intake_events WHERE timestamp_ms BETWEEN :startMs AND :endMs ORDER BY timestamp_ms DESC")
    suspend fun intakesBetween(startMs: Long, endMs: Long): List<IntakeEventEntity>

    @Query("SELECT * FROM intake_events WHERE substance_id = :substanceId ORDER BY timestamp_ms DESC, id DESC LIMIT :limit OFFSET :offset")
    suspend fun intakePage(substanceId: Long, limit: Int, offset: Int): List<IntakeEventEntity>

    @Query("SELECT * FROM intake_events ORDER BY timestamp_ms DESC, id DESC LIMIT :limit OFFSET :offset")
    suspend fun historyPage(limit: Int, offset: Int): List<IntakeEventEntity>

    @Query("UPDATE substances SET stock_current = :stock WHERE id = :substanceId")
    suspend fun updateStock(substanceId: Long, stock: Double)

    @Query("UPDATE substances SET archived = 1 WHERE id = :substanceId")
    suspend fun archiveSubstance(substanceId: Long)

    @Insert
    suspend fun insertStockAdjustment(adjustment: StockAdjustmentEntity): Long

    @Insert
    suspend fun insertPrescription(prescription: PrescriptionEntity): Long

    @Update
    suspend fun updatePrescription(prescription: PrescriptionEntity)

    @Query("DELETE FROM prescriptions WHERE id = :id")
    suspend fun deletePrescription(id: Long)

    @Query("SELECT * FROM prescriptions WHERE id = :id")
    suspend fun prescriptionById(id: Long): PrescriptionEntity?

    @Query("SELECT * FROM prescriptions WHERE substance_id=:substanceId ORDER BY order_epoch_day DESC,prescription_epoch_day DESC,id DESC LIMIT 1")
    suspend fun latestPrescription(substanceId: Long): PrescriptionEntity?

    @Query("SELECT * FROM prescriptions WHERE substance_id = :substanceId AND remaining_doses > 0 ORDER BY order_epoch_day DESC, prescription_epoch_day DESC, id DESC LIMIT 1")
    suspend fun currentPrescription(substanceId: Long): PrescriptionEntity?

    @Query("UPDATE prescriptions SET remaining_doses = :remaining WHERE id = :id")
    suspend fun updatePrescriptionRemaining(id: Long, remaining: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInteractionRule(rule: InteractionRuleEntity): Long

    @Update
    suspend fun updateInteractionRule(rule: InteractionRuleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInteractionTarget(target: InteractionTargetEntity): Long

    @Query("DELETE FROM interaction_targets WHERE rule_id = :ruleId")
    suspend fun deleteInteractionTargets(ruleId: Long)

    @Query("DELETE FROM interaction_rules WHERE id=:ruleId")
    suspend fun deleteInteractionRule(ruleId: Long)

    @Query("DELETE FROM macro_items WHERE macro_id=:macroId")
    suspend fun deleteMacroItems(macroId: Long)

    @Query("DELETE FROM macros WHERE id=:macroId")
    suspend fun deleteMacro(macroId: Long)

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
