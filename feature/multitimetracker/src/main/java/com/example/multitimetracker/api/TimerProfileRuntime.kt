package com.example.multitimetracker.api

import android.content.Context
import com.example.multitimetracker.TimeFenceRestoreReceiver
import com.example.multitimetracker.TimeFenceTimerScheduler
import com.example.multitimetracker.core.session.DefaultSessionCore
import com.example.multitimetracker.capsules.alerts.core.TimerAlertRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Host boundary for retiring and rebuilding profile-scoped Timer runtime state. */
object TimerProfileRuntime {
    fun retireActiveProfile(context: Context) {
        val app = context.applicationContext
        runCatching { DefaultSessionCore(app).readRunningSessions() }.getOrDefault(emptyList())
            .forEach { TimeFenceTimerScheduler.cancelTimedSession(app, it.id) }
        runBlocking(Dispatchers.IO) { TimerAlertRepository(app).live() }.forEach { rule ->
            val identity = rule.randomAlertIdentity.ifBlank { "timer-alert-${rule.id}" }
            rule.randomAlertScheduledAtMs.forEach { fireAt ->
                TimeFenceTimerScheduler.cancelRandomAlert(app, identity, fireAt)
            }
        }
    }

    fun restoreActiveProfile(context: Context) {
        TimeFenceRestoreReceiver.restoreActiveProfile(context.applicationContext)
    }
}
