package com.gernalix.luoghi.backup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.gernalix.luoghi.export.BackupFolderStore
import com.gernalix.luoghi.export.LuoghiExporter
import com.gernalix.luoghi.export.SafeFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

class BackupRepository(
    private val context: Context,
    private val validator: BackupValidator = BackupValidator(context.applicationContext),
) {
    suspend fun discoverConfiguredBackups(): BackupDiscovery = withContext(Dispatchers.IO) {
        val dir = BackupFolderStore.getExistingDataDir(context)
            ?: return@withContext BackupDiscovery(emptyList(), emptyList())
        val manifest = dir.findFile(LuoghiExporter.MANIFEST_FILE)?.readUtf8(context)
        val candidates = dir.listFiles()
            .asSequence()
            .filter { it.isFile && it.name.isRestorableDatabaseName() }
            .sortedWith(
                compareByDescending<DocumentFile> { it.name == LuoghiExporter.DB_FILE }
                    .thenByDescending { it.lastModified() }
            )
            .map { document ->
                BackupSource(
                    uri = document.uri,
                    displayName = document.name ?: LuoghiExporter.DB_FILE,
                    lastModifiedMs = document.lastModified().takeIf { it > 0L },
                    manifestJson = manifest.takeIf { document.name == LuoghiExporter.DB_FILE },
                )
            }
            .toList()
        inspectAll(candidates)
    }

    suspend fun inspectUri(uri: Uri): Result<ValidatedBackup> = try {
        Result.success(validator.inspect(sourceForUri(uri)))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    }

    suspend fun inspect(source: BackupSource): ValidatedBackup = validator.inspect(source)

    suspend fun preserveForLater(candidate: ValidatedBackup): String = withContext(Dispatchers.IO) {
        val originalName = candidate.preview.source.displayName
        if (originalName.startsWith("luoghi_recovery_") || originalName.startsWith("luoghi_pre_restore_")) {
            return@withContext originalName
        }
        val dir = BackupFolderStore.getExistingDataDir(context)
            ?: throw IllegalStateException("Backup folder is not available")
        val name = "luoghi_recovery_${candidate.preview.exportedAtMs}_${candidate.preview.sha256.take(12)}.db"
        dir.findFile(name)?.let { existing ->
            val existingValidated = validator.inspect(
                BackupSource(existing.uri, name, existing.lastModified().takeIf { it > 0L })
            )
            if (existingValidated.preview.sha256.equals(candidate.preview.sha256, ignoreCase = true)) {
                return@withContext name
            }
            existing.delete()
        }
        val target = dir.createFile("application/octet-stream", name)
            ?: throw IllegalStateException("Cannot create deferred recovery copy")
        try {
            val local = java.io.File.createTempFile("luoghi-deferred-", ".db", context.cacheDir)
            try {
                context.contentResolver.openInputStream(candidate.preview.source.uri)?.use { input ->
                    local.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Cannot read deferred backup")
                SafeFiles.copyFileToDocument(context, local, target)
            } finally {
                local.delete()
            }
            val preserved = validator.inspect(BackupSource(target.uri, name, target.lastModified().takeIf { it > 0L }))
            check(preserved.preview.sha256.equals(candidate.preview.sha256, ignoreCase = true)) {
                "Deferred recovery copy checksum mismatch"
            }
            name
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private suspend fun inspectAll(sources: List<BackupSource>): BackupDiscovery {
        val compatible = mutableListOf<ValidatedBackup>()
        val rejected = mutableListOf<RejectedBackup>()
        sources.forEach { source ->
            try {
                compatible += validator.inspect(source)
            } catch (error: BackupValidationException) {
                rejected += RejectedBackup(source, error.code)
            }
        }
        return BackupDiscovery(compatible, rejected)
    }

    private fun sourceForUri(uri: Uri): BackupSource {
        var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: LuoghiExporter.DB_FILE
        var size: Long? = null
        var lastModifiedMs: Long? = null
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(
                    OpenableColumns.DISPLAY_NAME,
                    OpenableColumns.SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null,
                null,
                null,
            )
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                        if (nameIndex >= 0) displayName = cursor.getString(nameIndex) ?: displayName
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                        if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                            lastModifiedMs = cursor.getLong(modifiedIndex).takeIf { it > 0L }
                        }
                    }
                }
        }
        return BackupSource(uri = uri, displayName = displayName, lastModifiedMs = lastModifiedMs).also {
            if (size != null && size!! > LuoghiBackupFormat.MAX_BACKUP_BYTES) {
                throw BackupValidationException(BackupValidationCode.FILE_TOO_LARGE, "Backup is too large")
            }
        }
    }
}

private fun String?.isRestorableDatabaseName(): Boolean {
    val value = this ?: return false
    if (value == LuoghiExporter.DB_FILE || value == LuoghiExporter.BAK_FILE) return true
    if (value == LuoghiExporter.TMP_FILE || value == LuoghiExporter.BAK_TMP_FILE) return true
    return (
        value.startsWith("luoghi_pre_restore_") ||
            value.startsWith("luoghi_recovery_") ||
            value.startsWith(LuoghiExporter.GENERATION_PREFIX)
        ) &&
        value.endsWith(".db")
}

private fun DocumentFile.readUtf8(context: Context): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_MANIFEST_BYTES) return@runCatching null
            output.write(buffer, 0, read)
        }
        output.toString(Charsets.UTF_8.name())
    }
}.getOrNull()

private const val MAX_MANIFEST_BYTES = 1024 * 1024
