// v471
// v458
package com.example.multitimetracker.persistence

import android.content.ContentValues
import android.content.Context
import android.os.SystemClock
import androidx.documentfile.provider.DocumentFile
import com.example.multitimetracker.R
import com.example.multitimetracker.export.BackupFolderStore
import com.example.multitimetracker.export.AuthoritativeExportPayloadBuilder
import com.example.multitimetracker.export.VaultFolders
import com.example.multitimetracker.export.VaultIndex
import com.example.multitimetracker.util.CapsuleWriteApi
import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * v394 – Multi-DB "vaults" (work/personal separation).
 *
 * Concept (simple):
 * - The app still works with ONE internal DB (fast, safe).
 * - But we can keep MANY *external* DB copies (vaults) inside the SAF folder.
 * - Switching vault = export current internal DB to its vault, then import the chosen vault into internal.
 */
object MultiDbVaults {

    enum class InitMode {
        EMPTY,
        CLONE_CURRENT
    }

    /**
     * Stable error codes for UI decisions.
     * IMPORTANT: UI must NOT parse localized messages.
     */
    enum class SwitchErrorCode {
        MISSING_TARGET_DB
    }

    class MissingVaultDbException(val vaultName: String, message: String) : IllegalStateException(message)

    private const val PREFS = "multitimetracker_multidb"
    private const val KEY_ACTIVE = "active_vault"
    private const val KEY_LIST = "vault_list"
    private const val KEY_PENDING_ACTIVATION_VAULT = "pending_activation_vault"
    private const val KEY_PENDING_ACTIVATION_SIG = "pending_activation_sig"

    private const val DB_NAME = "multitimer.db"
    private const val DB_BAK_NAME = "multitimer.db.bak"
    private const val DB_TMP_NAME = "multitimer.db.tmp"

    // v433: reduce switch time. Kotlin's default copyTo() uses a small buffer.
    // We use a larger buffer and we also skip exporting if internal DB didn't change.
    private const val COPY_BUF_BYTES = 1024 * 1024 // 1 MiB

    private const val KEY_EXPORT_FP_PREFIX = "export_fp_" // + vault

    data class SwitchResult(
        val fromVault: String,
        val toVault: String,
        val exportedOk: Boolean,
        val importedOk: Boolean,
        val errorMessage: String? = null,
        val errorCode: SwitchErrorCode? = null
    )

    data class ActivationNotice(
        val vaultName: String,
        val verified: Boolean
    )

    private data class PreparedVaultImport(
        val stagedDbFile: File,
        val expectedStats: IntegrityStatsSqlite.Stats
    )

    fun isConfigured(context: Context): Boolean = BackupFolderStore.getTreeUri(context) != null

    fun getActiveVaultName(context: Context): String {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getString(KEY_ACTIVE, null)?.trim().takeIf { !it.isNullOrBlank() } ?: "default"
    }

    fun listVaultNames(context: Context): List<String> {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_LIST, null)?.trim().orEmpty()
        val fromPrefs = raw.split("|")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toMutableList()

        // Always ensure default exists.
        if (!fromPrefs.any { it.equals("default", ignoreCase = true) }) fromPrefs.add(0, "default")

        // Also ensure the active vault is present.
        val active = getActiveVaultName(context)
        if (!fromPrefs.any { it.equals(active, ignoreCase = true) }) fromPrefs.add(0, active)

        return fromPrefs
            .distinctBy { it.lowercase(Locale.US) }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    fun createVault(context: Context, requestedName: String): String {
        if (!isConfigured(context)) {
            throw IllegalStateException(context.getString(R.string.backup_folder_not_configured))
        }

        val name = sanitizeName(requestedName)
        ensureVaultDir(context, name)
        addVaultToPrefs(context, name)

        return name
    }

