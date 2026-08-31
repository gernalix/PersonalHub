package com.gernalix.luoghi.capsules.safexport

import android.content.Context
import com.gernalix.luoghi.export.BackupFolderStore
import com.gernalix.luoghi.export.AutoExportGate
import com.gernalix.luoghi.export.ExportScheduler
import com.gernalix.luoghi.backup.RestoreSafetyStore

object PersistentMutationTracker {
    const val AUTOEXPORT_DEBOUNCE_MS = 1_200L

    fun record(context: Context, source: String) {
        val appContext = context.applicationContext
        SyncStatusStore.markDatabaseMutation(appContext, source)
        requestExport(appContext)
    }

    fun requestExport(context: Context) {
        val appContext = context.applicationContext
        if (AutoExportGate.isSuppressed) return
        if (RestoreSafetyStore.isAutoExportProtected(appContext)) return
        if (BackupFolderStore.getTreeUri(appContext) == null) return
        ExportScheduler.queueAfterMutation(appContext, AUTOEXPORT_DEBOUNCE_MS)
    }
}
