package com.gernalix.personalhub.core.alerts

enum class AlertDomain { TIMER, PLACE }

enum class AlertTrigger {
    TIMER_START,
    TIMER_STOP,
    PLACE_CHECK_IN,
    PLACE_CHECK_OUT,
    PLACE_BOTH,
}

enum class AlertTargetKind { ENTITY, TAGS }

enum class AlertMatchMode { ALL, ANY }

enum class AlertScope { ALWAYS, ONE_TIME }

data class PlaceAlertDraft(
    val message: String,
    val trigger: AlertTrigger,
    val targetKind: AlertTargetKind,
    val placeId: String? = null,
    val placeTagIds: Set<Long> = emptySet(),
    val matchMode: AlertMatchMode = AlertMatchMode.ALL,
    val scope: AlertScope = AlertScope.ALWAYS,
    val cooldownMs: Long = 0L,
)

data class AlertFire(
    val ruleId: String,
    val domain: AlertDomain,
    val trigger: AlertTrigger,
    val entityId: String?,
    val tagIds: Set<String>,
    val title: String,
    val message: String,
    val firedAtMs: Long,
)
