package com.gernalix.personalhub.alerts

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AlertDao {
    @Query("SELECT * FROM alert_rules WHERE domain = :domain ORDER BY created_at DESC, id ASC")
    fun observeRules(domain: String): Flow<List<AlertRuleEntity>>

    @Query("SELECT * FROM alert_rules WHERE domain = :domain AND enabled = 1 AND deleted_at IS NULL ORDER BY created_at ASC, id ASC")
    suspend fun activeRules(domain: String): List<AlertRuleEntity>

    @Query("SELECT * FROM alert_rules WHERE id = :ruleId LIMIT 1")
    suspend fun getRule(ruleId: String): AlertRuleEntity?

    @Upsert
    suspend fun upsertRule(rule: AlertRuleEntity)

    @Query("UPDATE alert_rules SET enabled = :enabled, updated_at = :updatedAt WHERE id = :ruleId")
    suspend fun setEnabled(ruleId: String, enabled: Boolean, updatedAt: Long): Int

    @Query("UPDATE alert_rules SET deleted_at = :deletedAt, enabled = 0, updated_at = :deletedAt WHERE id = :ruleId")
    suspend fun softDelete(ruleId: String, deletedAt: Long): Int

    @Query("UPDATE alert_rules SET last_fired_at = :firedAt, enabled = :enabled, updated_at = :firedAt WHERE id = :ruleId")
    suspend fun markFired(ruleId: String, firedAt: Long, enabled: Boolean): Int

    @Query("SELECT * FROM alert_place_tag_targets WHERE rule_id IN (:ruleIds)")
    suspend fun placeTagTargets(ruleIds: List<String>): List<AlertPlaceTagTargetEntity>

    @Query("SELECT * FROM alert_timer_tag_targets WHERE rule_id IN (:ruleIds)")
    suspend fun timerTagTargets(ruleIds: List<String>): List<AlertTimerTagTargetEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaceTagTargets(targets: List<AlertPlaceTagTargetEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTimerTagTargets(targets: List<AlertTimerTagTargetEntity>)

    @Query("DELETE FROM alert_place_tag_targets WHERE rule_id = :ruleId")
    suspend fun clearPlaceTagTargets(ruleId: String)

    @Query("DELETE FROM alert_timer_tag_targets WHERE rule_id = :ruleId")
    suspend fun clearTimerTagTargets(ruleId: String)
}
