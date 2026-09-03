package com.gernalix.personalhub.core.database

import android.content.Context
import androidx.work.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Durable generation tracking, coalesced WorkManager jobs, and process/reboot recovery. */
object HubAutoExport {
    private var started = false
    internal const val AUTO_EXPORT_WORK = "personalhub-autoexport"
    internal const val RECOVERY_WORK = "personalhub-export-recovery"
    internal const val EXPORT_DELAY_MS = 1200L

    internal interface Scheduler {
        fun cancelLegacyWork(context: Context)
        fun enqueuePeriodicRecovery(context: Context)
        fun enqueueAutoExport(context: Context)
    }

    private object WorkManagerScheduler : Scheduler {
        override fun cancelLegacyWork(context: Context) {
            listOf("luoghi_mutation_export", "luoghi_periodic_export").forEach { WorkManager.getInstance(context).cancelUniqueWork(it) }
        }

        override fun enqueuePeriodicRecovery(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                RECOVERY_WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<HubExportWorker>(15, TimeUnit.MINUTES).build(),
            )
        }

        override fun enqueueAutoExport(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                AUTO_EXPORT_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<HubExportWorker>().setInitialDelay(EXPORT_DELAY_MS, TimeUnit.MILLISECONDS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build(),
            )
        }
    }

    @Volatile private var scheduler: Scheduler = WorkManagerScheduler

    internal fun setSchedulerForTests(testScheduler: Scheduler) {
        scheduler = testScheduler
        started = false
    }

    internal fun resetSchedulerForTests() {
        scheduler = WorkManagerScheduler
        started = false
    }

    @Synchronized fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scheduler.cancelLegacyWork(app)
        scheduler.enqueuePeriodicRecovery(app)
        // Raw timer commits and Room commits share persistent triggers. Polling also closes
        // the commit-before-enqueue crash/race window; the periodic worker survives process death.
        Executors.newSingleThreadScheduledExecutor { Thread(it, "personalhub-dirty-check").apply { isDaemon = true } }
            .scheduleWithFixedDelay({ runCatching { if (dirty(app)) request(app); com.gernalix.personalhub.core.database.capsules.sync.DatasetteSync.checkForChanges(app) } }, 0, 2, TimeUnit.SECONDS)
    }
    fun dirty(context: Context): Boolean {
        if (DatabaseVault.folder(context) == null) return false
        val generation = PersonalHubDatabase.get(context).openHelper.readableDatabase.query("SELECT generation FROM hub_generation WHERE id=1").use { c -> c.moveToFirst(); c.getLong(0) }
        return generation != DatabaseVault.preferences(context).getLong("exported_generation", -1)
    }
    fun request(context: Context) {
        if (DatabaseVault.folder(context) == null) return
        try {
            scheduler.enqueueAutoExport(context.applicationContext)
        } catch (error: Throwable) {
            DatabaseVault.recordAutoExportFailure(context, error)
            throw error
        }
    }
}

class HubExportWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result { return try {
        do {
            if (isStopped) return Result.retry()
            DatabaseVault.exportNow(applicationContext)
        } while (HubAutoExport.dirty(applicationContext))
        Result.success()
    } catch (error: Exception) {
        DatabaseVault.recordAutoExportFailure(applicationContext, error)
        Result.retry()
    }
    }
}
