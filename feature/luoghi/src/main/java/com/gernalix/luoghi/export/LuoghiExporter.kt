package com.gernalix.luoghi.export

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.room.RoomDatabase
import com.gernalix.luoghi.AppPatchVersion
import com.gernalix.luoghi.BuildConfig
import com.gernalix.luoghi.capsules.safexport.SyncStatusStore
import com.gernalix.luoghi.data.LuoghiDatabase
import com.gernalix.luoghi.data.DatabaseMutationCoordinator
import com.gernalix.luoghi.backup.RestoreSafetyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

object LuoghiExporter {
    private const val TAG = "LuoghiExporter"
    const val DB_FILE = "luoghi.db"
    const val BAK_FILE = "luoghi.db.bak"
    const val MANIFEST_FILE = "luoghi_manifest.json"
    const val TMP_FILE = "luoghi.db.tmp"
    const val BAK_TMP_FILE = "luoghi.db.bak.tmp"
    const val GENERATION_PREFIX = "luoghi_generation_"
    private const val GENERATIONS_TO_KEEP = 3
    private val preRestoreNameFormatter = DateTimeFormatter
        .ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC)

    sealed class Result {
        data object NotConfigured : Result()
        data object Suppressed : Result()
        data class Success(val exportedFiles: List<String>) : Result()
        data class Failure(val message: String) : Result()
    }

    suspend fun exportNow(context: Context): Result {
        if (AutoExportGate.isSuppressed) return Result.Suppressed
        if (RestoreSafetyStore.isAutoExportProtected(context)) return Result.Suppressed
        return DatabaseMutationCoordinator.mutex.withLock {
            BackupOperationCoordinator.mutex.withLock {
                if (AutoExportGate.isSuppressed || RestoreSafetyStore.isAutoExportProtected(context)) {
                    Result.Suppressed
                } else {
                    exportLocked(context.applicationContext)
                }
            }
        }
    }

    internal suspend fun exportLocked(context: Context): Result =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            if (!BackupFolderStore.ensureSavedTreeWritable(appContext)) return@withContext Result.NotConfigured
            SyncStatusStore.markExportStarted(appContext, DB_FILE)
            runCatching {
                val db = LuoghiDatabase.get(appContext)
                checkpointWal(db)
                requireIntegrity(db, "source")
                val source = appContext.getDatabasePath(LuoghiDatabase.DB_NAME)
                val tmpLocal = File(appContext.cacheDir, TMP_FILE)
                source.copyTo(tmpLocal, overwrite = true)
                requireIntegrity(tmpLocal, "local tmp")

                val dir = BackupFolderStore.getOrCreateDataDir(appContext)
                val generation = writeGeneration(appContext, dir, tmpLocal)
                updateBackup(appContext, dir, tmpLocal)
                val tmpDoc = SafeFiles.replaceDocument(dir, TMP_FILE)
                SafeFiles.copyFileToDocument(appContext, tmpLocal, tmpDoc)
                requireIntegrity(appContext, tmpDoc, "SAF tmp")
                val primary = SafeFiles.replaceDocument(dir, DB_FILE)
                SafeFiles.copyFileToDocument(appContext, tmpLocal, primary)
                requireIntegrity(appContext, primary, "SAF primary")
                writeManifest(appContext, dir, tmpLocal)
                tmpDoc.delete()
                pruneOldGenerations(dir, keepName = generation)
                SyncStatusStore.markExportSuccess(appContext, DB_FILE, "ok")
                Result.Success(listOf(DB_FILE, BAK_FILE, MANIFEST_FILE))
            }.getOrElse { t ->
                Log.e(TAG, "Export failed", t)
                val message = t.message ?: t::class.java.simpleName
                SyncStatusStore.markExportFailure(appContext, message, DB_FILE)
                Result.Failure(message)
            }
        }

    internal suspend fun createPreventiveBackupLocked(
        context: Context,
        database: LuoghiDatabase,
        nowMs: Long = System.currentTimeMillis(),
    ): String = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        check(BackupFolderStore.ensureSavedTreeWritable(appContext)) { "Backup folder is not configured" }
        checkpointWal(database)
        requireIntegrity(database, "source before restore")
        val source = appContext.getDatabasePath(LuoghiDatabase.DB_NAME)
        val local = File(appContext.cacheDir, "luoghi-pre-restore.db")
        source.copyTo(local, overwrite = true)
        requireIntegrity(local, "preventive backup")
        val name = "luoghi_pre_restore_${preRestoreNameFormatter.format(Instant.ofEpochMilli(nowMs))}_${UUID.randomUUID().toString().take(8)}.db"
        val dir = BackupFolderStore.getOrCreateDataDir(appContext)
        val document = SafeFiles.replaceDocument(dir, name)
        SafeFiles.copyFileToDocument(appContext, local, document)
        requireIntegrity(appContext, document, "SAF preventive backup")
        name
    }

    private fun checkpointWal(db: RoomDatabase) {
        db.query("PRAGMA wal_checkpoint(FULL)", null).use { cursor ->
            if (cursor.moveToFirst() && cursor.getInt(0) != 0) {
                error("WAL checkpoint busy")
            }
        }
    }

    private fun requireIntegrity(db: RoomDatabase, label: String) {
        db.query("PRAGMA integrity_check", null).use { cursor ->
            val ok = cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)
            check(ok) { "SQLite integrity_check failed for $label" }
        }
        db.query("PRAGMA foreign_key_check", null).use { cursor ->
            check(!cursor.moveToFirst()) { "SQLite foreign_key_check failed for $label" }
        }
    }

    private fun requireIntegrity(file: File, label: String) {
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                val ok = cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)
                check(ok) { "SQLite integrity_check failed for $label" }
            }
            db.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                check(!cursor.moveToFirst()) { "SQLite foreign_key_check failed for $label" }
            }
        }
    }

    private fun requireIntegrity(context: Context, doc: DocumentFile, label: String) {
        val local = File(context.cacheDir, "validate-${doc.name ?: label}.db")
        SafeFiles.copyDocumentToFile(context, doc, local)
        requireIntegrity(local, label)
    }

    private fun updateBackup(context: Context, dir: DocumentFile, tmpLocal: File) {
        val primary = dir.findFile(DB_FILE)
        val localBak = File(context.cacheDir, BAK_FILE)
        val backupSource = if (primary != null && primary.exists() && runCatching {
                SafeFiles.copyDocumentToFile(context, primary, localBak)
                requireIntegrity(localBak, "existing primary before bak")
            }.isSuccess
        ) {
            localBak
        } else {
            tmpLocal
        }
        val stagedBak = SafeFiles.replaceDocument(dir, BAK_TMP_FILE)
        SafeFiles.copyFileToDocument(context, backupSource, stagedBak)
        requireIntegrity(context, stagedBak, "SAF staged bak")
        val bak = SafeFiles.replaceDocument(dir, BAK_FILE)
        SafeFiles.copyFileToDocument(context, backupSource, bak)
        requireIntegrity(context, bak, "SAF bak")
        stagedBak.delete()
    }

    private fun writeGeneration(context: Context, dir: DocumentFile, source: File): String {
        val name = "$GENERATION_PREFIX${preRestoreNameFormatter.format(Instant.now())}_${UUID.randomUUID().toString().take(8)}.db"
        val document = dir.createFile("application/octet-stream", name)
            ?: throw IllegalStateException("Cannot create SAF backup generation")
        SafeFiles.copyFileToDocument(context, source, document)
        requireIntegrity(context, document, "SAF generation")
        return name
    }

    private fun pruneOldGenerations(dir: DocumentFile, keepName: String) {
        dir.listFiles()
            .filter { it.isFile && it.name?.startsWith(GENERATION_PREFIX) == true && it.name?.endsWith(".db") == true }
            .sortedByDescending { it.lastModified() }
            .drop(GENERATIONS_TO_KEEP)
            .filterNot { it.name == keepName }
            .forEach { runCatching { it.delete() } }
    }

    private fun writeManifest(context: Context, dir: DocumentFile, snapshotFile: File) {
        val counts = tableCounts(snapshotFile)
        val manifest = JSONObject()
            .put("format", com.gernalix.luoghi.backup.LuoghiBackupFormat.ID)
            .put("format_version", com.gernalix.luoghi.backup.LuoghiBackupFormat.CURRENT_VERSION)
            .put("schema_version", com.gernalix.luoghi.backup.LuoghiBackupFormat.CURRENT_SCHEMA)
            .put("backup_uuid", UUID.randomUUID().toString())
            .put("application_id", BuildConfig.APPLICATION_ID)
            .put("version_code", BuildConfig.VERSION_CODE)
            .put("version_name", BuildConfig.VERSION_NAME)
            .put("asset_patch", AppPatchVersion.current(context))
            .put("exported_at", System.currentTimeMillis())
            .put("database", DB_FILE)
            .put("backup", BAK_FILE)
            .put("database_size_bytes", snapshotFile.length())
            .put("database_sha256", sha256(snapshotFile))
        counts.forEach { (table, count) -> manifest.put(table, count) }
        val manifestText = manifest.toString(2)
        val local = File(context.cacheDir, MANIFEST_FILE)
        SafeFiles.writeAtomicUtf8(local, manifestText)
        val doc = SafeFiles.replaceDocument(dir, MANIFEST_FILE, "application/json")
        SafeFiles.copyFileToDocument(context, local, doc)
    }

    private fun tableCounts(file: File): Map<String, Int> =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            listOf(
                "places",
                "place_aliases",
                "place_links",
                "place_events",
                "global_stats_state",
                "route_distance_cache",
                "history_audit_log",
                "history_actions",
            ).associateWith { table ->
                database.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
            }
        }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
