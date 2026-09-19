package com.gernalix.personalhub.core.database

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * Durable SAF backup/export only.
 *
 * personalhub.db remains authoritative. This component never imports from the selected SAF folder;
 * inbound replacement is possible only through the explicit validated database-import flow.
 */
object HubAutoExport {
    private var started = false
    internal const val AUTO_EXPORT_WORK = "personalhub-autoexport"
    internal const val RECOVERY_WORK = "personalhub-export-recovery"
    internal const val EXPORT_DELAY_MS = 1200L
    internal val AUTO_EXPORT_POLICY = ExistingWorkPolicy.REPLACE

    interface Scheduler {
        fun cancelLegacyWork(context: Context)
        fun enqueuePeriodicRecovery(context: Context)
        fun enqueueAutoExport(context: Context, policy: ExistingWorkPolicy)
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

        override fun enqueueAutoExport(context: Context, policy: ExistingWorkPolicy) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                AUTO_EXPORT_WORK, policy,
                OneTimeWorkRequestBuilder<HubExportWorker>().setInitialDelay(EXPORT_DELAY_MS, TimeUnit.MILLISECONDS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build(),
            )
        }
    }

    @Volatile private var scheduler: Scheduler = WorkManagerScheduler

    fun setSchedulerForTests(testScheduler: Scheduler) {
        scheduler = testScheduler
        started = false
    }

    fun resetSchedulerForTests() {
        scheduler = WorkManagerScheduler
        started = false
    }

    @Synchronized fun start(context: Context) {
        if (started) return
        val app = context.applicationContext
        scheduler.cancelLegacyWork(app)
        scheduler.enqueuePeriodicRecovery(app)
        requestIfDirty(app)
        com.gernalix.personalhub.core.database.capsules.sync.DatasetteSync.checkForChanges(app)
        started = true
    }
    fun dirty(context: Context): Boolean {
        if (DatabaseVault.folder(context) == null) return false
        val generation = PersonalHubDatabase.get(context).openHelper.readableDatabase.query("SELECT generation FROM hub_generation WHERE id=1").use { c -> c.moveToFirst(); c.getLong(0) }
        return generation != DatabaseVault.preferences(context).getLong("exported_generation", -1)
    }
    fun request(context: Context) {
        if (DatabaseVault.folder(context) == null) return
        try {
            scheduler.enqueueAutoExport(context.applicationContext, AUTO_EXPORT_POLICY)
        } catch (error: Throwable) {
            DatabaseVault.recordAutoExportFailure(context, error)
            throw error
        }
    }
    fun requestIfDirty(context: Context) {
        if (dirty(context)) request(context)
    }

    internal fun exportUntilClean(context: Context, isStopped: () -> Boolean): Boolean {
        do {
            if (isStopped()) return false
            DatabaseVault.exportNow(context)
        } while (dirty(context))
        return true
    }
}

class HubExportWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result { return try {
        if (!HubAutoExport.dirty(applicationContext)) return Result.success()
        if (HubAutoExport.exportUntilClean(applicationContext) { isStopped }) Result.success() else Result.retry()
    } catch (error: Exception) {
        DatabaseVault.recordAutoExportFailure(applicationContext, error)
        Result.retry()
    }
    }
}
