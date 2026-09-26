package com.example.multitimetracker.api

import android.content.Context
import com.example.multitimetracker.capsules.alerts.controller.hasDuplicateTimeFenceRuleForTriggerAndTags
import com.example.multitimetracker.capsules.alerts.core.RandomAlertWindow
import com.example.multitimetracker.capsules.alerts.core.planRandomAlertInstants
import com.example.multitimetracker.model.TimeFenceDelivery
import com.example.multitimetracker.model.TimeFenceMatchMode
import com.example.multitimetracker.model.TimeFenceRule
import com.example.multitimetracker.model.TimeFenceScope
import com.example.multitimetracker.model.TimeFenceTrigger
import com.example.multitimetracker.persistence.SnapshotStore
import com.example.multitimetracker.util.CapsuleWriteApi
import com.gernalix.personalhub.core.alerts.AlertDomain
import com.gernalix.personalhub.core.alerts.AlertMatchMode
import com.gernalix.personalhub.core.alerts.AlertScope
import com.gernalix.personalhub.core.alerts.AlertTargetKind
import com.gernalix.personalhub.core.alerts.AlertTrigger
import com.gernalix.personalhub.core.alerts.ManagedAlertCatalog
import com.gernalix.personalhub.core.alerts.ManagedAlertDraft
import com.gernalix.personalhub.core.alerts.ManagedAlertOption
import com.gernalix.personalhub.core.alerts.ManagedAlertProvider
import com.gernalix.personalhub.core.alerts.ManagedAlertRandomWindow
import com.gernalix.personalhub.core.alerts.ManagedAlertRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Public host boundary for Timer alert management.
 *
 * Timer keeps its legacy snapshot persistence; this adapter exposes that data through the shared
 * alert-management contract without moving ownership into the host application.
 */
class TimerAlertsApi(context: Context) : ManagedAlertProvider {
    private val app = context.applicationContext

    override val domain: AlertDomain = AlertDomain.TIMER

    override suspend fun load(): ManagedAlertCatalog = withContext(Dispatchers.IO) {
        val snapshot = SnapshotStore.load(app)
        val tags = snapshot?.tags.orEmpty()
            .filterNot { it.isDeleted || it.isArchived }
            .sortedBy { it.name.lowercase() }
        val tagLabels = tags.associate { it.id to it.name }
        ManagedAlertCatalog(
            domain = domain,
            rules = snapshot?.timeFenceRules.orEmpty()
                .filterNot { it.isDeleted }
                .sortedByDescending { it.id }
                .map { rule -> rule.toManaged(tagLabels) },
            tags = tags.map { ManagedAlertOption(it.id.toString(), it.name) },
        )
    }

    override suspend fun create(draft: ManagedAlertDraft): Boolean = mutate { snapshot ->
        if (!valid(draft)) return@mutate null
        val tagIds = draft.tagIds.mapNotNullTo(linkedSetOf()) { it.toLongOrNull() }
        val trigger = draft.trigger.toTimerTrigger() ?: return@mutate null
        if (hasDuplicateTimeFenceRuleForTriggerAndTags(snapshot.timeFenceRules, null, trigger, tagIds)) {
            return@mutate null
        }
        val id = maxOf(
            System.currentTimeMillis(),
            (snapshot.timeFenceRules.maxOfOrNull { it.id } ?: 0L) + 1L,
        )
        val created = TimeFenceRule(
            id = id,
            message = draft.message.trim(),
            trigger = trigger,
            delivery = TimeFenceDelivery.NOTIFICATION,
            scope = draft.scope.toTimerScope(),
            matchMode = draft.matchMode.toTimerMatchMode(),
            tagIds = tagIds,
            cooldownMs = draft.cooldownMs.coerceAtLeast(0L),
        ).withRandomPlan(draft)
        snapshot.copy(timeFenceRules = (snapshot.timeFenceRules + created).sortedBy { it.id })
    }

