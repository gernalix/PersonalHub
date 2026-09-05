// v470
package com.example.multitimetracker.capsules.alerts.controller

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.multitimetracker.BuildConfig
import com.example.multitimetracker.R
import com.example.multitimetracker.capsules.alerts.core.*
import com.example.multitimetracker.capsules.alerts.public.TimeFenceEvent
import com.example.multitimetracker.capsules.alerts.state.AlertsHostState
import com.example.multitimetracker.capsules.alerts.state.AlertsUiState
import com.example.multitimetracker.capsules.system.CapsuleRuntimeChange
import com.example.multitimetracker.capsules.system.CapsuleRuntimeParticipant
import com.example.multitimetracker.model.PreFencePrompt
import com.example.multitimetracker.model.SessionUi
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.TimeFenceDelivery
import com.example.multitimetracker.model.TimeFenceMatchMode
import com.example.multitimetracker.model.TimeFenceRule
import com.example.multitimetracker.model.TimeFenceScope
import com.example.multitimetracker.model.TimeFenceTrigger
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlin.math.max
import kotlin.random.Random

private const val MAX_PREFENCE_PROMPT_QUEUE_SIZE = 3

internal fun timeFenceRuleMatchesTagIds(
    matchMode: TimeFenceMatchMode,
    ruleTagIds: Set<Long>,
    eventTagIds: Set<Long>
): Boolean {
    return when (matchMode) {
        TimeFenceMatchMode.AND -> ruleTagIds.all { eventTagIds.contains(it) }
        TimeFenceMatchMode.OR -> ruleTagIds.isEmpty() || ruleTagIds.any { eventTagIds.contains(it) }
    }
}

internal fun normalizedTimeFenceTagIds(tagIds: Set<Long>): List<Long> =
    tagIds.filter { it > 0L }.sorted()

internal fun hasDuplicateTimeFenceRuleForTriggerAndTags(
    rules: List<TimeFenceRule>,
    candidateRuleId: Long?,
    trigger: TimeFenceTrigger,
    tagIds: Set<Long>
): Boolean {
    val normalizedCandidate = normalizedTimeFenceTagIds(tagIds)
    return rules.any { rule ->
        !rule.isDeleted &&
            rule.id != candidateRuleId &&
            rule.trigger == trigger &&
            normalizedTimeFenceTagIds(rule.tagIds) == normalizedCandidate
    }
}

/**
 * Capsule-specific ViewModel (lightweight) for Alerts.
 *
 * NOTE: This is intentionally NOT an AndroidX ViewModel.
 * It is owned by MainViewModel and receives only the minimal hooks it needs.
 *
 * ALERTS ISOLATION:
 * - Only this capsule may mutate `timeFenceRules` and the pre-fence prompt queue.
 * - The rest of the app emits TimeFenceEvent(s); evaluation + firing happens here.
 */
