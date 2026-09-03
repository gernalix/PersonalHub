package com.gernalix.luoghi.export
import android.content.Context
import com.gernalix.personalhub.core.database.HubAutoExport
object ExportScheduler {
    fun queueAfterMutation(context: Context, debounceMs: Long = 1200L) = HubAutoExport.request(context)
    fun ensurePeriodic(context: Context) = HubAutoExport.start(context)
    fun cancelPendingMutation(context: Context) = Unit
}
