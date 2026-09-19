package com.gernalix.personalhub.core.database

import com.gernalix.personalhub.core.database.capsules.sync.*
import com.gernalix.personalhub.core.database.capsules.gitdata.GitDataSync
import com.gernalix.personalhub.core.database.capsules.gitdata.GitDataTracking
import com.gernalix.personalhub.core.database.capsules.gitdata.GitDataSettings
import com.gernalix.personalhub.core.database.capsules.gitdata.GitHistoryStore
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Whole-database transfer capsule. Neither feature code nor UI replaces database files. */
class ImportRolledBack(cause: Throwable) : IllegalStateException("Import rolled back; reopening the previous database", cause)

internal val LEGACY_TIMER_SYNC_TABLES = setOf("sync_meta", "sync_queue", "sync_shadow")

object DatabaseVault {
    private val operations = ReentrantLock(true)
    /** Acquire this before DatabaseGate when a transfer needs both locks. */
    internal fun <T> withOperations(block: () -> T): T = operations.withLock(block)
    private const val PRE_IMPORT_BACKUP_PREFIX = "personalhub-pre-import-"
    private const val PRE_IMPORT_BACKUP_SUFFIX = ".db"
    private const val TRANSIENT_BACKUP_NAME = "personalhub-backup.db"
    private const val LEGACY_TRANSIENT_BACKUP_PREFIX = "personalhub-backup-"
    private const val STARTUP_ROLLBACK_PREFIX = "personalhub-startup-v"
    internal const val CANONICAL_DOCUMENT_URI = "canonical_document_uri"
    internal const val BACKUP_DOCUMENT_URI = "backup_document_uri"
    private val noTransferHooks = object : TransferHooks {}
    private var transferHooks: TransferHooks = noTransferHooks
    private var exportPublisherFactory: (Context, Uri) -> ExportPublisher = { context, uri -> DocumentFileExportPublisher(context, uri) }
    private var directorySyncForTests: ((File) -> Unit)? = null
    private fun rootPreferences(context: Context) =
        context.getSharedPreferences("personalhub_transfer", Context.MODE_PRIVATE)

    internal fun preferences(context: Context) = context.getSharedPreferences(
        "personalhub_transfer" + DatabaseProfiles.preferenceSuffix(context),
        Context.MODE_PRIVATE,
    )
    internal fun setTransferHooksForTests(hooks: TransferHooks?) {
        transferHooks = hooks ?: noTransferHooks
    }
    fun setExportPublisherFactoryForTests(factory: ((Context, Uri) -> ExportPublisher)?) {
        exportPublisherFactory = factory ?: { context, uri -> DocumentFileExportPublisher(context, uri) }
    }
    internal fun setDirectorySyncForTests(sync: ((File) -> Unit)?) {
        directorySyncForTests = sync
    }
    fun folder(context: Context): String? = rootPreferences(context).getString("tree_uri", null)
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

    private fun cleanupLegacyTransientBackups(context: Context) {
        databaseDir(context).listFiles()
            ?.filter {
                it.isFile &&
                    it.name.startsWith(LEGACY_TRANSIENT_BACKUP_PREFIX) &&
                    it.name.endsWith(".db")
            }
            ?.forEach { backup ->
                sidecars(backup)
                backup.delete()
            }
    }

