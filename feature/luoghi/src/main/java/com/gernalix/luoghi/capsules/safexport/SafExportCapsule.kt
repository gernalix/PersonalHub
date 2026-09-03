package com.gernalix.luoghi.capsules.safexport

import android.content.Context
import android.net.Uri
import com.gernalix.luoghi.export.BackupFolderStore
import com.gernalix.luoghi.export.ExportScheduler

class SafExportCapsule(private val context: Context) {
    fun verify(): BackupFolderStore.ValidationStatus =
        BackupFolderStore.validateSavedTree(context)

    fun saveSelectedFolder(
        treeUri: Uri,
        queueInitialExport: Boolean = true,
    ): BackupFolderStore.ValidationStatus {
        com.gernalix.personalhub.core.database.DatabaseVault.configureFolder(context, treeUri)
        BackupFolderStore.saveTreeUri(context, treeUri)
        val status = verify()
        if (status == BackupFolderStore.ValidationStatus.READY && queueInitialExport) {
            ensurePeriodic()
            PersistentMutationTracker.requestExport(context)
        }
        return status
    }

    fun ensurePeriodic() {
        ExportScheduler.ensurePeriodic(context)
    }

    fun folderLabel(): String? = BackupFolderStore.describeTree(context)

    fun syncStatus(): SyncStatusStore.Snapshot = SyncStatusStore.read(context)
}
