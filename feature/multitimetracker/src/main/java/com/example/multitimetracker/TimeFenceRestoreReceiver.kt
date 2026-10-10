package com.example.multitimetracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.multitimetracker.capsules.alerts.core.buildLegacyTimerAlertCleanup
import com.example.multitimetracker.capsules.alerts.core.executeLegacyTimerAlertCleanup
import com.example.multitimetracker.core.session.DefaultSessionCore
import com.example.multitimetracker.persistence.SnapshotStore

class TimeFenceRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // Opening an externally migrated database can take longer than a broadcast's
                // deadline. Never wait for DatabaseGate on the main/broadcast thread.
                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                    "timer-restore-active-profile",
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<TimeFenceRestoreWorker>().build(),
                )
            }
            else -> return
        }
    }

    companion object {
    fun restoreActiveProfile(context: Context) {
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
        snapshot.timeFenceRules
            .filter { it.isEnabled && !it.isDeleted && it.randomAlertsEnabled }
            .forEach { rule ->
                val identity = rule.randomAlertIdentity.ifBlank { "timer-alert-${rule.id}" }
                rule.randomAlertScheduledAtMs
                    .filter { it > System.currentTimeMillis() }
                    .forEach { fireAtMs ->
                        TimeFenceTimerScheduler.scheduleRandomAlert(
                            context = appContext,
                            identity = identity,
                            fireAtMs = fireAtMs,
                            title = appContext.getString(R.string.random_alert_timer_title),
                            message = rule.message,
                            ruleId = rule.id,
                        )
                    }
            }
    }
    }
}

class TimeFenceRestoreWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        TimeFenceRestoreReceiver.restoreActiveProfile(applicationContext)
        return Result.success()
    }
}
