// v455
package com.example.multitimetracker.persistence

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.documentfile.provider.DocumentFile
import com.example.multitimetracker.BuildConfig
import com.example.multitimetracker.R
import com.example.multitimetracker.export.BackupFolderStore
import java.io.File
import java.io.FileOutputStream

/**
 * Keeps a copy of the internal SQLite database inside the user-selected "MultiTimer data" folder.
 *
 * Why:
 * - the internal app sandbox can be wiped on uninstall/reinstall or signing changes
 * - the user folder is sync'ed (Autosync) and survives uninstall
 * - Datasette can open the external .db directly
 *
 * This is intentionally a *copy* (vault), not a live database file, to avoid sync tools
 * touching a DB file while it's being actively written.
 */
object SqliteVault {

    private const val PREFS = "multitimetracker_sqlite_vault"
    private const val KEY_LAST_EXPORT_MS = "last_export_ms"
    private const val KEY_PENDING_STARTUP_RESTORE_SOURCE = "pending_startup_restore_source"

    // Export can be triggered by every DB save; keep it cheap.
    private const val EXPORT_THROTTLE_MS = 5_000L

    private const val VAULT_DB_NAME = BackupFolderPolicy.PRIMARY_DB_NAME
    private const val VAULT_DB_BAK_NAME = BackupFolderPolicy.EMERGENCY_DB_NAME
    private const val VAULT_DB_TMP_NAME = BackupFolderPolicy.TEMP_DB_NAME

    // v327: backup used for import rollback (internal sandbox)
    private const val PREIMPORT_BACKUP_NAME = "multitimer_preimport_backup.db"
    private const val INSTRUMENTATION_SAFETY_BACKUP_NAME = "multitimer_instrumentation_safety_backup.db"

    @Volatile internal var duringExportForTests: (() -> Unit)? = null

    data class ImportResult(
        val importedFileName: String,
        val sha256Hex: String?,
        val expectedStats: IntegrityStatsSqlite.Stats
    )

    private data class ValidatedImportCandidate(
        val sha256Hex: String?,
        val expectedStats: IntegrityStatsSqlite.Stats
    )

    private fun copyDocToDoc(context: Context, from: DocumentFile, to: DocumentFile) {
        context.contentResolver.openInputStream(from.uri).use { input ->
            requireNotNull(input) { "openInputStream returned null" }
            context.contentResolver.openOutputStream(to.uri, "wt").use { output ->
                requireNotNull(output) { "openOutputStream returned null" }
                input.copyTo(output)
                output.flush()
                (output as? FileOutputStream)?.fd?.sync()
            }
        }
    }

    private fun copyFileToDoc(context: Context, from: File, to: DocumentFile) {
        from.inputStream().use { input ->
            context.contentResolver.openOutputStream(to.uri, "wt").use { output ->
                requireNotNull(output) { "openOutputStream returned null" }
                input.copyTo(output)
                output.flush()
                (output as? FileOutputStream)?.fd?.sync()
            }
        }
    }

    private fun createExactVaultFile(dir: DocumentFile, mimeType: String, displayName: String): DocumentFile? {
        dir.findFile(displayName)?.delete()
        val created = dir.createFile(mimeType, displayName) ?: return null
        if (created.name == displayName) return created

        if (created.renameTo(displayName)) {
            return dir.findFile(displayName) ?: created
        }

        runCatching { created.delete() }
        return null
    }

