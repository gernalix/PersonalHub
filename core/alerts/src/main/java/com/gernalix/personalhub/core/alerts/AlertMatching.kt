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
}
