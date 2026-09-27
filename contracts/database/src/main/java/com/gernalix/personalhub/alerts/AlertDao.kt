package com.gernalix.personalhub.alerts

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface AlertDao {
    @Query("SELECT * FROM alert_rules WHERE domain = :domain ORDER BY created_at DESC, id ASC")
    fun observeRules(domain: String): Flow<List<AlertRuleEntity>>

    @Query("SELECT * FROM alert_rules WHERE domain = :domain ORDER BY created_at DESC, id ASC")
    suspend fun listRules(domain: String): List<AlertRuleEntity>

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

    @Query("DELETE FROM alert_rules WHERE id = :ruleId")
    suspend fun purge(ruleId: String): Int

    @Query("DELETE FROM alert_rules WHERE domain = :domain")
    suspend fun clearDomain(domain: String): Int

    @Query("UPDATE alert_rules SET last_fired_at = :firedAt, enabled = :enabled, updated_at = :firedAt WHERE id = :ruleId")
    suspend fun markFired(ruleId: String, firedAt: Long, enabled: Boolean): Int

    @Query("SELECT * FROM alert_rule_targets WHERE rule_id IN (:ruleIds)")
    suspend fun targets(ruleIds: List<String>): List<AlertRuleTargetEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTargets(targets: List<AlertRuleTargetEntity>)

    @Query("DELETE FROM alert_rule_targets WHERE rule_id = :ruleId")
    suspend fun clearTargets(ruleId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFiring(firing: AlertFiringEntity)

    @Transaction
    suspend fun recordSuccessfulDelivery(firing: AlertFiringEntity, keepEnabled: Boolean) {
        insertFiring(firing)
        check(markFired(firing.ruleId, firing.firedAt, keepEnabled) == 1) {
            "Alert rule disappeared before successful delivery could be recorded"
        }
    }

    @Query("SELECT * FROM alert_firings WHERE domain = :domain ORDER BY fired_at DESC, id DESC LIMIT :limit")
    suspend fun recentFirings(domain: String, limit: Int = 200): List<AlertFiringEntity>
}
