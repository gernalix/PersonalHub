package com.gernalix.personalhub.core.database

import com.gernalix.personalhub.core.database.capsules.sync.*
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Whole-database transfer capsule. Neither feature code nor UI replaces database files. */
class ImportRolledBack(cause: Throwable) : IllegalStateException("Import rolled back; reopening the previous database", cause)

object DatabaseVault {
    private val operations = ReentrantLock(true)
    private const val PRE_IMPORT_BACKUP_PREFIX = "personalhub-pre-import-"
    private const val PRE_IMPORT_BACKUP_SUFFIX = ".db"
    private val noTransferHooks = object : TransferHooks {}
    private var transferHooks: TransferHooks = noTransferHooks
    private var exportPublisherFactory: (Context, Uri) -> ExportPublisher = { context, uri -> DocumentFileExportPublisher(context, uri) }
    private var directorySyncForTests: ((File) -> Unit)? = null
    internal fun preferences(context: Context) = context.getSharedPreferences("personalhub_transfer", Context.MODE_PRIVATE)
    internal fun setTransferHooksForTests(hooks: TransferHooks?) {
        transferHooks = hooks ?: noTransferHooks
    }
    fun setExportPublisherFactoryForTests(factory: ((Context, Uri) -> ExportPublisher)?) {
        exportPublisherFactory = factory ?: { context, uri -> DocumentFileExportPublisher(context, uri) }
    }
    internal fun setDirectorySyncForTests(sync: ((File) -> Unit)?) {
        directorySyncForTests = sync
    }
    fun folder(context: Context): String? = preferences(context).getString("tree_uri", null)
    fun error(context: Context): String? = preferences(context).getString("error", null)
    fun lastExport(context: Context): Long = preferences(context).getLong("exported_at", 0)
    fun exportedGeneration(context: Context): Long = preferences(context).getLong("exported_generation", -1)
    fun currentGeneration(context: Context): Long = PersonalHubDatabase.get(context).openHelper.readableDatabase.query("SELECT generation FROM hub_generation WHERE id=1").use { c ->
        require(c.moveToFirst()) { "Missing database generation" }
        c.getLong(0)
    }
    fun autoExportStatus(context: Context): AutoExportStatus {
        val current = currentGeneration(context)
        val exported = exportedGeneration(context)
        return AutoExportStatus(
            folderConfigured = folder(context) != null,
            currentGeneration = current,
            exportedGeneration = exported,
            lastSuccessfulExportAt = lastExport(context),
            lastError = error(context),
            stale = folder(context) != null && current != exported,
        )
    }
    fun recordAutoExportFailure(context: Context, error: Throwable) {
        preferences(context).edit().putString("error", error.message ?: error.javaClass.simpleName).commit()
    }
    private fun fileHash(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun syncCopy(source: File, target: File) {
        source.inputStream().use { input -> FileOutputStream(target).use { out -> input.copyTo(out); out.fd.sync() } }
    }
    private fun atomicMove(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        // Ensure rename durability as well as file-content durability.
        syncDirectory(to.parentFile!!)
    }
    private fun syncDirectory(directory: File) {
        directorySyncForTests?.let { sync ->
            sync(directory)
            return
        }
        val fd = android.system.Os.open(directory.path, android.system.OsConstants.O_RDONLY, 0)
        try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
    }
    private fun sidecars(file: File) { listOf("-wal", "-shm", "-journal").forEach { File(file.path + it).delete() } }
    private fun marker(context: Context) = File(context.filesDir, "personalhub-import.pending")
    private fun damagedMarker(context: Context) = File(context.filesDir, "personalhub-import.pending.damaged")
    private fun databaseDir(context: Context) = context.getDatabasePath(PersonalHubDatabase.DB_NAME).parentFile!!
    private fun validPendingBackup(context: Context, marker: File): File? {
        val expectedDir = databaseDir(context).canonicalFile
        val raw = runCatching { marker.readText().trim() }.getOrElse { "" }
        if (raw.isBlank()) return null
        return runCatching {
            File(raw).canonicalFile.takeIf { backup -> backup.isFile && backup.parentFile == expectedDir }
        }.getOrNull()
    }
    private fun writeImportMarker(context: Context, backup: File) {
        val final = marker(context)
        val temp = File(context.filesDir, "${final.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { out ->
                out.write(backup.canonicalPath.toByteArray())
                out.fd.sync()
            }
            transferHooks.beforeImportMarkerPublish(temp, final)
            atomicMove(temp, final)
        } finally {
            temp.delete()
        }
    }
    internal fun writeImportMarkerForTests(context: Context, backup: File) = writeImportMarker(context, backup)
    private fun retireInvalidImportMarker(context: Context, marker: File) {
        runCatching {
            val damaged = damagedMarker(context)
            if (damaged.exists()) damaged.delete()
            atomicMove(marker, damaged)
        }.getOrElse {
            preferences(context).edit().putString("error", "Invalid interrupted import marker; recovery backups were preserved").commit()
            return
        }
        preferences(context).edit().putString("error", "Invalid interrupted import marker was preserved; recovery backups were preserved").commit()
    }

