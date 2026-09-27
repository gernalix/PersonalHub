package com.gernalix.personalhub.core.alerts

import android.content.Context
import com.gernalix.personalhub.alerts.AlertRuleTargetEntity
import com.gernalix.personalhub.alerts.AlertRuleEntity
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class PlaceAlertRepository(
    context: Context,
    private val database: PersonalHubDatabase = PersonalHubDatabase.get(context.applicationContext),
) {
    private val dao = database.alertDao()

    fun observeRules(): Flow<List<AlertRuleEntity>> = dao.observeRules(AlertDomain.PLACE.moduleId)

    suspend fun listRules(): List<AlertRuleEntity> = dao.listRules(AlertDomain.PLACE.moduleId)

    suspend fun create(draft: PlaceAlertDraft): String {
        validate(draft)
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        dao.upsertRule(
            AlertRuleEntity(
                id = id,
                domain = AlertDomain.PLACE.moduleId,
                trigger = draft.trigger.name,
                targetKind = draft.targetKind.name,
                entityId = draft.placeId?.takeIf { draft.targetKind == AlertTargetKind.ENTITY },
                matchMode = draft.matchMode.name,
                message = draft.message.trim(),
                scope = draft.scope.name,
                enabled = draft.enabled,
                cooldownMs = draft.cooldownMs.coerceAtLeast(0L),
                createdAt = now,
                updatedAt = now,
            )
        )
        dao.clearTargets(id)
        if (draft.targetKind == AlertTargetKind.TAGS) {
            dao.insertTargets(
                draft.placeTagIds.sorted().map { tagId ->
                    AlertRuleTargetEntity(ruleId = id, targetId = tagId)
                }
            )
        }
        return id
    }

    suspend fun update(ruleId: String, draft: PlaceAlertDraft): Boolean {
        validate(draft)
        val existing = dao.getRule(ruleId) ?: return false
        if (existing.domain != AlertDomain.PLACE.moduleId) return false
        dao.upsertRule(
            existing.copy(
                trigger = draft.trigger.name,
                targetKind = draft.targetKind.name,
                entityId = draft.placeId?.takeIf { draft.targetKind == AlertTargetKind.ENTITY },
                matchMode = draft.matchMode.name,
                message = draft.message.trim(),
                scope = draft.scope.name,
                enabled = draft.enabled,
                cooldownMs = draft.cooldownMs.coerceAtLeast(0L),
                updatedAt = System.currentTimeMillis(),
            )
        )
        dao.clearTargets(ruleId)
        if (draft.targetKind == AlertTargetKind.TAGS) {
            dao.insertTargets(
                draft.placeTagIds.sorted().map { tagId ->
                    AlertRuleTargetEntity(ruleId = ruleId, targetId = tagId)
                }
            )
        }
        return true
    }

    suspend fun setEnabled(ruleId: String, enabled: Boolean): Boolean =
        dao.setEnabled(ruleId, enabled, System.currentTimeMillis()) > 0

    suspend fun delete(ruleId: String): Boolean =
        dao.softDelete(ruleId, System.currentTimeMillis()) > 0

    suspend fun targets(ruleIds: List<String>): Map<String, Set<String>> {
        if (ruleIds.isEmpty()) return emptyMap()
        return dao.targets(ruleIds)
            .groupBy { it.ruleId }
            .mapValues { (_, rows) -> rows.mapTo(linkedSetOf()) { it.targetId } }
    }

    private fun validate(draft: PlaceAlertDraft) {
        require(draft.message.trim().isNotEmpty()) { "Alert message cannot be blank" }
        require(
            draft.trigger == AlertTrigger.PLACE_CHECK_IN ||
                draft.trigger == AlertTrigger.PLACE_CHECK_OUT ||
                draft.trigger == AlertTrigger.PLACE_BOTH
        ) { "Places alerts must use Places triggers" }
        when (draft.targetKind) {
            AlertTargetKind.ENTITY -> require(!draft.placeId.isNullOrBlank()) { "Place target is required" }
            AlertTargetKind.TAGS -> require(draft.placeTagIds.isNotEmpty()) { "At least one Places tag is required" }
        }
    }
}
