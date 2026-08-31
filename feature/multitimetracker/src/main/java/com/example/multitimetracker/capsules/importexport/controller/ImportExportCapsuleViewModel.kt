// v471
// v462
package com.example.multitimetracker.capsules.importexport.controller

import com.example.multitimetracker.util.CapsuleWriteApi

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.example.multitimetracker.BuildConfig
import com.example.multitimetracker.R
import com.example.multitimetracker.capsules.system.ImportExportCapsule
import com.example.multitimetracker.capsules.system.ImportExportSnapshot
import com.example.multitimetracker.core.contracts.ClosedSessionRecord
import com.example.multitimetracker.core.contracts.TaggedSessionRecord
import com.example.multitimetracker.export.BackupFolderStore
import com.example.multitimetracker.export.CsvExporter
import com.example.multitimetracker.export.CsvImporter
import com.example.multitimetracker.export.VaultFolders
import com.example.multitimetracker.export.VaultIndex
import com.example.multitimetracker.export.BackupMetaWriter
import com.example.multitimetracker.export.AuthoritativeExportPayloadBuilder
import com.example.multitimetracker.export.DocHash
import com.example.multitimetracker.export.ShareUtils
import com.example.multitimetracker.export.ZipBackupExporter
import com.example.multitimetracker.export.SnapshotExportAdapter
import com.example.multitimetracker.persistence.AuditLogSqlite
import com.example.multitimetracker.persistence.CriticalDataGuard
import com.example.multitimetracker.persistence.DataIntegrityGate
import com.example.multitimetracker.persistence.ForensicLog
import com.example.multitimetracker.persistence.IntegrityStatsSqlite
import com.example.multitimetracker.persistence.SnapshotSqlite
import com.example.multitimetracker.persistence.SnapshotStore
import com.example.multitimetracker.persistence.UiPrefsStore
import com.example.multitimetracker.model.ActiveChainRun
import com.example.multitimetracker.model.LifePeriod
import com.example.multitimetracker.model.QuickEventEntry
import com.example.multitimetracker.model.QuickEventFieldDefinition
import com.example.multitimetracker.model.QuickEventFieldValue
import com.example.multitimetracker.model.QuickEventMacro
import com.example.multitimetracker.model.QuickEventMacroAction
import com.example.multitimetracker.model.QuickEventTemplate
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.Task
import com.example.multitimetracker.model.TaskChain
import com.example.multitimetracker.model.TimeFenceRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Capsule-specific ViewModel (lightweight) for Import/Export.
 *
 * Owned by MainViewModel. Uses explicit hooks; does not access MainViewModel state directly.
 */
fun createImportExportCapsule(
    getExportSnapshot: () -> ImportExportSnapshot,
    applyImportedCsvSnapshot: (CsvImporter.ImportedSnapshot) -> Unit,
    activateImportedSnapshotFromStore: (Context, SnapshotStore.Snapshot) -> String?,
    persist: () -> Unit,
    scheduleAutoBackup: () -> Unit,
    computeBackupSignature: () -> String,
    setLastBackupSignature: (String) -> Unit,
    buildManualExportZipName: (Long) -> String,
    setImportVerificationReport: (String?) -> Unit
): ImportExportCapsule {
    return ImportExportCapsuleViewModel(
        getExportSnapshot = getExportSnapshot,
        applyImportedCsvSnapshot = applyImportedCsvSnapshot,
        activateImportedSnapshotFromStore = activateImportedSnapshotFromStore,
        persist = persist,
        scheduleAutoBackup = scheduleAutoBackup,
        computeBackupSignature = computeBackupSignature,
        setLastBackupSignature = setLastBackupSignature,
        buildManualExportZipName = buildManualExportZipName,
        setImportVerificationReport = setImportVerificationReport
    )
}

