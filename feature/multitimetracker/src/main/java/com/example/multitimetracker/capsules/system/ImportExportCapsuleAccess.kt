// v462
package com.example.multitimetracker.capsules.system

import android.content.Context
import com.example.multitimetracker.export.CsvImporter
import com.example.multitimetracker.persistence.SnapshotStore

/**
 * FCS boundary contract for Import/Export capsule.
 *
 * The capsule must NOT access MainViewModel state directly.
 */
interface ImportExportCapsuleAccess {
    fun exportSnapshot(): ImportExportSnapshot
    fun applyImportedCsvSnapshot(snapshot: CsvImporter.ImportedSnapshot)
    fun activateImportedSnapshotFromStore(ctx: Context, snap: SnapshotStore.Snapshot): String?

    fun persist()
    fun scheduleAutoBackup()

    fun computeBackupSignature(): String
    fun setLastBackupSignature(sig: String)
    fun buildManualExportZipName(nowMs: Long): String
    fun setImportVerificationReport(report: String?)
}
