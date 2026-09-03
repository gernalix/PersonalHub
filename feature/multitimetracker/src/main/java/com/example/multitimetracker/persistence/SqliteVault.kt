package com.example.multitimetracker.persistence

import android.content.Context
import com.gernalix.personalhub.core.database.DatabaseNavigation
import com.gernalix.personalhub.core.database.DatabaseVault
import com.gernalix.personalhub.core.database.HubAutoExport
import java.io.File

/** Legacy callers delegate whole-database transfer to the global capsule. */
object SqliteVault {
    data class ImportResult(val importedFileName: String, val sha256Hex: String?, val expectedStats: IntegrityStatsSqlite.Stats)
    fun exportToUserFolderIfConfigured(context: Context, force: Boolean = false) = HubAutoExport.request(context)
    fun exportNow(context: Context) = DatabaseNavigation.open(context)
    fun restoreFromUserFolderIfPossible(context: Context) = Unit
    internal fun consumePendingStartupRestore(context: Context): String? = null
    fun createPreImportBackup(context: Context, validateCopy: Boolean = true): File = DatabaseVault.backupCurrent(context)
    fun restoreInternalDbFromPreImportBackup(context: Context): Boolean { DatabaseNavigation.open(context); return false }
    fun hasPreImportBackup(context: Context) = false
    fun restoreInstrumentationSafetyBackupIfCurrentEmpty(context: Context) = false
    fun clearInternalDbForFreshStart(context: Context): Boolean { DatabaseNavigation.open(context); return false }
    fun overwriteInternalDbFromUri(context: Context, uri: android.net.Uri): ImportResult {
        DatabaseNavigation.open(context)
        throw IllegalStateException(context.getString(com.example.multitimetracker.R.string.backup_db_not_found))
    }
    fun overwriteInternalDbFromUserFolder(context: Context): ImportResult? { DatabaseNavigation.open(context); return null }
    fun inspectBackupFolder(context: Context) = com.example.multitimetracker.BackupFolderInspection(
        "PersonalHub", com.example.multitimetracker.BackupFolderInspectionKind.NO_RESTORABLE_DATA,
    )
}