    /**
     * Removes only backups that cannot be needed for an in-progress import.
     * If a marker cannot be read or validated, it is deliberately treated as pending: preserving
     * an extra recovery copy is safer than deleting the only copy that can roll back a swap.
     */
    fun cleanupOrphanedPreImportBackups(context: Context) = operations.withLock {
        val pendingMarker = marker(context)
        val databaseDir = context.getDatabasePath(PersonalHubDatabase.DB_NAME).parentFile ?: return@withLock
        val protected = when {
            !pendingMarker.exists() -> null
            else -> validPendingBackup(context, pendingMarker) ?: return@withLock
        }
        databaseDir.listFiles()
            ?.filter { it.name.startsWith(PRE_IMPORT_BACKUP_PREFIX) && it.name.endsWith(PRE_IMPORT_BACKUP_SUFFIX) }
            ?.filter { it.canonicalFile != protected }
            ?.forEach { backup ->
                sidecars(backup)
                backup.delete()
            }
    }

    /** Run before any feature/database initialization. An interrupted replacement restores the last good DB. */
    fun recoverInterruptedImport(context: Context) {
        val marker = marker(context)
        if (!marker.isFile) return
        val backup = validPendingBackup(context, marker) ?: return retireInvalidImportMarker(context, marker)
        val target = context.getDatabasePath(PersonalHubDatabase.DB_NAME)
        val recovery = File(target.parentFile, "personalhub-recover.tmp")
        syncCopy(backup, recovery)
        sidecars(target)
        atomicMove(recovery, target)
        check(marker.delete())
        syncDirectory(context.filesDir)
        preferences(context).edit().putString("error", "Interrupted import was rolled back").commit()
        cleanupOrphanedPreImportBackups(context)
    }

