package com.gernalix.personalhub.soldi

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gernalix.personalhub.soldi.capsules.playsubscriptions.PlaySubscriptionsCapsule

/** Stable class name retained for existing WorkManager records. */
class PlaySubscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = PlaySubscriptionsCapsule.runWorker(applicationContext, runAttemptCount)
}
