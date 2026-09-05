package com.example.multitimetracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.multitimetracker.capsules.alerts.core.buildTimeFenceAlarmReconciliation
import com.example.multitimetracker.capsules.alerts.core.executeTimeFenceAlarmReconciliation
import com.example.multitimetracker.core.session.DefaultSessionCore
import com.example.multitimetracker.persistence.SnapshotStore

class TimeFenceRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> restoreTimerAlerts(context)
            else -> return
        }
    }

    private fun restoreTimerAlerts(context: Context) {
        val appContext = context.applicationContext
        val snapshot = SnapshotStore.load(appContext) ?: return
        val runningSessions = runCatching {
            DefaultSessionCore(appContext).readRunningSessions()
        }.getOrDefault(emptyList())

        val reconciliation = buildTimeFenceAlarmReconciliation(
            beforeRules = snapshot.timeFenceRules,
            afterRules = snapshot.timeFenceRules,
            sessions = runningSessions,
            tags = snapshot.tags,
            nowMs = System.currentTimeMillis(),
        )
        executeTimeFenceAlarmReconciliation(appContext, reconciliation)
    }
}