    private fun copyDocToFile(context: Context, from: DocumentFile, to: File) {
        context.contentResolver.openInputStream(from.uri).use { input ->
            requireNotNull(input) { "openInputStream returned null" }
            to.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun copyUriToFile(context: Context, uri: android.net.Uri, to: File) {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "openInputStream returned null" }
            to.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun displayNameForUri(context: Context, uri: android.net.Uri): String? {
        val fromResolver = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                }
        }.getOrNull()

        return fromResolver
            ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun sha256Hex(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { b -> "%02x".format(b) }
    }
    fun inspectBackupFolder(context: Context): com.example.multitimetracker.BackupFolderInspection {
        val descriptor = BackupFolderStore.describeDataDir(context)
        return com.example.multitimetracker.inspectBackupFolderEntries(
            folderLabel = descriptor.displayName,
            entryNames = descriptor.entryNames,
        )
    }

    private fun deleteStaleStableCopyDuplicates(dir: DocumentFile) {
        val stablePrefixes = listOf(VAULT_DB_NAME, VAULT_DB_BAK_NAME, VAULT_DB_TMP_NAME)
        dir.listFiles()
            .filter { it.isFile }
            .filter { doc ->
                val name = doc.name ?: return@filter false
                name !in stablePrefixes && stablePrefixes.any { stable -> name.startsWith("$stable ") }
            }
            .forEach { doc -> runCatching { doc.delete() } }
    }

    private fun validateVaultDocCopy(
        context: Context,
        doc: DocumentFile,
        expectedCounts: CriticalDataCounts,
        label: String,
    ) {
        val validationFile = File(context.cacheDir, "mtt_validate_${label}_${System.currentTimeMillis()}.db")
        runCatching { validationFile.delete() }
        try {
            copyDocToFile(context, doc, validationFile)
            validateSqliteIntegrity(context, validationFile)?.let { error ->
                throw IllegalStateException(error)
            }
            validateMinimumSchema(context, validationFile)?.let { error ->
                throw IllegalStateException(error)
            }
            val actualCounts = CriticalDataGuard.readFile(validationFile)
            CriticalDataGuard.requireNoCriticalDrop(
                context = context,
                component = "SqliteVault",
                action = "validate_$label",
                before = expectedCounts,
                after = actualCounts,
                sourceFile = SnapshotSqlite.DB_NAME,
                destFile = doc.name,
            )
        } finally {
            runCatching { validationFile.delete() }
        }
    }

    private fun copyValidatedDocToDoc(
        context: Context,
        from: DocumentFile,
        to: DocumentFile,
        expectedCounts: CriticalDataCounts,
        label: String,
    ) {
        val rollbackFile = File(context.cacheDir, "mtt_rollback_${label}_${System.currentTimeMillis()}.db")
        val hadPrevious = to.isFile && (to.length() > 0L)
        if (hadPrevious) {
            runCatching {
                copyDocToFile(context, to, rollbackFile)
                validateSqliteIntegrity(context, rollbackFile)?.let { throw IllegalStateException(it) }
            }.onFailure {
                runCatching { rollbackFile.delete() }
            }
        }
        copyDocToDoc(context, from, to)
        runCatching {
            validateVaultDocCopy(context, to, expectedCounts, label)
        }.onFailure { error ->
            if (hadPrevious && rollbackFile.exists()) {
                runCatching { copyFileToDoc(context, rollbackFile, to) }
            } else {
                runCatching { to.delete() }
            }
            throw error
        }.getOrThrow()
        runCatching { rollbackFile.delete() }
    }

    private fun replaceStableVaultDb(context: Context, dir: DocumentFile, internalDb: File) {
        val expectedCounts = CriticalDataGuard.readFile(internalDb)
        ForensicLog.record(
            context = context,
            component = "SqliteVault",
            action = "export_start",
            sourceFile = internalDb.name,
            destFile = VAULT_DB_NAME,
            counts = expectedCounts,
        )
        deleteStaleStableCopyDuplicates(dir)
        dir.findFile(VAULT_DB_TMP_NAME)?.delete()
        val tmp = createExactVaultFile(dir, "application/octet-stream", VAULT_DB_TMP_NAME)
            ?: throw IllegalStateException(context.getString(R.string.vault_create_db_failed_fmt, VAULT_DB_TMP_NAME))
        copyFileToDoc(context, internalDb, tmp)
        validateVaultDocCopy(context, tmp, expectedCounts, "tmp")
        duringExportForTests?.invoke()

        val bakFile = dir.findFile(VAULT_DB_BAK_NAME)?.takeIf { it.isFile }
            ?: createExactVaultFile(dir, "application/octet-stream", VAULT_DB_BAK_NAME)
            ?: throw IllegalStateException(context.getString(R.string.vault_create_bak_failed_fmt, VAULT_DB_BAK_NAME))
        copyValidatedDocToDoc(context, tmp, bakFile, expectedCounts, "bak")

        val existing = dir.findFile(VAULT_DB_NAME)?.takeIf { it.isFile }
        val outFile = existing
            ?: createExactVaultFile(dir, "application/octet-stream", VAULT_DB_NAME)
            ?: throw IllegalStateException(context.getString(R.string.vault_create_db_failed_fmt, VAULT_DB_NAME))

        runCatching {
            copyValidatedDocToDoc(context, tmp, outFile, expectedCounts, "promoted")
        }.recoverCatching {
            copyValidatedDocToDoc(context, bakFile, outFile, expectedCounts, "promoted_recovered")
        }.getOrThrow()

        tmp.delete()
        validateVaultDocCopy(context, outFile, expectedCounts, "final")
        ForensicLog.record(
            context = context,
            component = "SqliteVault",
            action = "export_success",
            sourceFile = internalDb.name,
            destFile = VAULT_DB_NAME,
            counts = expectedCounts,
        )
    }

    fun exportToUserFolderIfConfigured(context: Context, force: Boolean = false) {
        // If the user didn't pick a folder, do nothing.
        if (BackupFolderStore.getTreeUri(context) == null) return

        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_EXPORT_MS, 0L)
        if (!force && now - last < EXPORT_THROTTLE_MS) {
            val sync = SyncStatusStore.read(context)
            val alreadySynced =
                sync.lastSuccessfulExportAtMs + SyncStatusStore.SYNC_TOLERANCE_MS >= sync.lastDatabaseMutationAtMs
            if (alreadySynced) return
        }

        // Mirror UI prefs into SQLite so the vault copy contains ALL app state.
        runCatching { UiPrefsStore.mirrorAllToSqlite(context) }

        // v314: refresh integrity counters so the exported DB is self-describing.
        runCatching { IntegrityStatsSqlite.refreshInternal(context) }

        val internalDb = SnapshotSqlite.internalDbFile(context)
        if (!internalDb.exists() || internalDb.length() <= 0L) return

        SyncStatusStore.markExportStarted(context, VAULT_DB_NAME)
        runCatching {
            val checkpoint = SnapshotSqlite.checkpointWalOrThrow(context)
            validateSqliteIntegrity(context, internalDb)?.let { error ->
                throw IllegalStateException(error)
            }
            val dir = BackupFolderStore.getOrCreateDataDir(context)

            replaceStableVaultDb(context, dir, internalDb)
            deleteStaleStableCopyDuplicates(dir)

            val successAt = System.currentTimeMillis()
            prefs.edit().putLong(KEY_LAST_EXPORT_MS, successAt).apply()
            // For Settings UI
            UiPrefsStore.setLastAutoExportMs(context, successAt)
            SyncStatusStore.markExportSuccess(
                context = context,
                safFile = VAULT_DB_NAME,
                integrityCheck = "source/tmp/bak/final integrity_check ok; wal_checkpoint busy=${checkpoint.busy} log=${checkpoint.log} checkpointed=${checkpoint.checkpointed}",
                nowMs = successAt,
            )
        }.onFailure { error ->
            SyncStatusStore.markExportFailure(
                context = context,
                error = error.message ?: error::class.java.simpleName,
                safFile = VAULT_DB_NAME,
                integrityCheck = "export aborted before final integrity_check PASS",
            )
            ForensicLog.record(
                context = context,
                component = "SqliteVault",
                action = "export_failure",
                sourceFile = internalDb.name,
                destFile = VAULT_DB_NAME,
                counts = runCatching { CriticalDataGuard.readFile(internalDb) }.getOrNull(),
                throwable = error,
            )
        }
    }