    override suspend fun update(ruleId: String, draft: ManagedAlertDraft): Boolean = mutate { snapshot ->
        if (!valid(draft)) return@mutate null
        val id = ruleId.toLongOrNull() ?: return@mutate null
        val existing = snapshot.timeFenceRules.firstOrNull { it.id == id && !it.isDeleted } ?: return@mutate null
        val tagIds = draft.tagIds.mapNotNullTo(linkedSetOf()) { it.toLongOrNull() }
        val trigger = draft.trigger.toTimerTrigger() ?: return@mutate null
        if (hasDuplicateTimeFenceRuleForTriggerAndTags(snapshot.timeFenceRules, id, trigger, tagIds)) {
            return@mutate null
        }
        val updated = existing.copy(
            message = draft.message.trim(),
            trigger = trigger,
            delivery = TimeFenceDelivery.NOTIFICATION,
            scope = draft.scope.toTimerScope(),
            matchMode = draft.matchMode.toTimerMatchMode(),
            tagIds = tagIds,
            timerMinutes = 0,
            cooldownMs = draft.cooldownMs.coerceAtLeast(0L),
        ).withRandomPlan(draft)
        snapshot.copy(timeFenceRules = snapshot.timeFenceRules.map { if (it.id == id) updated else it })
    }

    override suspend fun setEnabled(ruleId: String, enabled: Boolean): Boolean = mutate { snapshot ->
        val id = ruleId.toLongOrNull() ?: return@mutate null
        val existing = snapshot.timeFenceRules.firstOrNull { it.id == id && !it.isDeleted } ?: return@mutate null
        val updated = if (!enabled) {
            existing.copy(isEnabled = false, randomAlertScheduledAtMs = emptyList())
        } else {
            existing.copy(isEnabled = true).withRandomPlan(
                ManagedAlertDraft(
                    domain = domain,
                    message = existing.message,
                    trigger = existing.trigger.toManagedTrigger(),
                    targetKind = AlertTargetKind.TAGS,
                    tagIds = existing.tagIds.mapTo(linkedSetOf()) { it.toString() },
                    matchMode = existing.matchMode.toManagedMatchMode(),
                    scope = existing.scope.toManagedScope(),
                    cooldownMs = existing.cooldownMs,
                    randomAlertsEnabled = existing.randomAlertsEnabled,
                    randomAlertsCount = existing.randomAlertsCount,
                    randomAlertsWindow = existing.randomAlertsWindow.toManagedRandomWindow(),
                )
            )
        }
        snapshot.copy(timeFenceRules = snapshot.timeFenceRules.map { if (it.id == id) updated else it })
    }

    override suspend fun delete(ruleId: String): Boolean = mutate { snapshot ->
        val id = ruleId.toLongOrNull() ?: return@mutate null
        val found = snapshot.timeFenceRules.any { it.id == id && !it.isDeleted }
        if (!found) return@mutate null
        val now = System.currentTimeMillis()
        snapshot.copy(
            timeFenceRules = snapshot.timeFenceRules.map {
                if (it.id == id) it.copy(
                    isDeleted = true,
                    deletedAtMs = now,
                    isEnabled = false,
                    randomAlertScheduledAtMs = emptyList(),
                ) else it
            }
        )
    }

    private suspend fun mutate(transform: (SnapshotStore.Snapshot) -> SnapshotStore.Snapshot?): Boolean =
        mutationMutex.withLock {
            withContext(Dispatchers.IO) {
                val current = SnapshotStore.load(app) ?: return@withContext false
                val next = transform(current) ?: return@withContext false
                if (next == current) return@withContext true
                TimerProfileRuntime.retireActiveProfile(app)
                try {
                    saveSnapshot(next)
                } finally {
                    TimerProfileRuntime.restoreActiveProfile(app)
                }
                true
            }
        }

    @OptIn(CapsuleWriteApi::class)
    private fun saveSnapshot(snapshot: SnapshotStore.Snapshot) {
        SnapshotStore.save(
            context = app,
            tasks = snapshot.tasks,
            tags = snapshot.tags,
            closedSessions = snapshot.closedSessions,
            tagSessions = snapshot.tagSessions,
            lifePeriods = snapshot.lifePeriods,
            timeFenceRules = snapshot.timeFenceRules,
            installAtMs = snapshot.installAtMs,
            appUsageMs = snapshot.appUsageMs,
            activeSessionStart = snapshot.activeSessionStart,
            activeTagStart = snapshot.activeTagStart,
            tagParents = snapshot.tagParents,
            chains = snapshot.chains,
            activeChainRun = snapshot.activeChainRun,
            chronologySessions = snapshot.chronologySessions,
            runningSessions = snapshot.runningSessions,
            quickEventTemplates = snapshot.quickEventTemplates,
            quickEventEntries = snapshot.quickEventEntries,
            quickEventFieldDefinitions = snapshot.quickEventFieldDefinitions,
            quickEventFieldValues = snapshot.quickEventFieldValues,
            quickEventMacros = snapshot.quickEventMacros,
            quickEventMacroActions = snapshot.quickEventMacroActions,
        )
    }

