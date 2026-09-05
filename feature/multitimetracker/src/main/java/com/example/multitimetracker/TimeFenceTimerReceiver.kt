// v471
// v470
package com.example.multitimetracker

import com.example.multitimetracker.util.CapsuleWriteApi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.multitimetracker.capsules.alerts.core.hasScheduledTimeFenceAlreadyFired
import com.example.multitimetracker.capsules.alerts.controller.timeFenceRuleMatchesTagIds
import com.example.multitimetracker.model.TimeFenceDelivery
import com.example.multitimetracker.model.TimeFenceRule
import com.example.multitimetracker.model.TimeFenceScope
import com.example.multitimetracker.persistence.SnapshotStore
import com.example.multitimetracker.core.session.DefaultSessionCore
import com.example.multitimetracker.persistence.AuditLogSqlite
import com.example.multitimetracker.widget.QuickSessionWidgetProvider
import org.json.JSONObject

internal data class TimeFenceTimerTargetState(
    val isRunning: Boolean,
    val startAtMs: Long?,
    val tagIds: Set<Long>,
    val displayName: String
)

internal fun shouldFireScheduledTimeFence(
    rule: TimeFenceRule,
    expectedStartAtMs: Long,
    targetState: TimeFenceTimerTargetState?,
    nowMs: Long
): Boolean {
    val target = targetState ?: return false
    if (!target.isRunning) return false
    if (target.startAtMs == null || target.startAtMs != expectedStartAtMs) return false
    if (hasScheduledTimeFenceAlreadyFired(rule, expectedStartAtMs)) return false
    if (rule.isDeleted || !rule.isEnabled) return false
    if (rule.delivery != TimeFenceDelivery.NOTIFICATION) return false
    if (!timeFenceRuleMatchesTagIds(rule.matchMode, rule.tagIds, target.tagIds)) return false
    val last = rule.lastFiredAtMs
    if (rule.cooldownMs > 0L && last != null && nowMs - last < rule.cooldownMs) return false
    return true
}

internal data class ScheduledTimeFenceRequest(
    val ruleId: Long,
    val sessionId: Long,
    val expectedSessionStartAtMs: Long,
)

internal fun parseScheduledTimeFenceRequest(
    hasLegacyTaskExtra: Boolean,
    ruleId: Long,
    sessionId: Long,
    expectedSessionStartAtMs: Long,
): ScheduledTimeFenceRequest? {
    if (hasLegacyTaskExtra) {
        failLegacyTaskPath()
    }
    if (ruleId <= 0L || sessionId <= 0L || expectedSessionStartAtMs <= 0L) {
        return null
    }
    return ScheduledTimeFenceRequest(
        ruleId = ruleId,
        sessionId = sessionId,
        expectedSessionStartAtMs = expectedSessionStartAtMs,
    )
}

