package com.example.multitimetracker.capsules.remotesync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.gernalix.personalhub.core.database.DatabaseProfiles
import java.util.concurrent.TimeUnit

object RemoteSyncScheduler {
    private const val IMMEDIATE_WORK = "mtt-remote-sync-immediate"
    private const val PERIODIC_WORK = "mtt-remote-sync-periodic"
    private const val DEBOUNCE_SECONDS = 2L

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun requestDebounced(context: Context) {
        if (!RemoteSyncConfig.fromBuildConfig().isEnabled) return
        val profileId = DatabaseProfiles.activeProfileId(context)
        val request = OneTimeWorkRequestBuilder<RemoteSyncWorker>()
            .setInputData(workDataOf(RemoteSyncWorker.PROFILE_ID to profileId))
            .setConstraints(networkConstraint)
            .setInitialDelay(DEBOUNCE_SECONDS, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork("$IMMEDIATE_WORK-$profileId", ExistingWorkPolicy.REPLACE, request)
    }

    fun ensurePeriodicRecovery(context: Context) {
        if (!RemoteSyncConfig.fromBuildConfig().isEnabled) return
        val profileId = DatabaseProfiles.activeProfileId(context)
        val request = PeriodicWorkRequestBuilder<RemoteSyncWorker>(15, TimeUnit.MINUTES)
            .setInputData(workDataOf(RemoteSyncWorker.PROFILE_ID to profileId))
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork("$PERIODIC_WORK-$profileId", ExistingPeriodicWorkPolicy.KEEP, request)
        requestDebounced(context)
    }

    fun cancelActiveProfile(context: Context) {
        val profileId = DatabaseProfiles.activeProfileId(context)
        WorkManager.getInstance(context.applicationContext).apply {
            cancelUniqueWork("$IMMEDIATE_WORK-$profileId")
            cancelUniqueWork("$PERIODIC_WORK-$profileId")
        }
    }

    internal fun debounceSecondsForTests(): Long = DEBOUNCE_SECONDS
}
