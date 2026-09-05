package com.example.multitimetracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.multitimetracker.core.session.DefaultSessionCore
import com.example.multitimetracker.persistence.SnapshotStore
import com.example.multitimetracker.util.CapsuleWriteApi
import com.example.multitimetracker.widget.QuickSessionWidgetProvider

@OptIn(CapsuleWriteApi::class)
class TimeFenceTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_FIRE_TIMED_SESSION -> handleTimedSession(context, intent)
            ACTION_ACK_TIMED_SESSION -> handleTimedSessionAcknowledge(context, intent)
            else -> return
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

    companion object {
        const val ACTION_FIRE_TIMED_SESSION = "com.example.multitimetracker.ACTION_TIMED_SESSION"
        const val ACTION_ACK_TIMED_SESSION = "com.example.multitimetracker.ACTION_ACK_TIMED_SESSION"
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }
}
