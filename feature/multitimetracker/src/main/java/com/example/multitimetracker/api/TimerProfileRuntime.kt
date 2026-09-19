package com.example.multitimetracker.api

import android.content.Context
import com.example.multitimetracker.TimeFenceRestoreReceiver
import com.example.multitimetracker.TimeFenceTimerScheduler
import com.example.multitimetracker.capsules.remotesync.RemoteSyncScheduler
import com.example.multitimetracker.core.session.DefaultSessionCore
import com.example.multitimetracker.persistence.SnapshotStore

/** Host boundary for retiring and rebuilding profile-scoped Timer runtime state. */
object TimerProfileRuntime {
    fun retireActiveProfile(context: Context) {
        val app = context.applicationContext
        runCatching { DefaultSessionCore(app).readRunningSessions() }.getOrDefault(emptyList())
            .forEach { TimeFenceTimerScheduler.cancelTimedSession(app, it.id) }
        SnapshotStore.load(app)?.timeFenceRules.orEmpty().forEach { rule ->
            val identity = rule.randomAlertIdentity.ifBlank { "timer-alert-${rule.id}" }
            rule.randomAlertScheduledAtMs.forEach { fireAt ->
                TimeFenceTimerScheduler.cancelRandomAlert(app, identity, fireAt)
            }
        }
        RemoteSyncScheduler.cancelActiveProfile(app)
    }

    fun restoreActiveProfile(context: Context) {
        TimeFenceRestoreReceiver.restoreActiveProfile(context.applicationContext)
        RemoteSyncScheduler.ensurePeriodicRecovery(context.applicationContext)
    }
}