    private fun valid(draft: ManagedAlertDraft): Boolean =
        draft.domain == domain &&
            draft.targetKind == AlertTargetKind.TAGS &&
            draft.message.trim().isNotEmpty() &&
            draft.tagIds.isNotEmpty() &&
            draft.trigger in setOf(AlertTrigger.TIMER_START, AlertTrigger.TIMER_STOP)

    private fun TimeFenceRule.withRandomPlan(draft: ManagedAlertDraft): TimeFenceRule {
        val count = draft.randomAlertsCount.coerceIn(0, 99)
        val enabled = draft.randomAlertsEnabled && count > 0 && isEnabled && !isDeleted
        val identity = randomAlertIdentity.ifBlank { "timer-alert-$id" }
        val window = draft.randomAlertsWindow.toTimerWindow()
        val scheduled = if (enabled) {
            planRandomAlertInstants(System.currentTimeMillis(), count, window) { max -> kotlin.random.Random.nextLong(1L, max + 1L) }
        } else {
            emptyList()
        }
        return copy(
            randomAlertsEnabled = draft.randomAlertsEnabled,
            randomAlertsCount = count,
            randomAlertsWindow = window.name,
            randomAlertIdentity = identity,
            randomAlertScheduledAtMs = scheduled,
        )
    }

    private fun TimeFenceRule.toManaged(tagLabels: Map<Long, String>) = ManagedAlertRule(
        id = id.toString(),
        domain = domain,
        message = message,
        trigger = trigger.toManagedTrigger(),
        targetKind = AlertTargetKind.TAGS,
        tagIds = tagIds.mapTo(linkedSetOf()) { it.toString() },
        tagLabels = tagIds.mapNotNull(tagLabels::get),
        matchMode = matchMode.toManagedMatchMode(),
        scope = scope.toManagedScope(),
        enabled = isEnabled,
        cooldownMs = cooldownMs,
        randomAlertsEnabled = randomAlertsEnabled,
        randomAlertsCount = randomAlertsCount,
        randomAlertsWindow = randomAlertsWindow.toManagedRandomWindow(),
    )

    private fun AlertTrigger.toTimerTrigger(): TimeFenceTrigger? = when (this) {
        AlertTrigger.TIMER_START -> TimeFenceTrigger.ON_START
        AlertTrigger.TIMER_STOP -> TimeFenceTrigger.ON_STOP
        else -> null
    }

    private fun TimeFenceTrigger.toManagedTrigger(): AlertTrigger = when (this) {
        TimeFenceTrigger.ON_START -> AlertTrigger.TIMER_START
        TimeFenceTrigger.ON_STOP -> AlertTrigger.TIMER_STOP
    }

    private fun AlertScope.toTimerScope(): TimeFenceScope = when (this) {
        AlertScope.ALWAYS -> TimeFenceScope.ALWAYS
        AlertScope.ONE_TIME -> TimeFenceScope.ONE_TIME
    }

    private fun TimeFenceScope.toManagedScope(): AlertScope = when (this) {
        TimeFenceScope.ALWAYS -> AlertScope.ALWAYS
        TimeFenceScope.ONE_TIME -> AlertScope.ONE_TIME
    }

    private fun AlertMatchMode.toTimerMatchMode(): TimeFenceMatchMode = when (this) {
        AlertMatchMode.ALL -> TimeFenceMatchMode.AND
        AlertMatchMode.ANY -> TimeFenceMatchMode.OR
    }

    private fun TimeFenceMatchMode.toManagedMatchMode(): AlertMatchMode = when (this) {
        TimeFenceMatchMode.AND -> AlertMatchMode.ALL
        TimeFenceMatchMode.OR -> AlertMatchMode.ANY
    }

    private fun ManagedAlertRandomWindow.toTimerWindow(): RandomAlertWindow = when (this) {
        ManagedAlertRandomWindow.HOUR -> RandomAlertWindow.HOUR
        ManagedAlertRandomWindow.DAY -> RandomAlertWindow.DAY
    }

    private fun String.toManagedRandomWindow(): ManagedAlertRandomWindow =
        if (this == RandomAlertWindow.HOUR.name) ManagedAlertRandomWindow.HOUR else ManagedAlertRandomWindow.DAY

    companion object {
        private val mutationMutex = Mutex()
    }
}