    fun renameVault(context: Context, currentName: String, requestedName: String): String {
        if (!isConfigured(context)) {
            throw IllegalStateException(context.getString(R.string.backup_folder_not_configured))
        }

        val from = sanitizeName(currentName)
        if (from.equals("default", ignoreCase = true)) {
            throw IllegalStateException(context.getString(R.string.multidb_default_vault_protected))
        }

        val to = sanitizeName(requestedName)
        if (to.equals(from, ignoreCase = true)) return from
        if (listVaultNames(context).any { it.equals(to, ignoreCase = true) }) {
            throw IllegalStateException(context.getString(R.string.multidb_vault_already_exists_fmt, to))
        }

        val dir = findVaultDir(context, from)
            ?: throw IllegalStateException(context.getString(R.string.multidb_vault_missing_dir_fmt, from))
        if (!dir.renameTo(to)) {
            throw IllegalStateException(context.getString(R.string.multidb_rename_failed_fmt, from))
        }

        replaceVaultInPrefs(context, from, to)
        return to
    }

    fun deleteVault(context: Context, vaultName: String) {
        if (!isConfigured(context)) {
            throw IllegalStateException(context.getString(R.string.backup_folder_not_configured))
        }

        val name = sanitizeName(vaultName)
        if (name.equals("default", ignoreCase = true)) {
            throw IllegalStateException(context.getString(R.string.multidb_default_vault_protected))
        }
        if (name.equals(getActiveVaultName(context), ignoreCase = true)) {
            throw IllegalStateException(context.getString(R.string.multidb_delete_active_forbidden))
        }

        val dir = findVaultDir(context, name)
        if (dir != null && !dir.delete()) {
            throw IllegalStateException(context.getString(R.string.multidb_rename_failed_fmt, name))
        }
        removeVaultFromPrefs(context, name)
    }

    /**
     * v433: ensure the vault has a DB file so it becomes switchable.
     *
     * Two distinct intents:
     * - EMPTY: create a brand-new DB with schema only (no user data)
     * - CLONE_CURRENT: copy current internal DB into the vault
     */
    fun initVaultDb(context: Context, vaultName: String, mode: InitMode) {
        if (!isConfigured(context)) {
            throw IllegalStateException(context.getString(R.string.backup_folder_not_configured))
        }

        val name = sanitizeName(vaultName)
        ensureVaultDir(context, name)
        addVaultToPrefs(context, name)

        when (mode) {
            InitMode.EMPTY -> createEmptyDbInVault(context, name)
            InitMode.CLONE_CURRENT -> exportInternalToVault(context, name, snapshotReason = "init-clone", allowSkipIfUnchanged = false)
        }
    }

    /**
     * Switch internal DB to [targetVault].
     *
     * Safety invariants:
     * - We export current internal DB to the *current* vault first (best-effort), to avoid data loss.
     * - Then we import the target vault DB into internal.
     */
    @OptIn(CapsuleWriteApi::class)
    fun switchToVault(context: Context, targetVault: String): SwitchResult {
        if (!isConfigured(context)) {
            throw IllegalStateException(context.getString(R.string.backup_folder_not_configured))
        }

        val switchStartedAt = SystemClock.elapsedRealtime()
        var exportDurationMs = 0L
        var importDurationMs = 0L

        // v435: ensure the current INTERNAL DB is consistent before we attempt any switch.
        val preGate = DataIntegrityGate.runCriticalChecks(context)
        if (!preGate.ok) {
            return SwitchResult(
                fromVault = getActiveVaultName(context),
                toVault = sanitizeName(targetVault),
                exportedOk = false,
                importedOk = false,
                errorMessage = preGate.technicalReport ?: preGate.blockingBody,
                errorCode = null
            )
        }

        // v443: the switch path already validated the live DB via the integrity gate,
        // so copying the local rollback backup without a second full SQLite check is enough.
        runCatching { SqliteVault.createPreImportBackup(context, validateCopy = false) }

        val from = getActiveVaultName(context)
        val to = sanitizeName(targetVault)
        clearPendingActivation(context)

        ensureVaultDir(context, from)
        ensureVaultDir(context, to)
        addVaultToPrefs(context, to)

        // v443: keep the current vault up to date, but skip switch-only redundant .bak and timestamp snapshot.
        val exportAttempt = runCatching {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                exportInternalToVault(
                    context = context,
                    vaultName = from,
                    snapshotReason = null,
                    allowSkipIfUnchanged = true,
                    writeBak = false
                )
            } finally {
                exportDurationMs = SystemClock.elapsedRealtime() - startedAt
            }
        }
        val exportedOk = exportAttempt.isSuccess

