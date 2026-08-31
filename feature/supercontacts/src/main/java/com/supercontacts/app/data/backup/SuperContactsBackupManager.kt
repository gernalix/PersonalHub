package com.supercontacts.app.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.supercontacts.app.R
import com.supercontacts.app.data.local.SuperContactsDatabase
import com.supercontacts.app.data.repository.ContactPhotoResolver
import com.supercontacts.app.data.storage.SafRootContract
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@OptIn(FlowPreview::class)
class SuperContactsBackupManager(
    context: Context,
    private val closeDataLayer: () -> Unit,
    private val notifyDataLayerChanged: () -> Unit,
) {
    companion object {
        const val BACKUP_FILE_NAME = "super_contacts_backup.sqlite"

        private const val LOG_TAG = "SCBackup"
        private const val IMPORT_STAGING_NAME = "super_contacts_import_source.sqlite"
        private const val IMPORT_VALIDATION_DB_NAME = "super_contacts_import_validation.db"
        private const val ROLLBACK_DB_NAME = "super_contacts_pre_restore.sqlite"
        private const val AUTO_EXPORT_DEBOUNCE_MS = 350L
        private const val BACKUP_METADATA_ID = 1
    }

    private val appContext = context.applicationContext
    private val prefs = BackupPreferencesStore(appContext)
    private val photoResolver = ContactPhotoResolver(appContext)
    private val safRootContract = SafRootContract(appContext)
    private val operationMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val autoExportRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val _state = MutableStateFlow(readState())

    val state: StateFlow<BackupState> = _state.asStateFlow()

    init {
        scope.launch {
            runCatching {
                operationMutex.withLock {
                    enforceConfiguredRootContract()
                }
            }.onFailure { error ->
                Log.w(LOG_TAG, "Configured SAF root contract enforcement failed.", error)
            }
        }
        scope.launch {
            autoExportRequests
                .debounce(AUTO_EXPORT_DEBOUNCE_MS)
                .collect {
                    if (!prefs.readAutoExportEnabled()) return@collect
                    runCatching {
                        operationMutex.withLock {
                            exportInternal(manual = false)
                        }
                    }.onFailure { error ->
                        recordError(error.message ?: appContext.getString(R.string.backup_export_failed))
                    }
                }
        }
    }

    fun notifyDatabaseChanged() {
        autoExportRequests.tryEmit(Unit)
    }

    suspend fun setBackupFolder(treeUri: Uri) {
        withContext(Dispatchers.IO) {
            operationMutex.withLock {
                setBusy(true)
                try {
                    takePersistablePermissionIfNeeded(treeUri)
                    resolveBackupDirectory(treeUri, requireWrite = true)
                    safRootContract.enforce(treeUri)
                    prefs.writeFolderUri(treeUri.toString())
                    prefs.writeLastError(null)
                    refreshState()
                } catch (error: Throwable) {
                    prefs.clearFolderUri()
                    recordError(error.message ?: appContext.getString(R.string.backup_folder_access_failed))
                    throw error
                } finally {
                    setBusy(false)
                }
            }
        }
    }

    suspend fun setAutoExportEnabled(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            prefs.writeAutoExportEnabled(enabled)
            refreshState()
            if (enabled) {
                autoExportRequests.tryEmit(Unit)
            }
        }
    }

    suspend fun exportNow() {
        withContext(Dispatchers.IO) {
            operationMutex.withLock {
                Log.i(LOG_TAG, "Manual backup export requested")
                exportInternal(manual = true)
            }
        }
    }

    suspend fun importFromUri(uri: Uri) {
        withContext(Dispatchers.IO) {
            operationMutex.withLock {
                importFromUriLocked(uri = uri, sourcePhotosDirectories = resolveImportSiblingPhotosDirectories(uri))
            }
        }
    }

    suspend fun importFromBackupFolder(treeUri: Uri) {
        withContext(Dispatchers.IO) {
            operationMutex.withLock {
                takePersistablePermissionIfNeeded(treeUri)
                resolveBackupDirectory(treeUri, requireWrite = true)
                val backupUri = safRootContract.resolveBackupUri(treeUri, create = false)
                    ?: throw IllegalStateException(appContext.getString(R.string.backup_import_file_missing))
                prefs.writeFolderUri(treeUri.toString())
                importFromUriLocked(
                    uri = backupUri,
                    sourcePhotosDirectories = photoResolver.knownDocumentPhotoDirectories(treeUri),
                )
            }
        }
    }

    private fun importFromUriLocked(
        uri: Uri,
        sourcePhotosDirectories: List<DocumentFile>,
    ) {
        setBusy(true)
        Log.i(LOG_TAG, "Backup import requested from uri=$uri")
        val preparedImport = prepareImport(uri)
        val rollbackFile = File(appContext.getDatabasePath(SuperContactsDatabase.DATABASE_NAME).parentFile, ROLLBACK_DB_NAME)
        deleteFileAndSidecars(rollbackFile)
        createSnapshotFile(rollbackFile)
        var layerClosed = false
        try {
            closeDataLayer()
            layerClosed = true
            replaceInternalDatabaseWith(preparedImport.file)
            verifyCurrentDatabaseOpens()
            importPhotosIfAvailable(sourcePhotosDirectories)
            notifyDataLayerChanged()
            if (prefs.readAutoExportEnabled()) {
                Log.i(LOG_TAG, "Auto-exporting restored database after successful import")
                runCatching { exportInternal(manual = false) }
            }
            prefs.writeLastError(null)
            refreshState()
            Log.i(LOG_TAG, "Backup import completed successfully")
        } catch (error: Throwable) {
            if (layerClosed && rollbackFile.exists()) {
                runCatching {
                    replaceDatabaseFile(
                        source = rollbackFile,
                        destination = appContext.getDatabasePath(SuperContactsDatabase.DATABASE_NAME),
                    )
                    verifyCurrentDatabaseOpens()
                }
            }
            if (layerClosed) {
                notifyDataLayerChanged()
            }
            recordError(error.message ?: appContext.getString(R.string.backup_import_failed))
            Log.e(LOG_TAG, "Backup import failed", error)
            throw error
        } finally {
            deleteFileAndSidecars(preparedImport.file)
            deleteFileAndSidecars(rollbackFile)
            deleteFileAndSidecars(File(appContext.cacheDir, IMPORT_STAGING_NAME))
            setBusy(false)
        }
    }

    private fun exportInternal(manual: Boolean) {
        val folderUriString = prefs.readFolderUri()
        if (folderUriString.isNullOrBlank()) {
            if (manual) {
                throw IllegalStateException(appContext.getString(R.string.backup_folder_not_configured))
            }
            return
        }

        setBusy(true)
        val snapshotFile = File(appContext.cacheDir, "super_contacts_export_snapshot.sqlite")
        try {
            Log.d(LOG_TAG, "Creating SQLite snapshot for backup (manual=$manual)")
            createSnapshotFile(snapshotFile)
            val folderUri = Uri.parse(folderUriString)
            resolveBackupDirectory(folderUri, requireWrite = true)
            safRootContract.writeBackup(folderUri, snapshotFile)
            prefs.writeLastExportAt(System.currentTimeMillis())
            prefs.writeLastError(null)
            refreshState()
            Log.i(LOG_TAG, "Backup export completed successfully")
        } catch (error: Throwable) {
            if (manual) {
                Log.e(LOG_TAG, "Manual backup export failed", error)
                throw error
            }
            recordError(error.message ?: appContext.getString(R.string.backup_export_failed))
            Log.e(LOG_TAG, "Auto backup export failed", error)
        } finally {
            deleteFileAndSidecars(snapshotFile)
            setBusy(false)
        }
    }

    private fun enforceConfiguredRootContract() {
        val folderUriString = prefs.readFolderUri()?.takeIf { it.isNotBlank() } ?: return
        val folderUri = runCatching { Uri.parse(folderUriString) }.getOrNull() ?: return
        resolveBackupDirectory(folderUri, requireWrite = true)
        safRootContract.enforce(folderUri)
        refreshState()
    }

    private fun prepareImport(uri: Uri): PreparedImport {
        val stagingFile = File(appContext.cacheDir, IMPORT_STAGING_NAME)
        deleteFileAndSidecars(stagingFile)
        copyUriToFile(uri, stagingFile)
        validateSqliteIntegrity(stagingFile)

        val inputVersion = readUserVersion(stagingFile)
        Log.i(LOG_TAG, "Validating backup import candidate with schemaVersion=$inputVersion")
        if (!SuperContactsDatabase.canMigrateFrom(inputVersion)) {
            throw IllegalStateException(
                appContext.getString(
                    R.string.backup_import_version_unsupported,
                    inputVersion,
                    SuperContactsDatabase.SCHEMA_VERSION,
                ),
            )
        }
        validateSuperContactsOwnership(stagingFile, inputVersion)

        val validationFile = appContext.getDatabasePath(IMPORT_VALIDATION_DB_NAME)
        deleteFileAndSidecars(validationFile)
        copyFile(stagingFile, validationFile)

        val validationDb = SuperContactsDatabase.openTemporary(appContext, IMPORT_VALIDATION_DB_NAME)
        try {
            val writableDb = validationDb.openHelper.writableDatabase
            val cursor = writableDb.query("SELECT 1")
            cursor.close()
            checkpointWal(writableDb)
        } catch (error: Throwable) {
            throw IllegalStateException(
                appContext.getString(
                    R.string.backup_import_migration_failed,
                    error.message ?: error::class.java.simpleName,
                ),
            )
        } finally {
            validationDb.close()
        }

        validateSqliteIntegrity(validationFile)
        val migratedVersion = readUserVersion(validationFile)
        Log.i(LOG_TAG, "Validated backup import candidate migrated to schemaVersion=$migratedVersion")
        if (migratedVersion != SuperContactsDatabase.SCHEMA_VERSION) {
            throw IllegalStateException(
                appContext.getString(
                    R.string.backup_import_version_unsupported,
                    migratedVersion,
                    SuperContactsDatabase.SCHEMA_VERSION,
                ),
            )
        }

        return PreparedImport(
            file = validationFile,
            displayName = queryDisplayName(uri) ?: uri.lastPathSegment ?: BACKUP_FILE_NAME,
        )
    }

    private fun createSnapshotFile(targetFile: File) {
        deleteFileAndSidecars(targetFile)
        targetFile.parentFile?.mkdirs()

        val database = SuperContactsDatabase.getInstance(appContext)
        val writableDb = database.openHelper.writableDatabase

        val vacuumSucceeded = vacuumIntoSnapshot(writableDb, targetFile)
        if (!vacuumSucceeded) {
            throw IllegalStateException(appContext.getString(R.string.backup_export_failed))
        }
        if (!targetFile.isFile || targetFile.length() <= 0L) {
            throw IllegalStateException(appContext.getString(R.string.backup_export_failed))
        }

        upsertBackupMetadata(targetFile, System.currentTimeMillis())
        validateSqliteIntegrity(targetFile)
    }

    private fun replaceInternalDatabaseWith(sourceFile: File) {
        val destination = appContext.getDatabasePath(SuperContactsDatabase.DATABASE_NAME)
        replaceDatabaseFile(sourceFile, destination)
    }

    private fun replaceDatabaseFile(
        source: File,
        destination: File,
    ) {
        destination.parentFile?.mkdirs()
        deleteFileAndSidecars(destination)
        runCatching {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            copyFile(source, destination)
            source.delete()
        }
    }

    private fun verifyCurrentDatabaseOpens() {
        val db = SuperContactsDatabase.getInstance(appContext)
        val cursor = db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM contacts")
        cursor.close()
    }

    private fun importPhotosIfAvailable(sourcePhotoDirectories: List<DocumentFile>) {
        if (sourcePhotoDirectories.isEmpty()) return
        val folderUriString = prefs.readFolderUri() ?: return
        val targetRootUri = runCatching { Uri.parse(folderUriString) }.getOrNull() ?: return
        val targetPhotos = resolveBackupPhotosDirectory(targetRootUri, create = true) ?: return
        sourcePhotoDirectories
            .filterNot { sameDocumentLocation(it, targetPhotos) }
            .forEach { sourcePhotos ->
                copyPhotoDocuments(sourcePhotos, targetPhotos)
            }
    }

    private fun resolveImportSiblingPhotosDirectories(importUri: Uri): List<DocumentFile> {
        if (importUri.scheme != "file") return emptyList()
        val backupFile = File(importUri.path ?: return emptyList())
        val parent = backupFile.parentFile ?: return emptyList()
        return ContactPhotoResolver.knownFilePhotoDirectories(parent)
            .map(DocumentFile::fromFile)
    }

    private fun resolveBackupPhotosDirectory(
        rootUri: Uri,
        create: Boolean,
    ): DocumentFile? {
        val root = when (rootUri.scheme) {
            "file" -> {
                val file = File(rootUri.path ?: return null)
                file.mkdirs()
                DocumentFile.fromFile(file)
            }

            else -> DocumentFile.fromTreeUri(appContext, rootUri)
        } ?: return null
        return photoResolver.canonicalDocumentPhotoDirectory(root.uri, create)
    }

    private fun copyPhotoDocuments(
        sourcePhotos: DocumentFile,
        targetPhotos: DocumentFile,
    ) {
        sourcePhotos.listFiles()
            .filter { it.isFile && it.name?.isNotBlank() == true }
            .forEach { source ->
                val name = source.name ?: return@forEach
                val target = targetPhotos.findFile(name)
                    ?: targetPhotos.createFile(source.type ?: "image/jpeg", name)
                    ?: return@forEach
                val input = openInputStream(source.uri) ?: return@forEach
                input.use {
                    writeInputStreamToUri(it, target.uri)
                }
            }
    }

    private fun sameDocumentLocation(
        first: DocumentFile,
        second: DocumentFile,
    ): Boolean = first.uri == second.uri

    private fun takePersistablePermissionIfNeeded(uri: Uri) {
        if (uri.scheme == "file") return
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        appContext.contentResolver.takePersistableUriPermission(uri, flags)
    }

    private fun resolveBackupDirectory(
        treeUri: Uri,
        requireWrite: Boolean,
    ): DocumentFile {
        val directory = when (treeUri.scheme) {
            "file" -> {
                val file = File(treeUri.path ?: throw IllegalStateException(appContext.getString(R.string.backup_folder_access_failed)))
                file.mkdirs()
                DocumentFile.fromFile(file)
            }

            else -> DocumentFile.fromTreeUri(appContext, treeUri)
        } ?: throw IllegalStateException(appContext.getString(R.string.backup_folder_access_failed))

        val canRead = runCatching { directory.canRead() }.getOrDefault(false)
        val canWrite = runCatching { directory.canWrite() }.getOrDefault(false)
        if (!canRead || (requireWrite && !canWrite) || !hasPersistedPermission(treeUri, requireWrite)) {
            prefs.clearFolderUri()
            throw IllegalStateException(appContext.getString(R.string.backup_folder_permission_revoked))
        }
        return directory
    }

    private fun hasPersistedPermission(
        uri: Uri,
        requireWrite: Boolean,
    ): Boolean {
        if (uri.scheme == "file") {
            val file = File(uri.path ?: return false)
            return if (requireWrite) file.canWrite() else file.canRead()
        }
        val permission = appContext.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri } ?: return false
        return if (requireWrite) permission.isWritePermission else permission.isReadPermission
    }

    private fun validateSuperContactsOwnership(
        dbFile: File,
        version: Int,
    ) {
        val database = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val metadataAppId = if (hasTable(database, "backup_metadata")) {
                database.rawQuery(
                    "SELECT app_id FROM backup_metadata WHERE id = ?",
                    arrayOf(BACKUP_METADATA_ID.toString()),
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            } else {
                null
            }
            if (metadataAppId == SuperContactsDatabase.APP_ID) return

            val requiredTables = mutableSetOf("contacts", "contact_fields", "room_master_table")
            if (version >= 2) {
                requiredTables += "tags"
                requiredTables += "contact_tags"
            }
            if (version >= 3) {
                requiredTables += "contact_events"
            }
            if (version >= 5) {
                requiredTables += "contact_initiatives"
            }
            if (version >= 6) {
                requiredTables += "backup_metadata"
            }

            val missingTables = requiredTables.filterNot { hasTable(database, it) }
            if (missingTables.isNotEmpty()) {
                throw IllegalStateException(appContext.getString(R.string.backup_import_not_supercontacts))
            }
        } finally {
            database.close()
        }
    }

    private fun hasTable(
        database: SQLiteDatabase,
        tableName: String,
    ): Boolean =
        database.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
            arrayOf(tableName),
        ).use { cursor -> cursor.moveToFirst() }

    private fun upsertBackupMetadata(
        targetFile: File,
        exportedAt: Long,
    ) {
        val database = SQLiteDatabase.openDatabase(targetFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS backup_metadata (
                    id INTEGER NOT NULL PRIMARY KEY,
                    app_id TEXT NOT NULL,
                    schema_version INTEGER NOT NULL,
                    backup_format_version INTEGER NOT NULL,
                    exported_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT OR REPLACE INTO backup_metadata (
                    id,
                    app_id,
                    schema_version,
                    backup_format_version,
                    exported_at
                ) VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    BACKUP_METADATA_ID,
                    SuperContactsDatabase.APP_ID,
                    SuperContactsDatabase.SCHEMA_VERSION,
                    SuperContactsDatabase.BACKUP_FORMAT_VERSION,
                    exportedAt,
                ),
            )
        } finally {
            database.close()
        }
    }

    private fun validateSqliteIntegrity(dbFile: File) {
        if (!dbFile.exists() || dbFile.length() <= 0L) {
            throw IllegalStateException(appContext.getString(R.string.backup_import_file_missing))
        }

        val database = runCatching {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        }.getOrElse { error ->
            throw IllegalStateException(
                appContext.getString(
                    R.string.backup_import_corrupt,
                    error.message ?: error::class.java.simpleName,
                ),
            )
        }

        try {
            val result = database.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else ""
            }
            if (!result.equals("ok", ignoreCase = true)) {
                throw IllegalStateException(appContext.getString(R.string.backup_import_corrupt, result))
            }
        } finally {
            database.close()
        }
    }

    private fun readUserVersion(dbFile: File): Int {
        val database = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        return try {
            database.rawQuery("PRAGMA user_version", null).use { cursor ->
                if (!cursor.moveToFirst()) 0 else cursor.getInt(0)
            }
        } finally {
            database.close()
        }
    }

    private fun copyFile(
        source: File,
        destination: File,
    ) {
        destination.parentFile?.mkdirs()
        FileInputStream(source).channel.use { input ->
            FileOutputStream(destination).channel.use { output ->
                output.transferFrom(input, 0, input.size())
                output.force(true)
            }
        }
    }

    private fun copyUriToFile(
        uri: Uri,
        destination: File,
    ) {
        openInputStream(uri).use { input ->
            requireNotNull(input) { appContext.getString(R.string.backup_import_file_missing) }
            destination.parentFile?.mkdirs()
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
    }

    private fun writeInputStreamToUri(
        input: InputStream,
        destinationUri: Uri,
    ) {
        when (destinationUri.scheme) {
            "file" -> {
                FileOutputStream(File(requireNotNull(destinationUri.path))).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }

            else -> {
                val descriptor = appContext.contentResolver.openFileDescriptor(destinationUri, "rwt")
                    ?: throw IllegalStateException(appContext.getString(R.string.backup_export_failed))
                descriptor.use { fileDescriptor ->
                    FileOutputStream(fileDescriptor.fileDescriptor).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
            }
        }
    }

    private fun openInputStream(uri: Uri): InputStream? =
        when (uri.scheme) {
            "file" -> uri.path?.let(::FileInputStream)
            else -> appContext.contentResolver.openInputStream(uri)
        }

    private fun queryDisplayName(uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.path?.let(::File)?.name
        }
        return runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (columnIndex >= 0) cursor.getString(columnIndex) else null
                }
        }.getOrNull()
    }

    private fun sqlLiteral(value: String): String =
        "'" + value.replace("'", "''") + "'"

    private fun vacuumIntoSnapshot(database: SupportSQLiteDatabase, targetFile: File): Boolean {
        var lastError: Throwable? = null
        repeat(5) { attempt ->
            deleteFileAndSidecars(targetFile)
            runCatching {
                database.execSQL("VACUUM INTO ${sqlLiteral(targetFile.absolutePath)}")
            }.onSuccess {
                if (targetFile.isFile && targetFile.length() > 0L) {
                    return true
                }
            }.onFailure { error ->
                lastError = error
                Log.w(LOG_TAG, "VACUUM INTO backup snapshot failed on attempt ${attempt + 1}.", error)
                Thread.sleep(150L * (attempt + 1))
            }
        }
        lastError?.let { Log.e(LOG_TAG, "VACUUM INTO backup snapshot failed after retries.", it) }
        return false
    }

    private fun checkpointWal(database: SupportSQLiteDatabase) {
        database.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            while (cursor.moveToNext()) {
                // Drain the pragma result so checkpoint execution completes before continuing.
            }
        }
    }

    private fun deleteFileAndSidecars(file: File) {
        runCatching { file.delete() }
        runCatching { File(file.absolutePath + "-wal").delete() }
        runCatching { File(file.absolutePath + "-shm").delete() }
    }

    private fun recordError(message: String) {
        prefs.writeLastError(message)
        refreshState()
    }

    private fun setBusy(isBusy: Boolean) {
        _state.value = _state.value.copy(isBusy = isBusy)
    }

    private fun refreshState() {
        _state.value = readState(isBusy = _state.value.isBusy)
    }

    private fun readState(isBusy: Boolean = false): BackupState {
        val folderUriString = prefs.readFolderUri()
        val folderUri = folderUriString?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val accessible = folderUri?.let {
            runCatching { resolveBackupDirectory(it, requireWrite = false) }.isSuccess
        } ?: false

        return BackupState(
            folderUri = folderUriString,
            folderLabel = folderUri?.let(::describeFolderLabel),
            autoExportEnabled = prefs.readAutoExportEnabled(),
            lastExportAt = prefs.readLastExportAt(),
            lastError = prefs.readLastError(),
            isConfigured = folderUri != null,
            isAccessible = accessible,
            isBusy = isBusy,
        )
    }

    private fun describeFolderLabel(uri: Uri): String? =
        when (uri.scheme) {
            "file" -> uri.path?.let(::File)?.name
            else -> uri.lastPathSegment?.substringAfterLast(':')
        }

    private data class PreparedImport(
        val file: File,
        val displayName: String,
    )
}
