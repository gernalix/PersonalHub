package com.gernalix.luoghi.export

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object ExportScheduler {
    private const val MUTATION_WORK = "luoghi_mutation_export"
    private const val PERIODIC_WORK = "luoghi_periodic_export"

    fun queueAfterMutation(context: Context, debounceMs: Long = 1200L) {
        val request = OneTimeWorkRequestBuilder<ExportWorker>()
            .setInitialDelay(debounceMs, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(MUTATION_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<ExportWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.NONE)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancelPendingMutation(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(MUTATION_WORK)
    }
}
