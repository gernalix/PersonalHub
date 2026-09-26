package com.example.multitimetracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.multitimetracker.core.session.DefaultSessionCore
import com.example.multitimetracker.persistence.SnapshotStore
import com.example.multitimetracker.util.CapsuleWriteApi
import com.example.multitimetracker.widget.QuickSessionWidgetProvider
import com.gernalix.personalhub.core.database.DatabaseProfiles
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.core.hubcontext.WorkflowyHubBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(CapsuleWriteApi::class)
class TimeFenceTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != ACTION_FIRE_TIMED_SESSION && action != ACTION_ACK_TIMED_SESSION && action != ACTION_RANDOM_ALERT) return
        if (!isForActiveProfile(context, intent)) return
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_FIRE_TIMED_SESSION -> handleTimedSession(appContext, intent)
                    ACTION_ACK_TIMED_SESSION -> handleTimedSessionAcknowledge(appContext, intent)
                    ACTION_RANDOM_ALERT -> handleRandomAlert(appContext, intent)
                }
            } finally {
                pendingResult.finish()
            }
        }
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

    private suspend fun handleRandomAlert(context: Context, intent: Intent) {
        val title = intent.getStringExtra(EXTRA_RANDOM_ALERT_TITLE) ?: context.getString(R.string.app_name)
        val text = intent.getStringExtra(EXTRA_RANDOM_ALERT_TEXT) ?: context.getString(R.string.notification_label)
        val id = intent.getIntExtra(EXTRA_NOTIFICATION_ID, title.hashCode())
        val ruleId = intent.getLongExtra(EXTRA_ALERT_RULE_ID, -1L)
        val linkedUrl = if (ruleId > 0L) WorkflowyHubBridge.notificationUrl(
            context, HubEntityRef("timer", "alert", ruleId.toString()),
        ) else null
        TimeFenceNotifier.notifyTimedSession(
            context = context,
            notificationId = id,
            title = title,
            message = text,
            notificationType = com.example.multitimetracker.model.TimedTagNotificationType.NORMAL,
            linkedUrl = linkedUrl,
        )
    }

    companion object {
        internal fun isForActiveProfile(context: Context, intent: Intent): Boolean =
            intent.getStringExtra(EXTRA_PROFILE_ID) == DatabaseProfiles.activeProfileId(context)

        const val ACTION_FIRE_TIMED_SESSION = "com.example.multitimetracker.ACTION_TIMED_SESSION"
        const val ACTION_ACK_TIMED_SESSION = "com.example.multitimetracker.ACTION_ACK_TIMED_SESSION"
        const val ACTION_RANDOM_ALERT = "com.example.multitimetracker.ACTION_RANDOM_ALERT"
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        const val EXTRA_RANDOM_ALERT_TITLE = "extra_random_alert_title"
        const val EXTRA_RANDOM_ALERT_TEXT = "extra_random_alert_text"
        const val EXTRA_ALERT_RULE_ID = "extra_alert_rule_id"
    }
}
