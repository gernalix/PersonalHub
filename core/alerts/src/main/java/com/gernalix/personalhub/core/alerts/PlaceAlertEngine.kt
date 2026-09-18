package com.gernalix.personalhub.core.alerts

import android.content.Context
import com.gernalix.personalhub.core.database.PersonalHubDatabase

/**
 * Battery-friendly Places alert engine.
 *
 * It is invoked only after an explicit user check-in/check-out is committed. It never requests
 * location updates and it is intentionally independent from Android geofencing.
 */
class PlaceAlertEngine(
    context: Context,
    private val database: PersonalHubDatabase = PersonalHubDatabase.get(context.applicationContext),
    private val appContext: Context = context.applicationContext,
    private val emitTaskerBroadcast: Boolean = false,
) {
    suspend fun onPlaceEvent(
        placeUuid: String,
        eventTrigger: AlertTrigger,
        firedAtMs: Long = System.currentTimeMillis(),
    ): Int {
        require(
            eventTrigger == AlertTrigger.PLACE_CHECK_IN || eventTrigger == AlertTrigger.PLACE_CHECK_OUT
        ) { "Only explicit Places check-in/out events are supported" }

        val alertDao = database.alertDao()
        val rules = alertDao.activeRules(AlertDomain.PLACE.name)
        if (rules.isEmpty()) return 0

        val place = database.placeDao().getPlace(placeUuid) ?: return 0
        val actualTagIds = database.placeDao().tagIdsForPlace(placeUuid).mapTo(linkedSetOf()) { it.toString() }
        val tagTargets = alertDao.placeTagTargets(rules.map { it.id })
            .groupBy { it.ruleId }
            .mapValues { (_, rows) -> rows.mapTo(linkedSetOf()) { it.placeTagId.toString() } }

        var firedCount = 0
        for (rule in rules) {
            val ruleTrigger = runCatching { AlertTrigger.valueOf(rule.trigger) }.getOrNull() ?: continue
            if (!AlertMatching.placeTrigger(ruleTrigger, eventTrigger)) continue
            if (rule.cooldownMs > 0L && rule.lastFiredAt != null && firedAtMs - rule.lastFiredAt < rule.cooldownMs) continue

            val targetKind = runCatching { AlertTargetKind.valueOf(rule.targetKind) }.getOrNull() ?: continue
            val targetMatches = when (targetKind) {
                AlertTargetKind.ENTITY -> rule.entityId == placeUuid
                AlertTargetKind.TAGS -> {
                    val required = tagTargets[rule.id].orEmpty()
                    val mode = runCatching { AlertMatchMode.valueOf(rule.matchMode) }.getOrDefault(AlertMatchMode.ALL)
                    AlertMatching.tags(mode, required, actualTagIds)
                }
            }
            if (!targetMatches) continue

            val fire = AlertFire(
                ruleId = rule.id,
                domain = AlertDomain.PLACE,
                trigger = eventTrigger,
                entityId = placeUuid,
                tagIds = actualTagIds,
                title = place.nickname.takeIf { it.isNotBlank() } ?: "Places",
                message = rule.message,
                firedAtMs = firedAtMs,
            )
            val notificationId = (rule.id + ":" + placeUuid).hashCode() and Int.MAX_VALUE
            if (!AlertNotificationDispatcher.post(appContext, notificationId, fire, emitTaskerBroadcast)) continue

            val keepEnabled = rule.scope != AlertScope.ONE_TIME.name
            alertDao.markFired(rule.id, firedAtMs, keepEnabled)
            firedCount += 1
        }
        return firedCount
    }
}
