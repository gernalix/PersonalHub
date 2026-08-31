package com.gernalix.luoghi.export

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class ExportWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return when (LuoghiExporter.exportNow(applicationContext)) {
            LuoghiExporter.Result.NotConfigured -> Result.success()
            LuoghiExporter.Result.Suppressed -> Result.success()
            is LuoghiExporter.Result.Success -> Result.success()
            is LuoghiExporter.Result.Failure -> Result.retry()
        }
    }
}
