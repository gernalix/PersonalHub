package com.gernalix.personalhub.core.database

import android.content.Context
import androidx.work.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Durable generation tracking, coalesced WorkManager jobs, and process/reboot recovery. */
object HubAutoExport {
    private var started = false
    @Synchronized fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        listOf("luoghi_mutation_export", "luoghi_periodic_export").forEach { WorkManager.getInstance(app).cancelUniqueWork(it) }
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
            "personalhub-export-recovery", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<HubExportWorker>(15, TimeUnit.MINUTES).build(),
        )
        // Raw timer commits and Room commits share persistent triggers. Polling also closes
        // the commit-before-enqueue crash/race window; the periodic worker survives process death.
        Executors.newSingleThreadScheduledExecutor { Thread(it, "personalhub-dirty-check").apply { isDaemon = true } }
            .scheduleWithFixedDelay({ runCatching { if (dirty(app)) request(app) } }, 0, 2, TimeUnit.SECONDS)
    }
    fun dirty(context: Context): Boolean {
        if (DatabaseVault.folder(context) == null) return false
        val generation = PersonalHubDatabase.get(context).openHelper.readableDatabase.query("SELECT generation FROM hub_generation WHERE id=1").use { c -> c.moveToFirst(); c.getLong(0) }
        return generation != DatabaseVault.preferences(context).getLong("exported_generation", -1)
    }
    fun request(context: Context) {
        if (DatabaseVault.folder(context) == null) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            "personalhub-autoexport", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<HubExportWorker>().setInitialDelay(1200, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build(),
        )
    }
}

class HubExportWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result { return try {
        do {
            if (isStopped) return Result.retry()
            DatabaseVault.exportNow(applicationContext)
        } while (HubAutoExport.dirty(applicationContext))
        Result.success()
    } catch (_: Exception) { Result.retry() }
    }
}