class AlertsCapsuleViewModel(
    hostStateFlow: StateFlow<AlertsHostState>,
    runtimeScope: CoroutineScope,
    private val getContext: () -> Context?,
    private val resolveSessionStartAtMs: (Long, Long) -> Long?,
    private val getTags: () -> List<Tag>,
    private val getRunningSessions: () -> List<com.example.multitimetracker.model.SessionUi>,
    private val persist: () -> Unit,
    private val persistAsync: () -> Unit,
    private val scheduleAutoBackup: () -> Unit,
    // NOTE: Kotlin forbids naming parameters in function types and forbids named arguments when invoking them.
    private val logUserEvent: (String, String, Long, String, JSONObject?, Boolean) -> Unit,
    private val logSystemEvent: (String, String?, Long?, String, JSONObject?) -> Unit,
    private val elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val logDebug: (String, String) -> Unit = { tag, message -> Log.d(tag, message) },
    private val showNotificationOverride: ((Long, String, String) -> Boolean)? = null
) : CapsuleRuntimeParticipant {
    override val capsuleId: String = "alerts"
    private val ruleMutationLock = Any()
    private val liveRules = MutableStateFlow<List<TimeFenceRule>>(emptyList())
    private val timeMachineRules = MutableStateFlow<List<TimeFenceRule>?>(null)
    private val preFencePrompts = MutableStateFlow<List<PreFencePrompt>>(emptyList())
    val uiState: StateFlow<AlertsUiState> = combine(hostStateFlow, liveRules, timeMachineRules, preFencePrompts) { host, currentRules, projectedRules, prompts ->
        AlertsUiState(
            tags = host.tags,
            timeFenceRules = projectedRules ?: currentRules,
            tagLastUsedMsByTagId = host.tagLastUsedMsByTagId,
            preFencePrompts = prompts,
        )
    }.stateIn(
        runtimeScope,
        SharingStarted.Eagerly,
        AlertsUiState(
            tags = hostStateFlow.value.tags,
            timeFenceRules = emptyList(),
            tagLastUsedMsByTagId = hostStateFlow.value.tagLastUsedMsByTagId,
            preFencePrompts = emptyList(),
        )
    )

    override fun onCapsuleRuntimeChanged(context: Context?, change: CapsuleRuntimeChange) {
        if (change != CapsuleRuntimeChange.TIME_MACHINE_CHANGED) {
            preFencePrompts.value = emptyList()
        }
    }

    fun dismissPreFencePrompt(prompt: PreFencePrompt? = null) {
        preFencePrompts.update { current ->
            if (prompt == null) {
                emptyList()
            } else {
                current.filterNot { queued ->
                    queued.ruleId == prompt.ruleId &&
                        queued.sessionId == prompt.sessionId &&
                        queued.firedAtMs == prompt.firedAtMs &&
                        queued.message == prompt.message
                }
            }
        }
    }

    private fun enqueuePreFencePrompt(prompt: PreFencePrompt) {
        preFencePrompts.update { current ->
            (current + prompt).takeLast(MAX_PREFENCE_PROMPT_QUEUE_SIZE)
        }
    }

    private fun replaceRules(newRules: List<TimeFenceRule>) {
        liveRules.value = newRules
    }

    fun rules(): List<TimeFenceRule> = liveRules.value

    fun replaceTimeFenceRules(newRules: List<TimeFenceRule>) {
        replaceRules(newRules)
    }

    fun showTimeMachineRules(newRules: List<TimeFenceRule>) {
        timeMachineRules.value = newRules
    }

    fun clearTimeMachineRules() {
        timeMachineRules.value = null
    }

    fun showTimerAlertPrompt(ruleId: Long, sessionId: Long, title: String, message: String, firedAtMs: Long) {
        enqueuePreFencePrompt(
            PreFencePrompt(
                ruleId = ruleId,
                sessionId = sessionId,
                sessionTitle = title,
                message = message,
                firedAtMs = firedAtMs,
            )
        )
    }

    private fun showInAppPrompt(ruleId: Long, sessionId: Long, title: String, message: String, firedAtMs: Long): Boolean {
        showNotificationOverride?.let { return it(ruleId, title, message) }
        showTimerAlertPrompt(ruleId, sessionId, title, message, firedAtMs)
        return true
    }

    fun reconcileSnapshotRuntimeAlarms(
        context: Context,
        rules: List<TimeFenceRule>,
        sessions: List<SessionUi>,
        tags: List<Tag>,
        nowMs: Long,
    ) {
        executeLegacyTimerAlertCleanup(
            context = context,
            cleanup = buildLegacyTimerAlertCleanup(rules = rules, sessions = sessions),
        )
    }


    // --- JSON payload helpers (kept inside the capsule)
    private fun timeFenceRuleToPayload(r: TimeFenceRule): JSONObject {
        return JSONObject()
            .put("id", r.id)
            .put("message", r.message)
            .put("trigger", r.trigger.name)
            .put("delivery", r.delivery.name)
            .put("scope", r.scope.name)
            .put("matchMode", r.matchMode.name)
            .put("tagIds", JSONArray(r.tagIds.toList().sorted()))
            .put("cooldownMs", r.cooldownMs)
            .put("timerMinutes", r.timerMinutes)
            .put("isEnabled", r.isEnabled)
            .put("isDeleted", r.isDeleted)
            .put("deletedAtMs", r.deletedAtMs)
            .put("lastFiredAtMs", r.lastFiredAtMs)
    }

    fun addTimeFenceRule(
        message: String,
        trigger: TimeFenceTrigger,
        delivery: TimeFenceDelivery = TimeFenceDelivery.NOTIFICATION,
        scope: TimeFenceScope,
        matchMode: TimeFenceMatchMode = TimeFenceMatchMode.AND,
        tagIds: Set<Long>,
        cooldownMs: Long = 0L,
    ): Boolean {
        val msg = message.trim()
        if (msg.isBlank()) return false

        val beforeRules = rules()
        val rule = synchronized(ruleMutationLock) {
            val currentRules = beforeRules
            if (hasDuplicateTimeFenceRuleForTriggerAndTags(currentRules, null, trigger, tagIds)) {
                return false
            }
            val id = System.currentTimeMillis() + Random.nextInt(0, 9999)
            val created = TimeFenceRule(
                id = id,
                message = msg,
                trigger = trigger,
                delivery = delivery,
                scope = scope,
                matchMode = matchMode,
                tagIds = tagIds,
                timerMinutes = 0,
                cooldownMs = max(0L, cooldownMs)
            )
            replaceRules((currentRules + created).sortedBy { it.id })
            created
        }

        getContext()?.let { ctx ->
            logUserEvent(
                "ALERT_CREATE",
                "ALERT",
                rule.id,
                ctx.getString(R.string.audit_alert_created, rule.message),
                timeFenceRuleToPayload(rule),
                true
            )
        }

        persistAsync()
        scheduleAutoBackup()
        return true
    }

    fun updateTimeFenceRule(
        ruleId: Long,
        message: String,
        trigger: TimeFenceTrigger,
        delivery: TimeFenceDelivery,
        scope: TimeFenceScope,
        matchMode: TimeFenceMatchMode,
        tagIds: Set<Long>,
        cooldownMs: Long,
    ): Boolean {
        val msg = message.trim()
        if (msg.isBlank()) return false

        val beforeRules = rules()
        var oldRule: TimeFenceRule? = null
        val newRules = synchronized(ruleMutationLock) {
            val currentRules = beforeRules
            if (hasDuplicateTimeFenceRuleForTriggerAndTags(currentRules, ruleId, trigger, tagIds)) {
                return false
            }
            oldRule = currentRules.firstOrNull { it.id == ruleId }
            currentRules.map { r ->
                if (r.id != ruleId) r
                else r.copy(
                    message = msg,
                    trigger = trigger,
                    delivery = delivery,
                    scope = scope,
                    matchMode = matchMode,
                    tagIds = tagIds,
                    timerMinutes = 0,
                    cooldownMs = max(0L, cooldownMs)
                )
            }.also { replaceRules(it) }
        }

        getContext()?.let { ctx ->
            val newRule = newRules.firstOrNull { it.id == ruleId }
            val label = newRule?.message?.trim()?.takeIf { it.isNotBlank() }
                ?: oldRule?.message?.trim()?.takeIf { it.isNotBlank() }
                ?: ("Alert #" + ruleId)

            logUserEvent(
                "ALERT_UPDATE",
                "ALERT",
                ruleId,
                ctx.getString(R.string.audit_alert_updated, label),
                newRule?.let { timeFenceRuleToPayload(it) } ?: oldRule?.let { timeFenceRuleToPayload(it) },
                true
            )
        }

        persistAsync()
        scheduleAutoBackup()
        return true
    }

    fun deleteTimeFenceRule(ruleId: Long) {
    val now = System.currentTimeMillis()
    val beforeRules = rules()
    val oldRule = beforeRules.firstOrNull { it.id == ruleId }
    val newRules = beforeRules.map { r ->
        if (r.id == ruleId) r.copy(isDeleted = true, deletedAtMs = now) else r
    }
    replaceRules(newRules)

    getContext()?.let { ctx ->
        val msg = oldRule?.message ?: ""
        logUserEvent(
            "ALERT_TRASH",
            "ALERT",
            ruleId,
            ctx.getString(R.string.audit_alert_trashed, msg.ifBlank { ruleId.toString() }),
            timeFenceRuleToPayload(oldRule ?: TimeFenceRule(
                id = ruleId,
                message = msg,
                trigger = TimeFenceTrigger.ON_START,
                scope = TimeFenceScope.ALWAYS,
                tagIds = emptySet()
            )),
            true
        )
    }
    persistAsync()
    scheduleAutoBackup()
}

fun restoreTimeFenceRule(ruleId: Long) {
    val beforeRules = rules()
    val oldRule = beforeRules.firstOrNull { it.id == ruleId }
    val newRules = beforeRules.map { r ->
        if (r.id == ruleId) r.copy(isDeleted = false, deletedAtMs = null) else r
    }
    replaceRules(newRules)

    getContext()?.let { ctx ->
        val msg = oldRule?.message ?: ""
        logUserEvent(
            "ALERT_RESTORE",
            "ALERT",
            ruleId,
            ctx.getString(R.string.audit_alert_restored, msg.ifBlank { ruleId.toString() }),
            oldRule?.let { timeFenceRuleToPayload(it) },
            false
        )
    }
    persistAsync()
    scheduleAutoBackup()
}

fun purgeTimeFenceRule(ruleId: Long) {
    val beforeRules = rules()
    val oldRule = beforeRules.firstOrNull { it.id == ruleId }
    val newRules = beforeRules.filterNot { it.id == ruleId }
    replaceRules(newRules)

    getContext()?.let { ctx ->
        val msg = oldRule?.message ?: ""
        logUserEvent(
            "ALERT_PURGE",
            "ALERT",
            ruleId,
            ctx.getString(R.string.audit_alert_purged, msg.ifBlank { ruleId.toString() }),
            oldRule?.let { timeFenceRuleToPayload(it) },
            false
        )
    }
    persistAsync()
    scheduleAutoBackup()
}

fun purgeAllDeletedTimeFenceRules() {
    val beforeRules = rules()
    val trashed = beforeRules.filter { it.isDeleted }
    if (trashed.isEmpty()) return

    val newRules = beforeRules.filterNot { it.isDeleted }
    replaceRules(newRules)

    getContext()?.let { ctx ->
        logUserEvent(
            "ALERT_PURGE_ALL",
            "ALERT",
            -1L,
            ctx.getString(R.string.audit_alert_purge_all, trashed.size),
            JSONObject().put("count", trashed.size),
            false
        )
    }
    persistAsync()
    scheduleAutoBackup()
}
fun setTimeFenceRuleEnabled(ruleId: Long, enabled: Boolean) {
        val beforeRules = rules()
        val before = beforeRules.firstOrNull { it.id == ruleId }
        val newRules = beforeRules.map { r -> if (r.id == ruleId) r.copy(isEnabled = enabled) else r }
        replaceRules(newRules)

        getContext()?.let { ctx ->
            val after = newRules.firstOrNull { it.id == ruleId }
            val action = if (enabled) "ALERT_ENABLE" else "ALERT_DISABLE"
            val label = after?.message?.trim()?.takeIf { it.isNotBlank() }
                ?: before?.message?.trim()?.takeIf { it.isNotBlank() }
                ?: "Alert"

            val summary = if (enabled) {
                ctx.getString(R.string.audit_alert_enabled, label)
            } else {
                ctx.getString(R.string.audit_alert_disabled, label)
            }

            logUserEvent(
                action,
                "ALERT",
                ruleId,
                summary,
                after?.let { timeFenceRuleToPayload(it) } ?: before?.let { timeFenceRuleToPayload(it) },
                true
            )
        }

        persistAsync()
        scheduleAutoBackup()
    }

    /**
     * Evaluate emitted TimeFenceEvent(s) against current rules, fire side-effects and update rule state.
     *
     * Side-effects:
     * - Timer Alerts are always immediate in-app prompts.
     */
    fun handleTimeFenceEvents(events: List<TimeFenceEvent>, nowMs: Long) {
        if (events.isEmpty()) return

        val rules = rules()
        if (rules.isEmpty()) return

        val traceStartMs = if (BuildConfig.DEBUG) elapsedRealtimeMs() else 0L

        val tags = getTags()

        // Map tagId -> name for nicer titles
        val tagNameById = tags.associateBy({ it.id }, { it.name })

        var newRules = rules
        var changed = false

        for (ev in events) {
            for (ruleId in newRules.map { it.id }) {
                val r = newRules.firstOrNull { it.id == ruleId } ?: continue
                val match = matchTimeFenceRuleForEvent(
                    rule = r,
                    event = ev,
                    nowMs = nowMs,
                    tagNameById = tagNameById
                ) ?: continue
                val title = match.title

                if (!showInAppPrompt(r.id, ev.sessionId, title, r.message, nowMs)) continue
                logSystemEvent(
                    "ALERT_FIRED",
                    "TIME_FENCE_RULE",
                    r.id,
                    "Alert fired",
                    JSONObject()
                        .put("ruleId", r.id)
                        .put("sessionId", ev.sessionId)
                        .put("trigger", ev.trigger.name)
                        .put("delivery", TimeFenceDelivery.NOTIFICATION.name)
                )

                // Update rule state (last fired + optional one-time disable)
                val updated = when (r.scope) {
                    TimeFenceScope.ONE_TIME -> r.copy(isEnabled = false, lastFiredAtMs = nowMs)
                    TimeFenceScope.ALWAYS -> r.copy(lastFiredAtMs = nowMs)
                }

                newRules = newRules.map { rr -> if (rr.id == r.id) updated else rr }
                changed = true
            }
        }

        if (changed) {
            replaceRules(newRules)
            persistAsync()
            scheduleAutoBackup()
        }

        if (BuildConfig.DEBUG) {
            val elapsed = elapsedRealtimeMs() - traceStartMs
            logDebug("AlertPerf", "handleTimeFenceEvents events=${events.size} changed=$changed elapsedMs=$elapsed")
        }
    }
}