        // Import target vault into internal.
        val importAttempt = runCatching {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val prepared = prepareVaultImport(context, to)
                try {
                    importVaultToInternal(context, prepared)

                    // v435: hard-stop integrity check after importing a vault.
                    val postGate = DataIntegrityGate.runCriticalChecks(context)
                    if (!postGate.ok) {
                        val restored = runCatching { SqliteVault.restoreInternalDbFromPreImportBackup(context) }.getOrDefault(false)
                        if (restored) {
                            throw IllegalStateException(context.getString(R.string.multidb_integrity_failed_rollback_done))
                        }
                        throw IllegalStateException(context.getString(R.string.multidb_integrity_failed_rollback_failed))
                    }

                    // v436: compare the imported DB against the target vault itself, not the previous active vault.
                    val afterStats = postGate.stats
                        ?: IntegrityStatsSqlite.Stats(-1L,-1L,-1L,-1L,-1L,-1L,-1L,-1L,-1L,-1L,-1L,-1L, System.currentTimeMillis())

                    if (DataIntegrityGate.isCatastrophicLoss(prepared.expectedStats, afterStats)) {
                        val restored = runCatching { SqliteVault.restoreInternalDbFromPreImportBackup(context) }.getOrDefault(false)
                        if (restored) {
                            throw IllegalStateException(context.getString(R.string.multidb_panic_stop_rollback_done))
                        }
                        throw IllegalStateException(context.getString(R.string.multidb_panic_stop_failed))
                    }

                    // Keep per-vault UI prefs aligned with the just-imported DB.
                    runCatching { UiPrefsStore.restoreAllFromSqliteIfPresent(context, overwrite = true) }
                } finally {
                    runCatching { prepared.stagedDbFile.delete() }
                }
            } finally {
                importDurationMs = SystemClock.elapsedRealtime() - startedAt
            }
        }
        val importedOk = importAttempt.isSuccess

        val importEx = importAttempt.exceptionOrNull()
        val exportEx = exportAttempt.exceptionOrNull()

        val errorCode: SwitchErrorCode? = when (importEx) {
            is MissingVaultDbException -> SwitchErrorCode.MISSING_TARGET_DB
            else -> null
        }

        // v428: propagate real error message back to UI (ARCHITECTURE_LOCK v427 requirement).
        val errorMessage = importEx?.message
            ?: exportEx?.message

        if (importedOk) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ACTIVE, to)
                .apply()

            runCatching {
                SnapshotStore.load(context)?.let { snapshot ->
                    recordPendingActivation(
                        context = context,
                        vaultName = to,
                        expectedSignature = AuthoritativeExportPayloadBuilder.activationSignature(
                            AuthoritativeExportPayloadBuilder.fromSnapshot(snapshot)
                        )
                    )
                }
            }

            val switchDurationMs = SystemClock.elapsedRealtime() - switchStartedAt
            runCatching {
                AuditLogSqlite.insert(
                    context = context,
                    isSystem = true,
                    action = "DB_VAULT_SWITCH",
                    entityType = "DB_VAULT",
                    summary = "switched database from $from to $to",
                    payload = JSONObject()
                        .put("fromVault", from)
                        .put("toVault", to)
                        .put("exportedOk", exportedOk)
                        .put("exportDurationMs", exportDurationMs)
                        .put("importDurationMs", importDurationMs)
                        .put("totalDurationMs", switchDurationMs)
                )
            }
        }

        return SwitchResult(
            fromVault = from,
            toVault = to,
            exportedOk = exportedOk,
            importedOk = importedOk,
            errorMessage = errorMessage,
            errorCode = errorCode
        )

    }

    private fun addVaultToPrefs(context: Context, name: String) {
        val list = listVaultNames(context).toMutableList()
        if (!list.any { it.equals(name, ignoreCase = true) }) list.add(name)
        val raw = list.distinctBy { it.lowercase(Locale.US) }.sortedWith(String.CASE_INSENSITIVE_ORDER).joinToString("|")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LIST, raw)
            .apply()
    }

    private fun replaceVaultInPrefs(context: Context, oldName: String, newName: String) {
        val list = listVaultNames(context)
            .map { if (it.equals(oldName, ignoreCase = true)) newName else it }
            .distinctBy { it.lowercase(Locale.US) }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = sp.edit()
        editor.putString(KEY_LIST, list.joinToString("|"))
        if (getActiveVaultName(context).equals(oldName, ignoreCase = true)) {
            editor.putString(KEY_ACTIVE, newName)
        }
        val oldFpKey = KEY_EXPORT_FP_PREFIX + oldName.lowercase(Locale.US)
        val newFpKey = KEY_EXPORT_FP_PREFIX + newName.lowercase(Locale.US)
        sp.getString(oldFpKey, null)?.let { editor.putString(newFpKey, it) }
        editor.remove(oldFpKey)
        editor.apply()
    }

    private fun removeVaultFromPrefs(context: Context, name: String) {
        val list = listVaultNames(context)
            .filterNot { it.equals(name, ignoreCase = true) }
            .distinctBy { it.lowercase(Locale.US) }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LIST, list.joinToString("|"))
            .remove(KEY_EXPORT_FP_PREFIX + name.lowercase(Locale.US))
            .apply()
    }

    private fun sanitizeName(input: String): String {
        val trimmed = input.trim().ifBlank { "default" }
        // Keep it filesystem + SAF friendly.
        val cleaned = trimmed
            .replace(Regex("[^a-zA-Z0-9._ -]"), "")
            .trim()
            .replace(Regex("\\s+"), " ")
        return cleaned.ifBlank { "default" }
    }

    private fun ensureVaultDir(context: Context, name: String): DocumentFile {
        // v425: respect the SAF organizer structure:
        // <picked root>/MultiTimer data/vault/vaults/<name>/
        return VaultFolders.ensureNamedVaultDir(context, name)
    }

    private fun findVaultDir(context: Context, name: String): DocumentFile? {
        val root = VaultFolders.ensureRoot(context)
        return root.vaults.findFile(name)?.takeIf { it.isDirectory }
    }

    private fun recordPendingActivation(context: Context, vaultName: String, expectedSignature: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING_ACTIVATION_VAULT, vaultName)
            .putString(KEY_PENDING_ACTIVATION_SIG, expectedSignature)
            .apply()
    }

    private fun clearPendingActivation(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PENDING_ACTIVATION_VAULT)
            .remove(KEY_PENDING_ACTIVATION_SIG)
            .apply()
    }

    fun consumePendingActivationNotice(context: Context, activeSignature: String): ActivationNotice? {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val vaultName = sp.getString(KEY_PENDING_ACTIVATION_VAULT, null)?.trim().orEmpty()
        val expectedSignature = sp.getString(KEY_PENDING_ACTIVATION_SIG, null)?.trim().orEmpty()
        if (vaultName.isBlank() || expectedSignature.isBlank()) return null

        val verified = activeSignature == expectedSignature
        if (verified) {
            sp.edit()
                .remove(KEY_PENDING_ACTIVATION_VAULT)
                .remove(KEY_PENDING_ACTIVATION_SIG)
                .apply()
        }

        return ActivationNotice(
            vaultName = vaultName,
            verified = verified
        )
    }

    private fun exportInternalToVault(
        context: Context,
        vaultName: String,
        @Suppress("UNUSED_PARAMETER") snapshotReason: String?,
        allowSkipIfUnchanged: Boolean,
        writeBak: Boolean = true
    ) {
        // Make sure the DB file on disk is consistent.
        runCatching { UiPrefsStore.mirrorAllToSqlite(context) }
        val refreshedStats = runCatching { IntegrityStatsSqlite.refreshInternal(context) }.getOrNull()
        SnapshotSqlite.checkpointWalOrThrow(context)

        val internalDb = SnapshotSqlite.internalDbFile(context)
        if (!internalDb.exists() || internalDb.length() <= 0L) return
        validateLocalDbFile(context, internalDb, "source")

        // v433: skip export if nothing changed since last export to this vault.
        if (allowSkipIfUnchanged) {
            val fp = internalDbFingerprint(internalDb)
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val key = KEY_EXPORT_FP_PREFIX + vaultName.lowercase(Locale.US)
            val prev = sp.getString(key, null)
            if (prev == fp) {
                val dir = ensureVaultDir(context, vaultName)
                val hasDb = dir.findFile(DB_NAME)?.isFile == true
                if (hasDb) return
            }
        }

        val dir = ensureVaultDir(context, vaultName)

        writeStableDbPipeline(context, dir, internalDb, vaultName)
        val currentOut = requireNotNull(dir.findFile(DB_NAME)?.takeIf { it.isFile }) {
            context.getString(R.string.multidb_create_db_failed_fmt, vaultName)
        }

        // v433: persist export fingerprint so we can skip future redundant exports.
        runCatching {
            val fp = internalDbFingerprint(internalDb)
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val key = KEY_EXPORT_FP_PREFIX + vaultName.lowercase(Locale.US)
            sp.edit().putString(key, fp).apply()
        }

        val statsJson = (refreshedStats ?: runCatching { IntegrityStatsSqlite.computeInternal(context) }.getOrNull())?.toJson()

        VaultIndex.record(
            context = context,
            kind = VaultIndex.Kind.VAULT_CURRENT,
            file = currentOut,
            vaultName = vaultName,
            reason = "export-current",
            statsJson = statsJson
        )

        // v525: no timestamped SQLite autoexports. Stable files only:
        // multitimer.db, multitimer.db.tmp, and one multitimer.db.bak.
    }

    private fun internalDbFingerprint(file: File): String {
        // Good enough for "did the DB change?". (We don't want expensive hashing here.)
        return "${file.lastModified()}|${file.length()}"
    }

    private fun writeStableDbPipeline(
        context: Context,
        dir: DocumentFile,
        sourceDb: File,
        vaultName: String,
    ) {
        dir.findFile(DB_TMP_NAME)?.delete()
        val tmp = stableFile(context, dir, DB_TMP_NAME, vaultName)
        copyFileToDoc(context, sourceDb, tmp)
        validateDocDb(context, tmp, "tmp")

        val bak = stableFile(context, dir, DB_BAK_NAME, vaultName)
        copyDocToDoc(context, tmp, bak)
        validateDocDb(context, bak, "bak")

        val primary = stableFile(context, dir, DB_NAME, vaultName)
        copyDocToDoc(context, tmp, primary)
        validateDocDb(context, primary, "final")
        tmp.delete()
    }

    private fun stableFile(
        context: Context,
        dir: DocumentFile,
        name: String,
        vaultName: String,
    ): DocumentFile {
        return dir.findFile(name)?.takeIf { it.isFile }
            ?: dir.createFile("application/octet-stream", name)
            ?: throw IllegalStateException(context.getString(R.string.multidb_create_db_failed_fmt, vaultName))
    }

    private fun validateDocDb(context: Context, doc: DocumentFile, label: String) {
        val tmp = File(context.cacheDir, "multidb_validate_${label}_${System.currentTimeMillis()}.db")
        runCatching { tmp.delete() }
        try {
            copyDocToFile(context, doc, tmp)
            validateLocalDbFile(context, tmp, label)
        } finally {
            runCatching { tmp.delete() }
        }
    }

    private fun validateLocalDbFile(context: Context, dbFile: File, label: String) {
        if (!dbFile.exists() || dbFile.length() <= 0L) {
            throw IllegalStateException(context.getString(R.string.backup_db_empty))
        }
        val db = android.database.sqlite.SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        )
        try {
            val result = db.rawQuery("PRAGMA integrity_check", null).use { c ->
                if (c.moveToFirst()) c.getString(0) else ""
            }
            if (!result.equals("ok", ignoreCase = true)) {
                throw IllegalStateException("$label: ${result.ifBlank { context.getString(R.string.backup_db_integrity_check_failed) }}")
            }
        } finally {
            db.close()
        }
    }

    private fun createEmptyDbInVault(context: Context, vaultName: String) {
        val dir = ensureVaultDir(context, vaultName)
        val createdAtMs = System.currentTimeMillis()
        // Create a fresh empty sqlite file in cache, then copy it into the vault.
        val tmp = File(context.cacheDir, "multitimer_empty_${System.currentTimeMillis()}.db")
        runCatching { tmp.delete() }

        val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(tmp, null)
        try {
            db.beginTransaction()
            try {
                SnapshotSqlite.createBaseSchema(db)
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sessions (
                      id INTEGER PRIMARY KEY,
                      title TEXT NOT NULL,
                      start_ms INTEGER NOT NULL,
                      end_ms INTEGER,
                      created_at_ms INTEGER NOT NULL,
                      updated_at_ms INTEGER NOT NULL,
                      deleted_at_ms INTEGER
                    );
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS session_tags (
                      session_id INTEGER NOT NULL,
                      tag_id INTEGER NOT NULL,
                      PRIMARY KEY(session_id, tag_id)
                    );
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_session_tags_tag ON session_tags(tag_id);")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_session_tags_session ON session_tags(session_id);")

                // Keep user_version aligned with the main DB.
                db.execSQL("PRAGMA user_version=${SnapshotSqlite.DB_VERSION}")

                // Store an explicit empty state so this vault never falls back to
                // legacy global snapshot storage from another vault/install.
                seedEmptyVaultState(db, createdAtMs)

                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            db.close()
        }

        validateLocalDbFile(context, tmp, "empty_source")
        writeStableDbPipeline(context, dir, tmp, vaultName)

        runCatching { tmp.delete() }
    }

    private fun seedEmptyVaultState(
        db: android.database.sqlite.SQLiteDatabase,
        createdAtMs: Long
    ) {
        putJsonRow(
            db = db,
            table = "snapshot",
            json = buildEmptySnapshotJson(createdAtMs),
            savedAtMs = createdAtMs
        )
        putJsonRow(
            db = db,
            table = "ui_prefs_mirror",
            json = JSONObject().toString(),
            savedAtMs = createdAtMs
        )
    }

    private fun putJsonRow(
        db: android.database.sqlite.SQLiteDatabase,
        table: String,
        json: String,
        savedAtMs: Long
    ) {
        val values = ContentValues().apply {
            put("id", 1)
            put("json", json)
            put("saved_at_ms", savedAtMs)
        }
        db.insertWithOnConflict(table, null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun buildEmptySnapshotJson(createdAtMs: Long): String {
        return JSONObject()
            .put("installAtMs", createdAtMs)
            .put("appUsageMs", 0L)
            .put("tasks", JSONArray())
            .put("tags", JSONArray())
            .put("closedSessions", JSONArray())
            .put("tagSessions", JSONArray())
            .put("lifePeriods", JSONArray())
            .put("timeFenceRules", JSONArray())
            .put("activeSessionStart", JSONArray())
            .put("activeTagStart", JSONArray())
            .put("tagParents", JSONArray())
            .put("chains", JSONArray())
            .toString()
    }

    private fun prepareVaultImport(context: Context, vaultName: String): PreparedVaultImport {
        val dir = ensureVaultDir(context, vaultName)
        val vaultDb = dir.findFile(DB_NAME)
            ?: throw MissingVaultDbException(
                vaultName = vaultName,
                message = context.getString(R.string.multidb_vault_missing_db_fmt, vaultName)
            )

        if (!vaultDb.isFile) {
            throw MissingVaultDbException(
                vaultName = vaultName,
                message = context.getString(R.string.multidb_vault_missing_db_fmt, vaultName)
            )
        }

        val internalDb = SnapshotSqlite.internalDbFile(context)
        internalDb.parentFile?.mkdirs()
        val stagedFile = File(internalDb.parentFile, "multidb_switch_import_tmp.db")
        runCatching { stagedFile.delete() }

        copyDocToFile(context, vaultDb, stagedFile)

        val expectedStats = validateAndReadExpectedStats(context, stagedFile)
            .getOrElse {
                runCatching { stagedFile.delete() }
                throw IllegalStateException(it)
            }

        return PreparedVaultImport(
            stagedDbFile = stagedFile,
            expectedStats = expectedStats
        )
    }

    private fun importVaultToInternal(context: Context, prepared: PreparedVaultImport) {
        // Close any open connections by forcing WAL checkpoint first.
        SnapshotSqlite.checkpointWalOrThrow(context)

        val internalDb = SnapshotSqlite.internalDbFile(context)
        internalDb.parentFile?.mkdirs()

        // Best-effort: remove sidecars so we don't carry over old WAL/SHM.
        SnapshotSqlite.invalidateSchemaEnsureCache(context)
        runCatching { File(internalDb.absolutePath + "-wal").delete() }
        runCatching { File(internalDb.absolutePath + "-shm").delete() }

        runCatching { internalDb.delete() }
        val moved = runCatching {
            java.nio.file.Files.move(
                prepared.stagedDbFile.toPath(),
                internalDb.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE
            )
        }.isSuccess
        if (!moved) {
            if (!prepared.stagedDbFile.renameTo(internalDb)) {
                throw IllegalStateException(context.getString(R.string.backup_db_swap_failed))
            }
        }

        // Defensive: ensure session tables exist even if the vault DB is older.
        runCatching { SnapshotSqlite.ensureSessionTables(context) }
    }


    private fun validateAndReadExpectedStats(
        context: Context,
        dbFile: File
    ): Result<IntegrityStatsSqlite.Stats> {
        if (!dbFile.exists() || dbFile.length() <= 0L) {
            return Result.failure(IllegalStateException(context.getString(R.string.backup_db_empty)))
        }

        return runCatching {
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
            try {
                val res = db.rawQuery("PRAGMA integrity_check", null).use { c ->
                    if (c.moveToFirst()) c.getString(0) else ""
                }
                if (!res.equals("ok", ignoreCase = true)) {
                    throw IllegalStateException(
                        res.ifBlank { context.getString(R.string.backup_db_integrity_check_failed) }
                    )
                }
                IntegrityStatsSqlite.readExpectedFromOpenDb(db)
            } finally {
                db.close()
            }
        }.recoverCatching { error ->
            throw IllegalStateException(
                when (error) {
                    is IllegalStateException -> error.message ?: context.getString(R.string.backup_db_integrity_check_failed)
                    is android.database.sqlite.SQLiteException -> context.getString(R.string.backup_db_open_failed_fmt, (error.message ?: ""))
                    else -> context.getString(R.string.backup_db_unknown_error_fmt, (error.message ?: ""))
                }
            )
        }
    }

    private fun copyFileToDoc(context: Context, from: File, to: DocumentFile) {
        java.io.BufferedInputStream(from.inputStream(), COPY_BUF_BYTES).use { input ->
            context.contentResolver.openOutputStream(to.uri, "wt").use { rawOut ->
                val output = java.io.BufferedOutputStream(
                    requireNotNull(rawOut) { "openOutputStream returned null" },
                    COPY_BUF_BYTES
                )
                output.use {
                    val buf = ByteArray(COPY_BUF_BYTES)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        it.write(buf, 0, n)
                    }
                    it.flush()
                }
            }
        }
    }

    private fun copyDocToDoc(context: Context, from: DocumentFile, to: DocumentFile) {
        context.contentResolver.openInputStream(from.uri).use { rawIn ->
            val input = java.io.BufferedInputStream(
                requireNotNull(rawIn) { "openInputStream returned null" },
                COPY_BUF_BYTES
            )
            context.contentResolver.openOutputStream(to.uri, "wt").use { rawOut ->
                val output = java.io.BufferedOutputStream(
                    requireNotNull(rawOut) { "openOutputStream returned null" },
                    COPY_BUF_BYTES
                )
                input.use {
                    output.use {
                        val buf = ByteArray(COPY_BUF_BYTES)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                        }
                        output.flush()
                    }
                }
            }
        }
    }

    private fun copyDocToFile(context: Context, from: DocumentFile, to: File) {
        context.contentResolver.openInputStream(from.uri).use { rawIn ->
            val input = java.io.BufferedInputStream(
                requireNotNull(rawIn) { "openInputStream returned null" },
                COPY_BUF_BYTES
            )
            input.use {
                java.io.BufferedOutputStream(to.outputStream(), COPY_BUF_BYTES).use { output ->
                    val buf = ByteArray(COPY_BUF_BYTES)
                    while (true) {
                        val n = it.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                    }
                    output.flush()
                }
            }
        }
    }
}
