package com.gernalix.personalhub.core.alerts

enum class ManagedAlertRandomWindow { HOUR, DAY }

data class ManagedAlertOption(
    val id: String,
    val label: String,
)

data class ManagedAlertRule(
    val id: String,
    val domain: AlertDomain,
    val message: String,
    val trigger: AlertTrigger,
    val targetKind: AlertTargetKind,
    val entityId: String? = null,
    val entityLabel: String? = null,
    val tagIds: Set<String> = emptySet(),
    val tagLabels: List<String> = emptyList(),
    val matchMode: AlertMatchMode = AlertMatchMode.ALL,
    val scope: AlertScope = AlertScope.ALWAYS,
    val enabled: Boolean = true,
    val cooldownMs: Long = 0L,
    val randomAlertsEnabled: Boolean = false,
    val randomAlertsCount: Int = 0,
    val randomAlertsWindow: ManagedAlertRandomWindow = ManagedAlertRandomWindow.DAY,
)

data class ManagedAlertDraft(
    val domain: AlertDomain,
    val message: String,
    val trigger: AlertTrigger,
    val targetKind: AlertTargetKind,
    val entityId: String? = null,
    val tagIds: Set<String> = emptySet(),
    val matchMode: AlertMatchMode = AlertMatchMode.ALL,
    val scope: AlertScope = AlertScope.ALWAYS,
    val cooldownMs: Long = 0L,
    val randomAlertsEnabled: Boolean = false,
    val randomAlertsCount: Int = 0,
    val randomAlertsWindow: ManagedAlertRandomWindow = ManagedAlertRandomWindow.DAY,
)

data class ManagedAlertCatalog(
    val domain: AlertDomain,
    val rules: List<ManagedAlertRule>,
    val tags: List<ManagedAlertOption> = emptyList(),
    val entities: List<ManagedAlertOption> = emptyList(),
)

interface ManagedAlertProvider {
    val domain: AlertDomain

    suspend fun load(): ManagedAlertCatalog

    suspend fun create(draft: ManagedAlertDraft): Boolean

    suspend fun update(ruleId: String, draft: ManagedAlertDraft): Boolean

    suspend fun setEnabled(ruleId: String, enabled: Boolean): Boolean

    suspend fun delete(ruleId: String): Boolean
}