    fun validate(context: Context, file: File): Long {
        require(file.isFile && file.length() >= 100) { "Invalid SQLite file" }
        file.inputStream().use { input -> val header = ByteArray(16); java.io.DataInputStream(input).readFully(header); require(header.contentEquals("SQLite format 3\u0000".toByteArray())) { "Invalid SQLite header" } }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            require(db.version in 2..PersonalHubDatabase.SCHEMA_VERSION) { "Incompatible database version" }
            val asset = "com.gernalix.personalhub.core.database.PersonalHubDatabase/${db.version}.json"
            val schema = JSONObject(context.assets.open(asset).bufferedReader().use { it.readText() }).getJSONObject("database")
            db.rawQuery("PRAGMA quick_check", null).use { c -> require(c.moveToFirst() && c.getString(0) == "ok" && !c.moveToNext()) { "SQLite integrity check failed" } }
            db.rawQuery("PRAGMA foreign_key_check", null).use { require(!it.moveToFirst()) { "Invalid database relationships" } }
            val entities = schema.getJSONArray("entities")
            val tables = mutableSetOf<String>()
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                tables.add(table)
                val actual = db.rawQuery("PRAGMA table_info(`$table`)", null).use { c ->
                    buildMap<String, Triple<String, Boolean, Int>> { while (c.moveToNext()) put(c.getString(1), Triple(c.getString(2).uppercase(), c.getInt(3) != 0, c.getInt(5))) }
                }
                val fields = entity.getJSONArray("fields")
                require(actual.size == fields.length()) { "Incompatible table: $table" }
                val primary = entity.getJSONObject("primaryKey").getJSONArray("columnNames")
                for (j in 0 until fields.length()) {
                    val f = fields.getJSONObject(j); val column = f.getString("columnName")
                    val pk = (0 until primary.length()).indexOfFirst { primary.getString(it) == column } + 1
                    require(actual[column] == Triple(f.getString("affinity"), f.optBoolean("notNull", false), pk)) { "Incompatible column: $table.$column" }
                }
                val expectedKeys = mutableSetOf<String>()
                val keys = entity.optJSONArray("foreignKeys") ?: org.json.JSONArray()
                for (j in 0 until keys.length()) {
                    val fk = keys.getJSONObject(j); val columns = fk.getJSONArray("columns"); val referenced = fk.getJSONArray("referencedColumns")
                    for (k in 0 until columns.length()) expectedKeys.add(listOf(fk.getString("table"), columns.getString(k), referenced.getString(k), fk.getString("onUpdate"), fk.getString("onDelete")).joinToString("|"))
                }
                val actualKeys = db.rawQuery("PRAGMA foreign_key_list(`$table`)", null).use { c -> buildSet { while (c.moveToNext()) add((2..6).joinToString("|") { c.getString(it) }) } }
                require(expectedKeys == actualKeys) { "Incompatible foreign keys: $table" }
                val indexes = entity.optJSONArray("indices") ?: org.json.JSONArray()
                val actualIndexes = db.rawQuery("PRAGMA index_list(`$table`)", null).use { c -> buildMap { while (c.moveToNext()) put(c.getString(1), c.getInt(2) != 0) } }
                for (j in 0 until indexes.length()) {
                    val index = indexes.getJSONObject(j); val name = index.getString("name")
                    require(actualIndexes[name] == index.optBoolean("unique", false)) { "Missing or incompatible index: $name" }
                    val actualColumns = db.rawQuery("PRAGMA index_info(`$name`)", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(2)) } }
                    val columns = index.getJSONArray("columnNames")
                    require(actualColumns == (0 until columns.length()).map { columns.getString(it) }) { "Incompatible index columns: $name" }
                }
            }
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null).use { c ->
                while(c.moveToNext()) require(c.getString(0) in tables || c.getString(0) in listOf("android_metadata", "room_master_table")) { "Unexpected database table" }
            }
            db.rawQuery("SELECT name, tbl_name, sql FROM sqlite_master WHERE type='trigger'", null).use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0); val table = c.getString(1)
                    val op = name.substringAfterLast('_')
                    require(table in tables && op in listOf("INSERT", "UPDATE", "DELETE")) { "Unexpected database trigger" }
                    val sql = c.getString(2).replace("IF NOT EXISTS ", "").replace(Regex("\\s+"), " ").trim()
                    val expected = when (name) {
                        "hub_dirty_${table}_$op" -> {
                            require(table !in setOf("hub_sync_pending", "hub_sync_known"))
                            "CREATE TRIGGER `hub_dirty_${table}_$op` AFTER $op ON `$table` BEGIN UPDATE hub_generation SET generation=generation+1 WHERE id=1; END"
                        }
                        "hub_sync_${table}_$op" -> {
                            require(db.version >= 3 && table !in SyncJournal.excluded)
                            val keys = db.rawQuery("PRAGMA table_info(`$table`)", null).use { columns ->
                                buildList { while (columns.moveToNext()) if (columns.getInt(5) > 0) add(columns.getInt(5) to columns.getString(1)) }.sortedBy { it.first }.map { it.second }
                            }
                            SyncJournal.trigger(table, keys, op, legacy = db.version == 3)
                        }
                        else -> error("Unexpected database trigger")
                    }
                    require(sql == expected) { "Incompatible database trigger" }
                }
            }
            db.rawQuery("SELECT bytes, sha256 FROM people_photos", null).use { c -> while (c.moveToNext()) require(PhotoCapsule.sha256(c.getBlob(0)) == c.getString(1)) { "Photo integrity check failed" } }
            return db.rawQuery("SELECT generation FROM hub_generation WHERE id=1", null).use { c -> require(c.moveToFirst()) { "Missing database generation" }; c.getLong(0) }
        }
    }

    fun configureFolder(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        require(DocumentFile.fromTreeUri(context, uri)?.canWrite() == true) { "Export folder is not writable" }
        preferences(context).edit().putString("tree_uri", uri.toString()).putLong("exported_generation", -1).commit()
        HubAutoExport.request(context)
    }

    /** Checkpoint plus writer exclusion works on every supported Android SQLite version, including API 29. */
    private fun snapshot(context: Context, target: File): Long = DatabaseGate.access {
        val db = PersonalHubDatabase.get(context).openHelper.writableDatabase
        db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { c -> require(c.moveToFirst() && c.getInt(0) == 0) { "Database busy; export will retry" } }
        syncCopy(context.getDatabasePath(PersonalHubDatabase.DB_NAME), target)
        // Only the detached snapshot changes journal mode: it must travel without sidecars.
        SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READWRITE).use { copy ->
            copy.rawQuery("PRAGMA journal_mode=DELETE", null).use { c -> require(c.moveToFirst() && c.getString(0).equals("delete", true)) }
        }
        validate(context, target)
    }

    fun backupCurrent(context: Context): File = operations.withLock {
        File(context.getDatabasePath(PersonalHubDatabase.DB_NAME).parentFile, "personalhub-backup-${UUID.randomUUID()}.db").also { snapshot(context, it) }
    }

    fun exportNow(context: Context): Boolean = operations.withLock {
        val uri = folder(context)?.let(Uri::parse) ?: return false
        val prefs = preferences(context)
        val stage = File(context.cacheDir, "personalhub-export-${UUID.randomUUID()}.db")
        try {
            val generation = snapshot(context, stage)
            val publisher = exportPublisherFactory(context, uri)
            val temporary = publisher.createTemporary("personalhub-${UUID.randomUUID()}.tmp")
            val previous = publisher.find(PersonalHubDatabase.DB_NAME)
            var movedPrevious: ExportFile? = null
            try {
                publisher.writeFrom(stage, temporary)
                val verify = File(context.cacheDir, "personalhub-verify-${UUID.randomUUID()}.db")
                try {
                    publisher.readTo(temporary, verify)
                    require(validate(context, verify) == generation && stage.length() == verify.length() && fileHash(stage) == fileHash(verify)) { "Export readback differs" }
                } finally { verify.delete() }
                if (previous != null) {
                    val backup = publisher.find("personalhub.db.bak")
                    require(backup == null || backup.delete()) { "Cannot rotate export backup" }
                    require(previous.renameTo("personalhub.db.bak")) { "Provider cannot safely rotate export" }
                    movedPrevious = previous
                }
                require(temporary.renameTo(PersonalHubDatabase.DB_NAME)) { "Provider cannot publish export" }
                prefs.edit().putLong("exported_generation", generation).putLong("exported_at", System.currentTimeMillis()).remove("error").commit()
                true
            } catch (error: Throwable) {
                val moved = movedPrevious
                if (moved != null) {
                    val restored = runCatching { moved.renameTo(PersonalHubDatabase.DB_NAME) }.getOrDefault(false)
                    if (!restored) {
                        throw IllegalStateException(
                            "Export failed; previous SAF copy remains recoverable as personalhub.db.bak but could not be restored",
                            error,
                        )
                    }
                }
                throw error
            } finally { if (temporary.name != PersonalHubDatabase.DB_NAME) temporary.delete() }
        } catch (error: Throwable) {
            prefs.edit().putString("error", error.message ?: error.javaClass.simpleName).commit()
            throw error
        } finally { stage.delete() }
    }

    /** Returns only after verified replacement; caller must restart the process before allowing further edits. */
    fun importDatabase(context: Context, uri: Uri) = DatasetteSync.pauseUploads { operations.withLock {
        val target = context.getDatabasePath(PersonalHubDatabase.DB_NAME)
        target.parentFile!!.mkdirs()
        val stage = File(target.parentFile, "personalhub-import-${UUID.randomUUID()}.db")
        try {
            context.contentResolver.openInputStream(uri).use { input -> FileOutputStream(stage).use { out -> requireNotNull(input).copyTo(out); out.fd.sync() } }
            validate(context, stage)
            // Room upgrades accepted v2 exports without changing the source file.
            val importedVersion = SQLiteDatabase.openDatabase(stage.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
            if (importedVersion < PersonalHubDatabase.SCHEMA_VERSION) {
                PersonalHubDatabase.openTemporary(context, stage.absolutePath).let { temporary ->
                    try { temporary.openHelper.writableDatabase } finally { temporary.close() }
                }
            }
            validate(context, stage)
            DatabaseGate.replace {
                val backup = File(target.parentFile, "$PRE_IMPORT_BACKUP_PREFIX${UUID.randomUUID()}$PRE_IMPORT_BACKUP_SUFFIX")
                try {
                    snapshot(context, backup)
                    // Preserve this installation's sent/uncertain identities across file replacement.
                    // Imported data can omit rows that still need remote tombstones.
                    SQLiteDatabase.openDatabase(stage.path, null, SQLiteDatabase.OPEN_READWRITE).use { imported ->
                        imported.execSQL("ATTACH DATABASE ? AS previous", arrayOf(backup.path))
                        imported.beginTransaction()
                        try {
                            imported.execSQL("DELETE FROM hub_sync_pending")
                            imported.execSQL("DELETE FROM hub_sync_known")
                            imported.execSQL("INSERT OR IGNORE INTO hub_sync_known SELECT table_name,row_key FROM previous.hub_sync_known")
                            imported.execSQL("INSERT OR IGNORE INTO hub_sync_known SELECT table_name,row_key FROM previous.hub_sync_pending")
                            imported.setTransactionSuccessful()
                        } finally { imported.endTransaction() }
                        imported.execSQL("DETACH DATABASE previous")
                    }
                    DatasetteSettings.requireFull(context)
                    PersonalHubDatabase.closeInstance()
                    writeImportMarker(context, backup)
                    sidecars(target)
                    atomicMove(stage, target)
                    PersonalHubDatabase.get(context).openHelper.writableDatabase
                    validate(context, target)
                    preferences(context).edit().putLong("exported_generation", -1).remove("error").commit()
                    HubAutoExport.request(context)
                    retireSeparateDatabases(context)
                    check(marker(context).delete())
                    syncDirectory(context.filesDir)
                    cleanupOrphanedPreImportBackups(context)
                } catch (error: Throwable) {
                    PersonalHubDatabase.closeInstance()
                    if (marker(context).exists()) recoverInterruptedImport(context)
                    PersonalHubDatabase.get(context).openHelper.writableDatabase
                    DatabaseGate.resume()
                    throw ImportRolledBack(error)
                }
            }
        } finally { stage.delete(); sidecars(stage) }
    } }

    private fun retireSeparateDatabases(context: Context) {
        val names = listOf("luoghi.db", "multitimer.db", "mtt_remote_sync.db", "sostanze.db", "super_contacts.db", "wordpulse.db", "personalhub_migration_map.db")
        val backup = File(context.filesDir, "retired-databases-${System.currentTimeMillis()}")
        names.forEach { name ->
            listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
                val file = context.getDatabasePath(name + suffix)
                if (file.exists()) {
                    backup.mkdirs()
                    check(file.renameTo(File(backup, name + suffix))) { "Could not preserve former database backup" }
                }
            }
        }
    }

    internal interface TransferHooks {
        fun beforeImportMarkerPublish(temp: File, final: File) = Unit
    }

    interface ExportFile {
        val name: String?
        fun renameTo(displayName: String): Boolean
        fun delete(): Boolean
    }

    interface ExportPublisher {
        fun createTemporary(name: String): ExportFile
        fun find(name: String): ExportFile?
        fun writeFrom(source: File, target: ExportFile)
        fun readTo(source: ExportFile, target: File)
    }

    private class DocumentFileExportPublisher(private val context: Context, uri: Uri) : ExportPublisher {
        private val dir = requireNotNull(DocumentFile.fromTreeUri(context, uri)) { "Export folder unavailable" }
        override fun createTemporary(name: String) = DocumentExportFile(requireNotNull(dir.createFile("application/octet-stream", name)))
        override fun find(name: String) = dir.findFile(name)?.let(::DocumentExportFile)
        override fun writeFrom(source: File, target: ExportFile) {
            val file = target as DocumentExportFile
            context.contentResolver.openOutputStream(file.document.uri, "wt").use { out ->
                source.inputStream().use { it.copyTo(requireNotNull(out)) }
            }
        }
        override fun readTo(source: ExportFile, target: File) {
            val file = source as DocumentExportFile
            context.contentResolver.openInputStream(file.document.uri).use { input ->
                FileOutputStream(target).use { out ->
                    requireNotNull(input).copyTo(out)
                    out.fd.sync()
                }
            }
        }
    }

    private class DocumentExportFile(val document: DocumentFile) : ExportFile {
        override val name: String? get() = document.name
        override fun renameTo(displayName: String) = document.renameTo(displayName)
        override fun delete() = document.delete()
    }
}

data class AutoExportStatus(
    val folderConfigured: Boolean,
    val currentGeneration: Long,
    val exportedGeneration: Long,
    val lastSuccessfulExportAt: Long,
    val lastError: String?,
    val stale: Boolean,
)
