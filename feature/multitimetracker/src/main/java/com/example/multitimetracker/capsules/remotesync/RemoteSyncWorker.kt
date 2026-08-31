package com.example.multitimetracker.capsules.remotesync

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

internal class RemoteSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {
    override fun doWork(): Result {
        val config = RemoteSyncConfig.fromBuildConfig()
        if (!config.isEnabled) return Result.success()
        val queue = RemoteSyncQueueSqlite(applicationContext)
        return try {
            queue.refresh(LocalReplicaProjector(applicationContext).project())
            val client = DatasetteWriteClient(config)
            repeat(RemoteSyncContract.MAX_BATCHES_PER_RUN) {
                val batch = queue.nextBatch()
                if (batch.isEmpty()) {
                    RemoteSyncStatusStore.markSuccess(applicationContext, 0)
                    return Result.success()
                }
                when (val outcome = client.upsert(batch)) {
                    DatasetteWriteClient.Result.Success -> {
                        queue.markAttempt(batch, null)
                        queue.acknowledge(batch)
                    }
                    is DatasetteWriteClient.Result.Failure -> {
                        val failureClass = when (outcome.disposition) {
                            HttpDisposition.AUTH_FAILURE -> "AUTH"
                            HttpDisposition.RETRY -> "RETRYABLE_HTTP"
                            HttpDisposition.PERMANENT_FAILURE -> "PERMANENT_HTTP"
                            HttpDisposition.SUCCESS -> "UNEXPECTED"
                        }
                        queue.markAttempt(batch, failureClass)
                        RemoteSyncStatusStore.markFailure(applicationContext, failureClass, queue.pendingCount())
                        return when (outcome.disposition) {
                            HttpDisposition.RETRY -> Result.retry()
                            else -> Result.failure()
                        }
                    }
                }
            }
            val pending = queue.pendingCount()
            RemoteSyncStatusStore.markSuccess(applicationContext, pending)
            if (pending == 0) Result.success() else Result.retry()
        } catch (_: RuntimeException) {
            val pending = runCatching { queue.pendingCount() }.getOrDefault(-1)
            RemoteSyncStatusStore.markFailure(applicationContext, "LOCAL_PROJECTION", pending)
            Result.retry()
        }
    }
}