    fun exportNow(context: Context) {
        // No throttle, used by the manual "export" action.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_EXPORT_MS, 0L)
            .commit()
        exportToUserFolderIfConfigured(context)
    }

    internal fun resetTestHooks() {
        duringExportForTests = null
    }

    /**
     * If we detect a fresh install (internal DB missing), try to restore from the user folder.
     */
    fun restoreFromUserFolderIfPossible(context: Context) {
        val internalDb = SnapshotSqlite.internalDbFile(context)
        if (internalDb.exists() && internalDb.length() > 0L) return
        if (BackupFolderStore.getTreeUri(context) == null) return
        // v211: guardrail - do NOT auto-restore unless the user explicitly enabled it.
        if (!UiPrefsStore.isVaultAutoRestoreEnabled(context)) return

        runCatching<Unit> {
            val result = overwriteInternalDbFromUserFolder(context) ?: return@runCatching
            recordPendingStartupRestore(context, source = "auto_restore_user_folder:${result.importedFileName}")
        }
    }

    internal fun recordPendingStartupRestore(context: Context, source: String) {
        if (source.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING_STARTUP_RESTORE_SOURCE, source)
            .apply()
    }

    internal fun consumePendingStartupRestore(context: Context): String? {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val source = sp.getString(KEY_PENDING_STARTUP_RESTORE_SOURCE, null)?.trim().orEmpty()
        if (source.isBlank()) return null
        sp.edit()
            .remove(KEY_PENDING_STARTUP_RESTORE_SOURCE)
            .apply()
        return source
    }

    