@OptIn(CapsuleWriteApi::class)
private class ImportExportCapsuleViewModel(
    private val getExportSnapshot: () -> ImportExportSnapshot,
    private val applyImportedCsvSnapshot: (CsvImporter.ImportedSnapshot) -> Unit,
    private val activateImportedSnapshotFromStore: (Context, SnapshotStore.Snapshot) -> String?,
    private val persist: () -> Unit,
    private val scheduleAutoBackup: () -> Unit,
    private val computeBackupSignature: () -> String,
    private val setLastBackupSignature: (String) -> Unit,
    private val buildManualExportZipName: (Long) -> String,
    private val setImportVerificationReport: (String?) -> Unit
) : ImportExportCapsule {

    private data class FinalizedImport(
        val importRes: com.example.multitimetracker.persistence.SqliteVault.ImportResult,
        val beforeSignature: String,
        val afterSignature: String
    )

    private enum class ImportRollbackOutcome {
        PREVIOUS_DATA_RESTORED,
        RESET_TO_EMPTY_SETUP,
        FAILED,
    }

    private val exportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val exportInProgress = AtomicBoolean(false)

    private fun runOnMain(block: () -> Unit) {
        val mainLooper = android.os.Looper.getMainLooper()
        if (android.os.Looper.myLooper() == mainLooper) {
            block()
        } else {
            android.os.Handler(mainLooper).post(block)
        }
    }

    private fun showLongToast(context: Context, message: String) {
        val appContext = context.applicationContext
        runOnMain {
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
        }
    }
    /**
     * Called from UI after the user picks a folder (OpenDocumentTree).
     * Persists the permission and creates/uses the "MultiTimer data" subfolder.
     */
    override fun setBackupRootFolder(context: Context, treeUri: Uri) {
        // Persist permission for future sessions.
        val flags = (android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val permOk = runCatching {
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
        }.isSuccess

        // Validate immediately: if we can't create/use the folder, don't persist a broken URI.
        runCatching {
            BackupFolderStore.saveTreeUri(context, treeUri)
            BackupFolderStore.getOrCreateDataDir(context)
        }.onFailure { e: Throwable ->
            // Roll back the saved URI to avoid future "Export fallito" loops.
            context.getSharedPreferences("multitimetracker_backup", Context.MODE_PRIVATE)
                .edit()
                .remove("tree_uri")
                .apply()
            val extra = if (!permOk) " (permesso persistente NON concesso)" else ""
            Toast.makeText(context, context.getString(R.string.backup_folder_error_fmt, (e.message ?: ""), extra), Toast.LENGTH_LONG).show()
        }
    }

    override fun inspectBackupFolder(context: Context): com.example.multitimetracker.BackupFolderInspection {
        return com.example.multitimetracker.persistence.SqliteVault.inspectBackupFolder(context)
    }

    override fun exportBackup(context: Context) {
        val appContext = context.applicationContext
        val snap = getExportSnapshot()

        if (snap.closedSessions.isEmpty() && snap.tagSessions.isEmpty()) {
            showLongToast(
                appContext,
                context.getString(R.string.export_no_sessions_hint),
            )
        }

        if (!exportInProgress.compareAndSet(false, true)) {
            showLongToast(appContext, context.getString(R.string.export_already_running))
            return
        }

        exportScope.launch {
            try {
                exportBackupBlocking(appContext, snap)
            } finally {
                exportInProgress.set(false)
            }
        }
    }

    private fun exportBackupBlocking(context: Context, snap: ImportExportSnapshot) {
        try {
            val root = VaultFolders.ensureRoot(context)
            val dir = root.exports

            // 0) Always refresh the DB vault copy first (survives uninstall/reinstall).
            runCatching { com.example.multitimetracker.persistence.SqliteVault.exportNow(context) }

            // 1) Create a single ZIP containing CSV+JSON exports (no loose files in the folder).
            val now = System.currentTimeMillis()
            val zipName = buildManualExportZipName(now)

            // Compute signature once and reuse both for Settings + backup metadata.
            val signature = computeBackupSignature()

            val zipResult = ZipBackupExporter.exportCsvJsonZip(
                context = context,
                parentDir = dir,
                fileName = zipName
            ) { tmpDir ->
                // Snapshot lists are kept as List<Any> to avoid coupling this capsule to
                // the full ViewModel state type. Casting/validation lives in export layer.
                SnapshotExportAdapter.exportAllToDirectoryFromSnapshot(
                    context = context,
                    dir = tmpDir,
                    tasks = snap.tasks,
                    tags = snap.tags,
                    closedSessions = snap.closedSessions,
                    tagSessions = snap.tagSessions,
                    tagParentsByChild = snap.tagParentsByChild,
                    lifePeriods = snap.lifePeriods,
                    timeFenceRules = snap.timeFenceRules,
                    chains = snap.chains,
                    activeChainRun = snap.activeChainRun,
                    appUsageMs = snap.appUsageMs,
                    quickEventTemplates = snap.quickEventTemplates,
                    quickEventEntries = snap.quickEventEntries,
                    quickEventFieldDefinitions = snap.quickEventFieldDefinitions,
                    quickEventFieldValues = snap.quickEventFieldValues,
                    quickEventMacros = snap.quickEventMacros,
                    quickEventMacroActions = snap.quickEventMacroActions
                )

                // Extra metadata for smoke-checking and debugging.
                BackupMetaWriter.write(
                    context = context,
                    dir = tmpDir,
                    exportedAtMs = now,
                    zipFileName = zipName,
                    backupSignature = signature
                )
            }

            // v395: Record export into the SAF index (best effort).
            runCatching {
                val doc = dir.findFile(zipResult.zipFileName)
                if (doc != null && doc.isFile) {
                    VaultIndex.record(
                        context = context,
                        kind = VaultIndex.Kind.EXPORT_ZIP,
                        file = doc,
                        vaultName = null,
                        reason = "manual_export",
                        statsJson = runCatching { com.example.multitimetracker.persistence.IntegrityStatsSqlite.computeInternal(context).toJson() }.getOrNull(),
                        sha256Hex = VaultIndex.computeSha256Hex(context, doc)
                    )
                }
            }

            val zipSha256 = runCatching {
                dir.findFile(zipResult.zipFileName)?.let { DocHash.sha256Hex(context, it) }
            }.getOrNull()

            // 2) Update Settings timestamps.
            UiPrefsStore.setLastManualExportMs(context, now)
            UiPrefsStore.setLastManualExportMeta(
                context = context,
                zipName = zipResult.zipFileName,
                zipSha256 = zipSha256,
                backupSignature = signature
            )

            // 3) Audit log (manual export only).
            runCatching {
                val payload = JSONObject()
                    .put("zipFile", zipResult.zipFileName)
                    .put("exportedAtMs", now)
                    .put("backupSignature", signature)
                if (!zipSha256.isNullOrBlank()) payload.put("zipSha256", zipSha256)

                AuditLogSqlite.insert(
                    context = context,
                    isSystem = false,
                    action = "EXPORT_MANUAL",
                    summary = context.getString(R.string.audit_export_manual, zipResult.zipFileName),
                    payload = payload
                )

                // System event: stable marker for tooling / diagnostics.
                AuditLogSqlite.insert(
                    context = context,
                    isSystem = true,
                    action = "EXPORT_COMPLETED",
                    summary = context.getString(R.string.audit_export_completed, zipResult.zipFileName),
                    payload = payload
                )
            }

            runOnMain { setLastBackupSignature(signature) }
            showLongToast(
                context,
                context.getString(R.string.export_done_zip, zipResult.zipFileName),
            )
        } catch (e: Exception) {
            // If this is a SAF-related failure (permissions revoked, provider errors),
            // clear the saved URI so the next tap will re-open the folder picker.
            if (e is SecurityException || e is IllegalStateException) {
                BackupFolderStore.clearTreeUri(context)
            }
            showLongToast(context, context.getString(R.string.export_failed_fmt, (e.message ?: "")))
        }
    }

    override fun exportCsv(context: Context) {
        val payload = getExportSnapshot()

        if (payload.closedSessions.isEmpty() && payload.tagSessions.isEmpty()) {
            Toast.makeText(
                context,
                context.getString(R.string.export_nothing_to_export),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        try {
            val files = CsvExporter.exportManualShareFiles(
                context = context,
                tasks = typedList<Task>(payload.tasks),
                tags = typedList<Tag>(payload.tags),
                closedSessions = typedList<ClosedSessionRecord>(payload.closedSessions),
                tagSessions = typedList<TaggedSessionRecord>(payload.tagSessions),
                tagParentsByChild = payload.tagParentsByChild,
                lifePeriods = typedList<LifePeriod>(payload.lifePeriods),
                timeFenceRules = typedList<TimeFenceRule>(payload.timeFenceRules),
                chains = typedList<TaskChain>(payload.chains),
                activeChainRun = payload.activeChainRun as? ActiveChainRun,
                appUsageMs = payload.appUsageMs,
                nowMs = System.currentTimeMillis(),
                quickEventTemplates = typedList<QuickEventTemplate>(payload.quickEventTemplates),
                quickEventEntries = typedList<QuickEventEntry>(payload.quickEventEntries),
                quickEventFieldDefinitions = typedList<QuickEventFieldDefinition>(payload.quickEventFieldDefinitions),
                quickEventFieldValues = typedList<QuickEventFieldValue>(payload.quickEventFieldValues),
                quickEventMacros = typedList<QuickEventMacro>(payload.quickEventMacros),
                quickEventMacroActions = typedList<QuickEventMacroAction>(payload.quickEventMacroActions),
            )

            ShareUtils.shareFiles(context, files, title = context.getString(R.string.export_share_title))
        } catch (t: Throwable) {
            Log.e("MT_IMPORT", "exportCsv: failed", t)
            Toast.makeText(
                context,
                context.getString(R.string.export_share_failed_fmt, t.message ?: t::class.java.simpleName),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> typedList(values: List<Any>): List<T> = values as List<T>

    override fun importCsv(context: Context, uris: List<Uri>, scope: CoroutineScope) {
        scope.launch {
            try {
                Log.i("MT_IMPORT", "importCsv: START uris=${uris.size}")
                val snapshot = CsvImporter.importFromUris(context, uris)
                Log.i(
                    "MT_IMPORT",
                    "importCsv: parsed snapshot tasks=${snapshot.tasks.size} tags=${snapshot.tags.size} " +
                        "closedSessions=${snapshot.closedSessions.size} tagSessions=${snapshot.tagSessions.size} runtime=${snapshot.runtimeSnapshot != null}"
                )

                applyImportedCsvSnapshot(snapshot)
                Log.i("MT_IMPORT", "importCsv: imported snapshot applied")
                Log.i("MT_IMPORT", "importCsv: END")

                Toast.makeText(
                    context,
                    context.getString(R.string.import_completed_counts_fmt, snapshot.tasks.size, snapshot.tags.size),
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Log.e("MT_IMPORT", "importCsv: FAILED ${e.message ?: e::class.java.simpleName}", e)
                Toast.makeText(
                    context,
                    context.getString(R.string.import_failed_fmt, e.message ?: e::class.java.simpleName),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Async import wrapper used by MainViewModel.
     *
     * IMPORTANT: kept inside the capsule to avoid any direct access to MainViewModel state.
     */

override fun importDatabaseFromUri(context: Context, uri: Uri, scope: CoroutineScope) {
    scope.launch {
        try {
            val finalized = finalizeImportedDb(
                context = context,
                importAction = {
                    com.example.multitimetracker.persistence.SqliteVault.overwriteInternalDbFromUri(context, uri)
                }
            ) ?: return@launch

            UiPrefsStore.setLastImportMs(context, System.currentTimeMillis())
            UiPrefsStore.setLastImportMeta(
                context = context,
                dbFileName = finalized.importRes.importedFileName,
                dbSha256 = finalized.importRes.sha256Hex,
                beforeSignature = finalized.beforeSignature,
                afterSignature = finalized.afterSignature
            )
            setLastBackupSignature(finalized.afterSignature)
            scheduleAutoBackup()

            runCatching {
                val payload = JSONObject()
                    .put("dbFile", finalized.importRes.importedFileName)
                    .put("dbSha256", finalized.importRes.sha256Hex ?: JSONObject.NULL)
                    .put("beforeSignature", finalized.beforeSignature)
                    .put("afterSignature", finalized.afterSignature)
                AuditLogSqlite.insert(
                    context = context,
                    isSystem = false,
                    action = "IMPORT_DB",
                    summary = context.getString(R.string.audit_import_db, finalized.importRes.importedFileName),
                    payload = payload
                )
            }

            Toast.makeText(context, context.getString(R.string.import_done), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.import_failed, e.message ?: "?"), Toast.LENGTH_LONG).show()
        }
    }
}


    override fun restoreLastPreImportBackup(context: Context, scope: CoroutineScope) {
        scope.launch {
            try {
                val restored = restoreLastPreImportBackupBlocking(context)
                val messageId = if (restored) {
                    R.string.import_restore_backup_done
                } else {
                    R.string.import_restore_backup_missing
                }
                Toast.makeText(context, context.getString(messageId), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.import_restore_backup_failed, e.message ?: "?"), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun restoreLastPreImportBackupBlocking(context: Context): Boolean {
        val ok = com.example.multitimetracker.persistence.SqliteVault.restoreInternalDbFromPreImportBackup(context)
        if (!ok) return false

        val gate = DataIntegrityGate.runCriticalChecks(context)
        if (!gate.ok) {
            throw IllegalStateException(
                releaseSafeImportDetail(
                    context,
                    gate.technicalReport ?: gate.blockingBody ?: context.getString(R.string.integrity_gate_body)
                )
            )
        }

        val snap = SnapshotStore.load(context)
            ?: throw IllegalStateException(context.getString(R.string.import_snapshot_unreadable))
        activateImportedSnapshotFromStore(context, snap)?.let { failure ->
            throw IllegalStateException(failure)
        }
        setLastBackupSignature(computeBackupSignature())
        scheduleAutoBackup()
        setImportVerificationReport(null)

        runCatching {
            val payload = JSONObject().put("source", "preimport_backup")
            AuditLogSqlite.insert(
                context = context,
                isSystem = false,
                action = "IMPORT_ROLLBACK_RESTORE_MANUAL",
                summary = context.getString(R.string.audit_import_restore_backup),
                payload = payload
            )
        }

        return true
    }
    override fun importBackup(context: Context, scope: CoroutineScope) {
        scope.launch {
            try {
                val imported = importBackupBlocking(context)
                if (imported) {
                    Toast.makeText(context, context.getString(R.string.import_done), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                if (e is SecurityException || e is IllegalStateException) {
                    BackupFolderStore.clearTreeUri(context)
                }
                Toast.makeText(
                    context,
                    context.getString(R.string.import_failed, e.message ?: "?"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

        /**
     * Import from SAF backup folder.
     *
     * - Prefers DB vault import (multitimer.db) which overwrites the internal DB.
     * - Restores the in-app snapshot (SnapshotStore) and applies it via hook.
     */
    override suspend fun importBackupBlocking(context: Context): Boolean {
        return importBackupBlocking(context) {
            BackupFolderStore.getOrCreateDataDir(context)
            com.example.multitimetracker.persistence.SqliteVault
                .overwriteInternalDbFromUserFolder(context)
                ?: throw IllegalStateException(context.getString(R.string.backup_db_not_found))
        }
    }

    private fun importBackupBlocking(
        context: Context,
        importAction: () -> com.example.multitimetracker.persistence.SqliteVault.ImportResult
    ): Boolean {
        val finalized = finalizeImportedDb(
            context = context,
            importAction = importAction
        ) ?: return false

        UiPrefsStore.setLastImportMs(context, System.currentTimeMillis())

        UiPrefsStore.setLastImportMeta(
            context = context,
            dbFileName = finalized.importRes.importedFileName,
            dbSha256 = finalized.importRes.sha256Hex,
            beforeSignature = finalized.beforeSignature,
            afterSignature = finalized.afterSignature
        )

        runCatching {
            val payload = JSONObject()
                .put("importedFiles", org.json.JSONArray().put(finalized.importRes.importedFileName))
                .put("beforeBackupSignature", finalized.beforeSignature)
                .put("afterBackupSignature", finalized.afterSignature)
            if (!finalized.importRes.sha256Hex.isNullOrBlank()) {
                payload.put("importedDbSha256", finalized.importRes.sha256Hex)
            }

            AuditLogSqlite.insert(
                context = context,
                isSystem = true,
                action = "IMPORT",
                summary = context.getString(R.string.audit_import, finalized.importRes.importedFileName),
                payload = payload
            )

            AuditLogSqlite.insert(
                context = context,
                isSystem = true,
                action = "IMPORT_COMPLETED",
                summary = context.getString(R.string.audit_import_completed, finalized.importRes.importedFileName),
                payload = payload
            )
        }

        setLastBackupSignature(finalized.afterSignature)
        scheduleAutoBackup()
        return true
    }

    private fun hasMeaningfulPersistedState(context: Context): Boolean {
        val snapshot = SnapshotStore.load(context)
        val snapshotHasUserData = snapshot != null && (
            snapshot.tasks.isNotEmpty() ||
                snapshot.tags.isNotEmpty() ||
                snapshot.closedSessions.isNotEmpty() ||
                snapshot.tagSessions.isNotEmpty() ||
                snapshot.lifePeriods.isNotEmpty() ||
                snapshot.timeFenceRules.isNotEmpty() ||
                snapshot.activeSessionStart.isNotEmpty() ||
                snapshot.activeTagStart.isNotEmpty() ||
                snapshot.tagParents.isNotEmpty() ||
                snapshot.chains.isNotEmpty() ||
                snapshot.activeChainRun != null
            )
        if (snapshotHasUserData) return true

        return runCatching {
            val db = SnapshotSqlite.openReadableDb(context)
            try {
                val sessions = db.rawQuery(
                    "SELECT COUNT(*) FROM ${SnapshotSqlite.SESSIONS_TABLE}",
                    null
                ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
                val sessionTags = db.rawQuery(
                    "SELECT COUNT(*) FROM ${SnapshotSqlite.SESSION_TAGS_TABLE}",
                    null
                ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
                sessions > 0L || sessionTags > 0L
            } finally {
                db.close()
            }
        }.getOrDefault(false)
    }

    private fun ensurePreImportBackupIfNeeded(context: Context) {
        if (!hasMeaningfulPersistedState(context)) return

        val backup = com.example.multitimetracker.persistence.SqliteVault.createPreImportBackup(context)
            ?: throw IllegalStateException(context.getString(R.string.import_prebackup_failed))

        if (!backup.exists() || backup.length() <= 0L) {
            throw IllegalStateException(context.getString(R.string.import_prebackup_failed))
        }
    }

    private fun finalizeImportedDb(
        context: Context,
        importAction: () -> com.example.multitimetracker.persistence.SqliteVault.ImportResult
    ): FinalizedImport? {
        val beforeSnapshot = SnapshotStore.load(context)
        val beforeSignature = computeBackupSignature()
        ensurePreImportBackupIfNeeded(context)
        val importRes = try {
            importAction()
        } catch (t: Throwable) {
            val report = buildProblemReport(
                context = context,
                details = listOf(t.message ?: t::class.java.simpleName)
            )
            val hadPreImportBackup = com.example.multitimetracker.persistence.SqliteVault.hasPreImportBackup(context)
            val restoredPreviousData = if (hadPreImportBackup) {
                runCatching {
                    com.example.multitimetracker.persistence.SqliteVault.restoreInternalDbFromPreImportBackup(context)
                }.getOrDefault(false)
            } else {
                false
            }
            val finalReport = when {
                restoredPreviousData && restoreRollbackSnapshotState(context, beforeSnapshot) ->
                    report + "\n\n" + context.getString(R.string.import_verify_auto_restored)
                !hadPreImportBackup && clearRollbackSnapshotState(context) ->
                    report + "\n\n" + context.getString(R.string.import_verify_empty_setup_auto_restored)
                else ->
                    report + "\n\n" + context.getString(R.string.import_verify_rollback_failed)
            }
            setImportVerificationReport(finalReport)
            throw t
        }

        val gate = DataIntegrityGate.runCriticalChecks(context)
        if (!gate.ok) {
            val report = buildProblemReport(
                context = context,
                details = listOfNotNull(
                    gate.technicalReport ?: gate.blockingBody ?: context.getString(R.string.integrity_gate_body)
                )
            )
            val rollbackOutcome = rollbackFailedImport(
                context = context,
                importRes = importRes,
                report = report,
                rollbackSnapshot = beforeSnapshot,
                expected = importRes.expectedStats,
                actual = gate.stats
            )
            if (rollbackOutcome != ImportRollbackOutcome.FAILED) return null
            throw IllegalStateException(context.getString(R.string.import_verify_rollback_failed))
        }

        val actual = gate.stats ?: IntegrityStatsSqlite.computeInternal(context)
        val diffLines = buildImportDiffLines(expected = importRes.expectedStats, actual = actual)
        if (diffLines.isNotEmpty()) {
            val report = buildMismatchReport(context = context, diffs = diffLines)
            val rollbackOutcome = rollbackFailedImport(
                context = context,
                importRes = importRes,
                report = report,
                rollbackSnapshot = beforeSnapshot,
                expected = importRes.expectedStats,
                actual = actual
            )
            if (rollbackOutcome != ImportRollbackOutcome.FAILED) return null
            throw IllegalStateException(context.getString(R.string.import_verify_rollback_failed))
        }

        val snap = SnapshotStore.load(context)
            ?: run {
                val report = buildProblemReport(
                    context = context,
                    details = listOf(context.getString(R.string.import_snapshot_unreadable))
                )
                val rollbackOutcome = rollbackFailedImport(
                    context = context,
                    importRes = importRes,
                    report = report,
                    rollbackSnapshot = beforeSnapshot,
                    expected = importRes.expectedStats,
                    actual = actual
                )
                if (rollbackOutcome != ImportRollbackOutcome.FAILED) return null
                throw IllegalStateException(context.getString(R.string.import_snapshot_unreadable))
            }

        val runtimeActivationResult = runCatching {
            activateImportedSnapshotFromStore(context, snap)
        }
        val applyError = runtimeActivationResult.exceptionOrNull()
        if (applyError != null) {
            return keepVerifiedImportWithRuntimeActivationProblem(
                context = context,
                importRes = importRes,
                beforeSignature = beforeSignature,
                snap = snap,
                actual = actual,
                detail = context.getString(
                    R.string.import_apply_failed_fmt,
                    (applyError.message ?: applyError::class.java.simpleName)
                ),
                throwable = applyError
            )
        }

        val runtimeActivationFailure = runtimeActivationResult.getOrNull()
        if (runtimeActivationFailure != null) {
            return keepVerifiedImportWithRuntimeActivationProblem(
                context = context,
                importRes = importRes,
                beforeSignature = beforeSignature,
                snap = snap,
                actual = actual,
                detail = runtimeActivationFailure,
                throwable = null
            )
        }

        setImportVerificationReport(null)
        val afterSignature = snapshotSignature(snap)
        return FinalizedImport(
            importRes = importRes,
            beforeSignature = beforeSignature,
            afterSignature = afterSignature
        )
    }

    private fun keepVerifiedImportWithRuntimeActivationProblem(
        context: Context,
        importRes: com.example.multitimetracker.persistence.SqliteVault.ImportResult,
        beforeSignature: String,
        snap: SnapshotStore.Snapshot,
        actual: IntegrityStatsSqlite.Stats,
        detail: String,
        throwable: Throwable?,
    ): FinalizedImport {
        val afterSignature = snapshotSignature(snap)
        val report = buildProblemReport(
            context = context,
            details = listOf(detail)
        ) + "\n\n" + context.getString(R.string.import_verify_valid_db_kept)
        setImportVerificationReport(report)

        runCatching {
            ForensicLog.record(
                context = context,
                component = "ImportExportCapsule",
                action = "import_runtime_activation_failure_kept",
                sourceFile = importRes.importedFileName,
                destFile = SnapshotSqlite.DB_NAME,
                counts = CriticalDataGuard.readInternal(context),
                error = detail,
                throwable = throwable,
            )
            val payload = JSONObject()
                .put("dbFile", importRes.importedFileName)
                .put("dbSha256", importRes.sha256Hex ?: JSONObject.NULL)
                .put("beforeSignature", beforeSignature)
                .put("afterSignature", afterSignature)
                .put("report", report)
                .put("autoRollback", false)
                .put("expected", importRes.expectedStats.toJson())
                .put("actual", actual.toJson())
            AuditLogSqlite.insert(
                context = context,
                isSystem = false,
                action = "IMPORT_VERIFY_VALID_DB_KEPT",
                summary = context.getString(R.string.audit_import_verify_valid_db_kept),
                payload = payload
            )
        }

        return FinalizedImport(
            importRes = importRes,
            beforeSignature = beforeSignature,
            afterSignature = afterSignature
        )
    }

    private fun snapshotSignature(snapshot: SnapshotStore.Snapshot): String {
        return AuthoritativeExportPayloadBuilder.signature(
            AuthoritativeExportPayloadBuilder.fromSnapshot(snapshot)
        )
    }

    private fun buildImportDiffLines(
        expected: IntegrityStatsSqlite.Stats,
        actual: IntegrityStatsSqlite.Stats
    ): List<String> {
        val diffs = ArrayList<String>()

        fun diff(name: String, exp: Long, act: Long) {
            if (exp != act) diffs.add("$name: expected=$exp, imported=$act")
        }

        diff("sessions", expected.sessions, actual.sessions)
        diff("sessionTags", expected.sessionTags, actual.sessionTags)
        diff("tasks", expected.tasks, actual.tasks)
        diff("tags", expected.tags, actual.tags)
        diff("closedSessions", expected.closedSessions, actual.closedSessions)
        diff("tagSessions", expected.tagSessions, actual.tagSessions)
        diff("timeFenceRules", expected.timeFenceRules, actual.timeFenceRules)
        diff("tagParents", expected.tagParents, actual.tagParents)
        diff("lifePeriods", expected.lifePeriods, actual.lifePeriods)
        diff("chains", expected.chains, actual.chains)
        diff("totalClosedSessionMs", expected.totalClosedSessionMs, actual.totalClosedSessionMs)
        diff("runningSessions", expected.runningSessions, actual.runningSessions)

        return diffs
    }

    private fun buildMismatchReport(context: Context, diffs: List<String>): String {
        return buildString {
            appendLine(context.getString(R.string.import_verify_header))
            appendLine()
        if (BuildConfig.DEBUG) {
            diffs.forEach { appendLine("- $it") }
        } else {
            appendLine("- ${context.getString(R.string.import_verify_release_mismatch_detail)}")
        }
        }.trim()
    }

    private fun buildProblemReport(context: Context, details: List<String>): String {
        return buildString {
            appendLine(context.getString(R.string.import_verify_problem_header))
            appendLine()
            if (BuildConfig.DEBUG) {
                details.filter { it.isNotBlank() }.forEach { appendLine("- $it") }
            } else {
                appendLine("- ${context.getString(R.string.import_verify_release_problem_detail)}")
            }
        }.trim()
    }

    private fun releaseSafeImportDetail(context: Context, detail: String): String =
        if (BuildConfig.DEBUG) detail else context.getString(R.string.import_verify_release_problem_detail)

    private fun rollbackFailedImport(
        context: Context,
        importRes: com.example.multitimetracker.persistence.SqliteVault.ImportResult,
        report: String,
        rollbackSnapshot: SnapshotStore.Snapshot?,
        expected: IntegrityStatsSqlite.Stats?,
        actual: IntegrityStatsSqlite.Stats?
    ): ImportRollbackOutcome {
        val hadPreImportBackup = com.example.multitimetracker.persistence.SqliteVault.hasPreImportBackup(context)
        val restoredPreviousData = runCatching {
            com.example.multitimetracker.persistence.SqliteVault.restoreInternalDbFromPreImportBackup(context)
        }.getOrDefault(false)
        val restoredRollbackSnapshotOnly = !restoredPreviousData &&
            rollbackSnapshot != null &&
            restoreRollbackSnapshotState(context, rollbackSnapshot)
        val rollbackOutcome = when {
            restoredPreviousData || restoredRollbackSnapshotOnly -> ImportRollbackOutcome.PREVIOUS_DATA_RESTORED
            !hadPreImportBackup ->
                ImportRollbackOutcome.RESET_TO_EMPTY_SETUP
            else -> ImportRollbackOutcome.FAILED
        }
        val verifiedRollbackOutcome = when (rollbackOutcome) {
            ImportRollbackOutcome.PREVIOUS_DATA_RESTORED ->
                if (restoredRollbackSnapshotOnly || restoreRollbackSnapshotState(context, rollbackSnapshot)) {
                    ImportRollbackOutcome.PREVIOUS_DATA_RESTORED
                } else {
                    ImportRollbackOutcome.FAILED
                }

            ImportRollbackOutcome.RESET_TO_EMPTY_SETUP ->
                if (clearRollbackSnapshotState(context)) {
                    ImportRollbackOutcome.RESET_TO_EMPTY_SETUP
                } else {
                    ImportRollbackOutcome.FAILED
                }

            ImportRollbackOutcome.FAILED -> ImportRollbackOutcome.FAILED
        }

        val finalReport = when (verifiedRollbackOutcome) {
            ImportRollbackOutcome.PREVIOUS_DATA_RESTORED ->
                report + "\n\n" + context.getString(R.string.import_verify_auto_restored)
            ImportRollbackOutcome.RESET_TO_EMPTY_SETUP ->
                report + "\n\n" + context.getString(R.string.import_verify_empty_setup_auto_restored)
            ImportRollbackOutcome.FAILED ->
                report + "\n\n" + context.getString(R.string.import_verify_rollback_failed)
        }
        setImportVerificationReport(finalReport)

        runCatching {
            val payload = JSONObject()
                .put("dbFile", importRes.importedFileName)
                .put("dbSha256", importRes.sha256Hex ?: JSONObject.NULL)
                .put("report", report)
                .put("autoRollback", verifiedRollbackOutcome != ImportRollbackOutcome.FAILED)
                .put("rollbackMode", verifiedRollbackOutcome.name)
            if (expected != null) payload.put("expected", expected.toJson())
            if (actual != null) payload.put("actual", actual.toJson())

            AuditLogSqlite.insert(
                context = context,
                isSystem = false,
                action = if (verifiedRollbackOutcome != ImportRollbackOutcome.FAILED) "IMPORT_VERIFY_MISMATCH_ROLLBACK" else "IMPORT_VERIFY_PROBLEM",
                summary = if (verifiedRollbackOutcome != ImportRollbackOutcome.FAILED) {
                    context.getString(R.string.audit_import_verify_mismatch_rollback)
                } else {
                    context.getString(R.string.audit_import_verify_problem)
                },
                payload = payload
            )
        }

        if (verifiedRollbackOutcome == ImportRollbackOutcome.FAILED) return verifiedRollbackOutcome

        when (verifiedRollbackOutcome) {
            ImportRollbackOutcome.PREVIOUS_DATA_RESTORED -> {
                scheduleAutoBackup()
                showLongToast(context, context.getString(R.string.import_verify_rollback_done))
            }

            ImportRollbackOutcome.RESET_TO_EMPTY_SETUP -> {
                showLongToast(context, context.getString(R.string.import_verify_empty_setup_done))
            }

            ImportRollbackOutcome.FAILED -> Unit
        }
        return verifiedRollbackOutcome
    }

    private fun restoreRollbackSnapshotState(
        context: Context,
        rollbackSnapshot: SnapshotStore.Snapshot?,
    ): Boolean {
        val snap = rollbackSnapshot ?: SnapshotStore.load(context) ?: return false
        return runCatching {
            SnapshotStore.save(
                context = context,
                tasks = snap.tasks,
                tags = snap.tags,
                closedSessions = snap.closedSessions,
                tagSessions = snap.tagSessions,
                lifePeriods = snap.lifePeriods,
                timeFenceRules = snap.timeFenceRules,
                installAtMs = snap.installAtMs,
                appUsageMs = snap.appUsageMs,
                activeSessionStart = snap.activeSessionStart,
                activeTagStart = snap.activeTagStart,
                tagParents = snap.tagParents,
                chains = snap.chains,
                activeChainRun = snap.activeChainRun,
                chronologySessions = snap.chronologySessions,
                runningSessions = snap.runningSessions,
                quickEventTemplates = snap.quickEventTemplates,
                quickEventEntries = snap.quickEventEntries,
                quickEventFieldDefinitions = snap.quickEventFieldDefinitions,
                quickEventFieldValues = snap.quickEventFieldValues,
                quickEventMacros = snap.quickEventMacros,
                quickEventMacroActions = snap.quickEventMacroActions,
            )
            IntegrityStatsSqlite.refreshInternal(context)
            if (SnapshotStore.load(context) != snap) return false
            activateImportedSnapshotFromStore(context, snap) == null
        }.getOrDefault(false)
    }

    private fun clearRollbackSnapshotState(context: Context): Boolean {
        val snapshotPrefs = "multitimetracker_snapshot"
        val snapshotKey = "snapshot_json"
        runCatching {
            com.example.multitimetracker.persistence.SqliteVault.clearInternalDbForFreshStart(context)
        }
        runCatching {
            val db = SnapshotSqlite.openWritableDb(context)
            try {
                db.beginTransaction()
                db.delete("snapshot", null, null)
                db.delete("snapshot_history", null, null)
                db.delete("snapshot_payloads", null, null)
                db.delete(SnapshotSqlite.SESSIONS_TABLE, null, null)
                db.delete(SnapshotSqlite.SESSION_TAGS_TABLE, null, null)
                db.delete(SnapshotSqlite.AUDIT_TABLE, null, null)
                db.delete("integrity_stats", null, null)
                db.delete("ui_prefs_mirror", null, null)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
                db.close()
            }
            com.example.multitimetracker.persistence.PersistentMutationTracker.record(
                context,
                "ImportExportCapsuleViewModel.clearRollbackSnapshotState"
            )
        }
        runCatching { SnapshotSqlite.checkpointWal(context) }
        context.getSharedPreferences(snapshotPrefs, Context.MODE_PRIVATE)
            .edit()
            .remove(snapshotKey)
            .commit()
        runCatching {
            context.getExternalFilesDir(null)
                ?.let { java.io.File(it, "snapshot.json") }
                ?.takeIf { it.exists() }
                ?.delete()
        }

        val snapshotRowMissing = runCatching {
            SnapshotSqlite.readSnapshot(context) == null
        }.getOrDefault(false)
        val sessionTablesEmpty = runCatching {
            val db = SnapshotSqlite.openReadableDb(context)
            try {
                val sessions = db.rawQuery(
                    "SELECT COUNT(*) FROM ${SnapshotSqlite.SESSIONS_TABLE}",
                    null
                ).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
                val sessionTags = db.rawQuery(
                    "SELECT COUNT(*) FROM ${SnapshotSqlite.SESSION_TAGS_TABLE}",
                    null
                ).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
                sessions == 0L && sessionTags == 0L
            } finally {
                db.close()
            }
        }.getOrDefault(false)
        val prefSnapshotMissing = context.getSharedPreferences(snapshotPrefs, Context.MODE_PRIVATE)
            .getString(snapshotKey, null) == null
        val externalSnapshotMissing =
            context.getExternalFilesDir(null)
                ?.let { java.io.File(it, "snapshot.json") }
                ?.exists() != true

        return snapshotRowMissing &&
            sessionTablesEmpty &&
            prefSnapshotMissing &&
            externalSnapshotMissing
    }

    /**
     * Hook for callers that need to persist & trigger auto-export after import/restore.
     */
    fun onDataChanged() {
        persist()
        scheduleAutoBackup()
    }
}
