package com.gernalix.luoghi.backup

import android.content.Context
import android.net.Uri
import com.gernalix.luoghi.capsules.checkin.PlaceEventTypes
import com.gernalix.luoghi.capsules.safexport.SyncStatusStore
import com.gernalix.luoghi.capsules.safexport.PersistentMutationTracker
import com.gernalix.luoghi.data.LuoghiDatabase
import com.gernalix.luoghi.data.DatabaseMutationCoordinator
import com.gernalix.luoghi.export.AutoExportGate
import com.gernalix.luoghi.export.BackupOperationCoordinator
import com.gernalix.luoghi.export.ExportScheduler
import com.gernalix.luoghi.export.LuoghiExporter
import com.gernalix.luoghi.provider.PlacesContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RestoreCoordinator(
    context: Context,
    private val database: LuoghiDatabase = LuoghiDatabase.get(context.applicationContext),
    private val backups: BackupRepository = BackupRepository(context.applicationContext),
    private val restorer: SnapshotRestorer = SnapshotRestorer(database),
) {
    private val appContext = context.applicationContext

    suspend fun hasUserData(): Boolean = withContext(Dispatchers.IO) {
        !database.placeDao().readSnapshot().isEmpty
    }

    suspend fun discoverConfiguredBackups(): BackupDiscovery = backups.discoverConfiguredBackups()

    suspend fun inspectUri(uri: Uri): Result<ValidatedBackup> = backups.inspectUri(uri)

    suspend fun continueWithoutRestore(candidate: ValidatedBackup): Result<String> = try {
        val revalidated = backups.inspect(candidate.preview.source)
        check(revalidated.preview.sha256.equals(candidate.preview.sha256, ignoreCase = true)) {
            "Backup changed after preview"
        }
        val preserved = BackupOperationCoordinator.mutex.withLock {
            backups.preserveForLater(revalidated)
        }
        RestoreSafetyStore.setAutoExportProtected(appContext, false)
        ExportScheduler.ensurePeriodic(appContext)
        PersistentMutationTracker.requestExport(appContext)
        Result.success(preserved)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    }

    fun resumeAutoExport() {
        RestoreSafetyStore.setAutoExportProtected(appContext, false)
        ExportScheduler.ensurePeriodic(appContext)
        PersistentMutationTracker.requestExport(appContext)
    }

    fun protectAutoExport() {
        RestoreSafetyStore.setAutoExportProtected(appContext, true)
        ExportScheduler.cancelPendingMutation(appContext)
    }

    fun isAutoExportProtected(): Boolean = RestoreSafetyStore.isAutoExportProtected(appContext)

    suspend fun restore(candidate: ValidatedBackup): RestoreResult {
        return try {
            val revalidated = backups.inspect(candidate.preview.source)
            if (!revalidated.preview.sha256.equals(candidate.preview.sha256, ignoreCase = true)) {
                return RestoreResult.InvalidBackup(BackupValidationCode.CHECKSUM_MISMATCH)
            }
            val success = withContext(NonCancellable) {
                DatabaseMutationCoordinator.mutex.withLock {
                    AutoExportGate.suppress {
                        ExportScheduler.cancelPendingMutation(appContext)
                        BackupOperationCoordinator.mutex.withLock {
                            val before = database.placeDao().readSnapshot()
                            val preventiveBackup = if (before.isEmpty) {
                                null
                            } else {
                                LuoghiExporter.createPreventiveBackupLocked(appContext, database)
                            }
                            val restored = restorer.replaceAtomically(revalidated.snapshot)
                            SyncStatusStore.markDatabaseMutation(appContext, "backup.restore")
                            val exportResult = LuoghiExporter.exportLocked(appContext)
                            if (exportResult !is LuoghiExporter.Result.Success) {
                                restorer.replaceAtomically(before)
                                SyncStatusStore.markDatabaseMutation(appContext, "backup.restore_rollback")
                                throw IllegalStateException("Final backup export failed; database restore was rolled back")
                            }
                            RestoreSafetyStore.setAutoExportProtected(appContext, false)
                            RestoreResult.Success(
                                RestoreSummary(
                                    places = restored.places.size,
                                    events = restored.events.size,
                                    aliases = restored.aliases.size,
                                    links = restored.links.size,
                                    auditRows = restored.historyAuditLog.size,
                                    historyActions = restored.historyActions.size,
                                    activeCheckIns = restored.activeCheckInCount(),
                                    preventiveBackupFile = preventiveBackup,
                                    finalExportSucceeded = true,
                                )
                            )
                        }
                    }
                }
            }
            runCatching { ExportScheduler.ensurePeriodic(appContext) }
            runCatching {
                appContext.contentResolver.notifyChange(PlacesContract.Places.CONTENT_URI, null)
                appContext.contentResolver.notifyChange(PlacesContract.Events.CONTENT_URI, null)
                appContext.contentResolver.notifyChange(PlacesContract.Aliases.CONTENT_URI, null)
                appContext.contentResolver.notifyChange(PlacesContract.Links.CONTENT_URI, null)
            }
            success
        } catch (invalid: BackupValidationException) {
            RestoreResult.InvalidBackup(invalid.code)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            RestoreResult.Failure(error.message ?: error::class.java.simpleName)
        }
    }
}

private fun com.gernalix.luoghi.data.LuoghiSnapshot.activeCheckInCount(): Int {
    val checkOutSessions = events.asSequence()
        .filter { it.eventType == PlaceEventTypes.CHECK_OUT }
        .map { it.sessionUuid }
        .toSet()
    return events.count { it.eventType == PlaceEventTypes.CHECK_IN && it.sessionUuid !in checkOutSessions }
}
