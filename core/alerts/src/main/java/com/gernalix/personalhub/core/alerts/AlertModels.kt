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

/**
 * Domain-neutral alert rule used by the shared evaluator.
 *
 * Tag identifiers are meaningful only inside [domain]. Timer and Places therefore remain separate
 * namespaces even when their numeric IDs happen to be equal.
 */
data class AlertRuleSpec(
    val domain: AlertDomain,
    val trigger: AlertTrigger,
    val targetKind: AlertTargetKind,
    val entityId: String? = null,
    val requiredTagIds: Set<String> = emptySet(),
    val matchMode: AlertMatchMode = AlertMatchMode.ALL,
    val enabled: Boolean = true,
    val cooldownMs: Long = 0L,
    val lastFiredAtMs: Long? = null,
    val emptyTagQueryMatches: Boolean = false,
)

data class AlertEventSpec(
    val domain: AlertDomain,
    val trigger: AlertTrigger,
    val entityId: String? = null,
    val tagIds: Set<String> = emptySet(),
)

data class PlaceAlertDraft(
    val message: String,
    val trigger: AlertTrigger,
    val targetKind: AlertTargetKind,
    val placeId: String? = null,
    val placeTagIds: Set<String> = emptySet(),
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
    val tagNames: Set<String> = emptySet(),
)