@OptIn(CapsuleWriteApi::class)
class TimeFenceTimerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_FIRE_TIMER -> handleTimeFenceTimer(context, intent)
            ACTION_FIRE_TIMED_SESSION -> handleTimedSession(context, intent)
            ACTION_ACK_TIMED_SESSION -> handleTimedSessionAcknowledge(context, intent)
            else -> return
        }
    }

    private fun handleTimeFenceTimer(context: Context, intent: Intent) {
        val request = parseScheduledTimeFenceRequest(
            hasLegacyTaskExtra = intent.hasExtra(LEGACY_EXTRA_TASK_ID),
            ruleId = intent.getLongExtra(EXTRA_RULE_ID, -1L),
            sessionId = intent.getLongExtra(EXTRA_ALERT_SESSION_ID, -1L),
            expectedSessionStartAtMs = intent.getLongExtra(EXTRA_EXPECTED_SESSION_START_AT_MS, -1L),
        ) ?: return
        val receiverAtMs = System.currentTimeMillis()
        TimeFenceTimerScheduler.forgetScheduledTimerAlert(
            ruleId = request.ruleId,
            sessionId = request.sessionId,
            expectedSessionStartAtMs = request.expectedSessionStartAtMs,
        )

        val snap = SnapshotStore.load(context) ?: return
        val rule = snap.timeFenceRules.firstOrNull { it.id == request.ruleId } ?: return

        val now = System.currentTimeMillis()
        val sessionUi = runCatching {
            DefaultSessionCore(context).readSessionById(sessionId = request.sessionId)
        }.getOrNull()
        val targetState = sessionUi?.let { session ->
            TimeFenceTimerTargetState(
                isRunning = session.endMs == null,
                startAtMs = session.startMs,
                tagIds = session.tagIds,
                displayName = session.title
            )
        }
        if (!shouldFireScheduledTimeFence(rule, request.expectedSessionStartAtMs, targetState, now)) return

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
            .ifBlank { context.getString(R.string.time_fence_fallback_title) }
        val message = intent.getStringExtra(EXTRA_MESSAGE).orEmpty()

        val notifyAtMs = System.currentTimeMillis()
        context.sendBroadcast(
            Intent(ACTION_SHOW_TIMER_ALERT_PROMPT)
                .setPackage(context.packageName)
                .putExtra(EXTRA_RULE_ID, rule.id)
                .putExtra(EXTRA_ALERT_SESSION_ID, request.sessionId)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_MESSAGE, message)
                .putExtra(EXTRA_FIRED_AT_MS, notifyAtMs)
        )
        val fireAtMs = com.example.multitimetracker.capsules.alerts.core.scheduledTimeFenceFireAtMs(
            expectedSessionStartAtMs = request.expectedSessionStartAtMs,
            timerMinutes = rule.timerMinutes,
        )
        Log.i(
            "MTT_TIMER_ALERT",
            "receive ruleId=${rule.id} sessionId=${request.sessionId} sessionStartAtMs=${request.expectedSessionStartAtMs} " +
                "timerMinutes=${rule.timerMinutes} fireAtMs=$fireAtMs receiverAtMs=$receiverAtMs notifyAtMs=$notifyAtMs " +
                "alarm_delivery_lateness_ms=${receiverAtMs - fireAtMs} notification_post_delay_ms=${notifyAtMs - receiverAtMs}"
        )

        val taskOrSessionName = targetState?.displayName.orEmpty()
        AuditLogSqlite.insert(
            context = context,
            isSystem = true,
            action = "ALERT_FIRED",
            entityType = "TIME_FENCE_RULE",
            entityId = rule.id,
            summary = context.getString(R.string.audit_alert_fired, rule.message, taskOrSessionName),
            payload = JSONObject()
                .put("ruleId", rule.id)
                .put("sessionId", request.sessionId)
                .put("sessionTitle", taskOrSessionName)
                .put("delivery", rule.delivery.name)
                .put("timerMinutes", rule.timerMinutes)
                .put("fireAtMs", fireAtMs)
                .put("receiverAtMs", receiverAtMs)
                .put("notifyAtMs", notifyAtMs)
                .put("alarm_delivery_lateness_ms", receiverAtMs - fireAtMs)
                .put("notification_post_delay_ms", notifyAtMs - receiverAtMs)
        )

        // Update rule state *only when actually fired*.
        val updatedRules = snap.timeFenceRules.map { r ->
            if (r.id != request.ruleId) r
            else {
                when (r.scope) {
                    TimeFenceScope.ONE_TIME -> r.copy(isEnabled = false, lastFiredAtMs = now)
                    TimeFenceScope.ALWAYS -> r.copy(lastFiredAtMs = now)
                }
            }
        }

        SnapshotStore.save(
            context = context,
            tasks = snap.tasks,
            tags = snap.tags,
            closedSessions = snap.closedSessions,
            tagSessions = snap.tagSessions,
            timeFenceRules = updatedRules,
            installAtMs = snap.installAtMs,
            appUsageMs = snap.appUsageMs,
            activeSessionStart = snap.activeSessionStart,
            activeTagStart = snap.activeTagStart,
            tagParents = snap.tagParents,
            chains = snap.chains,
            activeChainRun = snap.activeChainRun
        )
    }

    private fun handleTimedSession(context: Context, intent: Intent) {
        val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        if (sessionId <= 0L) return

        val now = System.currentTimeMillis()
        val sessionCore = DefaultSessionCore(context)
        val session = runCatching { sessionCore.readSessionById(sessionId) }.getOrNull() ?: return
        if (session.endMs != null) return
        val expectedEndMs = session.expectedEndMs ?: return
        if (now < expectedEndMs) return

        val tags = SnapshotStore.load(context)?.tags.orEmpty()
        sessionCore.updateSessionTimes(
            sessionId = session.id,
            startMs = session.startMs,
            endMs = expectedEndMs,
        )
        TimedSessionSupport.showCompletionNotificationIfNeeded(context, session, tags)
        context.sendBroadcast(
            Intent(QuickSessionWidgetProvider.ACTION_SNAPSHOT_CHANGED).setPackage(context.packageName)
        )
    }

    private fun handleTimedSessionAcknowledge(context: Context, intent: Intent) {
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        if (notificationId <= 0) return
        TimeFenceNotifier.acknowledgeTimedSession(context, notificationId)
    }

    companion object {
        const val ACTION_FIRE_TIMER = "com.example.multitimetracker.ACTION_TIMEFENCE_TIMER"
        const val ACTION_FIRE_TIMED_SESSION = "com.example.multitimetracker.ACTION_TIMED_SESSION"
        const val ACTION_ACK_TIMED_SESSION = "com.example.multitimetracker.ACTION_ACK_TIMED_SESSION"

        const val EXTRA_RULE_ID = "extra_rule_id"
        const val EXTRA_ALERT_SESSION_ID = "extra_alert_session_id"
        const val EXTRA_EXPECTED_SESSION_START_AT_MS = "extra_expected_session_start_at_ms"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_FIRED_AT_MS = "extra_fired_at_ms"
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        const val ACTION_SHOW_TIMER_ALERT_PROMPT = "com.example.multitimetracker.ACTION_SHOW_TIMER_ALERT_PROMPT"

        private const val LEGACY_EXTRA_TASK_ID = "extra_task_id"
    }
}
