package com.example.multitimetracker.capsules.alerts.core

import android.content.Context
import com.example.multitimetracker.model.TimeFenceDelivery
import com.example.multitimetracker.model.TimeFenceMatchMode
import com.example.multitimetracker.model.TimeFenceRule
import com.example.multitimetracker.model.TimeFenceScope
import com.example.multitimetracker.model.TimeFenceTrigger
import com.gernalix.personalhub.alerts.AlertRuleEntity
import com.gernalix.personalhub.alerts.AlertRuleTargetEntity
import com.gernalix.personalhub.core.alerts.AlertDomain
import com.gernalix.personalhub.core.alerts.AlertMatchMode
import com.gernalix.personalhub.core.alerts.AlertScope
import com.gernalix.personalhub.core.alerts.AlertTargetKind
import com.gernalix.personalhub.core.alerts.AlertTrigger
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import org.json.JSONArray
import org.json.JSONObject

/** Canonical Timer alert persistence backed by the shared PersonalHub alert tables. */
class TimerAlertRepository(
    context: Context,
    private val database: PersonalHubDatabase = PersonalHubDatabase.get(context.applicationContext),
) {
    private val dao = database.alertDao()

    suspend fun list(): List<TimeFenceRule> {
        val rows = dao.listRules(AlertDomain.TIMER.moduleId)
        val targets = if (rows.isEmpty()) emptyMap() else dao.targets(rows.map { it.id })
            .groupBy { it.ruleId }
            .mapValues { (_, values) -> values.mapNotNullTo(linkedSetOf()) { it.targetId.toLongOrNull() } }
        return rows.mapNotNull { row -> row.toTimeFenceRule(targets[row.id].orEmpty()) }
    }

    suspend fun live(): List<TimeFenceRule> = list().filterNot { it.isDeleted }

    suspend fun save(rule: TimeFenceRule) {
        val now = System.currentTimeMillis()
        val id = rule.id.toString()
        val existing = dao.getRule(id)?.takeIf { it.domain == AlertDomain.TIMER.moduleId }
        dao.upsertRule(
            AlertRuleEntity(
                id = id,
                domain = AlertDomain.TIMER.moduleId,
                trigger = when (rule.trigger) {
                    TimeFenceTrigger.ON_START -> AlertTrigger.TIMER_START.name
                    TimeFenceTrigger.ON_STOP -> AlertTrigger.TIMER_STOP.name
                },
                targetKind = AlertTargetKind.TAGS.name,
                entityId = null,
                matchMode = when (rule.matchMode) {
                    TimeFenceMatchMode.AND -> AlertMatchMode.ALL.name
                    TimeFenceMatchMode.OR -> AlertMatchMode.ANY.name
                },
                message = rule.message,
                scope = when (rule.scope) {
                    TimeFenceScope.ALWAYS -> AlertScope.ALWAYS.name
                    TimeFenceScope.ONE_TIME -> AlertScope.ONE_TIME.name
                },
                enabled = rule.isEnabled,
                cooldownMs = rule.cooldownMs.coerceAtLeast(0L),
                lastFiredAt = rule.lastFiredAtMs,
                configJson = ruleConfig(rule).toString(),
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                deletedAt = rule.deletedAtMs.takeIf { rule.isDeleted },
            ),
        )
        dao.clearTargets(id)
        if (rule.tagIds.isNotEmpty()) {
            dao.insertTargets(rule.tagIds.sorted().map { AlertRuleTargetEntity(id, it.toString()) })
        }
    }

    suspend fun purge(ruleId: Long): Boolean = dao.purge(ruleId.toString()) > 0

    suspend fun clearAll(): Int = dao.clearDomain(AlertDomain.TIMER.moduleId)

    private fun AlertRuleEntity.toTimeFenceRule(tagIds: Set<Long>): TimeFenceRule? {
        if (domain != AlertDomain.TIMER.moduleId) return null
        val config = runCatching { JSONObject(configJson) }.getOrDefault(JSONObject())
        val scheduled = buildList {
            val array = config.optJSONArray("randomAlertScheduledAtMs") ?: JSONArray()
            for (index in 0 until array.length()) add(array.optLong(index))
        }.filter { it > 0L }
        return TimeFenceRule(
            id = id.toLongOrNull() ?: return null,
            message = message,
            trigger = when (trigger) {
                AlertTrigger.TIMER_START.name -> TimeFenceTrigger.ON_START
                AlertTrigger.TIMER_STOP.name -> TimeFenceTrigger.ON_STOP
                else -> return null
            },
            delivery = runCatching {
                TimeFenceDelivery.valueOf(config.optString("delivery", TimeFenceDelivery.NOTIFICATION.name))
            }.getOrDefault(TimeFenceDelivery.NOTIFICATION),
            scope = if (scope == AlertScope.ONE_TIME.name) TimeFenceScope.ONE_TIME else TimeFenceScope.ALWAYS,
            matchMode = if (matchMode == AlertMatchMode.ANY.name) TimeFenceMatchMode.OR else TimeFenceMatchMode.AND,
            tagIds = tagIds,
            timerMinutes = config.optInt("timerMinutes", 0),
            isEnabled = enabled,
            cooldownMs = cooldownMs,
            randomAlertsEnabled = config.optBoolean("randomAlertsEnabled", false),
            randomAlertsCount = config.optInt("randomAlertsCount", 0),
            randomAlertsWindow = config.optString("randomAlertsWindow", "DAY"),
            randomAlertIdentity = config.optString("randomAlertIdentity", ""),
            randomAlertScheduledAtMs = scheduled,
            lastFiredAtMs = lastFiredAt,
            isDeleted = deletedAt != null,
            deletedAtMs = deletedAt,
        )
    }

    private fun ruleConfig(rule: TimeFenceRule) = JSONObject()
        .put("delivery", rule.delivery.name)
        .put("timerMinutes", rule.timerMinutes)
        .put("randomAlertsEnabled", rule.randomAlertsEnabled)
        .put("randomAlertsCount", rule.randomAlertsCount)
        .put("randomAlertsWindow", rule.randomAlertsWindow)
        .put("randomAlertIdentity", rule.randomAlertIdentity)
        .put("randomAlertScheduledAtMs", JSONArray(rule.randomAlertScheduledAtMs))
}