    /**
     * v327: Create a local (internal sandbox) pre-import backup of the current internal DB.
     *
     * This enables "rollback" if the imported DB fails integrity checks.
     *
     * Returns the backup file if created, or null if no internal DB exists yet.
     */
    fun createPreImportBackup(context: Context, validateCopy: Boolean = true): File? {
        // Flush WAL so the file on disk is consistent before copying.
        SnapshotSqlite.checkpointWalOrThrow(context)

        val internalDb = SnapshotSqlite.internalDbFile(context)
        if (!internalDb.exists() || internalDb.length() <= 0L) return null

        val bak = File(internalDb.parentFile, PREIMPORT_BACKUP_NAME)
        runCatching { bak.delete() }

        // Best effort: also remove any sidecar files for the backup name.
        runCatching { File(bak.absolutePath + "-wal").delete() }
        runCatching { File(bak.absolutePath + "-shm").delete() }

        // Copy file bytes.
        internalDb.inputStream().use { input ->
            bak.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        if (validateCopy) {
            // Basic sanity: ensure it opens.
            val integrityError = validateSqliteIntegrity(context, bak)
            if (integrityError != null) {
                runCatching { bak.delete() }
                throw IllegalStateException(integrityError)
            }
        }

        return bak
    }

    /**
     * v327: Restore the internal DB from the latest pre-import backup.
     *
     * Returns true if restore succeeded.
     */
    fun restoreInternalDbFromPreImportBackup(context: Context): Boolean {
        SnapshotSqlite.checkpointWal(context)

        val internalDb = SnapshotSqlite.internalDbFile(context)
        val bak = File(internalDb.parentFile, PREIMPORT_BACKUP_NAME)
        if (!bak.exists() || bak.length() <= 0L) return false

        val integrityError = validateSqliteIntegrity(context, bak)
        if (integrityError != null) return false

        internalDb.parentFile?.mkdirs()
        val tmpFile = File(internalDb.parentFile, "multitimer_restore_tmp.db")
        runCatching { tmpFile.delete() }
        bak.inputStream().use { input ->
            tmpFile.outputStream().use { output ->
                input.copyTo(output)
                output.flush()
                output.fd.sync()
            }
        }

        return runCatching {
            replaceInternalDbFromStagedFile(context, tmpFile)
            true
        }.getOrElse {
            runCatching { tmpFile.delete() }
            false
        }
    }

    fun hasPreImportBackup(context: Context): Boolean {
        val internalDb = SnapshotSqlite.internalDbFile(context)
        val bak = File(internalDb.parentFile, PREIMPORT_BACKUP_NAME)
        return bak.exists() && bak.length() > 0L
    }

    fun createInstrumentationSafetyBackupIfNeeded(context: Context): File? {
        if (!BuildConfig.DEBUG || BuildConfig.APPLICATION_ID.endsWith(".devicetest")) return null
        SnapshotSqlite.checkpointWal(context)
        val internalDb = SnapshotSqlite.internalDbFile(context)
        if (!internalDb.exists() || internalDb.length() <= 0L) return null
        if (!hasMeaningfulDbFile(internalDb)) return null

        val bak = File(internalDb.parentFile, INSTRUMENTATION_SAFETY_BACKUP_NAME)
        if (bak.exists() && bak.length() > 0L && validateSqliteIntegrity(context, bak) == null) return bak

        internalDb.inputStream().use { input ->
            bak.outputStream().use { output ->
                input.copyTo(output)
                output.flush()
                output.fd.sync()
            }
        }
        val integrityError = validateSqliteIntegrity(context, bak)
        if (integrityError != null) {
            runCatching { bak.delete() }
            throw IllegalStateException(integrityError)
        }
        return bak
    }

    fun restoreInstrumentationSafetyBackupIfCurrentEmpty(context: Context): Boolean {
        if (!BuildConfig.DEBUG || BuildConfig.APPLICATION_ID.endsWith(".devicetest")) return false
        val internalDb = SnapshotSqlite.internalDbFile(context)
        val bak = File(internalDb.parentFile, INSTRUMENTATION_SAFETY_BACKUP_NAME)
        if (!bak.exists() || bak.length() <= 0L) return false
        if (hasMeaningfulInternalDb(context)) return false
        val integrityError = validateSqliteIntegrity(context, bak)
        if (integrityError != null) return false

        internalDb.parentFile?.mkdirs()
        runCatching { File(internalDb.absolutePath + "-wal").delete() }
        runCatching { File(internalDb.absolutePath + "-shm").delete() }
        val tmpFile = File(internalDb.parentFile, "multitimer_safety_restore_tmp.db")
        runCatching { tmpFile.delete() }
        bak.inputStream().use { input ->
            tmpFile.outputStream().use { output ->
                input.copyTo(output)
                output.flush()
                output.fd.sync()
            }
        }
        replaceInternalDbFromStagedFile(context, tmpFile)
        recordPendingStartupRestore(context, source = "instrumentation_safety_backup")
        return true
    }

    fun clearInternalDbForFreshStart(context: Context): Boolean {
        SnapshotSqlite.invalidateSchemaEnsureCache(context)
        SnapshotSqlite.checkpointWal(context)
        val internalDb = SnapshotSqlite.internalDbFile(context)
        val deletedBySqlite = runCatching {
            SQLiteDatabase.deleteDatabase(internalDb)
        }.getOrDefault(false)
        val deletedByContext = runCatching {
            context.deleteDatabase(SnapshotSqlite.DB_NAME)
        }.getOrDefault(false)

        val filesToDelete = listOf(
            internalDb,
            File(internalDb.absolutePath + "-wal"),
            File(internalDb.absolutePath + "-shm"),
            File(internalDb.parentFile, "multitimer_import_tmp.db"),
            File(internalDb.parentFile, "multitimer_import_tmp.db-wal"),
            File(internalDb.parentFile, "multitimer_import_tmp.db-shm"),
            File(internalDb.parentFile, "multitimer_restore_tmp.db"),
            File(internalDb.parentFile, "multitimer_restore_tmp.db-wal"),
            File(internalDb.parentFile, "multitimer_restore_tmp.db-shm"),
        )
        filesToDelete.forEach { file ->
            runCatching {
                if (file.exists()) {
                    file.delete()
                }
            }
        }
        if (deletedBySqlite || deletedByContext || !internalDb.exists()) {
            PersistentMutationTracker.record(context, "SqliteVault.clearInternalDbForFreshStart")
            return true
        }

        val wipedInPlace = runCatching {
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
            SnapshotSqlite.checkpointWal(context)
            true
        }.getOrDefault(false)

        if (wipedInPlace) {
            PersistentMutationTracker.record(context, "SqliteVault.clearInternalDbForFreshStart")
        }
        return wipedInPlace
    }

/**
 * Overwrite the internal DB with a user-picked multitimer.db (SAF single document).
 * Used by explicit "Import DB" action.
 */
fun overwriteInternalDbFromUri(context: Context, uri: android.net.Uri): ImportResult {
    // Ensure we have the latest prefs mirrored before switching DB (best effort).
    runCatching { UiPrefsStore.mirrorAllToSqlite(context) }

    val doc = DocumentFile.fromSingleUri(context, uri)
    val readable = runCatching {
        context.contentResolver.openInputStream(uri)?.use { true } ?: false
    }.getOrDefault(false)

    if (doc == null && !readable) {
        throw IllegalStateException(context.getString(R.string.import_db_invalid_uri))
    }
    if (doc != null && !doc.isFile && !readable) {
        throw IllegalStateException(context.getString(R.string.import_db_not_a_file))
    }

    val usableDoc = doc?.takeIf { it.isFile }
    val name = usableDoc?.name ?: displayNameForUri(context, uri) ?: VAULT_DB_NAME

    // Flush WAL so the file on disk is consistent before replacing it.
    SnapshotSqlite.checkpointWalOrThrow(context)

    val internalDb = SnapshotSqlite.internalDbFile(context)
    internalDb.parentFile?.mkdirs()

    val tmpFile = File(internalDb.parentFile, "multitimer_import_tmp.db")
    runCatching { tmpFile.delete() }

    // Copy to temp file first.
    if (usableDoc != null) {
        copyDocToFile(context, usableDoc, tmpFile)
    } else {
        copyUriToFile(context, uri, tmpFile)
    }

    val validated = validateImportCandidate(
        context = context,
        stagedFile = tmpFile,
        doc = usableDoc,
        sha256Hex = if (usableDoc != null) {
            runCatching { com.example.multitimetracker.export.DocHash.sha256Hex(context, usableDoc) }.getOrNull()
        } else {
            runCatching { sha256Hex(tmpFile) }.getOrNull()
        }
    )
    replaceInternalDbFromStagedFile(context, tmpFile)

    return ImportResult(
        importedFileName = name,
        sha256Hex = validated.sha256Hex,
        expectedStats = validated.expectedStats
    )
}
private fun validateImportCandidate(
    context: Context,
    stagedFile: File,
    doc: DocumentFile? = null,
    sha256Hex: String? = null
): ValidatedImportCandidate {
    val sha = sha256Hex ?: runCatching {
        if (doc != null) com.example.multitimetracker.export.DocHash.sha256Hex(context, doc) else null
    }.getOrNull()
    val integrityError = validateSqliteIntegrity(context, stagedFile)
    if (integrityError != null) {
        runCatching { stagedFile.delete() }
        throw IllegalStateException(integrityError)
    }

    val schemaError = validateMinimumSchema(context, stagedFile)
    if (schemaError != null) {
        runCatching { stagedFile.delete() }
        throw IllegalStateException(schemaError)
    }

    val hardenError = hardenImportCandidateSchema(context, stagedFile)
    if (hardenError != null) {
        runCatching { stagedFile.delete() }
        throw IllegalStateException(hardenError)
    }

    val expectedStats = runCatching { IntegrityStatsSqlite.readExpectedFromFile(stagedFile) }
        .getOrElse {
            runCatching { stagedFile.delete() }
            throw IllegalStateException(
                context.getString(R.string.backup_db_unknown_error_fmt, (it.message ?: ""))
            )
        }

    return ValidatedImportCandidate(
        sha256Hex = sha,
        expectedStats = expectedStats
    )
}

private fun hardenImportCandidateSchema(context: Context, dbFile: File): String? {
    return try {
        val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            db.beginTransaction()
            SnapshotSqlite.createQuickEventSchema(db)
            SnapshotSqlite.createExportUtcViews(db)
            IntegrityStatsSqlite.ensureTable(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
        validateSqliteIntegrity(context, dbFile)
    } catch (t: Throwable) {
        context.getString(R.string.import_db_schema_check_failed_fmt, (t.message ?: ""))
    }
}

private fun replaceInternalDbFromStagedFile(context: Context, stagedFile: File) {
    SnapshotSqlite.invalidateSchemaEnsureCache(context)
    SnapshotSqlite.checkpointWalOrThrow(context)

    val internalDb = SnapshotSqlite.internalDbFile(context)
    internalDb.parentFile?.mkdirs()
    val currentCounts = if (internalDb.exists() && internalDb.length() > 0L) {
        runCatching { CriticalDataGuard.readFile(internalDb) }.getOrDefault(CriticalDataCounts())
    } else {
        CriticalDataCounts()
    }
    if (currentCounts.settings > 0L) {
        copyCurrentSettingsMirrorIfCandidateMissing(internalDb, stagedFile)
    }
    val stagedCounts = CriticalDataGuard.readFile(stagedFile)
    CriticalDataGuard.requireNoCriticalDrop(
        context = context,
        component = "SqliteVault",
        action = "replace_internal_candidate",
        before = currentCounts,
        after = stagedCounts,
        sourceFile = internalDb.name,
        destFile = stagedFile.name,
    )

    runCatching { File(internalDb.absolutePath + "-wal").delete() }
    runCatching { File(internalDb.absolutePath + "-shm").delete() }

    runCatching { internalDb.delete() }
    val moved = runCatching {
        java.nio.file.Files.move(
            stagedFile.toPath(),
            internalDb.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE
        )
    }.isSuccess
    if (!moved) {
        if (!stagedFile.renameTo(internalDb)) {
            runCatching { stagedFile.delete() }
            throw IllegalStateException(context.getString(R.string.backup_db_swap_failed))
        }
    }

    runCatching { SnapshotSqlite.ensureSessionTables(context) }
    runCatching { SnapshotSqlite.ensureQuickEventTables(context) }
    runCatching { ensureUiPrefsMirrorTable(context) }
    val promotedCounts = CriticalDataGuard.readInternal(context)
    CriticalDataGuard.requireNoCriticalDrop(
        context = context,
        component = "SqliteVault",
        action = "replace_internal_db",
        before = stagedCounts,
        after = promotedCounts,
        sourceFile = stagedFile.name,
        destFile = internalDb.name,
    )
    runCatching { UiPrefsStore.restoreAllFromSqliteIfPresent(context, overwrite = true) }
    PersistentMutationTracker.record(context, "SqliteVault.replaceInternalDbFromStagedFile")
}

private fun copyCurrentSettingsMirrorIfCandidateMissing(currentDbFile: File, candidateDbFile: File) {
    if (!currentDbFile.exists() || !candidateDbFile.exists()) return
    val source = SQLiteDatabase.openDatabase(currentDbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    val row = try {
        source.rawQuery("SELECT json, saved_at_ms FROM ui_prefs_mirror WHERE id=1 LIMIT 1", null).use { c ->
            if (c.moveToFirst()) c.getString(0) to c.getLong(1) else null
        }
    } catch (_: Throwable) {
        null
    } finally {
        source.close()
    } ?: return

    val target = SQLiteDatabase.openDatabase(candidateDbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
    try {
        target.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ui_prefs_mirror (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                json TEXT NOT NULL,
                saved_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        val hasMeaningfulSettings = runCatching {
            target.rawQuery("SELECT json FROM ui_prefs_mirror WHERE id=1 LIMIT 1", null).use { c ->
                if (!c.moveToFirst()) {
                    false
                } else {
                    val obj = org.json.JSONObject(c.getString(0))
                    val keys = obj.keys()
                    var meaningful = false
                    while (keys.hasNext()) {
                        if (keys.next() != UiPrefsStore.KEY_LAST_AUTOCONSIST_PATCH) {
                            meaningful = true
                            break
                        }
                    }
                    meaningful
                }
            }
        }.getOrDefault(false)
        if (hasMeaningfulSettings) return

        val cv = android.content.ContentValues().apply {
            put("id", 1)
            put("json", row.first)
            put("saved_at_ms", row.second)
        }
        target.insertWithOnConflict("ui_prefs_mirror", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    } finally {
        target.close()
    }
}

private fun validateMinimumSchema(context: Context, dbFile: File): String? {
    return try {
        val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            fun hasTable(name: String): Boolean {
                return db.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
                    arrayOf(name)
                ).use { c -> c.moveToFirst() }
            }
            val missing = mutableListOf<String>()
            if (!hasTable("snapshot")) missing += "snapshot"
            if (!hasTable(SnapshotSqlite.AUDIT_TABLE)) missing += SnapshotSqlite.AUDIT_TABLE
            if (missing.isNotEmpty()) {
                context.getString(R.string.import_db_missing_tables_fmt, missing.joinToString(", "))
            } else null
        } finally {
            db.close()
        }
    } catch (t: Throwable) {
        context.getString(R.string.import_db_schema_check_failed_fmt, (t.message ?: ""))
    }
}

private fun ensureUiPrefsMirrorTable(context: Context) {
    val db = SnapshotSqlite.openWritableDb(context)
    db.beginTransaction()
    try {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ui_prefs_mirror (
              id INTEGER PRIMARY KEY CHECK(id = 1),
              json TEXT NOT NULL,
              saved_at_ms INTEGER NOT NULL
            );
            """.trimIndent()
        )
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
        db.close()
    }
}

private fun hasMeaningfulDbFile(dbFile: File): Boolean {
    if (!dbFile.exists() || dbFile.length() <= 0L) return false
    return runCatching {
        val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            fun count(table: String): Long {
                return runCatching {
                    db.rawQuery("SELECT COUNT(*) FROM $table", null).use { c ->
                        if (c.moveToFirst()) c.getLong(0) else 0L
                    }
                }.getOrDefault(0L)
            }
            count("snapshot") > 0L ||
                count(SnapshotSqlite.SESSIONS_TABLE) > 0L ||
                count(SnapshotSqlite.SESSION_TAGS_TABLE) > 0L ||
                count(SnapshotSqlite.AUDIT_TABLE) > 0L
        } finally {
            db.close()
        }
    }.getOrDefault(false)
}

internal fun hasMeaningfulInternalDb(context: Context): Boolean {
    val internalDb = SnapshotSqlite.internalDbFile(context)
    return hasMeaningfulDbFile(internalDb)
}

/**
     * Overwrite the internal DB with the vault copy inside the user folder.
     * Used by explicit "Import" action.
     *
     * Returns the name of the imported file, or null if not available.
     */
    private fun validateSqliteIntegrity(context: Context, dbFile: File): String? {
        if (!dbFile.exists() || dbFile.length() <= 0L) return context.getString(R.string.backup_db_empty)
        return try {
            val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val res = db.rawQuery("PRAGMA integrity_check", null).use { c ->
                    if (c.moveToFirst()) c.getString(0) else ""
                }
                if (res.equals("ok", ignoreCase = true)) {
                    null
                } else {
                    res.ifBlank { context.getString(R.string.backup_db_integrity_check_failed) }
                }
            } finally {
                db.close()
            }
        } catch (e: SQLiteException) {
            context.getString(R.string.backup_db_open_failed_fmt, (e.message ?: ""))
        } catch (t: Throwable) {
            context.getString(R.string.backup_db_unknown_error_fmt, (t.message ?: ""))
        }
    }

    fun overwriteInternalDbFromUserFolder(context: Context): ImportResult? {
        if (BackupFolderStore.getTreeUri(context) == null) return null
        val dir = BackupFolderStore.getOrCreateDataDir(context)

        val primary = dir.findFile(VAULT_DB_NAME)?.takeIf { it.isFile }
        val bak = dir.findFile(VAULT_DB_BAK_NAME)?.takeIf { it.isFile }
        if (primary == null && bak == null) return null

        // Flush WAL so the file on disk is consistent before replacing it.
        SnapshotSqlite.checkpointWalOrThrow(context)

        val internalDb = SnapshotSqlite.internalDbFile(context)
        internalDb.parentFile?.mkdirs()

        val tmpFile = File(internalDb.parentFile, "multitimer_import_tmp.db")
        runCatching { tmpFile.delete() }

        val failures = mutableListOf<String>()

        fun tryImport(doc: DocumentFile, label: String): Triple<Boolean, String?, IntegrityStatsSqlite.Stats> {
            val badStats = IntegrityStatsSqlite.Stats(-1L,-1L,-1L,-1L,-1L,-1L,-1L,-1L,-1L,-1L,-1L,-1L, System.currentTimeMillis())

            // Copy to temp file first.
            runCatching { tmpFile.delete() }
            copyDocToFile(context, doc, tmpFile)

            val validated = runCatching {
                validateImportCandidate(
                    context = context,
                    stagedFile = tmpFile,
                    doc = doc
                )
            }.getOrElse {
                failures.add("$label: ${it.message ?: context.getString(R.string.backup_db_integrity_check_failed)}")
                runCatching { tmpFile.delete() }
                return Triple(false, null, badStats)
            }

            val swapped = runCatching {
                replaceInternalDbFromStagedFile(context, tmpFile)
            }.isSuccess
            if (!swapped) {
                failures.add("$label: " + context.getString(R.string.backup_db_swap_failed))
                runCatching { tmpFile.delete() }
                return Triple(false, validated.sha256Hex, badStats)
            }

            return Triple(true, validated.sha256Hex, validated.expectedStats)
        }

        if (primary != null) {
            val (ok, sha, expected) = tryImport(primary, VAULT_DB_NAME)
            if (ok) return ImportResult(importedFileName = VAULT_DB_NAME, sha256Hex = sha, expectedStats = expected)
        }
        if (bak != null) {
            val (ok, sha, expected) = tryImport(bak, VAULT_DB_BAK_NAME)
            if (ok) return ImportResult(importedFileName = VAULT_DB_BAK_NAME, sha256Hex = sha, expectedStats = expected)
        }

        throw IllegalStateException(
            context.getString(
                R.string.backup_db_integrity_failed_fmt,
                failures.joinToString(" | ").ifBlank { "?" }
            )
        )
    }
}
