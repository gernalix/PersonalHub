package com.gernalix.luoghi.backup

import android.content.Context
import android.net.Uri
import com.gernalix.luoghi.data.LuoghiDatabase
import com.gernalix.personalhub.core.database.DatabaseNavigation
import com.gernalix.personalhub.core.database.HubAutoExport

/** Restore is always an explicit whole-PersonalHub operation. */
class RestoreCoordinator(context: Context, private val database: LuoghiDatabase = LuoghiDatabase.get(context)) {
    private val appContext = context.applicationContext
    suspend fun hasUserData() = !database.placeDao().readSnapshot().isEmpty
    suspend fun discoverConfiguredBackups() = BackupDiscovery(emptyList(), emptyList())
    suspend fun inspectUri(uri: Uri): Result<ValidatedBackup> {
        DatabaseNavigation.open(appContext)
        return Result.failure(IllegalStateException("Import personalhub.db"))
    }
    suspend fun continueWithoutRestore(candidate: ValidatedBackup): Result<String> = Result.success("")
    fun resumeAutoExport() = HubAutoExport.request(appContext)
    fun protectAutoExport() = Unit
    fun isAutoExportProtected() = false
    suspend fun restore(candidate: ValidatedBackup): RestoreResult {
        DatabaseNavigation.open(appContext)
        return RestoreResult.Failure("Import personalhub.db")
    }
}
