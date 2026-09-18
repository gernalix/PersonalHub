package com.gernalix.personalhub.core.alerts

object AlertMatching {
    fun tags(
        mode: AlertMatchMode,
        requiredIds: Set<String>,
        actualIds: Set<String>,
        emptyAnyMatches: Boolean = false,
    ): Boolean {
        if (requiredIds.isEmpty()) return mode == AlertMatchMode.ANY && emptyAnyMatches
        return when (mode) {
            AlertMatchMode.ALL -> requiredIds.all(actualIds::contains)
            AlertMatchMode.ANY -> requiredIds.any(actualIds::contains)
        }
    }

    fun placeTrigger(ruleTrigger: AlertTrigger, eventTrigger: AlertTrigger): Boolean =
        when (ruleTrigger) {
            AlertTrigger.PLACE_BOTH ->
                eventTrigger == AlertTrigger.PLACE_CHECK_IN || eventTrigger == AlertTrigger.PLACE_CHECK_OUT
            AlertTrigger.PLACE_CHECK_IN,
            AlertTrigger.PLACE_CHECK_OUT -> ruleTrigger == eventTrigger
            else -> false
        }

    fun ruleMatches(
        rule: AlertRuleSpec,
        event: AlertEventSpec,
        nowMs: Long,
    ): Boolean {
        if (!rule.enabled || rule.domain != event.domain) return false
        val triggerMatches = when (rule.domain) {
            AlertDomain.PLACE -> placeTrigger(rule.trigger, event.trigger)
            AlertDomain.TIMER -> rule.trigger == event.trigger &&
                (event.trigger == AlertTrigger.TIMER_START || event.trigger == AlertTrigger.TIMER_STOP)
        }
        if (!triggerMatches) return false

        val lastFiredAt = rule.lastFiredAtMs
        if (
            rule.cooldownMs > 0L &&
            lastFiredAt != null &&
            nowMs >= lastFiredAt &&
            nowMs - lastFiredAt < rule.cooldownMs
        ) {
            return false
        }

        return when (rule.targetKind) {
            AlertTargetKind.ENTITY -> !rule.entityId.isNullOrBlank() && rule.entityId == event.entityId
            AlertTargetKind.TAGS -> tags(
                mode = rule.matchMode,
                requiredIds = rule.requiredTagIds,
                actualIds = event.tagIds,
                emptyAnyMatches = rule.emptyTagQueryMatches,
            )
        }
    }
}