    private fun cleanupCompletedStartupRollbacks(context: Context) {
        databaseDir(context).listFiles()
            ?.filter {
                it.isFile &&
                    it.name.startsWith(STARTUP_ROLLBACK_PREFIX) &&
                    it.name.endsWith(".db")
            }
            ?.forEach { backup ->
                sidecars(backup)
                backup.delete()
            }
    }

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
        cleanupLegacyTransientBackups(context)
        val marker = marker(context)
        if (!marker.isFile) {
            // A process can die after the profile intent is persisted but before the database
            // replacement journal exists. In that window neither the canonical DB nor the active
            // profile has changed, so only the orphaned intent needs to be retired. The same rule
            // is safe after the journal commit point, where both already refer to the target.
            DatabaseProfiles.clearPendingSwitch(context)
            return
        }
        val backup = validPendingBackup(context, marker) ?: return retireInvalidImportMarker(context, marker)
        val target = context.getDatabasePath(PersonalHubDatabase.DB_NAME)
        val recovery = File(target.parentFile, "personalhub-recover.tmp")
        syncCopy(backup, recovery)
        sidecars(target)
        atomicMove(recovery, target)
        DatabaseProfiles.rollbackPendingSwitch(context)
        check(marker.delete())
        syncDirectory(context.filesDir)
        preferences(context).edit().putString("error", "Interrupted import was rolled back").commit()
        cleanupOrphanedPreImportBackups(context)
    }

    /** Validates or safely upgrades the canonical database before feature code can write to it. */
    fun ensureStartupReady(context: Context): Boolean = operations.withLock {
        val target = context.getDatabasePath(PersonalHubDatabase.DB_NAME)
        val prefs = preferences(context)
        val appVersion = runCatching {
            androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(
                context.packageManager.getPackageInfo(context.packageName, 0),
            )
        }.getOrDefault(0L)
        if (
            prefs.getInt("startup_gate_schema", -1) == PersonalHubDatabase.SCHEMA_VERSION &&
            prefs.getLong("startup_gate_app_version", -1L) == appVersion && target.isFile
        ) {
            cleanupCompletedStartupRollbacks(context)
            return@withLock true
        }

        fun pass() = prefs.edit()
            .putInt("startup_gate_schema", PersonalHubDatabase.SCHEMA_VERSION)
            .putLong("startup_gate_app_version", appVersion)
            .remove("error")
            .commit()
        fun fail(message: String): Boolean {
            prefs.edit().remove("startup_gate_schema").remove("startup_gate_app_version")
                .putString("error", message).commit()
            return false
        }

        if (!target.exists()) {
            return@withLock runCatching {
                PersonalHubDatabase.get(context).openHelper.writableDatabase
                validate(context, target)
                pass()
            }.getOrElse { fail("Database startup check failed; existing data was preserved") }
        }

        val sourceVersion = runCatching {
            SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
        }.getOrElse { return@withLock fail("Database is unreadable; existing data was preserved") }
        if (!PersonalHubDatabase.canMigrateFrom(sourceVersion)) {
            return@withLock fail("Database version is unsupported; existing data was preserved")
        }
        if (sourceVersion == PersonalHubDatabase.SCHEMA_VERSION) {
            return@withLock runCatching {
                validate(context, target)
                pass().also { ready -> if (ready) cleanupCompletedStartupRollbacks(context) }
            }.getOrElse { fail("Database validation failed; existing data was preserved") }
        }

        val snapshot = File(target.parentFile, "personalhub-startup-v$sourceVersion.db")
        return@withLock runCatching {
            SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { c ->
                    require(c.moveToFirst() && c.getInt(0) == 0) { "Database is busy" }
                }
            }
            syncCopy(target, snapshot)
            PersonalHubDatabase.closeInstance()
            if (!PersonalHubDatabase.hasProductionMigrationPath(context, sourceVersion)) {
                val remoteStage = File(
                    target.parentFile,
                    "personalhub-remote-migration-${UUID.randomUUID()}.db",
                )
                try {
                    syncCopy(target, remoteStage)
                    require(
                        GitDataSync.migrateStagingFromRemote(
                            context = context,
                            file = remoteStage,
                            from = sourceVersion,
                            to = PersonalHubDatabase.SCHEMA_VERSION,
                        ),
                    ) { "No packaged or remote migration path is available" }
                    validate(context, remoteStage)
                    sidecars(target)
                    atomicMove(remoteStage, target)
                } finally {
                    remoteStage.delete()
                    sidecars(remoteStage)
                }
            }
            val temporary = PersonalHubDatabase.openTemporary(context, target.absolutePath)
            try { temporary.openHelper.writableDatabase } finally { temporary.close() }
            validate(context, target)
            pass().also { ready ->
                if (ready) {
                    sidecars(snapshot)
                    snapshot.delete()
                    syncDirectory(target.parentFile!!)
                }
            }
        }.getOrElse {
            PersonalHubDatabase.closeInstance()
            if (snapshot.isFile) {
                sidecars(target)
                syncCopy(snapshot, target)
            }
            fail("Database upgrade failed and was rolled back; no writes were allowed")
        }
    }

    fun validate(context: Context, file: File): Long {
        require(file.isFile && file.length() >= 100) { "Invalid SQLite file" }
        file.inputStream().use { input -> val header = ByteArray(16); java.io.DataInputStream(input).readFully(header); require(header.contentEquals("SQLite format 3\u0000".toByteArray())) { "Invalid SQLite header" } }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            require(PersonalHubDatabase.canMigrateFrom(db.version)) { "Incompatible database version" }
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
            // Room validates every table PersonalHub owns above. Extra tables are intentionally
            // tolerated: real databases can retain inert legacy tables after migrations, and an
            // extra table cannot affect app data unless it has a trigger. Every trigger is still
            // validated below, so this does not weaken the executable-schema boundary.
            val databaseTables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null).use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(0)) }
            }
            db.rawQuery("SELECT name, tbl_name, sql FROM sqlite_master WHERE type='trigger'", null).use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0); val table = c.getString(1)
                    val op = name.substringAfterLast('_')
                    if (name.startsWith("hub_activity_") && table in tables) continue
                    require(table in databaseTables && op in listOf("INSERT", "UPDATE", "DELETE")) { "Unexpected database trigger: $name" }
                    val sql = c.getString(2).replace("IF NOT EXISTS ", "").replace(Regex("\\s+"), " ").trim()
                    val expected = when (name) {
                        "hub_dirty_${table}_$op" -> {
                            // Older Timer sync tables can retain valid generation triggers from
                            // before they were excluded from new trigger installation. Validate
                            // their exact SQL here; Room removes them on the next database open.
                            require(table !in SyncJournal.excluded || table in LEGACY_TIMER_SYNC_TABLES)
                            "CREATE TRIGGER `hub_dirty_${table}_$op` AFTER $op ON `$table` BEGIN UPDATE hub_generation SET generation=generation+1 WHERE id=1; END"
                        }
                        "hub_sync_${table}_$op" -> {
                            require(db.version >= 3 && table !in SyncJournal.excluded)
                            val keys = db.rawQuery("PRAGMA table_info(`$table`)", null).use { columns ->
                                buildList { while (columns.moveToNext()) if (columns.getInt(5) > 0) add(columns.getInt(5) to columns.getString(1)) }.sortedBy { it.first }.map { it.second }
                            }
                            SyncJournal.trigger(table, keys, op, legacy = db.version == 3)
                        }
                        "hub_git_dirty_${table}_$op" -> {
                            require(table !in GitDataTracking.operationalTables && table !in SyncJournal.excluded)
                            val columns = db.rawQuery("PRAGMA table_info(`$table`)", null).use { columnsCursor ->
                                buildList {
                                    while (columnsCursor.moveToNext()) add(columnsCursor.getString(1))
                                }
                            }
                            val keys = db.rawQuery("PRAGMA table_info(`$table`)", null).use { columnsCursor ->
                                buildList {
                                    while (columnsCursor.moveToNext()) {
                                        if (columnsCursor.getInt(5) > 0) {
                                            add(columnsCursor.getInt(5) to columnsCursor.getString(1))
                                        }
                                    }
                                }.sortedBy { it.first }.map { it.second }
                            }
                            GitDataTracking.trigger(table, columns, keys, op)
                        }
                        else -> error("Unexpected database trigger: $name")
                    }
                    require(sql == expected) { "Incompatible database trigger: $name" }
                }
            }
            db.rawQuery("SELECT bytes, sha256 FROM people_photos", null).use { c -> while (c.moveToNext()) require(sha256(c.getBlob(0)) == c.getString(1)) { "Photo integrity check failed" } }
            return db.rawQuery("SELECT generation FROM hub_generation WHERE id=1", null).use { c -> require(c.moveToFirst()) { "Missing database generation" }; c.getLong(0) }
        }
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun configureFolder(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        require(DocumentFile.fromTreeUri(context, uri)?.canWrite() == true) { "Export folder is not writable" }
        check(rootPreferences(context).edit().putString("tree_uri", uri.toString()).commit())
        preferences(context).edit()
            .putLong("exported_generation", -1)
            .remove(CANONICAL_DOCUMENT_URI)
            .remove(BACKUP_DOCUMENT_URI)
            .commit()
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
        cleanupLegacyTransientBackups(context)
        File(context.cacheDir, TRANSIENT_BACKUP_NAME).also { target ->
            sidecars(target)
            target.delete()
            snapshot(context, target)
        }
    }

    private fun verifyExportFile(
        context: Context,
        publisher: ExportPublisher,
        source: File,
        exported: ExportFile,
        generation: Long,
    ) {
        val verify = File(context.cacheDir, "personalhub-verify-${UUID.randomUUID()}.db")
        try {
            publisher.readTo(exported, verify)
            require(
                validate(context, verify) == generation &&
                    source.length() == verify.length() &&
                    fileHash(source) == fileHash(verify)
            ) { "Export readback differs" }
        } finally {
            verify.delete()
        }
    }

    private fun canonicalExportName(context: Context) = DatabaseProfiles.exportStem(context) + ".db"
    private fun legacyBackupExportName(context: Context) = canonicalExportName(context) + ".bak"
    private fun stagedExportName(context: Context) = canonicalExportName(context) + ".tmp"
    private fun previousExportName(context: Context) = canonicalExportName(context) + ".old.tmp"

    private fun documentUriPreference(context: Context, name: String) = when (name) {
        canonicalExportName(context) -> CANONICAL_DOCUMENT_URI
        else -> error("Unsupported stable export name: $name")
    }

    private fun retireLegacyExportBackup(
        context: Context,
        prefs: android.content.SharedPreferences,
        publisher: ExportPublisher,
    ) {
        val backupName = legacyBackupExportName(context)
        val seen = mutableSetOf<String>()
        fun remove(file: ExportFile?) {
            if (file == null || !seen.add(file.identity)) return
            require(file.name == backupName) { "Stored backup identity now names ${file.name}" }
            require(file.delete()) { "Cannot delete obsolete auto-export backup $backupName" }
        }
        prefs.getString(BACKUP_DOCUMENT_URI, null)?.let { identity ->
            remove(publisher.open(identity))
        }
        remove(publisher.find(backupName))
        require(prefs.edit().remove(BACKUP_DOCUMENT_URI).commit()) {
            "Cannot retire obsolete auto-export backup identity"
        }
    }

    private fun persistCanonicalExportIdentity(
        prefs: android.content.SharedPreferences,
        file: ExportFile,
    ) {
        require(
            prefs.edit().putString(CANONICAL_DOCUMENT_URI, file.identity).commit()
        ) { "Cannot persist canonical export identity" }
    }

    private fun createNamedExportFile(publisher: ExportPublisher, name: String): ExportFile {
        val created = publisher.create(name)
        if (created.name != name) {
            runCatching { created.delete() }
            error("Provider created ${created.name ?: "an unnamed document"} instead of $name")
        }
        return created
    }

    /**
     * Repairs a publish interrupted while only transient SAF documents existed.
     * Steady state is exactly one canonical database document.
     */
    private fun recoverInterruptedExport(
        context: Context,
        prefs: android.content.SharedPreferences,
        publisher: ExportPublisher,
    ): ExportFile? {
        val canonicalName = canonicalExportName(context)
        val stageName = stagedExportName(context)
        val oldName = previousExportName(context)
        val stored = prefs.getString(CANONICAL_DOCUMENT_URI, null)?.let(publisher::open)
        if (stored != null && stored.name != canonicalName) {
            require(prefs.edit().remove(CANONICAL_DOCUMENT_URI).commit()) {
                "Cannot retire stale canonical export identity"
            }
        }
        val canonical = publisher.find(canonicalName)
            ?: stored?.takeIf { it.name == canonicalName }
        val old = publisher.find(oldName)
        val staged = publisher.find(stageName)

        if (canonical != null) {
            if (old != null) require(old.delete()) { "Cannot delete stale previous export" }
            if (staged != null) require(staged.delete()) { "Cannot delete stale staged export" }
            persistCanonicalExportIdentity(prefs, canonical)
            return canonical
        }

        if (old != null) {
            val restored = publisher.rename(old, canonicalName)
            if (staged != null) require(staged.delete()) { "Cannot delete stale staged export" }
            persistCanonicalExportIdentity(prefs, restored)
            return restored
        }

        if (staged != null) {
            val verify = File(context.cacheDir, "personalhub-export-recovery-${UUID.randomUUID()}.db")
            try {
                publisher.readTo(staged, verify)
                validate(context, verify)
                val promoted = publisher.rename(staged, canonicalName)
                persistCanonicalExportIdentity(prefs, promoted)
                return promoted
            } catch (_: Throwable) {
                runCatching { staged.delete() }
            } finally {
                verify.delete()
            }
        }

        require(prefs.edit().remove(CANONICAL_DOCUMENT_URI).commit()) {
            "Cannot clear missing canonical export identity"
        }
        return null
    }

    private fun existingStableExportFile(
        context: Context,
        prefs: android.content.SharedPreferences,
        publisher: ExportPublisher,
        name: String,
    ): ExportFile? {
        val preference = documentUriPreference(context, name)
        prefs.getString(preference, null)?.let { identity ->
            publisher.open(identity)?.let { stored ->
                require(stored.name == name) { "Stored $name identity now names ${stored.name}" }
                return stored
            }
            require(prefs.edit().remove(preference).commit()) { "Cannot retire missing $name identity" }
        }
        val found = publisher.find(name) ?: return null
        require(found.name == name) { "Provider resolved ${found.name} instead of $name" }
        require(prefs.edit().putString(preference, found.identity).commit()) { "Cannot persist $name identity" }
        return found
    }

    private fun stableExportFile(
        context: Context,
        prefs: android.content.SharedPreferences,
        publisher: ExportPublisher,
        name: String,
    ): ExportFile {
        existingStableExportFile(context, prefs, publisher, name)?.let { return it }
        val created = publisher.create(name)
        if (created.name != name) {
            runCatching { created.delete() }
            error("Provider created ${created.name ?: "an unnamed document"} instead of $name")
        }
        require(prefs.edit().putString(documentUriPreference(context, name), created.identity).commit()) {
            "Cannot persist $name identity"
        }
        return created
    }

    private fun refreshedStableExportFile(publisher: ExportPublisher, file: ExportFile, name: String): ExportFile {
        val refreshed = requireNotNull(publisher.open(file.identity)) { "Provider lost $name identity" }
        require(refreshed.name == name) { "Provider renamed $name to ${refreshed.name}" }
        return refreshed
    }

    fun exportNow(context: Context): Boolean = operations.withLock {
        val uri = folder(context)?.let(Uri::parse) ?: return false
        val prefs = preferences(context)
        val stage = File(context.cacheDir, "personalhub-export-${UUID.randomUUID()}.db")
        val canonicalName = canonicalExportName(context)
        val stageName = stagedExportName(context)
        val oldName = previousExportName(context)
        try {
            val generation = snapshot(context, stage)
            val publisher = exportPublisherFactory(context, uri)
            val previous = recoverInterruptedExport(context, prefs, publisher)
            val staged = createNamedExportFile(publisher, stageName)
            var old: ExportFile? = null
            try {
                publisher.writeFrom(stage, staged)
                verifyExportFile(context, publisher, stage, staged, generation)
                if (previous != null) {
                    old = publisher.rename(previous, oldName)
                }
                val canonical = publisher.rename(staged, canonicalName)
                verifyExportFile(context, publisher, stage, canonical, generation)
                if (old != null) {
                    require(old.delete()) { "Cannot retire previous canonical export" }
                    old = null
                }
                retireLegacyExportBackup(context, prefs, publisher)
                persistCanonicalExportIdentity(prefs, canonical)
                require(
                    prefs.edit().putLong("exported_generation", generation)
                        .putLong("exported_at", System.currentTimeMillis()).remove("error").commit()
                ) { "Cannot persist successful export state" }
                true
            } catch (error: Throwable) {
                runCatching {
                    val previousNow = old ?: publisher.find(oldName)
                    if (previousNow != null) {
                        publisher.find(canonicalName)?.let { require(it.delete()) }
                        val restored = publisher.rename(previousNow, canonicalName)
                        persistCanonicalExportIdentity(prefs, restored)
                    } else if (previous == null) {
                        // First-ever export: a failed readback must not leave a corrupt canonical.
                        publisher.find(canonicalName)?.delete()
                    }
                    publisher.find(stageName)?.delete()
                }
                throw error
            }
        } catch (error: Throwable) {
            prefs.edit().putString("error", error.message ?: error.javaClass.simpleName).commit()
            throw error
        } finally { stage.delete() }
    }

    /** Returns only after verified replacement; caller must restart the process before allowing further edits. */
    fun importDatabase(context: Context, uri: Uri) = GitDataSync.pauseSync { DatasetteSync.pauseUploads { operations.withLock {
        val target = context.getDatabasePath(PersonalHubDatabase.DB_NAME)
        target.parentFile!!.mkdirs()
        val stage = File(target.parentFile, "personalhub-import-${UUID.randomUUID()}.db")
        try {
            context.contentResolver.openInputStream(uri).use { input -> FileOutputStream(stage).use { out -> requireNotNull(input).copyTo(out); out.fd.sync() } }
            validate(context, stage)
            // Room upgrades accepted v2 exports without changing the source file.
            val importedVersion = SQLiteDatabase.openDatabase(stage.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
            val gitHistoryEnabled = runCatching {
                GitDataSettings.configuration(context).enabled
            }.getOrDefault(false)
            if (importedVersion < PersonalHubDatabase.SCHEMA_VERSION || gitHistoryEnabled) {
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

                            // Git metadata belongs to this installation, never to the imported
                            // payload. Clear any imported projection first.
                            listOf(
                                GitDataTracking.TABLE,
                                GitDataTracking.EVENTS_TABLE,
                                GitDataTracking.CONTEXT_TABLE,
                                GitDataTracking.APPLIED_PATCHES_TABLE,
                                GitHistoryStore.TABLE,
                                GitHistoryStore.FIELD_STATS_TABLE,
                            ).forEach { table ->
                                if (rawTableExists(imported, "main", table)) {
                                    imported.execSQL("DELETE FROM `$table`")
                                }
                            }
                            if (gitHistoryEnabled) {
                                val preservedTables = listOf(
                                    GitDataTracking.TABLE,
                                    GitDataTracking.EVENTS_TABLE,
                                    GitDataTracking.APPLIED_PATCHES_TABLE,
                                    GitHistoryStore.TABLE,
                                    GitHistoryStore.FIELD_STATS_TABLE,
                                )
                                preservedTables.forEach { table ->
                                    require(rawTableExists(imported, "main", table)) {
                                        "Git operational table is missing from import staging: $table"
                                    }
                                    if (rawTableExists(imported, "previous", table)) {
                                        imported.execSQL(
                                            "INSERT OR REPLACE INTO `$table` SELECT * FROM previous.`$table`",
                                        )
                                    }
                                }
                            }
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
                    GitDataSync.onDatabaseReplaced(context)
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
    } } }

    internal fun snapshotProfileCopy(context: Context, file: File) = operations.withLock {
        file.parentFile?.mkdirs()
        snapshot(context, file)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { copy ->
            listOf(
                "hub_sync_pending", "hub_sync_known", "sync_queue", "sync_shadow", "sync_meta",
                GitDataTracking.TABLE, GitDataTracking.EVENTS_TABLE, GitDataTracking.CONTEXT_TABLE,
                GitDataTracking.APPLIED_PATCHES_TABLE, GitHistoryStore.TABLE,
                GitHistoryStore.FIELD_STATS_TABLE,
            ).forEach { table ->
                if (rawTableExists(copy, "main", table)) copy.execSQL("DELETE FROM `$table`")
            }
        }
        validate(context, file)
    }

    internal fun createEmptyProfileDatabase(context: Context, file: File) = operations.withLock {
        require(!file.exists()) { "Profile database already exists" }
        file.parentFile?.mkdirs()
        val temporary = PersonalHubDatabase.openStaging(context, file.absolutePath)
        try {
            temporary.openHelper.writableDatabase
        } finally {
            temporary.close()
        }
        validate(context, file)
    }

    /**
     * Saves the current profile and atomically mounts the target profile as personalhub.db.
     * Unlike a normal import, sync identities are never copied across profiles.
     */
    internal fun switchProfileDatabase(
        context: Context,
        currentProfileFile: File,
        targetProfileFile: File,
    ) = GitDataSync.pauseSync {
        DatasetteSync.pauseUploads {
            operations.withLock {
                require(targetProfileFile.isFile) { "Profile database is missing" }
                val target = context.getDatabasePath(PersonalHubDatabase.DB_NAME)
                val stage = File(target.parentFile, "personalhub-profile-${UUID.randomUUID()}.db")
                try {
                    syncCopy(targetProfileFile, stage)
                    val importedVersion = SQLiteDatabase.openDatabase(
                        stage.path,
                        null,
                        SQLiteDatabase.OPEN_READONLY,
                    ).use { it.version }
                    if (importedVersion < PersonalHubDatabase.SCHEMA_VERSION) {
                        PersonalHubDatabase.openTemporary(context, stage.absolutePath).let { temporary ->
                            try { temporary.openHelper.writableDatabase } finally { temporary.close() }
                        }
                    }
                    validate(context, stage)
                    DatabaseGate.replace {
                        val backup = File(
                            target.parentFile,
                            "$PRE_IMPORT_BACKUP_PREFIX${UUID.randomUUID()}$PRE_IMPORT_BACKUP_SUFFIX",
                        )
                        try {
                            currentProfileFile.parentFile?.mkdirs()
                            transferHooks.beforeProfileSnapshot()
                            snapshot(context, currentProfileFile)
                            transferHooks.afterProfileSnapshot()
                            snapshot(context, backup)
                            PersonalHubDatabase.closeInstance()
                            writeImportMarker(context, backup)
                            sidecars(target)
                            transferHooks.beforeProfileDatabaseRename()
                            atomicMove(stage, target)
                            transferHooks.afterProfileDatabaseRename()
                            validate(context, target)
                            transferHooks.beforeActiveProfileUpdate()
                            DatabaseProfiles.markPendingTargetActive(context)
                            transferHooks.afterActiveProfileUpdate()
                            transferHooks.beforeProfileMarkerRetirement()
                            check(marker(context).delete())
                            transferHooks.afterProfileMarkerRetirement()
                            syncDirectory(context.filesDir)
                            cleanupOrphanedPreImportBackups(context)
                        } catch (error: Throwable) {
                            PersonalHubDatabase.closeInstance()
                            if (marker(context).exists()) recoverInterruptedImport(context)
                            DatabaseGate.resume()
                            throw ImportRolledBack(error)
                        }
                    }
                } finally {
                    stage.delete()
                    sidecars(stage)
                }
            }
        }
    }

    fun importDatabaseFile(context: Context, file: File) {
        require(file.isFile) { "Database import source is missing" }
        importDatabase(context, Uri.fromFile(file))
    }

    private fun rawTableExists(
        db: SQLiteDatabase,
        schema: String,
        table: String,
    ): Boolean {
        require(schema == "main" || schema == "previous")
        return db.rawQuery(
            "SELECT 1 FROM $schema.sqlite_master WHERE type='table' AND name=? LIMIT 1",
            arrayOf(table),
        ).use { it.moveToFirst() }
    }

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
        fun beforeProfileSnapshot() = Unit
        fun afterProfileSnapshot() = Unit
        fun beforeProfileDatabaseRename() = Unit
        fun afterProfileDatabaseRename() = Unit
        fun beforeActiveProfileUpdate() = Unit
        fun afterActiveProfileUpdate() = Unit
        fun beforeProfileMarkerRetirement() = Unit
        fun afterProfileMarkerRetirement() = Unit
    }

    interface ExportFile {
        val identity: String
        val name: String?
        fun delete(): Boolean
    }

    interface ExportPublisher {
        fun create(name: String): ExportFile
        fun open(identity: String): ExportFile?
        fun find(name: String): ExportFile?
        fun rename(source: ExportFile, newName: String): ExportFile
        fun writeFrom(source: File, target: ExportFile)
        fun readTo(source: ExportFile, target: File)
    }

    private class DocumentFileExportPublisher(private val context: Context, private val treeUri: Uri) : ExportPublisher {
        private val resolver = context.contentResolver
        private val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        private val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId)
        private val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocumentId)
        private val metadataColumns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )

        private fun queryDocument(uri: Uri): DocumentExportFile? {
            repeat(2) { attempt ->
                resolver.query(uri, metadataColumns, null, null, null).use { cursor ->
                    if (cursor != null && cursor.moveToFirst()) {
                        val id = cursor.getString(0)
                        val name = if (cursor.isNull(1)) null else cursor.getString(1)
                        return DocumentExportFile(uri, id, name)
                    }
                }
                if (attempt == 0) SystemClock.sleep(50)
            }
            return null
        }

        override fun create(name: String): ExportFile {
            val uri = requireNotNull(
                DocumentsContract.createDocument(resolver, rootUri, "application/octet-stream", name)
            ) { "Provider returned no URI for $name" }
            return requireNotNull(queryDocument(uri)) { "Provider returned an unreadable URI for $name" }
        }

        override fun open(identity: String): ExportFile? = queryDocument(Uri.parse(identity))

        override fun find(name: String): ExportFile? {
            if (treeUri.authority == "com.android.externalstorage.documents") {
                val directId = "$rootDocumentId/$name"
                val directUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, directId)
                runCatching { queryDocument(directUri) }.getOrNull()?.let { direct ->
                    if (direct.name == name) return direct
                }
            }
            resolver.query(childrenUri, metadataColumns, null, null, null).use { cursor ->
                if (cursor == null) return null
                while (cursor.moveToNext()) {
                    if (!cursor.isNull(1) && cursor.getString(1) == name) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0))
                        return queryDocument(uri)
                    }
                }
            }
            return null
        }

        override fun rename(source: ExportFile, newName: String): ExportFile {
            val file = source as DocumentExportFile
            val renamedUri = requireNotNull(
                DocumentsContract.renameDocument(resolver, file.uri, newName)
            ) { "Provider could not rename ${file.name} to $newName" }
            val renamed = requireNotNull(queryDocument(renamedUri)) {
                "Provider returned an unreadable URI after renaming to $newName"
            }
            require(renamed.name == newName) {
                "Provider renamed ${file.name} to ${renamed.name} instead of $newName"
            }
            return renamed
        }

        override fun writeFrom(source: File, target: ExportFile) {
            val file = target as DocumentExportFile
            resolver.openOutputStream(file.uri, "wt").use { out ->
                source.inputStream().use { it.copyTo(requireNotNull(out)) }
            }
        }
        override fun readTo(source: ExportFile, target: File) {
            val file = source as DocumentExportFile
            resolver.openInputStream(file.uri).use { input ->
                FileOutputStream(target).use { out ->
                    requireNotNull(input).copyTo(out)
                    out.fd.sync()
                }
            }
        }
        private inner class DocumentExportFile(
            val uri: Uri,
            @Suppress("unused") val documentId: String,
            override val name: String?,
        ) : ExportFile {
            override val identity: String get() = uri.toString()
            override fun delete() = DocumentsContract.deleteDocument(resolver, uri)
        }
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
