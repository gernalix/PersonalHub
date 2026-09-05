package com.example.multitimetracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.multitimetracker.capsules.alerts.core.buildLegacyTimerAlertCleanup
import com.example.multitimetracker.capsules.alerts.core.executeLegacyTimerAlertCleanup
import com.example.multitimetracker.core.session.DefaultSessionCore
import com.example.multitimetracker.persistence.SnapshotStore

class TimeFenceRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> restoreTimedSessions(context)
            else -> return
        }
    }

    private fun restoreTimedSessions(context: Context) {
        val appContext = context.applicationContext
        val snapshot = SnapshotStore.load(appContext) ?: return
        val runningSessions = runCatching {
            DefaultSessionCore(appContext).readRunningSessions()
        }.getOrDefault(emptyList())
        executeLegacyTimerAlertCleanup(
            context = appContext,
            cleanup = buildLegacyTimerAlertCleanup(
                rules = snapshot.timeFenceRules,
                sessions = runningSessions,
            ),
        )
        TimedSessionSupport.restoreScheduledAlarmsAfterBootOrUpdate(
            context = appContext,
            tags = snapshot.tags,
            nowMs = System.currentTimeMillis(),
        )
    }
}
